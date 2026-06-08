package com.agibuffett.common.model;

import java.util.List;

/** 股票元信息,对应 {@code data/fundamental/<symbol>/meta.json}。 */
public class StockMeta {

    private String symbol;
    private String name;
    private String source;
    private String fetchedAt;
    private List<String> statements;
    private MarketMeta market;

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public List<String> getStatements() {
        return statements;
    }

    public void setStatements(List<String> statements) {
        this.statements = statements;
    }

    public MarketMeta getMarket() {
        return market;
    }

    public void setMarket(MarketMeta market) {
        this.market = market;
    }

    /** 行情汇总信息(meta.json 中的 market 节点)。 */
    public static class MarketMeta {
        private int dailyRows;
        private String start;
        private String end;

        public int getDailyRows() {
            return dailyRows;
        }

        public void setDailyRows(int dailyRows) {
            this.dailyRows = dailyRows;
        }

        public String getStart() {
            return start;
        }

        public void setStart(String start) {
            this.start = start;
        }

        public String getEnd() {
            return end;
        }

        public void setEnd(String end) {
            this.end = end;
        }
    }
}
