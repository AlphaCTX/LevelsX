package com.alphactx.storage;

import com.alphactx.model.ChallengeType;
import com.alphactx.model.PlayerData;
import com.alphactx.model.Skill;
import com.alphactx.model.Stats;
import com.alphactx.model.ScoreField;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.*;
import java.util.stream.Collectors;

public final class DataUtil {
    private DataUtil() {}

    public static void load(FileConfiguration cfg, PlayerData data) {
        data.setXp(cfg.getInt("xp", 0));
        data.setLevel(cfg.getInt("level", 1));
        data.addSkillPoints(cfg.getInt("skillPoints", 0));
        for (Skill s : Skill.values()) {
            data.setSkillLevel(s, cfg.getInt("skills." + s.name(), 0));
        }
        Stats stats = data.getStats();
        stats.addKill(cfg.getInt("stats.kills", 0));
        stats.addMobKill(cfg.getInt("stats.mobKills", 0));
        stats.addDeath(cfg.getInt("stats.deaths", 0));
        stats.addDamageDealt(cfg.getDouble("stats.damageDealt", 0));
        stats.addDamageTaken(cfg.getDouble("stats.damageTaken", 0));
        stats.addMoneyEarned(cfg.getDouble("stats.moneyEarned", 0));
        stats.addMoneySpent(cfg.getDouble("stats.moneySpent", 0));
        stats.addKilometersTraveled(cfg.getDouble("stats.km", 0));
        stats.setTimeOnline(cfg.getLong("stats.time", 0));
        data.setLastBalance(cfg.getDouble("lastBalance", 0));
        data.setScoreboardEnabled(cfg.getBoolean("scoreboardEnabled", false));
        data.setShowBalance(cfg.getBoolean("showBalance", false));
        List<String> order = cfg.getStringList("board.order");
        if (order.isEmpty()) {
            order = Arrays.stream(ScoreField.values()).map(Enum::name).collect(Collectors.toList());
        }
        data.getBoardOrder().clear();
        for (String s : order) {
            try { data.getBoardOrder().add(ScoreField.valueOf(s)); } catch (IllegalArgumentException ignored) {}
        }
        for (ScoreField f : ScoreField.values()) {
            boolean def = f == ScoreField.LEVEL || f == ScoreField.XP || f == ScoreField.PROGRESS;
            if (f == ScoreField.BALANCE) def = data.isShowBalance();
            data.setFieldEnabled(f, cfg.getBoolean("board.enabled."+f.name(), def));
        }
        data.loadLastDailyReset(cfg.getLong("lastDailyReset", System.currentTimeMillis()));
        data.loadLastWeeklyReset(cfg.getLong("lastWeeklyReset", System.currentTimeMillis()));
        for (ChallengeType ct : ChallengeType.values()) {
            data.addDailyProgress(ct, cfg.getDouble("daily." + ct.name(), 0));
            data.addWeeklyProgress(ct, cfg.getDouble("weekly." + ct.name(), 0));
        }
    }

    public static void save(FileConfiguration cfg, PlayerData data) {
        cfg.set("xp", data.getXp());
        cfg.set("level", data.getLevel());
        cfg.set("skillPoints", data.getSkillPoints());
        for (Skill s : Skill.values()) {
            cfg.set("skills." + s.name(), data.getSkillLevel(s));
        }
        Stats stats = data.getStats();
        cfg.set("stats.kills", stats.getKills());
        cfg.set("stats.mobKills", stats.getMobKills());
        cfg.set("stats.deaths", stats.getDeaths());
        cfg.set("stats.damageDealt", stats.getDamageDealt());
        cfg.set("stats.damageTaken", stats.getDamageTaken());
        cfg.set("stats.moneyEarned", stats.getMoneyEarned());
        cfg.set("stats.moneySpent", stats.getMoneySpent());
        cfg.set("stats.km", stats.getKilometersTraveled());
        cfg.set("stats.time", stats.getTimeOnline());
        cfg.set("lastBalance", data.getLastBalance());
        cfg.set("scoreboardEnabled", data.isScoreboardEnabled());
        cfg.set("showBalance", data.isFieldEnabled(ScoreField.BALANCE));
        cfg.set("board.order", data.getBoardOrder().stream().map(Enum::name).collect(Collectors.toList()));
        for (ScoreField f : ScoreField.values()) {
            cfg.set("board.enabled."+f.name(), data.isFieldEnabled(f));
        }
        cfg.set("lastDailyReset", data.getLastDailyReset());
        cfg.set("lastWeeklyReset", data.getLastWeeklyReset());
        for (Map.Entry<ChallengeType, Double> e : data.getDailyProgress().entrySet()) {
            cfg.set("daily." + e.getKey().name(), e.getValue());
        }
        for (Map.Entry<ChallengeType, Double> e : data.getWeeklyProgress().entrySet()) {
            cfg.set("weekly." + e.getKey().name(), e.getValue());
        }
    }
}
