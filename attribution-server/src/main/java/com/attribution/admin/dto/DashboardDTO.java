package com.attribution.admin.dto;

public class DashboardDTO {

    private long todayClicks;
    private long todayActivates;
    private long todayPurchases;
    private double todayRevenue;
    private long totalGames;
    private double callbackSuccessRate;

    public long getTodayClicks() { return todayClicks; }
    public void setTodayClicks(long todayClicks) { this.todayClicks = todayClicks; }
    public long getTodayActivates() { return todayActivates; }
    public void setTodayActivates(long todayActivates) { this.todayActivates = todayActivates; }
    public long getTodayPurchases() { return todayPurchases; }
    public void setTodayPurchases(long todayPurchases) { this.todayPurchases = todayPurchases; }
    public double getTodayRevenue() { return todayRevenue; }
    public void setTodayRevenue(double todayRevenue) { this.todayRevenue = todayRevenue; }
    public long getTotalGames() { return totalGames; }
    public void setTotalGames(long totalGames) { this.totalGames = totalGames; }
    public double getCallbackSuccessRate() { return callbackSuccessRate; }
    public void setCallbackSuccessRate(double callbackSuccessRate) { this.callbackSuccessRate = callbackSuccessRate; }
}
