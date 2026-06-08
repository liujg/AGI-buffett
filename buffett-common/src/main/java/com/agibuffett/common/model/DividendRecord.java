package com.agibuffett.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 一期分红送配,对应 dividend.json records 的一项(金额口径:每 10 股)。 */
public class DividendRecord {

    private String reportDate;
    private String discloseDate;
    private String recordDate;
    private String exDate;
    private String payDate;
    /** 分配类型(港股),如「年度分配」 */
    private String type;
    /** 币种:CNY / HKD / USD */
    private String currency;
    /** 港股每股派息(原币种) */
    private Double cashPerShare;
    // 注:含数字的字段名,Jackson SNAKE_CASE 会得到 cash_per10(数字前无下划线),
    // 与 Python 端 cash_per_10 不符,故显式指定 JSON key。
    /** 现金分红(元/10股) */
    @JsonProperty("cash_per_10")
    private Double cashPer10;
    @JsonProperty("bonus_per_10")
    private Double bonusPer10;
    @JsonProperty("transfer_per_10")
    private Double transferPer10;
    @JsonProperty("bonus_transfer_per_10")
    private Double bonusTransferPer10;
    /** 股息率(小数,如 0.0165) */
    private Double dividendYield;
    private Double eps;
    private Double bps;
    private String plan;
    private String planDesc;

    public String getReportDate() { return reportDate; }
    public void setReportDate(String reportDate) { this.reportDate = reportDate; }

    public String getDiscloseDate() { return discloseDate; }
    public void setDiscloseDate(String discloseDate) { this.discloseDate = discloseDate; }

    public String getRecordDate() { return recordDate; }
    public void setRecordDate(String recordDate) { this.recordDate = recordDate; }

    public String getExDate() { return exDate; }
    public void setExDate(String exDate) { this.exDate = exDate; }

    public String getPayDate() { return payDate; }
    public void setPayDate(String payDate) { this.payDate = payDate; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public Double getCashPerShare() { return cashPerShare; }
    public void setCashPerShare(Double cashPerShare) { this.cashPerShare = cashPerShare; }

    public Double getCashPer10() { return cashPer10; }
    public void setCashPer10(Double cashPer10) { this.cashPer10 = cashPer10; }

    public Double getBonusPer10() { return bonusPer10; }
    public void setBonusPer10(Double bonusPer10) { this.bonusPer10 = bonusPer10; }

    public Double getTransferPer10() { return transferPer10; }
    public void setTransferPer10(Double transferPer10) { this.transferPer10 = transferPer10; }

    public Double getBonusTransferPer10() { return bonusTransferPer10; }
    public void setBonusTransferPer10(Double bonusTransferPer10) { this.bonusTransferPer10 = bonusTransferPer10; }

    public Double getDividendYield() { return dividendYield; }
    public void setDividendYield(Double dividendYield) { this.dividendYield = dividendYield; }

    public Double getEps() { return eps; }
    public void setEps(Double eps) { this.eps = eps; }

    public Double getBps() { return bps; }
    public void setBps(Double bps) { this.bps = bps; }

    public String getPlan() { return plan; }
    public void setPlan(String plan) { this.plan = plan; }

    public String getPlanDesc() { return planDesc; }
    public void setPlanDesc(String planDesc) { this.planDesc = planDesc; }
}
