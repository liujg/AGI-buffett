package com.agibuffett.common.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 自选股清单中的一个分组(如 自选 / 重点观察 / 机会点)。 */
public class WatchGroup {

    /** 稳定标识,如 watch / focus / opportunity */
    private String key;
    /** 显示名 */
    private String name;
    private List<WatchItem> items;

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<WatchItem> getItems() {
        return items == null ? Collections.<WatchItem>emptyList() : items;
    }

    public void setItems(List<WatchItem> items) {
        this.items = items;
    }

    /** 取该分组下指定市场的标的(market 为 null 时返回全部)。 */
    public List<WatchItem> itemsOf(String market) {
        if (market == null) {
            return getItems();
        }
        List<WatchItem> out = new ArrayList<>();
        for (WatchItem it : getItems()) {
            if (market.equals(it.getMarket())) {
                out.add(it);
            }
        }
        return out;
    }
}
