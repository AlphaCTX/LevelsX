package com.alphactx.model;

public enum ScoreField {
    LEVEL("Level"),
    BALANCE("Balance"),
    XP("XP"),
    PROGRESS("Progress"),
    KILLS("Kills"),
    MOB_KILLS("Mob Kills"),
    DEATHS("Deaths"),
    KM("Kilometers");

    private final String label;
    ScoreField(String label){ this.label = label; }
    public String getLabel(){ return label; }
}
