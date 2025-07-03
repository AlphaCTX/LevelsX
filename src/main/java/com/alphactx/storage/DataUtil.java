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
        // Basisgegevens
        data.setXp(cfg.getInt("xp", 0));
        data.setLevel(cfg.getInt("level", 1));
        data.addSkillPoints(cfg.getInt("skillPoints", 0));

        // Skill levels
        for (Skill s : Skill.values()) {
            data.setSkillLevel(s, cfg.getInt("skills." + s.name(), 0));
        }

        // Stats
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

        // Balans en scoreboard-instellingen
        data.setLastBalance(cfg.getDouble("lastBalance", 0));
        data.setScoreboardEnabled(cfg.getBoolean("scoreboardEnabled", false));
        data.setShowBalance(cfg.getBoolean("showBalance", false));

        // Board order (met default als leeg)
        List<String> order = cfg.getStringList("board.order");
        if (order.isEmpty()) {
            order = Arrays.stream(ScoreField.values())
                          .map(Enum::name)
                          .collect(Collectors.toList());
        }
        data.getBoardOrder().clear();
        for (String name : order) {
            try {
                data.getBoardOrder().add(ScoreField.valueOf(name));
            } catch (IllegalArgumentException ignored) {}
        }

        // Per-field enabled/disabled
        for (ScoreField f : ScoreField.values()) {
            // standaard aan voor level/xp/progress, en voor balance gelijk aan showBalance
            boolean def = (f == ScoreField.LEVEL || f == ScoreField.XP || f == ScoreField.PROGRESS)
                          || (f == ScoreField.BALANCE && data.isShowBalance());
            data.setFieldEnabled(f, cfg.getBoolean("board.enabled." + f.name(), def));
        }

        // Reset-tijden voor challenges
        data.loadLastDailyReset(cfg.getLong("lastDailyReset", System.currentTimeMillis()));
        data.loadLastWeeklyReset(cfg.getLong("lastWeeklyReset", System.currentTimeMillis()));

        // Progress-challenges
        for (ChallengeType ct : ChallengeType.values()) {
            data.addDailyProgress(ct, cfg.getDouble("daily." + ct.name(), 0.0));
            data.addWeeklyProgress(ct, cfg.getDouble("weekly." + ct.name(), 0.0));
        }
    }

    public static void save(FileConfiguration cfg, PlayerData data) {
        // Basisgegevens
        cfg.set("xp", data.getXp());
        cfg.set("level", data.getLevel());
        cfg.set("skillPoints", data.getSkillPoints());

        // Skill levels
        for (Skill s : Skill.values()) {
            cfg.set("skills." + s.name(), data.getSkillLevel(s));
        }

        // Stats
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

        // Balans en scoreboard-instellingen
        cfg.set("lastBalance", data.getLastBalance());
        cfg.set("scoreboardEnabled", data.isScoreboardEnabled());
        cfg.set("showBalance", data.isShowBalance());

        // Board order & per-field enabled
        cfg.set("board.order", data.getBoardOrder().stream()
                                    .map(Enum::name)
                                    .collect(Collectors.toList()));
        for (ScoreField f : ScoreField.values()) {
            cfg.set("board.enabled." + f.name(), data.isFieldEnabled(f));
        }

        // Reset-tijden voor challenges
        cfg.set("lastDailyReset", data.getLastDailyReset());
        cfg.set("lastWeeklyReset", data.getLastWeeklyReset());

        // Progress-challenges
        for (Map.Entry<ChallengeType, Double> e : data.getDailyProgress().entrySet()) {
            cfg.set("daily." + e.getKey().name(), e.getValue());
        }
        for (Map.Entry<ChallengeType, Double> e : data.getWeeklyProgress().entrySet()) {
            cfg.set("weekly." + e.getKey().name(), e.getValue());
        }
    }
}
