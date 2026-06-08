package com.agibuffett.common.model;

import java.time.LocalDate;

/** 一根日线,对应 {@code data/market/<symbol>/daily.csv} 的一行。 */
public class DailyBar {

    private LocalDate date;
    private double open;
    private double close;
    private double high;
    private double low;
    private double volume;
    private double amount;
    private double amplitude;
    private double pctChange;
    private double change;
    private double turnover;

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public double getOpen() {
        return open;
    }

    public void setOpen(double open) {
        this.open = open;
    }

    public double getClose() {
        return close;
    }

    public void setClose(double close) {
        this.close = close;
    }

    public double getHigh() {
        return high;
    }

    public void setHigh(double high) {
        this.high = high;
    }

    public double getLow() {
        return low;
    }

    public void setLow(double low) {
        this.low = low;
    }

    public double getVolume() {
        return volume;
    }

    public void setVolume(double volume) {
        this.volume = volume;
    }

    public double getAmount() {
        return amount;
    }

    public void setAmount(double amount) {
        this.amount = amount;
    }

    public double getAmplitude() {
        return amplitude;
    }

    public void setAmplitude(double amplitude) {
        this.amplitude = amplitude;
    }

    public double getPctChange() {
        return pctChange;
    }

    public void setPctChange(double pctChange) {
        this.pctChange = pctChange;
    }

    public double getChange() {
        return change;
    }

    public void setChange(double change) {
        this.change = change;
    }

    public double getTurnover() {
        return turnover;
    }

    public void setTurnover(double turnover) {
        this.turnover = turnover;
    }
}
