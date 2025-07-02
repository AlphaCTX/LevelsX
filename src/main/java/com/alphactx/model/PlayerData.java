package com.alphactx.model;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import com.alphactx.model.ChallengeType;

/**
 * Stores player related data such as level, XP, skills and statistics.
 * Challenge progress is tracked per {@link ChallengeType} so daily
 * challenges can be completed.
 */

public class PlayerData {
    private final UUID uuid;
    private int level = 1;
    private int xp = 0;
    private int skillPoints = 0;
    private long lastJoin;
    private final Stats stats = new Stats();
    private final Map<Skill, Integer> skills = new EnumMap<>(Skill.class);
    private final Map<ChallengeType, Double> dailyProgress = new EnumMap<>(ChallengeType.class);
    private final Map<ChallengeType, Double> weeklyProgress = new EnumMap<>(ChallengeType.class);
    private long lastDailyReset = System.currentTimeMillis();
    private long lastWeeklyReset = System.currentTimeMillis();
    private boolean scoreboardEnabled = false;
    private double lastBalance = 0.0;

    public PlayerData(UUID uuid) {
        this.uuid = uuid;
        for (Skill skill : Skill.values()) {
            skills.put(skill, 0);
        }
        for (ChallengeType type : ChallengeType.values()) {
            dailyProgress.put(type, 0.0);
            weeklyProgress.put(type, 0.0);
        }
    }

    public UUID getUuid() {
        return uuid;
    }

    public int getLevel() {
        return level;
    }

    public int getXp() {
        return xp;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public void setXp(int xp) {
        this.xp = xp;
    }

    public int getSkillPoints() {
        return skillPoints;
    }

    public Stats getStats() {
        return stats;
    }

    public void setLastJoin(long time) {
        this.lastJoin = time;
    }

    public long getLastJoin() {
        return lastJoin;
    }

    public int getSkillLevel(Skill skill) {
        return skills.getOrDefault(skill, 0);
    }

    /** Set the level of a specific skill directly. */
    public void setSkillLevel(Skill skill, int level) {
        skills.put(skill, level);
    }

    /**
     * @return immutable view of skill levels
     */
    public Map<Skill, Integer> getSkills() {
        return new EnumMap<>(skills);
    }

    public void levelSkill(Skill skill) {
        if (skillPoints > 0) {
            skills.put(skill, getSkillLevel(skill) + 1);
            skillPoints--;
        }
    }

    public void addXp(int amount) {
        this.xp += amount;
    }

    public boolean tryLevelUp() {
        int needed = level * 100;
        boolean leveled = false;
        while (xp >= needed) {
            xp -= needed;
            level++;
            skillPoints += (level % 5 == 0) ? 1 : 0;
            leveled = true;
            needed = level * 100;
        }
        return leveled;
    }

    public void addSkillPoints(int amount) {
        this.skillPoints += amount;
    }

    public Map<ChallengeType, Double> getDailyProgress() {
        return dailyProgress;
    }

    public Map<ChallengeType, Double> getWeeklyProgress() {
        return weeklyProgress;
    }

    public void addDailyProgress(ChallengeType type, double amount) {
        dailyProgress.put(type, dailyProgress.getOrDefault(type, 0.0) + amount);
    }

    public void addWeeklyProgress(ChallengeType type, double amount) {
        weeklyProgress.put(type, weeklyProgress.getOrDefault(type, 0.0) + amount);
    }

    public long getLastDailyReset() {
        return lastDailyReset;
    }

    public void setLastDailyReset(long time) {
        this.lastDailyReset = time;
        dailyProgress.replaceAll((t, v) -> 0.0);
    }

    public void loadLastDailyReset(long time) {
        this.lastDailyReset = time;
    }

    public long getLastWeeklyReset() {
        return lastWeeklyReset;
    }

    public void setLastWeeklyReset(long time) {
        this.lastWeeklyReset = time;
        weeklyProgress.replaceAll((t, v) -> 0.0);
    }

    public void loadLastWeeklyReset(long time) {
        this.lastWeeklyReset = time;
    }

    public boolean isScoreboardEnabled() {
        return scoreboardEnabled;
    }

    public void setScoreboardEnabled(boolean enabled) {
        this.scoreboardEnabled = enabled;
    }

    public double getLastBalance() {
        return lastBalance;
    }

    public void setLastBalance(double lastBalance) {
        this.lastBalance = lastBalance;
    }
}
