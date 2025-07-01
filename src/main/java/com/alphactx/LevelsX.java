package com.alphactx;

import com.alphactx.model.PlayerData;
import com.alphactx.model.Skill;
import com.alphactx.model.Stats;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.*;
import java.util.stream.Collectors;

public class LevelsX extends JavaPlugin implements Listener {
    private final Map<UUID, PlayerData> players = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadData();
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("LevelsX enabled");
    }

    @Override
    public void onDisable() {
        saveData();
    }

    private PlayerData getData(UUID uuid) {
        return players.computeIfAbsent(uuid, PlayerData::new);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        PlayerData data = getData(event.getPlayer().getUniqueId());
        data.setLastJoin(System.currentTimeMillis());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        PlayerData data = getData(event.getPlayer().getUniqueId());
        long session = System.currentTimeMillis() - data.getLastJoin();
        data.getStats().addTimeOnline(session);
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity().getKiller() != null) {
            Player killer = event.getEntity().getKiller();
            PlayerData data = getData(killer.getUniqueId());
            if (event.getEntityType() == EntityType.PLAYER) {
                data.getStats().addKill();
            } else {
                data.getStats().addMobKill();
            }
            awardXp(killer, 10);
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        getData(player.getUniqueId()).getStats().addDeath();
    }

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player) {
            Player player = (Player) event.getDamager();
            getData(player.getUniqueId()).getStats().addDamageDealt(event.getDamage());
        }
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            getData(player.getUniqueId()).getStats().addDamageTaken(event.getDamage());
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getFrom().getWorld().equals(event.getTo().getWorld())) {
            double distance = event.getFrom().distance(event.getTo());
            getData(event.getPlayer().getUniqueId()).getStats().addKilometersTraveled(distance / 1000.0);
        }
    }

    private void awardXp(Player player, int amount) {
        PlayerData data = getData(player.getUniqueId());
        data.addXp(amount);
        if (data.tryLevelUp()) {
            player.sendMessage("Leveled up to " + data.getLevel());
            if (data.getLevel() % 20 == 0) {
                ItemStack reward = new ItemStack(Material.DIAMOND);
                player.getInventory().addItem(reward);
            } else if (data.getLevel() % 5 != 0) {
                double money = getConfig().getDouble("moneyReward", 100.0);
                player.sendMessage("You earned $" + money);
            }
        }
    }

    private void openSkillGui(Player player) {
        Inventory inv = Bukkit.createInventory(player, 9, "Skills");
        player.openInventory(inv);
    }

    private void openAdminGui(Player player) {
        Inventory inv = Bukkit.createInventory(player, 9, "Admin");
        player.openInventory(inv);
    }

    private void showLeaderboard(Player player, String stat) {
        List<Map.Entry<UUID, PlayerData>> sorted = players.entrySet().stream()
                .sorted((a, b) -> compareStat(b.getValue().getStats(), a.getValue().getStats(), stat))
                .limit(10)
                .collect(Collectors.toList());
        player.sendMessage("Leaderboard for " + stat + ":");
        int i = 1;
        for (Map.Entry<UUID, PlayerData> e : sorted) {
            String name = Optional.ofNullable(Bukkit.getOfflinePlayer(e.getKey()).getName()).orElse("Unknown");
            player.sendMessage(i + ". " + name + " - " + getStatValue(e.getValue().getStats(), stat));
            i++;
        }
    }

    private int compareStat(Stats a, Stats b, String stat) {
        return Double.compare(getStatValue(a, stat), getStatValue(b, stat));
    }

    private double getStatValue(Stats stats, String stat) {
        switch (stat.toLowerCase()) {
            case "kills":
                return stats.getKills();
            case "mobkills":
                return stats.getMobKills();
            case "deaths":
                return stats.getDeaths();
            case "damage":
                return stats.getDamageDealt();
            case "distance":
                return stats.getKilometersTraveled();
            default:
                return 0;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players may use this command.");
            return true;
        }
        Player player = (Player) sender;
        if (args.length == 0) {
            openSkillGui(player);
            return true;
        }
        if (args[0].equalsIgnoreCase("admin")) {
            if (!player.hasPermission("skill.admin")) {
                player.sendMessage("No permission");
                return true;
            }
            openAdminGui(player);
            return true;
        }
        if (args[0].equalsIgnoreCase("leaderboard")) {
            String stat = args.length > 1 ? args[1] : "kills";
            showLeaderboard(player, stat);
            return true;
        }
        player.sendMessage("/skill, /skill admin, /skill leaderboard <stat>");
        return true;
    }

    private void loadData() {
        FileConfiguration cfg = getConfig();
        if (cfg.isConfigurationSection("players")) {
            for (String key : cfg.getConfigurationSection("players").getKeys(false)) {
                UUID id = UUID.fromString(key);
                PlayerData data = new PlayerData(id);
                data.setLevel(cfg.getInt("players." + key + ".level", 1));
                data.setXp(cfg.getInt("players." + key + ".xp", 0));
                data.addSkillPoints(cfg.getInt("players." + key + ".skillPoints", 0));
                Stats stats = data.getStats();
                stats.addKill(cfg.getInt("players." + key + ".stats.kills", 0));
                stats.addMobKill(cfg.getInt("players." + key + ".stats.mobKills", 0));
                stats.addDeath(cfg.getInt("players." + key + ".stats.deaths", 0));
                stats.addDamageDealt(cfg.getDouble("players." + key + ".stats.damageDealt", 0));
                stats.addDamageTaken(cfg.getDouble("players." + key + ".stats.damageTaken", 0));
                stats.addMoneyEarned(cfg.getDouble("players." + key + ".stats.moneyEarned", 0));
                stats.addMoneySpent(cfg.getDouble("players." + key + ".stats.moneySpent", 0));
                stats.addKilometersTraveled(cfg.getDouble("players." + key + ".stats.km", 0));
                players.put(id, data);
            }
        }
    }

    private void saveData() {
        FileConfiguration cfg = getConfig();
        for (Map.Entry<UUID, PlayerData> entry : players.entrySet()) {
            UUID id = entry.getKey();
            PlayerData data = entry.getValue();
            cfg.set("players." + id + ".xp", data.getXp());
            cfg.set("players." + id + ".level", data.getLevel());
            cfg.set("players." + id + ".skillPoints", data.getSkillPoints());
            Stats stats = data.getStats();
            cfg.set("players." + id + ".stats.kills", stats.getKills());
            cfg.set("players." + id + ".stats.mobKills", stats.getMobKills());
            cfg.set("players." + id + ".stats.deaths", stats.getDeaths());
            cfg.set("players." + id + ".stats.damageDealt", stats.getDamageDealt());
            cfg.set("players." + id + ".stats.damageTaken", stats.getDamageTaken());
            cfg.set("players." + id + ".stats.moneyEarned", stats.getMoneyEarned());
            cfg.set("players." + id + ".stats.moneySpent", stats.getMoneySpent());
            cfg.set("players." + id + ".stats.km", stats.getKilometersTraveled());
        }
        saveConfig();
    }
}
