package com.agibuffett.analysis;

import com.agibuffett.analysis.analyzer.FundamentalSummaryAnalyzer;
import com.agibuffett.analysis.analyzer.MarketSummaryAnalyzer;
import com.agibuffett.common.storage.LocalStore;

import java.nio.file.Paths;
import java.util.List;

/**
 * 分析端演示入口。
 *
 * <pre>
 *   mvn -q -pl buffett-analysis -am exec:java \
 *       -Dexec.mainClass=com.agibuffett.analysis.Main \
 *       -Dexec.args="data 600519"
 * </pre>
 *
 * 参数:[dataDir] [symbol...];默认 data 目录 + 列出本地全部股票。
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        String dataDir = args.length > 0 ? args[0] : "data";
        LocalStore store = new LocalStore(Paths.get(dataDir));

        List<String> symbols;
        if (args.length > 1) {
            symbols = java.util.Arrays.asList(args).subList(1, args.length);
        } else {
            symbols = store.listSymbols();
        }
        if (symbols.isEmpty()) {
            System.out.println("没有可分析的股票。先运行 fetcher-python 抓取数据到: "
                    + store.getDataDir());
            return;
        }

        AnalysisEngine engine = new AnalysisEngine(store)
                .register(new FundamentalSummaryAnalyzer())
                .register(new MarketSummaryAnalyzer());
        // TODO 待定:在此注册更多分析器 / 规则(估值、现金流质量、护城河打分等)

        for (String symbol : symbols) {
            System.out.println("==== " + symbol + " ====");
            for (AnalysisResult r : engine.analyze(symbol)) {
                System.out.println(r);
            }
        }
    }
}
