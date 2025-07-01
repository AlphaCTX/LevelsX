package com.alphactx.model;

public class Stats {
    private long timeOnline;
    private int kills;
    private int mobKills;
    private int deaths;
    private double damageDealt;
    private double damageTaken;
    private double moneyEarned;
    private double moneySpent;
    private double kilometersTraveled;

    public long getTimeOnline() {
        return timeOnline;
    }

    public void addTimeOnline(long ms) {
        this.timeOnline += ms;
    }

    public void setTimeOnline(long ms) {
        this.timeOnline = ms;
    }

    public int getKills() {
        return kills;
    }

    public void addKill() {
        this.kills++;
    }

    public void addKill(int amount) {
        this.kills += amount;
    }

    public int getMobKills() {
        return mobKills;
    }

    public void addMobKill() {
        this.mobKills++;
    }

    public void addMobKill(int amount) {
        this.mobKills += amount;
    }

    public int getDeaths() {
        return deaths;
    }

    public void addDeath() {
        this.deaths++;
    }

    public void addDeath(int amount) {
        this.deaths += amount;
    }

    public double getDamageDealt() {
        return damageDealt;
    }

    public void addDamageDealt(double amount) {
        this.damageDealt += amount;
    }

    public double getDamageTaken() {
        return damageTaken;
    }

    public void addDamageTaken(double amount) {
        this.damageTaken += amount;
    }

    public double getMoneyEarned() {
        return moneyEarned;
    }

    public void addMoneyEarned(double amount) {
        this.moneyEarned += amount;
    }

    public double getMoneySpent() {
        return moneySpent;
    }

    public void addMoneySpent(double amount) {
        this.moneySpent += amount;
    }

    public double getKilometersTraveled() {
        return kilometersTraveled;
    }

    public void addKilometersTraveled(double distance) {
        this.kilometersTraveled += distance;
    }
}
