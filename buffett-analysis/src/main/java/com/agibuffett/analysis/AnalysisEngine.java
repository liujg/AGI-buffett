package com.agibuffett.analysis;

import com.agibuffett.common.storage.LocalStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 分析流水线:对给定股票依次运行已注册的分析器,汇总结果。
 * 单个分析器抛异常不影响其它分析器。
 */
public class AnalysisEngine {

    private static final Logger log = LoggerFactory.getLogger(AnalysisEngine.class);

    private final LocalStore store;
    private final List<Analyzer> analyzers = new ArrayList<>();

    public AnalysisEngine(LocalStore store) {
        this.store = store;
    }

    public AnalysisEngine register(Analyzer analyzer) {
        analyzers.add(analyzer);
        return this;
    }

    /** 对单只股票运行全部分析器。 */
    public List<AnalysisResult> analyze(String symbol) {
        AnalysisContext ctx = new AnalysisContext(symbol, store);
        List<AnalysisResult> results = new ArrayList<>();
        for (Analyzer a : analyzers) {
            try {
                results.add(a.analyze(ctx));
            } catch (Exception e) {
                log.warn("分析器 {} 处理 {} 失败: {}", a.name(), symbol, e.toString());
            }
        }
        return results;
    }
}
