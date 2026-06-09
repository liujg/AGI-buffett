package com.agibuffett.web;

import com.agibuffett.common.model.DailyBar;
import com.agibuffett.common.model.DividendData;
import com.agibuffett.common.model.DividendRecord;
import com.agibuffett.common.model.FinancialStatement;
import com.agibuffett.common.model.PeriodRecord;
import com.agibuffett.common.model.WatchGroup;
import com.agibuffett.common.model.WatchItem;
import com.agibuffett.common.model.Watchlist;
import com.agibuffett.common.storage.LocalStore;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 为前端提供数据:自选股清单 + 个股基本面详情。
 * 金额统一折算为 <b>亿元</b>;趋势取年报序列。
 */
public class StockService {

    private static final double YI = 1.0e8;
    private static final int TREND_YEARS = 8;
    private static final int PRICE_DAYS = 120;
    /** 总股本同比增幅超过此阈值(%)视为转增/送股(拆股),股本减少率按 0 计,不计入资本回报。 */
    private static final double SPLIT_JUMP = 30.0;

    private final LocalStore store;

    public StockService(LocalStore store) {
        this.store = store;
    }

    public Watchlist watchlist() {
        return store.readWatchlist();
    }

    /** 自选分组的内置显示名(与 Python 端 DEFAULT_GROUPS 一致)。 */
    private static final Map<String, String> GROUP_NAMES = new LinkedHashMap<String, String>() {{
        put("watch", "自选");
        put("focus", "重点观察");
        put("opportunity", "机会点");
    }};
    private static final java.util.Set<String> VALID_MARKETS =
            new java.util.HashSet<>(java.util.Arrays.asList("A", "HK", "US"));
    private final Object watchLock = new Object();

    /**
     * 添加一个标的到自选分组,写回 watchlist.json,返回更新后的清单。
     * 语义对齐 Python 端 WatchlistStore.add:同分组内按 symbol+market 去重。
     */
    public Watchlist addWatch(String symbol, String market, String name, String group) {
        String sym = symbol == null ? "" : symbol.trim();
        String mkt = (market == null ? "A" : market).trim().toUpperCase();
        String grp = (group == null || group.trim().isEmpty()) ? "watch" : group.trim();
        if (sym.isEmpty()) {
            throw new IllegalArgumentException("代码不能为空");
        }
        if (!VALID_MARKETS.contains(mkt)) {
            throw new IllegalArgumentException("market 必须为 A / HK / US");
        }
        synchronized (watchLock) {
            Watchlist wl = store.readWatchlist();
            List<WatchGroup> groups = new ArrayList<>(wl.getGroups());
            WatchGroup g = null;
            for (WatchGroup x : groups) {
                if (grp.equals(x.getKey())) {
                    g = x;
                    break;
                }
            }
            if (g == null) {                       // 分组不存在则新建
                g = new WatchGroup();
                g.setKey(grp);
                g.setName(GROUP_NAMES.getOrDefault(grp, grp));
                g.setItems(new ArrayList<>());
                groups.add(g);
                wl.setGroups(groups);
            }
            List<WatchItem> items = new ArrayList<>(g.getItems());
            for (WatchItem it : items) {           // 去重:同 symbol+market 已存在则只补名称
                if (sym.equals(it.getSymbol()) && mkt.equals(it.getMarket())) {
                    if (name != null && !name.trim().isEmpty()
                            && (it.getName() == null || it.getName().isEmpty())) {
                        it.setName(name.trim());
                    }
                    g.setItems(items);
                    touchAndWrite(wl);
                    return wl;
                }
            }
            WatchItem item = new WatchItem();
            item.setSymbol(sym);
            item.setMarket(mkt);
            item.setName(name == null ? "" : name.trim());
            items.add(item);
            g.setItems(items);
            touchAndWrite(wl);
            return wl;
        }
    }

