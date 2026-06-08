package com.agibuffett.common.model;

import java.util.Collections;
import java.util.List;

/** 自选股清单,对应 {@code data/watchlist.json}。 */
public class Watchlist {

    private int version = 1;
    private String updatedAt;
    private List<WatchGroup> groups;

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<WatchGroup> getGroups() {
        return groups == null ? Collections.<WatchGroup>emptyList() : groups;
    }

    public void setGroups(List<WatchGroup> groups) {
        this.groups = groups;
    }

    /** 按 key 取分组,找不到返回 null。 */
    public WatchGroup group(String key) {
        for (WatchGroup g : getGroups()) {
            if (key.equals(g.getKey())) {
                return g;
            }
        }
        return null;
    }
}
