package com.payguard.fraud;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payguard.fraud")
public class FraudProperties {

    private double threshold = 0.7;
    private int velocityWindowMinutes = 10;
    private int velocityMinAttempts = 3;
    private int velocityHighAttempts = 8;
    private int amountMinHistory = 3;
    private double amountZscale = 5.0;
    private int geoJumpWindowMinutes = 90;

    public double getThreshold() { return threshold; }
    public void setThreshold(double threshold) { this.threshold = threshold; }
    public int getVelocityWindowMinutes() { return velocityWindowMinutes; }
    public void setVelocityWindowMinutes(int velocityWindowMinutes) { this.velocityWindowMinutes = velocityWindowMinutes; }
    public int getVelocityMinAttempts() { return velocityMinAttempts; }
    public void setVelocityMinAttempts(int velocityMinAttempts) { this.velocityMinAttempts = velocityMinAttempts; }
    public int getVelocityHighAttempts() { return velocityHighAttempts; }
    public void setVelocityHighAttempts(int velocityHighAttempts) { this.velocityHighAttempts = velocityHighAttempts; }
    public int getAmountMinHistory() { return amountMinHistory; }
    public void setAmountMinHistory(int amountMinHistory) { this.amountMinHistory = amountMinHistory; }
    public double getAmountZscale() { return amountZscale; }
    public void setAmountZscale(double amountZscale) { this.amountZscale = amountZscale; }
    public int getGeoJumpWindowMinutes() { return geoJumpWindowMinutes; }
    public void setGeoJumpWindowMinutes(int geoJumpWindowMinutes) { this.geoJumpWindowMinutes = geoJumpWindowMinutes; }
}
