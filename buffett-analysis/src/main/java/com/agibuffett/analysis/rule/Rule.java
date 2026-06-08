package com.agibuffett.analysis.rule;

import com.agibuffett.analysis.AnalysisContext;

/**
 * 规则引擎扩展点(骨架):一条可命中/不命中的投资规则。
 *
 * <p>例如「连续 N 年 ROE > 15%」「自由现金流为正」等。具体规则集待定;
 * 后续可由 {@code RuleEngine} 批量执行并汇总成评分 / 选股结论。
 */
public interface Rule {

    String name();

    /** 规则描述(展示用)。 */
    String description();

    /** 在给定上下文下评估规则。 */
    Signal evaluate(AnalysisContext ctx);

    /** 规则信号。 */
    enum Signal {
        PASS, FAIL, NOT_APPLICABLE
    }
}
