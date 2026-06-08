package com.agibuffett.analysis.analyzer;

import com.agibuffett.analysis.AnalysisContext;
import com.agibuffett.analysis.AnalysisResult;
import com.agibuffett.analysis.Analyzer;
import com.agibuffett.common.model.DailyBar;

import java.util.List;

/**
 * 示例分析器:基于日线给出简单的行情概览(最新价、区间高低、最大回撤、累计收益)。
 *
 * <p>仅作为分析流水线的可运行样例 —— 真正的价值投资指标/规则待定。
 * 它只依赖固定的日线 schema,不涉及随数据源变化的报表字段。
 */
public class MarketSummaryAnalyzer implements Analyzer {

    @Override
    public String name() {
        return "market_summary";
    }

    @Override
    public AnalysisResult analyze(AnalysisContext ctx) {
        AnalysisResult r = new AnalysisResult(name(), ctx.symbol());
        List<DailyBar> bars = ctx.daily();
        if (bars.isEmpty()) {
            return r.note("无行情数据");
        }

        DailyBar first = bars.get(0);
        DailyBar last = bars.get(bars.size() - 1);

        double high = Double.NEGATIVE_INFINITY;
        double low = Double.POSITIVE_INFINITY;
        double peak = Double.NEGATIVE_INFINITY;
        double maxDrawdown = 0.0;
        for (DailyBar b : bars) {
            high = Math.max(high, b.getHigh());
            low = Math.min(low, b.getLow());
            peak = Math.max(peak, b.getClose());
            if (peak > 0) {
                maxDrawdown = Math.max(maxDrawdown, (peak - b.getClose()) / peak);
            }
        }

        double totalReturn = first.getClose() > 0
                ? (last.getClose() - first.getClose()) / first.getClose()
                : Double.NaN;

        return r.metric("bars", bars.size())
                .metric("start", first.getDate().toString())
                .metric("end", last.getDate().toString())
                .metric("lastClose", last.getClose())
                .metric("periodHigh", high)
                .metric("periodLow", low)
                .metric("totalReturnPct", round2(totalReturn * 100))
                .metric("maxDrawdownPct", round2(maxDrawdown * 100));
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
