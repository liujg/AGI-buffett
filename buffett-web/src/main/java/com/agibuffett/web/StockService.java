package com.agibuffett.web;

import com.agibuffett.common.model.DailyBar;
import com.agibuffett.common.model.DividendData;
import com.agibuffett.common.model.DividendRecord;
import com.agibuffett.common.model.FinancialStatement;
import com.agibuffett.common.model.PeriodRecord;
import com.agibuffett.common.model.WatchItem;
import com.agibuffett.common.model.Watchlist;
import com.agibuffett.common.storage.LocalStore;

import java.nio.file.Files;
import java.util.ArrayList;
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

    private final LocalStore store;

    public StockService(LocalStore store) {
        this.store = store;
    }

    public Watchlist watchlist() {
        return store.readWatchlist();
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

        // 股息率(来自分红数据,最近一期)
        DividendData dividends = store.readDividends(symbol);
        DividendRecord divLatest = dividends.latestWithYield();
        kpi.put("dividendYield",
                divLatest != null && divLatest.getDividendYield() != null
                        ? round2(divLatest.getDividendYield() * 100) : null);
        out.put("kpi", kpi);

        // ---- 年报趋势 ----
        List<String> years = new ArrayList<>();
        List<Object> trendAssets = new ArrayList<>();
        List<Object> trendRevenue = new ArrayList<>();
        List<Object> trendProfit = new ArrayList<>();
        List<PeriodRecord> annualBs = annuals(bs, mkt);
        List<PeriodRecord> annualIs = annuals(is, mkt);
        int n = Math.min(TREND_YEARS, annualBs.size());
        // annuals() 已按报告期降序;反转为时间升序展示
        for (int i = n - 1; i >= 0; i--) {
            PeriodRecord rb = annualBs.get(i);
            String year = yearOf(rb.getReportDate());
            years.add(year);
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
        out.put("trend", trend);

        // ---- 近期价格:前复权(daily.csv)+ 后复权(daily_hfq.csv),同日期对齐 ----
        List<DailyBar> qfq = store.readDaily(symbol, LocalStore.DAILY);
        if (!qfq.isEmpty()) {
            int from = Math.max(0, qfq.size() - PRICE_DAYS);
            // 后复权按日期建索引,与前复权同日对齐
            Map<String, Double> hfqByDate = new LinkedHashMap<>();
            for (DailyBar b : store.readDaily(symbol, LocalStore.DAILY_HFQ)) {
                hfqByDate.put(b.getDate().toString(), b.getClose());
            }
            List<String> dates = new ArrayList<>();
            List<Object> qClose = new ArrayList<>();
            List<Object> hClose = new ArrayList<>();
            for (int i = from; i < qfq.size(); i++) {
                String d = qfq.get(i).getDate().toString();
                dates.add(d);
                qClose.add(round2(qfq.get(i).getClose()));
                Double hv = hfqByDate.get(d);
                hClose.add(hv == null ? null : round2(hv));
            }
            Map<String, Object> price = new LinkedHashMap<>();
            price.put("dates", dates);
            price.put("qfq", qClose);
            price.put("hfq", hfqByDate.isEmpty() ? null : hClose);
            price.put("lastQfq", round2(qfq.get(qfq.size() - 1).getClose()));
            DailyBar lastH = lastOf(store.readDaily(symbol, LocalStore.DAILY_HFQ));
            price.put("lastHfq", lastH == null ? null : round2(lastH.getClose()));
            out.put("price", price);
        }

        // ---- 近期分红(最多 8 期,仅含现金分红的)----
        List<Map<String, Object>> divList = new ArrayList<>();
        for (DividendRecord r : dividends.getRecords()) {
            if (r.getCashPer10() == null && r.getCashPerShare() == null) {
                continue;  // 跳过不分红/无现金的期
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("reportDate", r.getReportDate());
            m.put("cashPer10", r.getCashPer10());        // A 股:元/10股
            m.put("cashPerShare", r.getCashPerShare());  // 港股:每股(原币种)
            m.put("currency", r.getCurrency());
            m.put("yield", r.getDividendYield() == null ? null : round2(r.getDividendYield() * 100));
            m.put("exDate", r.getExDate());
            m.put("desc", r.getPlanDesc());
            divList.add(m);
            if (divList.size() >= 8) {
                break;
            }
        }
        out.put("dividends", divList);
        return out;
    }

    private static DailyBar lastOf(List<DailyBar> bars) {
        return bars.isEmpty() ? null : bars.get(bars.size() - 1);
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

    private static String firstNonEmpty(String a, String b) {
        return (a != null && !a.isEmpty()) ? a : b;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
