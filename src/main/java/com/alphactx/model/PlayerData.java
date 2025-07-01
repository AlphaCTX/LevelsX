package com.alphactx.model;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

public class PlayerData {
    private final UUID uuid;
    private int level = 1;
    private int xp = 0;
    private int skillPoints = 0;
    private long lastJoin;
    private final Stats stats = new Stats();
    private final Map<Skill, Integer> skills = new EnumMap<>(Skill.class);

    public PlayerData(UUID uuid) {
        this.uuid = uuid;
        for (Skill skill : Skill.values()) {
            skills.put(skill, 0);
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
}
