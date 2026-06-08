package com.agibuffett.analysis;

import com.agibuffett.common.model.DailyBar;
import com.agibuffett.common.model.FinancialStatement;
import com.agibuffett.common.model.StockMeta;
import com.agibuffett.common.storage.LocalStore;

import java.util.List;

/**
 * 一次分析的上下文:封装数据访问,惰性加载某只股票的报表与行情。
 * 分析器({@link Analyzer})通过它取数,而不直接接触文件布局。
 */
public class AnalysisContext {

    private final String symbol;
    private final LocalStore store;

    private StockMeta meta;
    private FinancialStatement balanceSheet;
    private FinancialStatement incomeStatement;
    private FinancialStatement cashFlow;
    private List<DailyBar> daily;

    public AnalysisContext(String symbol, LocalStore store) {
        this.symbol = symbol;
        this.store = store;
    }

    public String symbol() {
        return symbol;
    }

    public StockMeta meta() {
        if (meta == null) {
            meta = store.readMeta(symbol);
        }
        return meta;
    }

    public FinancialStatement balanceSheet() {
        if (balanceSheet == null) {
            balanceSheet = store.readBalanceSheet(symbol);
        }
        return balanceSheet;
    }

    public FinancialStatement incomeStatement() {
        if (incomeStatement == null) {
            incomeStatement = store.readIncomeStatement(symbol);
        }
        return incomeStatement;
    }

    public FinancialStatement cashFlow() {
        if (cashFlow == null) {
            cashFlow = store.readCashFlow(symbol);
        }
        return cashFlow;
    }

    public List<DailyBar> daily() {
        if (daily == null) {
            daily = store.readDaily(symbol);
        }
        return daily;
    }
}
