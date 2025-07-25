package com.alphactx.model;

import java.util.*;
import java.util.stream.Collectors;

import com.alphactx.model.ChallengeType;
import com.alphactx.model.ScoreField;

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
    private boolean showBalance = false;
    private double lastBalance = 0.0;
    private final List<ScoreField> boardOrder = new ArrayList<>();
    private final Map<ScoreField, Boolean> boardEnabled = new EnumMap<>(ScoreField.class);

    public PlayerData(UUID uuid) {
        this.uuid = uuid;
        for (Skill skill : Skill.values()) {
            skills.put(skill, 0);
        }
        for (ChallengeType type : ChallengeType.values()) {
            dailyProgress.put(type, 0.0);
            weeklyProgress.put(type, 0.0);
        }
        boardOrder.addAll(Arrays.asList(
                ScoreField.LEVEL,
                ScoreField.XP,
                ScoreField.PROGRESS,
                ScoreField.BALANCE,
                ScoreField.KILLS,
                ScoreField.MOB_KILLS,
                ScoreField.DEATHS,
                ScoreField.KM
        ));
        for (ScoreField f : ScoreField.values()) {
            boolean def = f == ScoreField.LEVEL || f == ScoreField.XP || f == ScoreField.PROGRESS;
            boardEnabled.put(f, def);
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

    /** Set the player's skill point balance directly. */
    public void setSkillPoints(int amount) {
        this.skillPoints = amount;
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

    public boolean isShowBalance() {
        return showBalance;
    }

    public void setShowBalance(boolean showBalance) {
        this.showBalance = showBalance;
    }

    public double getLastBalance() {
        return lastBalance;
    }

    public void setLastBalance(double lastBalance) {
        this.lastBalance = lastBalance;
    }

    public List<ScoreField> getBoardOrder() {
        return boardOrder;
    }

    public boolean isFieldEnabled(ScoreField field) {
        return boardEnabled.getOrDefault(field, false);
    }

    public void setFieldEnabled(ScoreField field, boolean enabled) {
        boardEnabled.put(field, enabled);
    }
}
