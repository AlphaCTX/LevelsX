package com.alphactx;

import com.alphactx.model.PlayerData;
import com.alphactx.model.Skill;
import com.alphactx.model.Stats;
import com.alphactx.model.ChallengeType;
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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.*;
import java.util.stream.Collectors;

public class LevelsX extends JavaPlugin implements Listener {
    private final Map<UUID, PlayerData> players = new HashMap<>();
    private final Map<ChallengeType, Double> challengeGoals = new EnumMap<>(ChallengeType.class);

    private void initChallenges() {
        FileConfiguration cfg = getConfig();
        for (ChallengeType type : ChallengeType.values()) {
            challengeGoals.put(type, cfg.getDouble("challengeGoals." + type.name(), getDefaultGoal(type)));
        }
    }

    private double getDefaultGoal(ChallengeType type) {
        switch (type) {
            case MOB_KILLS:
                return 10.0;
            case DAMAGE_TAKEN:
                return 500.0;
            case MONEY_EARNED:
            case MONEY_SPENT:
                return 1000.0;
            case KILOMETERS_TRAVELED:
                return 5.0;
            default:
                return 0;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        initChallenges();
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
        long now = System.currentTimeMillis();
        if (now - data.getLastChallengeReset() > 86400000L) {
            data.setLastChallengeReset(now);
        }
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
                data.addChallengeProgress(ChallengeType.MOB_KILLS, 1);
                checkChallenge(killer, data, ChallengeType.MOB_KILLS);
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
            PlayerData data = getData(player.getUniqueId());
            data.getStats().addDamageDealt(event.getDamage());
        }
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            PlayerData data = getData(player.getUniqueId());
            data.getStats().addDamageTaken(event.getDamage());
            data.addChallengeProgress(ChallengeType.DAMAGE_TAKEN, event.getDamage());
            checkChallenge(player, data, ChallengeType.DAMAGE_TAKEN);
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getFrom().getWorld().equals(event.getTo().getWorld())) {
            double distance = event.getFrom().distance(event.getTo());
            PlayerData data = getData(event.getPlayer().getUniqueId());
            double km = distance / 1000.0;
            data.getStats().addKilometersTraveled(km);
            data.addChallengeProgress(ChallengeType.KILOMETERS_TRAVELED, km);
            checkChallenge(event.getPlayer(), data, ChallengeType.KILOMETERS_TRAVELED);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        String title = event.getView().getTitle();
        if (title.equals("Skills")) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            if (slot >= 0 && slot < Skill.values().length) {
                Skill skill = Skill.values()[slot];
                PlayerData data = getData(player.getUniqueId());
                data.levelSkill(skill);
                openSkillGui(player);
            }
        } else if (title.equals("Admin")) {
            event.setCancelled(true);
            if (event.getRawSlot() == 4) {
                double money = getConfig().getDouble("moneyReward", 100.0);
                if (event.isLeftClick()) {
                    money -= 10;
                } else if (event.isRightClick()) {
                    money += 10;
                }
                getConfig().set("moneyReward", Math.max(0, money));
                saveConfig();
                openAdminGui(player);
            }
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
                data.getStats().addMoneyEarned(money);
                data.addChallengeProgress(ChallengeType.MONEY_EARNED, money);
                checkChallenge(player, data, ChallengeType.MONEY_EARNED);
            }
        }
    }

    private void openSkillGui(Player player) {
        Inventory inv = Bukkit.createInventory(player, 9, "Skills");
        PlayerData data = getData(player.getUniqueId());
        int slot = 0;
        for (Skill skill : Skill.values()) {
            ItemStack item = new ItemStack(Material.BOOK);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(skill.name() + " Lv." + data.getSkillLevel(skill));
            item.setItemMeta(meta);
            inv.setItem(slot++, item);
        }
        player.openInventory(inv);
    }

    private void openAdminGui(Player player) {
        Inventory inv = Bukkit.createInventory(player, 9, "Admin");
        ItemStack item = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("Money reward: $" + getConfig().getDouble("moneyReward", 100.0));
        item.setItemMeta(meta);
        inv.setItem(4, item);
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

    private void checkChallenge(Player player, PlayerData data, ChallengeType type) {
        double progress = data.getChallengeProgress().get(type);
        double goal = challengeGoals.getOrDefault(type, Double.MAX_VALUE);
        if (progress >= goal) {
            player.sendMessage("Challenge completed: " + type.name().toLowerCase());
            awardXp(player, 20);
            data.getChallengeProgress().put(type, goal);
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
        if (args[0].equalsIgnoreCase("challenges")) {
            PlayerData data = getData(player.getUniqueId());
            player.sendMessage("Daily challenges:");
            for (ChallengeType type : ChallengeType.values()) {
                double progress = data.getChallengeProgress().get(type);
                double goal = challengeGoals.get(type);
                player.sendMessage("- " + type.name().toLowerCase() + ": " + progress + "/" + goal);
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("spend") && args.length > 1) {
            try {
                double amount = Double.parseDouble(args[1]);
                PlayerData data = getData(player.getUniqueId());
                data.getStats().addMoneySpent(amount);
                data.addChallengeProgress(ChallengeType.MONEY_SPENT, amount);
                checkChallenge(player, data, ChallengeType.MONEY_SPENT);
                player.sendMessage("You spent $" + amount);
            } catch (NumberFormatException e) {
                player.sendMessage("Invalid amount");
            }
            return true;
        }
        player.sendMessage("/skill, /skill admin, /skill leaderboard <stat>, /skill challenges, /skill spend <amount>");
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
                if (cfg.isConfigurationSection("players." + key + ".challenges")) {
                    for (String t : cfg.getConfigurationSection("players." + key + ".challenges").getKeys(false)) {
                        ChallengeType type = ChallengeType.valueOf(t);
                        data.addChallengeProgress(type, cfg.getDouble("players." + key + ".challenges." + t));
                    }
                }
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
            for (Map.Entry<ChallengeType, Double> e : data.getChallengeProgress().entrySet()) {
                cfg.set("players." + id + ".challenges." + e.getKey().name(), e.getValue());
            }
        }
        saveConfig();
    }
}
