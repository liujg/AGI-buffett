package com.agibuffett.common.model;

/** 自选股清单中的一个标的。 */
public class WatchItem {

    private String symbol;
    /** 市场:A(沪深)/ HK(港股)/ US(美股) */
    private String market = "A";
    private String name;

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getMarket() {
        return market;
    }

    public void setMarket(String market) {
        this.market = market;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
