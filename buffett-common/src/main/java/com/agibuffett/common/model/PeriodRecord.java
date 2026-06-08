package com.agibuffett.common.model;

import java.util.Collections;
import java.util.Map;

/**
 * 单个报告期的报表数据:报告期 + 行项目(列名 -> 数值)。
 * 数值缺失时为 {@code null}。
 */
public class PeriodRecord {

    /** 报告期,形如 "20231231" */
    private String reportDate;
    private Map<String, Double> items;

    public String getReportDate() {
        return reportDate;
    }

    public void setReportDate(String reportDate) {
        this.reportDate = reportDate;
    }

    public Map<String, Double> getItems() {
        return items == null ? Collections.<String, Double>emptyMap() : items;
    }

    public void setItems(Map<String, Double> items) {
        this.items = items;
    }

    /** 取某个行项目的值,缺失返回 null。 */
    public Double get(String item) {
        return getItems().get(item);
    }

    /** 取某个行项目的值,缺失返回默认值。 */
    public double getOrDefault(String item, double def) {
        Double v = getItems().get(item);
        return v == null ? def : v;
    }
}
