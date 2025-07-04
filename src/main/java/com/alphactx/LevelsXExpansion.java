package com.alphactx;

import com.alphactx.model.PlayerData;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * PlaceholderAPI expansion for LevelsX.
 */
public class LevelsXExpansion extends PlaceholderExpansion {

    private final LevelsX plugin;

    public LevelsXExpansion(LevelsX plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "levelsx";
    }

    @Override
    public @NotNull String getAuthor() {
        return "AlphaCtx";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        if (player == null) return "";
        PlayerData data = plugin.getPlayerData(player.getUniqueId());
        switch (params.toLowerCase()) {
            case "level":
                return String.valueOf(data.getLevel());
            case "xp":
                return String.valueOf(data.getXp());
            case "skillpoints":
                return String.valueOf(data.getSkillPoints());
            case "kills":
                return String.valueOf(data.getStats().getKills());
            case "mobkills":
                return String.valueOf(data.getStats().getMobKills());
            case "deaths":
                return String.valueOf(data.getStats().getDeaths());
            case "progress":
                return data.getXp() + "/" + (data.getLevel() * 100);
            default:
                return "";
        }
    }

    @Override
    public boolean persist() {
        return true;
    }
}
