package com.agibuffett.analysis;

import java.util.LinkedHashMap;
import java.util.Map;

/** 单个分析器的输出:命名指标 + 文字结论。 */
public class AnalysisResult {

    private final String analyzer;
    private final String symbol;
    private final Map<String, Object> metrics = new LinkedHashMap<>();
    private final StringBuilder notes = new StringBuilder();

    public AnalysisResult(String analyzer, String symbol) {
        this.analyzer = analyzer;
        this.symbol = symbol;
    }

    public AnalysisResult metric(String key, Object value) {
        metrics.put(key, value);
        return this;
    }

    public AnalysisResult note(String text) {
        if (notes.length() > 0) {
            notes.append('\n');
        }
        notes.append(text);
        return this;
    }

    public String getAnalyzer() {
        return analyzer;
    }

    public String getSymbol() {
        return symbol;
    }

    public Map<String, Object> getMetrics() {
        return metrics;
    }

    public String getNotes() {
        return notes.toString();
    }

    @Override
    public String toString() {
        return "[" + analyzer + "] " + symbol + " " + metrics
                + (notes.length() > 0 ? " | " + notes : "");
    }
}
