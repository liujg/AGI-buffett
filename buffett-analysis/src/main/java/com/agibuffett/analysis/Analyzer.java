package com.agibuffett.analysis;

/**
 * 分析器:对一只股票的数据做一类分析(指标计算 / 规则判定)。
 *
 * <p>这是数据分析的扩展点 —— 具体算法待定。新增分析逻辑时实现本接口,
 * 在 {@link AnalysisEngine} 注册即可纳入分析流水线。
 */
public interface Analyzer {

    /** 分析器名称(用于结果标识、日志)。 */
    String name();

    /** 基于上下文取数并产出结果。 */
    AnalysisResult analyze(AnalysisContext ctx);
}