    /** 从指定分组(group 为空则全部分组)移除某代码,写回并返回更新后的清单。 */
    public Watchlist removeWatch(String symbol, String group) {
        String sym = symbol == null ? "" : symbol.trim();
        if (sym.isEmpty()) {
            throw new IllegalArgumentException("代码不能为空");
        }
        synchronized (watchLock) {
            Watchlist wl = store.readWatchlist();
            for (WatchGroup g : wl.getGroups()) {
                if (group != null && !group.isEmpty() && !group.equals(g.getKey())) {
                    continue;
                }
                List<WatchItem> kept = new ArrayList<>();
                for (WatchItem it : g.getItems()) {
                    if (!sym.equals(it.getSymbol())) {
                        kept.add(it);
                    }
                }
                g.setItems(kept);
            }
            touchAndWrite(wl);
            return wl;
        }
    }

    private void touchAndWrite(Watchlist wl) {
        wl.setUpdatedAt(java.time.LocalDate.now().toString());
        store.writeWatchlist(wl);
    }

    /** 个股详情。无基本面数据(如港股/美股未抓取)时 hasData=false。 */
    public Map<String, Object> detail(String symbol, String market) {
        String mkt = (market == null ? "A" : market).toUpperCase();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", symbol);
        out.put("market", mkt);
        out.put("currency", currencyOf(mkt));   // CNY / HKD / USD
        out.put("name", nameFromWatchlist(symbol));

        boolean hasFundamental = Files.exists(store.statementFile(symbol, LocalStore.BALANCE_SHEET));
        if (!hasFundamental) {
            out.put("hasData", false);
            return out;
        }
        out.put("hasData", true);

        FinancialStatement bs = store.readBalanceSheet(symbol);
        FinancialStatement is = store.readIncomeStatement(symbol);
        try {
            out.put("name", firstNonEmpty(store.readMeta(symbol).getName(), (String) out.get("name")));
        } catch (RuntimeException ignore) {
            // meta 缺失不影响
        }

        PeriodRecord bsLatest = bs.latest();
        PeriodRecord isAnnual = latestAnnual(is, mkt);
        out.put("reportDate", bsLatest == null ? null : bsLatest.getReportDate());
        out.put("annualDate", isAnnual == null ? null : isAnnual.getReportDate());

        // ---- KPI(跨市场:按候选键依次匹配 A 股英文码 / 港美股中文名)----
        Map<String, Object> kpi = new LinkedHashMap<>();
        Double assets = firstVal(bsLatest, ASSETS);
        Double liab = firstVal(bsLatest, LIAB);
        kpi.put("totalAssets", yi(assets));
        kpi.put("totalLiabilities", yi(liab));
        kpi.put("totalEquity", yi(firstVal(bsLatest, EQUITY)));
        kpi.put("monetaryFunds", yi(firstVal(bsLatest, CASH)));
        kpi.put("assetLiabilityRatio",
                (assets != null && assets != 0 && liab != null) ? round2(liab / assets * 100) : null);
        kpi.put("revenue", yi(firstVal(isAnnual, REVENUE)));
        kpi.put("netProfit", yi(firstVal(isAnnual, NETPROFIT)));

        // 股息率:先放东财“最近一期”值作回退;下方会用「去年每股分红合计 ÷ 当前股价」覆盖估算。
        DividendData dividends = store.readDividends(symbol);
        DividendRecord divLatest = dividends.latestWithYield();
        kpi.put("dividendYield",
                divLatest != null && divLatest.getDividendYield() != null
                        ? round2(divLatest.getDividendYield() * 100) : null);
        out.put("kpi", kpi);

        // ---- 年报趋势(全部年份)+ 同比增长 ----
        List<String> years = new ArrayList<>();
        List<Double> trendAssets = new ArrayList<>();
        List<Double> trendRevenue = new ArrayList<>();
        List<Double> trendProfit = new ArrayList<>();
        List<PeriodRecord> annualBs = annuals(bs, mkt);
        List<PeriodRecord> annualIs = annuals(is, mkt);
        // annuals() 已按报告期降序;反转为时间升序展示
        for (int i = annualBs.size() - 1; i >= 0; i--) {
            PeriodRecord rb = annualBs.get(i);
            years.add(yearOf(rb.getReportDate()));
            trendAssets.add(yi(firstVal(rb, ASSETS)));
            PeriodRecord ri = annualByDate(annualIs, rb.getReportDate());
            trendRevenue.add(yi(firstVal(ri, REVENUE)));
            trendProfit.add(yi(firstVal(ri, NETPROFIT)));
        }
        Map<String, Object> trend = new LinkedHashMap<>();
        trend.put("years", years);
        trend.put("totalAssets", trendAssets);
        trend.put("revenue", trendRevenue);
        trend.put("netProfit", trendProfit);
        trend.put("totalAssetsYoY", yoy(trendAssets));   // 同比增长 %
        trend.put("revenueYoY", yoy(trendRevenue));
        trend.put("netProfitYoY", yoy(trendProfit));
        out.put("trend", trend);

        // ---- 行情:按月聚合全历史(不复权/前复权/后复权),前端默认看 3 年、可左右滑 ----
        List<DailyBar> rawBars = store.readDaily(symbol, LocalStore.DAILY_RAW);
        List<DailyBar> qfqBars = store.readDaily(symbol, LocalStore.DAILY);
        List<DailyBar> hfqBars = store.readDaily(symbol, LocalStore.DAILY_HFQ);
        // 年末不复权收盘:年份 -> 当年最后一个交易日收盘(用于年末股息率)
        Map<String, Double> yearEndClose = new LinkedHashMap<>();
        for (DailyBar b : rawBars) {
            yearEndClose.put(b.getDate().toString().substring(0, 4), b.getClose());
        }
        List<DailyBar> base = !qfqBars.isEmpty() ? qfqBars : (!rawBars.isEmpty() ? rawBars : hfqBars);
        if (!base.isEmpty()) {
            Map<String, Double> rawM = monthly(rawBars);
            Map<String, Double> qfqM = monthly(qfqBars);
            Map<String, Double> hfqM = monthly(hfqBars);
            Map<String, double[]> rawO = monthlyOHLC(rawBars);
            Map<String, double[]> qfqO = monthlyOHLC(qfqBars);
            Map<String, double[]> hfqO = monthlyOHLC(hfqBars);
            List<String> months = new ArrayList<>();
            List<Double> rC = new ArrayList<>(), qC = new ArrayList<>(), hC = new ArrayList<>();
            List<List<Double>> rO = new ArrayList<>(), qO = new ArrayList<>(), hO = new ArrayList<>();
            for (String m : monthly(base).keySet()) {   // 月份主轴(升序)
                months.add(m);
                rC.add(round(rawM.get(m)));
                qC.add(round(qfqM.get(m)));
                hC.add(round(hfqM.get(m)));
                rO.add(ohlcRow(rawO, m));
                qO.add(ohlcRow(qfqO, m));
                hO.add(ohlcRow(hfqO, m));
            }
            Map<String, Object> price = new LinkedHashMap<>();
            price.put("months", months);
            price.put("raw", rawBars.isEmpty() ? null : rC);
            price.put("qfq", qfqBars.isEmpty() ? null : qC);
            price.put("hfq", hfqBars.isEmpty() ? null : hC);
            price.put("rawOhlc", rawBars.isEmpty() ? null : rO);
            price.put("qfqOhlc", qfqBars.isEmpty() ? null : qO);
            price.put("hfqOhlc", hfqBars.isEmpty() ? null : hO);
            price.put("lastRaw", lastClose(rawBars));
            price.put("lastQfq", lastClose(qfqBars));
            price.put("lastHfq", lastClose(hfqBars));
            out.put("price", price);
        }

        // ---- 近期分红:按年合并(同一年的中期+末期派现合计)----
        Map<String, YearDividend> byYear = new LinkedHashMap<>();  // records 已降序,年份按出现序
        for (DividendRecord r : dividends.getRecords()) {
            // 规则:一次性特别股息(特别分配,如中国移动 2017 上市20周年特别息)不计入经常性股息率
            if (isSpecial(r.getType())) {
                continue;
            }
            Double per10 = r.getCashPer10();
            Double perSh = r.getCashPerShare();
            if (per10 == null && perSh == null) {
                continue;  // 跳过不分红/无现金的期(含实物分派,已在抓取端解析为 null)
            }
            String rd = r.getReportDate();
            String yr = (rd != null && rd.length() >= 4) ? rd.substring(0, 4) : rd;
            YearDividend y = byYear.get(yr);
            if (y == null) {
                y = new YearDividend();
                byYear.put(yr, y);
            }
            y.times++;
            if (per10 != null) {
                y.cashPer10 = nz(y.cashPer10) + per10;
                y.perShareSum += per10 / 10.0;
            }
            if (perSh != null) {
                y.cashPerShare = nz(y.cashPerShare) + perSh;
                y.perShareSum += perSh;
            }
            if (r.getDividendYield() != null) {
                y.emYield = nz(y.emYield) + r.getDividendYield() * 100;
            }
            if (y.currency == null) {
                y.currency = r.getCurrency();
            }
            // 该年度是否含末期/年度派息(用于判断年度是否"完整"):
            // A 股看报告期是否为年报(YYYY1231);港股看分配类型(年度分配)。
            if ((rd != null && rd.endsWith("1231"))
                    || isAnnualType(r.getType())) {
                y.hasAnnual = true;
            }
            // 取该年内最晚的除息日
            if (r.getExDate() != null && (y.exDate == null || r.getExDate().compareTo(y.exDate) > 0)) {
                y.exDate = r.getExDate();
            }
        }

        // 股息率(估算):用最近一个"完整年度"的每股分红合计 ÷ 当前股价。
        // 旧实现取"最近一期"东财股息率,会漏掉同年的中期派现而偏低(如茅台只算年末派息 → 2.22%)。
        // 完整年度 = 含年度/末期派息;若某年只有中期(如港股当年末期未公布),则回退到上一个完整年度。
        Double curPrice = lastClose(rawBars);            // 当前价用不复权收盘(与每股分红口径一致)
        if (curPrice == null) {
            curPrice = lastClose(qfqBars);               // 不复权缺失时退回前复权(最新价与不复权一致)
        }
        if (curPrice != null && curPrice != 0 && !byYear.isEmpty()) {
            List<String> ys = new ArrayList<>(byYear.keySet());
            ys.sort(Comparator.reverseOrder());          // 年度降序(键均为 4 位年份)
            String pickYear = null;
            // 1) 最近一个"完整年度"(含年度/末期派息)
            for (String y : ys) {
                YearDividend yd = byYear.get(y);
                if (yd.hasAnnual && yd.perShareSum > 0) {
                    pickYear = y;
                    break;
                }
            }
            // 2) 没有任何完整年度:退回最近一个早于今年、且有派息的年度
            if (pickYear == null) {
                int curYear = java.time.Year.now().getValue();
                for (String y : ys) {
                    int yi = parseYearOr(y, curYear);
                    if (yi < curYear && byYear.get(y).perShareSum > 0) {
                        pickYear = y;
                        break;
                    }
                }
            }
            // 3) 再兜底:最近一个有派息的年度
            if (pickYear == null) {
                for (String y : ys) {
                    if (byYear.get(y).perShareSum > 0) {
                        pickYear = y;
                        break;
                    }
                }
            }
            if (pickYear != null) {
                double perShare = byYear.get(pickYear).perShareSum;
                kpi.put("dividendYield", round2(perShare / curPrice * 100));
                kpi.put("dividendYieldYear", pickYear);                   // 用于前端标注口径
                kpi.put("dividendYieldPrice", curPrice);                  // 当前股价
                kpi.put("dividendPerShare", round2(perShare));            // 该年度每股分红合计
            }
        }

        // 各年末总股本(A 股:SHARE_CAPITAL 即股数,精确;港美股资产负债表无股本股数,
        // 退而用 归母净利 / 每股基本盈利 反推加权平均股数,作趋势/减少率估算)
        Map<String, Double> sharesByYear = new LinkedHashMap<>();
        for (PeriodRecord r : annualBs) {
            Double sc = firstVal(r, SHARE_CAPITAL);
            if (sc != null) {
                sharesByYear.put(yearOf(r.getReportDate()), sc);
            }
        }
        // 年度营收 / 归母净利(用于营收、利润增长列;同时补算港美股股数)
        Map<String, Double> revByYear = new LinkedHashMap<>();
        Map<String, Double> profitByYear = new LinkedHashMap<>();
        for (PeriodRecord r : annualIs) {
            String y = yearOf(r.getReportDate());
            revByYear.put(y, firstVal(r, REVENUE));
            Double np = firstVal(r, NETPROFIT);
            profitByYear.put(y, np);
            if (!sharesByYear.containsKey(y)) {           // 无精确股本时按 净利/每股基本盈利 反推
                Double eps = firstVal(r, BASIC_EPS);
                if (np != null && eps != null && eps != 0) {
                    sharesByYear.put(y, np / eps);
                }
            }
        }

        List<Map<String, Object>> divList = new ArrayList<>();
        for (Map.Entry<String, YearDividend> e : byYear.entrySet()) {
            String yr = e.getKey();
            YearDividend y = e.getValue();
            Double yEnd = yearEndClose.get(yr);
            Double yieldYearEnd = (yEnd != null && yEnd != 0) ? round2(y.perShareSum / yEnd * 100) : null;
            // 股本减少率:资本回报只认"回购缩股"(正)。股本增加均非返还资本,不计入,仅标注:
            //   - 大幅跳升(>SPLIT_JUMP%)→ "转增"(送股/转增,等比拆股);
            //   - 一般增加 → "增发"(IPO/二次上市如中国移动2022 A股、定增、期权行权)。
            Double shareReduction = null;   // 仅在回购缩股时为正数;增发/转增按 0 计并标注
            String shareNote = null;        // "转增" / "增发"
            Double curShares = sharesByYear.get(yr);
            Double prevShares = sharesByYear.get(prevYear(yr));
            if (curShares != null && prevShares != null && prevShares != 0) {
                double red = round2((prevShares - curShares) / prevShares * 100);
                if (red >= 0) {
                    shareReduction = red;             // 回购缩股,计入资本回报
                } else {
                    shareReduction = 0.0;             // 股本增加,不计入
                    shareNote = (red <= -SPLIT_JUMP) ? "转增" : "增发";
                }
            }
            // 资本回报率 = 经常性现金股息率 + 回购缩股率(增发/转增/缺失均按 0 计)
            Double capitalReturn = null;
            if (yieldYearEnd != null) {
                capitalReturn = round2(yieldYearEnd + (shareReduction != null ? shareReduction : 0.0));
            }
            // 营收增长、利润增长(同比 %)
            Double revenueGrowth = null;
            Double curR = revByYear.get(yr);
            Double prevR = revByYear.get(prevYear(yr));
            if (curR != null && prevR != null && prevR != 0) {
                revenueGrowth = round2((curR - prevR) / Math.abs(prevR) * 100);
            }
            Double profitGrowth = null;
            Double curP = profitByYear.get(yr);
            Double prevP = profitByYear.get(prevYear(yr));
            if (curP != null && prevP != null && prevP != 0) {
                profitGrowth = round2((curP - prevP) / Math.abs(prevP) * 100);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("reportDate", yr);               // 年度
            m.put("revenueGrowth", revenueGrowth); // 营收同比 %
            m.put("profitGrowth", profitGrowth);   // 归母净利同比 %
            m.put("times", y.times);               // 当年派现次数
            m.put("cashPer10", round(y.cashPer10));        // A 股:全年合计 元/10股
            m.put("cashPerShare", round(y.cashPerShare));  // 港股:全年合计 每股
            m.put("currency", y.currency);
            m.put("yield", y.emYield == null ? null : round2(y.emYield));
            m.put("yearEndClose", round(yEnd));
            m.put("yieldYearEnd", yieldYearEnd);
            m.put("shareReduction", shareReduction);   // 股本减少率 %(回购缩股为正;增发/转增为 0)
            m.put("shareNote", shareNote);             // "转增"/"增发":股本增加,前端标注且不计入回报
            m.put("capitalReturn", capitalReturn);     // 资本回报率 %
            m.put("exDate", y.exDate);
            divList.add(m);
        }
        out.put("dividends", divList);
        return out;
    }

    /** 单个年度的分红聚合。 */
    private static final class YearDividend {
        int times = 0;
        Double cashPer10 = null;     // A 股全年合计(元/10股)
        Double cashPerShare = null;  // 港股全年合计(每股)
        double perShareSum = 0.0;    // 全年每股合计(用于算年末股息率)
        Double emYield = null;       // 东财各期股息率之和
        String currency = null;
        String exDate = null;
        boolean hasAnnual = false;   // 该年度是否含年度/末期派息(判断年度是否完整)
    }

    /** 是否为一次性特别股息(特别分配/特别股息)。这类不计入经常性股息率/资本回报率。 */
    private static boolean isSpecial(String type) {
        return type != null && type.contains("特别");
    }

    /** 港股分配类型是否为年度/末期(年报)派息。 */
    private static boolean isAnnualType(String type) {
        if (type == null) {
            return false;
        }
        return type.contains("年度") || type.contains("末期") || type.contains("年报") || type.contains("全年");
    }

    /** 把 4 位年份字符串转 int;无法解析时返回回退值。 */
    private static int parseYearOr(String y, int fallback) {
        if (y == null || y.length() < 4) {
            return fallback;
        }
        try {
            return Integer.parseInt(y.substring(0, 4));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static double nz(Double v) {
        return v == null ? 0.0 : v;
    }

    private static DailyBar lastOf(List<DailyBar> bars) {
        return bars.isEmpty() ? null : bars.get(bars.size() - 1);
    }

    /** 按月聚合:YYYY-MM -> 当月最后一个交易日收盘(bars 升序,后写覆盖)。 */
    private static Map<String, Double> monthly(List<DailyBar> bars) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (DailyBar b : bars) {
            m.put(b.getDate().toString().substring(0, 7), b.getClose());
        }
        return m;
    }

    /**
     * 按月聚合成月K:YYYY-MM -> [开盘, 最高, 最低, 收盘]。
     * 开盘取当月首个交易日开盘价,最高/最低取全月极值,收盘取当月最后一个交易日收盘价(bars 升序)。
     */
    private static Map<String, double[]> monthlyOHLC(List<DailyBar> bars) {
        Map<String, double[]> m = new LinkedHashMap<>();
        for (DailyBar b : bars) {
            String key = b.getDate().toString().substring(0, 7);
            double[] o = m.get(key);
            if (o == null) {
                m.put(key, new double[]{b.getOpen(), b.getHigh(), b.getLow(), b.getClose()});
            } else {
                o[1] = Math.max(o[1], b.getHigh());
                o[2] = Math.min(o[2], b.getLow());
                o[3] = b.getClose();
            }
        }
        return m;
    }

    /** 把某月的 OHLC 转成四舍五入后的列表;缺失返回 null(与月份主轴对齐)。 */
    private static List<Double> ohlcRow(Map<String, double[]> ohlc, String month) {
        double[] o = ohlc.get(month);
        if (o == null) {
            return null;
        }
        List<Double> row = new ArrayList<>(4);
        for (double v : o) {
            row.add(round2(v));
        }
        return row;
    }

    /** 同比增长 %(逐期相对上一期);首期或基数缺失/为 0 时为 null。 */
    private static List<Object> yoy(List<Double> v) {
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < v.size(); i++) {
            Double cur = v.get(i);
            Double prev = i > 0 ? v.get(i - 1) : null;
            out.add((cur != null && prev != null && prev != 0)
                    ? round2((cur - prev) / Math.abs(prev) * 100) : null);
        }
        return out;
    }

    private static Map<String, Double> byDate(List<DailyBar> bars) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (DailyBar b : bars) {
            m.put(b.getDate().toString(), b.getClose());
        }
        return m;
    }

