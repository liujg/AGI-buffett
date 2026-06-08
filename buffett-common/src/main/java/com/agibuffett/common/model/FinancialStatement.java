package com.agibuffett.common.model;

import java.util.Collections;
import java.util.List;

/**
 * 一张财务报表(资产负债表 / 利润表 / 现金流量表),对应
 * {@code data/fundamental/<symbol>/<statement>.json}。
 */
public class FinancialStatement {

    private String symbol;
    /** balance_sheet / income_statement / cash_flow */
    private String statement;
    private String source;
    private String fetchedAt;
    /** 按报告期降序(最新在前) */
    private List<PeriodRecord> records;

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getStatement() {
        return statement;
    }

    public void setStatement(String statement) {
        this.statement = statement;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getFetchedAt() {
        return fetchedAt;
    }

    public void setFetchedAt(String fetchedAt) {
        this.fetchedAt = fetchedAt;
    }

    public List<PeriodRecord> getRecords() {
        return records == null ? Collections.<PeriodRecord>emptyList() : records;
    }

    public void setRecords(List<PeriodRecord> records) {
        this.records = records;
    }

    /** 返回指定报告期(如 "20231231")的记录,找不到返回 null。 */
    public PeriodRecord recordOf(String reportDate) {
        for (PeriodRecord r : getRecords()) {
            if (reportDate.equals(r.getReportDate())) {
                return r;
            }
        }
        return null;
    }

    /** 最新一期(records 已降序,取第一条)。 */
    public PeriodRecord latest() {
        List<PeriodRecord> rs = getRecords();
        return rs.isEmpty() ? null : rs.get(0);
    }
}
