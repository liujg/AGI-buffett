package com.agibuffett.analysis.analyzer;

import com.agibuffett.analysis.AnalysisContext;
import com.agibuffett.analysis.AnalysisResult;
import com.agibuffett.analysis.Analyzer;
import com.agibuffett.common.model.FinancialStatement;
import com.agibuffett.common.model.PeriodRecord;

/**
 * 示例分析器:从资产负债表读取最新一期的规模与杠杆指标。
 *
 * <p>用于演示「Python 抓取 → 本地 JSON → Java 读取分析」整条链路;
 * 字段名为东财(EM)报表口径,金额单位为元,展示时折算为亿元。
 * 真正的价值投资指标体系待定。
 */
public class FundamentalSummaryAnalyzer implements Analyzer {

    private static final double YI = 1.0e8; // 亿

    @Override
    public String name() {
        return "fundamental_summary";
    }

    @Override
    public AnalysisResult analyze(AnalysisContext ctx) {
        AnalysisResult r = new AnalysisResult(name(), ctx.symbol());

        FinancialStatement bs = ctx.balanceSheet();
        PeriodRecord latest = bs.latest();
        if (latest == null) {
            return r.note("无资产负债表数据");
        }

        Double assets = latest.get("TOTAL_ASSETS");
        Double liabilities = latest.get("TOTAL_LIABILITIES");
        Double equity = latest.get("TOTAL_EQUITY");
        Double cash = latest.get("MONETARYFUNDS");

        r.metric("name", safeName(ctx))
                .metric("reportDate", latest.getReportDate())
                .metric("totalAssets(亿)", toYi(assets))
                .metric("totalLiabilities(亿)", toYi(liabilities))
                .metric("totalEquity(亿)", toYi(equity))
                .metric("monetaryFunds(亿)", toYi(cash));

        if (assets != null && assets != 0 && liabilities != null) {
            r.metric("assetLiabilityRatioPct", round2(liabilities / assets * 100));
        }
        return r;
    }

    /** meta.json 可能缺失(如仅抓了报表),取不到名字时返回空串而非报错。 */
    private static String safeName(AnalysisContext ctx) {
        try {
            return ctx.meta().getName();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static Double toYi(Double v) {
        return v == null ? null : round2(v / YI);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