    private static Double lastClose(List<DailyBar> bars) {
        DailyBar b = lastOf(bars);
        return b == null ? null : round2(b.getClose());
    }

    /** null 安全的两位四舍五入。 */
    private static Double round(Double v) {
        return v == null ? null : round2(v);
    }

    private static String currencyOf(String market) {
        if ("HK".equals(market)) {
            return "HKD";
        }
        if ("US".equals(market)) {
            return "USD";
        }
        return "CNY";
    }

    // ---- helpers ----
    private String nameFromWatchlist(String symbol) {
        for (com.agibuffett.common.model.WatchGroup g : watchlist().getGroups()) {
            for (WatchItem it : g.getItems()) {
                if (symbol.equals(it.getSymbol())) {
                    return it.getName();
                }
            }
        }
        return symbol;
    }

    // 跨市场 KPI 候选键:A 股为东财英文码,港股/美股为东财中文科目名
    private static final String[] ASSETS = {"TOTAL_ASSETS", "总资产"};
    private static final String[] LIAB = {"TOTAL_LIABILITIES", "总负债"};
    private static final String[] EQUITY = {"TOTAL_EQUITY", "总权益", "股东权益合计", "股东权益"};
    private static final String[] CASH = {"MONETARYFUNDS", "现金及等价物", "现金及现金等价物", "货币资金"};
    private static final String[] REVENUE = {"TOTAL_OPERATE_INCOME", "营业额", "营业收入", "营业总收入"};
    private static final String[] NETPROFIT =
            {"PARENT_NETPROFIT", "股东应占溢利", "归属于母公司股东的净利润", "净利润"};
    // A 股股本(面值 1 元,数值即总股本股数);港美股该科目为金额/口径不一致,不用于股本减少率。
    private static final String[] SHARE_CAPITAL = {"SHARE_CAPITAL"};
    // 每股基本盈利(港美股反推股数:归母净利 / 每股基本盈利 = 加权平均股数)
    private static final String[] BASIC_EPS = {"BASIC_EPS", "每股基本盈利", "基本每股收益", "每股盈利"};

