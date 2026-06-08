package com.agibuffett.common.model;

import java.util.Collections;
import java.util.List;

/** 分红送配数据,对应 {@code data/fundamental/<symbol>/dividend.json}。 */
public class DividendData {

    private String symbol;
    private String source;
    private String fetchedAt;
    /** 按报告期降序 */
    private List<DividendRecord> records;

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(String fetchedAt) { this.fetchedAt = fetchedAt; }

    public List<DividendRecord> getRecords() {
        return records == null ? Collections.<DividendRecord>emptyList() : records;
    }

    public void setRecords(List<DividendRecord> records) { this.records = records; }

    /** 最近一期有股息率的记录(records 已降序)。 */
    public DividendRecord latestWithYield() {
        for (DividendRecord r : getRecords()) {
            if (r.getDividendYield() != null) {
                return r;
            }
        }
        return null;
    }
}