    /** 年报识别:A/HK 取报告期 1231;US 财年末非 1231 且仅存年报,故全部视为年报。 */
    private static List<PeriodRecord> annuals(FinancialStatement s, String market) {
        List<PeriodRecord> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        boolean usAll = "US".equals(market);
        for (PeriodRecord r : s.getRecords()) {
            String d = r.getReportDate();
            if (usAll || (d != null && d.endsWith("1231"))) {
                out.add(r);
            }
        }
        return out; // records 已降序,故年报也降序
    }

    private static PeriodRecord latestAnnual(FinancialStatement s, String market) {
        List<PeriodRecord> a = annuals(s, market);
        return a.isEmpty() ? null : a.get(0);
    }

    private static PeriodRecord annualByDate(List<PeriodRecord> annuals, String reportDate) {
        for (PeriodRecord r : annuals) {
            if (reportDate.equals(r.getReportDate())) {
                return r;
            }
        }
        return null;
    }

    /** 按候选键依次取值,返回首个非空。 */
    private static Double firstVal(PeriodRecord r, String[] keys) {
        if (r == null) {
            return null;
        }
        for (String k : keys) {
            Double v = r.get(k);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static Double yi(Double v) {
        return v == null ? null : round2(v / YI);
    }

    private static String yearOf(String reportDate) {
        return reportDate != null && reportDate.length() >= 4 ? reportDate.substring(0, 4) : reportDate;
    }

    /** "2025" -> "2024";无法解析时返回 null。 */
    private static String prevYear(String yr) {
        if (yr == null || yr.length() < 4) {
            return null;
        }
        try {
            return String.valueOf(Integer.parseInt(yr.substring(0, 4)) - 1);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String firstNonEmpty(String a, String b) {
        return (a != null && !a.isEmpty()) ? a : b;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
