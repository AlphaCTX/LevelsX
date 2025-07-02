package com.alphactx;

import com.alphactx.model.PlayerData;
import com.alphactx.model.Skill;
import com.alphactx.model.Stats;
import com.alphactx.model.ChallengeType;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
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
import org.bukkit.event.player.AsyncPlayerChatEvent;
import com.alphactx.storage.DataStorage;
import com.alphactx.storage.SqliteStorage;
import com.alphactx.storage.MySqlStorage;
import com.alphactx.storage.DataUtil;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.ChatColor;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.*;
import java.io.File;
import java.util.stream.Collectors;

public class LevelsX extends JavaPlugin implements Listener, TabCompleter {
    private final Map<UUID, PlayerData> players = new HashMap<>();
    private final Map<ChallengeType, Double> challengeGoals = new EnumMap<>(ChallengeType.class);
    private final List<ChallengeType> activeDaily = new ArrayList<>();
    private final List<ChallengeType> activeWeekly = new ArrayList<>();
    private long lastDailySelect;
    private long lastWeeklySelect;
    private ScoreboardManager scoreboardManager;
    private Economy economy;
    private final Map<UUID, Integer> pendingRewardLevel = new HashMap<>();
    private int levelCap;
    private DataStorage storage;

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

    private void selectDailyChallenges() {
        activeDaily.clear();
        List<ChallengeType> list = new ArrayList<>(Arrays.asList(ChallengeType.values()));
        Collections.shuffle(list);
        for (int i = 0; i < 3 && i < list.size(); i++) {
            activeDaily.add(list.get(i));
        }
    }

    private void selectWeeklyChallenges() {
        activeWeekly.clear();
        List<ChallengeType> list = new ArrayList<>(Arrays.asList(ChallengeType.values()));
        Collections.shuffle(list);
        for (int i = 0; i < 3 && i < list.size(); i++) {
            activeWeekly.add(list.get(i));
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        scoreboardManager = Bukkit.getScoreboardManager();
        levelCap = getConfig().getInt("levelCap", 100);
        setupStorage();
        if (!setupEconomy()) {
            getLogger().severe("Vault dependency not found");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        initChallenges();
        selectDailyChallenges();
        selectWeeklyChallenges();
        lastDailySelect = System.currentTimeMillis();
        lastWeeklySelect = lastDailySelect;
        loadData();
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("skill")).setTabCompleter(this);
        Bukkit.getScheduler().runTaskTimer(this, this::checkBalances, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::checkChallengeResets, 72000L, 72000L);
        getLogger().info("LevelsX enabled");
    }

    @Override
    public void onDisable() {
        checkBalances();
        saveData();
        try {
            if (storage != null) storage.close();
        } catch (Exception e) {
            getLogger().severe("Failed to close storage: " + e.getMessage());
        }
    }

    private PlayerData getData(UUID uuid) {
        return players.computeIfAbsent(uuid, PlayerData::new);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        PlayerData data = getData(event.getPlayer().getUniqueId());
        data.setLastJoin(System.currentTimeMillis());
        data.setLastBalance(economy.getBalance(event.getPlayer()));
        long now = System.currentTimeMillis();
        if (now - data.getLastDailyReset() > 86400000L) {
            data.setLastDailyReset(now);
        }
        if (now - data.getLastWeeklyReset() > 604800000L) {
            data.setLastWeeklyReset(now);
        }
        updateLungCapacity(event.getPlayer(), data);
        updateScoreboard(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        PlayerData data = getData(event.getPlayer().getUniqueId());
        long session = System.currentTimeMillis() - data.getLastJoin();
        data.getStats().addTimeOnline(session);
        double current = economy.getBalance(event.getPlayer());
        double diff = current - data.getLastBalance();
        if (Math.abs(diff) > 0.01) {
            if (diff > 0) {
                data.getStats().addMoneyEarned(diff);
                addProgress(event.getPlayer(), data, ChallengeType.MONEY_EARNED, diff);
            } else {
                diff = -diff;
                data.getStats().addMoneySpent(diff);
                addProgress(event.getPlayer(), data, ChallengeType.MONEY_SPENT, diff);
            }
        }
        data.setLastBalance(current);
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        event.setFormat(ChatColor.GOLD + "[LevelsX] " + ChatColor.RESET + "%s: %s");
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity().getKiller() != null) {
            Player killer = event.getEntity().getKiller();
            PlayerData data = getData(killer.getUniqueId());
            int xp = 0;
            if (event.getEntityType() == EntityType.PLAYER) {
                data.getStats().addKill();
                xp = 25;
            } else {
                data.getStats().addMobKill();
                xp = 10;
                addProgress(killer, data, ChallengeType.MOB_KILLS, 1);
            }
            int healLvl = data.getSkillLevel(Skill.HEALING);
            if (healLvl > 0) {
                double amount = healLvl * 2.0;
                killer.setHealth(Math.min(killer.getMaxHealth(), killer.getHealth() + amount));
            }
            awardXp(killer, xp);
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
            int dmgLvl = data.getSkillLevel(Skill.DAMAGE);
            if (dmgLvl > 0) {
                event.setDamage(event.getDamage() * (1 + 0.05 * dmgLvl));
            }
            int steal = data.getSkillLevel(Skill.LIFESTEAL);
            if (steal > 0) {
                double heal = event.getDamage() * 0.05 * steal;
                player.setHealth(Math.min(player.getMaxHealth(), player.getHealth() + heal));
            }
        }
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            PlayerData data = getData(player.getUniqueId());
            data.getStats().addDamageTaken(event.getDamage());
            int reduce = data.getSkillLevel(Skill.DAMAGE_REDUCTION);
            if (reduce > 0) {
                event.setDamage(event.getDamage() * (1 - 0.05 * reduce));
            }
            addProgress(player, data, ChallengeType.DAMAGE_TAKEN, event.getDamage());
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getFrom().getWorld().equals(event.getTo().getWorld())) {
            double distance = event.getFrom().distance(event.getTo());
            PlayerData data = getData(event.getPlayer().getUniqueId());
            double km = distance / 1000.0;
            data.getStats().addKilometersTraveled(km);
            addProgress(event.getPlayer(), data, ChallengeType.KILOMETERS_TRAVELED, km);
            int xp = (int) Math.round(km * 5);
            if (xp > 0) {
                awardXp(event.getPlayer(), xp);
            }
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
                updateLungCapacity(player, data);
                openSkillGui(player);
            } else if (slot == 6) {
                openChallengesGui(player);
            } else if (slot == 7) {
                openStatsGui(player);
            }
        } else if (title.equals("Admin")) {
            event.setCancelled(true);
            int rewardSlots = levelCap / 20;
            int capSlot = event.getInventory().getSize() - 3;
            int moneySlot = capSlot + 1;
            int backSlot = capSlot + 2;
            if (pendingRewardLevel.containsKey(player.getUniqueId()) && event.getClickedInventory() == player.getInventory()) {
                ItemStack current = event.getCurrentItem();
                if (current != null && current.getType() != Material.AIR) {
                    int lvl = pendingRewardLevel.remove(player.getUniqueId());
                    getConfig().set("itemRewards." + lvl, current.getType().name());
                    saveConfig();
                    msg(player, "Set reward for level " + lvl + " to " + current.getType());
                    openAdminGui(player);
                }
                return;
            }
            if (event.getRawSlot() >= 0 && event.getRawSlot() < rewardSlots) {
                int level = (event.getRawSlot() + 1) * 20;
                pendingRewardLevel.put(player.getUniqueId(), level);
                msg(player, "Click an item in your inventory to set reward for level " + level);
            } else if (event.getRawSlot() == moneySlot) {
                double money = getConfig().getDouble("moneyReward", 100.0);
                if (event.isLeftClick()) {
                    money -= 10;
                } else if (event.isRightClick()) {
                    money += 10;
                }
                getConfig().set("moneyReward", Math.max(0, money));
                saveConfig();
                openAdminGui(player);
            } else if (event.getRawSlot() == capSlot) {
                if (event.isLeftClick()) {
                    levelCap = Math.max(1, levelCap - 10);
                } else if (event.isRightClick()) {
                    levelCap += 10;
                }
                getConfig().set("levelCap", levelCap);
                saveConfig();
                openAdminGui(player);
            } else if (event.getRawSlot() == backSlot) {
                openSkillGui(player);
            }
        } else if (title.equals("Stats")) {
            event.setCancelled(true);
            if (event.getRawSlot() == 26) {
                openSkillGui(player);
            }
        } else if (title.equals("Challenges")) {
            event.setCancelled(true);
            if (event.getRawSlot() == 8) {
                openSkillGui(player);
            }
        }
    }

    private void awardXp(Player player, int amount) {
        PlayerData data = getData(player.getUniqueId());
        data.addXp(amount);
        if (data.getLevel() < levelCap && data.tryLevelUp()) {
            msg(player, "Leveled up to " + data.getLevel());
            if (data.getLevel() % 20 == 0) {
                String matName = getConfig().getString("itemRewards." + data.getLevel(), getConfig().getString("itemReward", "DIAMOND"));
                Material mat = Material.matchMaterial(matName);
                if (mat != null && mat != Material.AIR) {
                    player.getInventory().addItem(new ItemStack(mat));
                }
            } else if (data.getLevel() % 5 != 0) {
                double money = getConfig().getDouble("moneyReward", 100.0);
                economy.depositPlayer(player, money);
                msg(player, "You earned $" + money);
                data.getStats().addMoneyEarned(money);
                addProgress(player, data, ChallengeType.MONEY_EARNED, money);
                data.setLastBalance(economy.getBalance(player));
            }
        } else if (data.getLevel() >= levelCap) {
            int needed = data.getLevel() * 100;
            data.setXp(Math.min(data.getXp(), needed));
        }
        updateScoreboard(player);
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
        ItemStack challenges = new ItemStack(Material.PAPER);
        ItemMeta ch = challenges.getItemMeta();
        ch.setDisplayName("Challenges");
        challenges.setItemMeta(ch);
        inv.setItem(6, challenges);

        ItemStack stats = new ItemStack(Material.BOOK);
        ItemMeta sm = stats.getItemMeta();
        sm.setDisplayName("View Stats");
        stats.setItemMeta(sm);
        inv.setItem(7, stats);

        ItemStack points = new ItemStack(Material.EXPERIENCE_BOTTLE);
        ItemMeta pm = points.getItemMeta();
        pm.setDisplayName("Skill Points: " + data.getSkillPoints());
        points.setItemMeta(pm);
        inv.setItem(8, points);

        player.openInventory(inv);
    }

    private void openAdminGui(Player player) {
        int rewardSlots = levelCap / 20;
        int total = rewardSlots + 3;
        int size = ((total + 8) / 9) * 9;
        Inventory inv = Bukkit.createInventory(player, size, "Admin");
        for (int i = 0; i < rewardSlots; i++) {
            int level = (i + 1) * 20;
            String matName = getConfig().getString("itemRewards." + level, getConfig().getString("itemReward", "DIAMOND"));
            Material mat = Material.matchMaterial(matName);
            if (mat == null) mat = Material.DIRT;
            ItemStack it = new ItemStack(mat);
            ItemMeta im = it.getItemMeta();
            im.setDisplayName("Level " + level + " reward");
            it.setItemMeta(im);
            inv.setItem(i, it);
        }

        int capSlot = size - 3;
        ItemStack cap = new ItemStack(Material.EXPERIENCE_BOTTLE);
        ItemMeta cm = cap.getItemMeta();
        cm.setDisplayName("Level Cap: " + levelCap);
        cap.setItemMeta(cm);
        inv.setItem(capSlot, cap);

        ItemStack item = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("Money reward: $" + getConfig().getDouble("moneyReward", 100.0));
        item.setItemMeta(meta);
        inv.setItem(capSlot + 1, item);

        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta bm = back.getItemMeta();
        bm.setDisplayName("Back");
        back.setItemMeta(bm);
        inv.setItem(capSlot + 2, back);

        player.openInventory(inv);
    }

    private void openChallengesGui(Player player) {
        Inventory inv = Bukkit.createInventory(player, 18, "Challenges");
        PlayerData data = getData(player.getUniqueId());
        int i = 0;
        for (ChallengeType type : activeDaily) {
            double progress = data.getDailyProgress().get(type);
            double goal = challengeGoals.get(type);
            inv.setItem(i++, createItem(Material.PAPER, "Daily " + type.name(), String.format("%.1f/%.1f", progress, goal)));
        }
        i = 9;
        for (ChallengeType type : activeWeekly) {
            double progress = data.getWeeklyProgress().get(type);
            double goal = challengeGoals.get(type);
            inv.setItem(i++, createItem(Material.MAP, "Weekly " + type.name(), String.format("%.1f/%.1f", progress, goal)));
        }
        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta bm = back.getItemMeta();
        bm.setDisplayName("Back");
        back.setItemMeta(bm);
        inv.setItem(17, back);
        player.openInventory(inv);
    }

    private void openStatsGui(Player player) {
        Inventory inv = Bukkit.createInventory(player, 27, "Stats");
        PlayerData data = getData(player.getUniqueId());
        Stats stats = data.getStats();
        inv.setItem(0, createItem(Material.EXPERIENCE_BOTTLE, "Level " + data.getLevel(),
                "XP: " + data.getXp(),
                "Skill points: " + data.getSkillPoints()));
        inv.setItem(1, createItem(Material.IRON_SWORD, "Kills", String.valueOf(stats.getKills())));
        inv.setItem(2, createItem(Material.ZOMBIE_HEAD, "Mob Kills", String.valueOf(stats.getMobKills())));
        inv.setItem(3, createItem(Material.SKELETON_SKULL, "Deaths", String.valueOf(stats.getDeaths())));
        inv.setItem(4, createItem(Material.DIAMOND_SWORD, "Damage Dealt", String.format("%.1f", stats.getDamageDealt())));
        inv.setItem(5, createItem(Material.SHIELD, "Damage Taken", String.format("%.1f", stats.getDamageTaken())));
        inv.setItem(6, createItem(Material.EMERALD, "Money Earned", String.format("%.2f", stats.getMoneyEarned())));
        inv.setItem(7, createItem(Material.GOLD_INGOT, "Money Spent", String.format("%.2f", stats.getMoneySpent())));
        inv.setItem(8, createItem(Material.COMPASS, "Kilometers Traveled", String.format("%.2f", stats.getKilometersTraveled())));
        inv.setItem(9, createItem(Material.CLOCK, "Time Online", String.format("%.1f h", stats.getTimeOnline() / 3600000.0)));
        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta bm = back.getItemMeta();
        bm.setDisplayName("Back");
        back.setItemMeta(bm);
        inv.setItem(26, back);
        player.openInventory(inv);
    }

    private void showLeaderboard(Player player, String stat) {
        List<Map.Entry<UUID, PlayerData>> sorted = players.entrySet().stream()
                .sorted((a, b) -> compareStat(b.getValue().getStats(), a.getValue().getStats(), stat))
                .limit(10)
                .collect(Collectors.toList());
        msg(player, "Leaderboard for " + stat + ":");
        int i = 1;
        for (Map.Entry<UUID, PlayerData> e : sorted) {
            String name = Optional.ofNullable(Bukkit.getOfflinePlayer(e.getKey()).getName()).orElse("Unknown");
            msg(player, i + ". " + name + " - " + getStatValue(e.getValue().getStats(), stat));
            i++;
        }
    }

    private void checkChallenge(Player player, PlayerData data, ChallengeType type, boolean daily) {
        double progress = daily ? data.getDailyProgress().get(type) : data.getWeeklyProgress().get(type);
        double goal = challengeGoals.getOrDefault(type, Double.MAX_VALUE);
        if (progress >= goal) {
            msg(player, (daily ? "Daily" : "Weekly") + " challenge completed: " + type.name().toLowerCase());
            awardXp(player, daily ? 20 : 20);
            if (daily) {
                data.getDailyProgress().put(type, goal);
            } else {
                data.getWeeklyProgress().put(type, goal);
            }
        }
    }

    private void addProgress(Player player, PlayerData data, ChallengeType type, double amount) {
        if (activeDaily.contains(type)) {
            data.addDailyProgress(type, amount);
            checkChallenge(player, data, type, true);
        }
        if (activeWeekly.contains(type)) {
            data.addWeeklyProgress(type, amount);
            checkChallenge(player, data, type, false);
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
            msg(sender, "Only players may use this command.");
            return true;
        }
        Player player = (Player) sender;
        if (args.length == 0) {
            openSkillGui(player);
            return true;
        }
        if (args[0].equalsIgnoreCase("help")) {
            msg(player, "/skill - open GUI");
            msg(player, "/skill admin - admin GUI");
            msg(player, "/skill leaderboard <stat>");
            msg(player, "/skill stats");
            msg(player, "/skill challenges");
            msg(player, "/skill spend <amount>");
            msg(player, "/skill scoreboard");
            msg(player, "/skill backup <sql|sqlite>");
            return true;
        }
        if (args[0].equalsIgnoreCase("admin")) {
            if (!player.hasPermission("skill.admin")) {
                msg(player, "No permission");
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
        if (args[0].equalsIgnoreCase("stats")) {
            openStatsGui(player);
            return true;
        }
        if (args[0].equalsIgnoreCase("challenges")) {
            openChallengesGui(player);
            return true;
        }
        if (args[0].equalsIgnoreCase("scoreboard")) {
            toggleScoreboard(player);
            return true;
        }
        if (args[0].equalsIgnoreCase("backup") && args.length > 1) {
            if (!player.hasPermission("skill.admin")) {
                msg(player, "No permission");
                return true;
            }
            String dest = args[1];
            try {
                if (dest.equalsIgnoreCase("sql")) {
                    FileConfiguration cfg = getConfig();
                    MySqlStorage backup = new MySqlStorage(
                            cfg.getString("storage.mysql.host", "localhost"),
                            cfg.getInt("storage.mysql.port", 3306),
                            cfg.getString("storage.mysql.database", "levelsx"),
                            cfg.getString("storage.mysql.user", "root"),
                            cfg.getString("storage.mysql.password", ""));
                    backup.save(players);
                    backup.close();
                } else {
                    SqliteStorage backup = new SqliteStorage(new File(getDataFolder(), "backup"));
                    backup.save(players);
                    backup.close();
                }
                msg(player, "Backup completed");
            } catch (Exception e) {
                msg(player, "Backup failed: " + e.getMessage());
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("spend") && args.length > 1) {
            try {
                double amount = Double.parseDouble(args[1]);
                PlayerData data = getData(player.getUniqueId());
                data.getStats().addMoneySpent(amount);
                addProgress(player, data, ChallengeType.MONEY_SPENT, amount);
                economy.withdrawPlayer(player, amount);
                msg(player, "You spent $" + amount);
                data.setLastBalance(economy.getBalance(player));
                int xp = (int) (amount / 100);
                if (xp > 0) {
                    awardXp(player, xp);
                }
            } catch (NumberFormatException e) {
                msg(player, "Invalid amount");
            }
            return true;
        }
        msg(player, "Use /skill help for commands");
        return true;
    }

    private void loadData() {
        try {
            if (storage != null) {
                storage.load(players);
            }
        } catch (Exception e) {
            getLogger().severe("Failed to load data: " + e.getMessage());
        }
    }

    private void saveData() {
        try {
            if (storage != null) {
                storage.save(players);
            }
        } catch (Exception e) {
            getLogger().severe("Failed to save data: " + e.getMessage());
        }
    }

    private ItemStack createItem(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (lore.length > 0) {
            meta.setLore(Arrays.asList(lore));
        }
        item.setItemMeta(meta);
        return item;
    }

    private void msg(CommandSender target, String message) {
        target.sendMessage(ChatColor.GOLD + "[LevelsX] " + ChatColor.RESET + message);
    }

    private void updateLungCapacity(Player player, PlayerData data) {
        int level = data.getSkillLevel(Skill.LUNG_CAPACITY);
        player.setMaximumAir(300 + level * 20);
    }

    private String createBar(double progress) {
        int filled = (int) Math.round(progress * 10);
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            bar.append(i < filled ? ChatColor.GREEN : ChatColor.DARK_GRAY).append("|");
        }
        return bar.toString();
    }

    private void updateScoreboard(Player player) {
        PlayerData data = getData(player.getUniqueId());
        if (!data.isScoreboardEnabled()) {
            return;
        }
        Scoreboard board = scoreboardManager.getNewScoreboard();
        Objective obj = board.registerNewObjective("levelsx", "dummy", ChatColor.GREEN + "LevelsX");
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        int needed = data.getLevel() * 100;
        obj.getScore(ChatColor.YELLOW + "Level: " + ChatColor.WHITE + data.getLevel()).setScore(4);
        if (data.getLevel() >= levelCap) {
            obj.getScore(ChatColor.AQUA + "MAX LEVEL").setScore(3);
        } else {
            obj.getScore(ChatColor.YELLOW + "XP: " + ChatColor.WHITE + data.getXp() + "/" + needed).setScore(3);
            obj.getScore(createBar((double) data.getXp() / needed)).setScore(2);
        }
        player.setScoreboard(board);
    }

    private void checkBalances() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData data = getData(p.getUniqueId());
            double current = economy.getBalance(p);
            double diff = current - data.getLastBalance();
            if (Math.abs(diff) > 0.01) {
                if (diff > 0) {
                    data.getStats().addMoneyEarned(diff);
                    addProgress(p, data, ChallengeType.MONEY_EARNED, diff);
                } else {
                    diff = -diff;
                    data.getStats().addMoneySpent(diff);
                    addProgress(p, data, ChallengeType.MONEY_SPENT, diff);
                }
                data.setLastBalance(current);
            }
        }
    }

    private void checkChallengeResets() {
        long now = System.currentTimeMillis();
        if (now - lastDailySelect > 86400000L) {
            lastDailySelect = now;
            selectDailyChallenges();
            for (PlayerData d : players.values()) {
                d.setLastDailyReset(now);
            }
        }
        if (now - lastWeeklySelect > 604800000L) {
            lastWeeklySelect = now;
            selectWeeklyChallenges();
            for (PlayerData d : players.values()) {
                d.setLastWeeklyReset(now);
            }
        }
    }

    private void toggleScoreboard(Player player) {
        PlayerData data = getData(player.getUniqueId());
        data.setScoreboardEnabled(!data.isScoreboardEnabled());
        if (data.isScoreboardEnabled()) {
            updateScoreboard(player);
            msg(player, "Scoreboard enabled");
        } else {
            player.setScoreboard(scoreboardManager.getNewScoreboard());
            msg(player, "Scoreboard disabled");
        }
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        economy = rsp.getProvider();
        return economy != null;
    }

    private void setupStorage() {
        FileConfiguration cfg = getConfig();
        String type = cfg.getString("storage.type", "sqlite");
        try {
            if (type.equalsIgnoreCase("mysql")) {
                String host = cfg.getString("storage.mysql.host", "localhost");
                int port = cfg.getInt("storage.mysql.port", 3306);
                String db = cfg.getString("storage.mysql.database", "levelsx");
                String user = cfg.getString("storage.mysql.user", "root");
                String pass = cfg.getString("storage.mysql.password", "");
                storage = new MySqlStorage(host, port, db, user, pass);
            } else {
                storage = new SqliteStorage(getDataFolder());
            }
        } catch (Exception e) {
            getLogger().severe("Failed to init storage: " + e.getMessage());
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("help", "admin", "leaderboard", "stats", "challenges", "spend", "scoreboard", "backup")
                    .stream().filter(s -> s.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("leaderboard")) {
            return Arrays.asList("kills", "mobkills", "deaths", "damage", "distance")
                    .stream().filter(s -> s.startsWith(args[1].toLowerCase())).collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("backup")) {
            return Arrays.asList("sql", "sqlite")
                    .stream().filter(s -> s.startsWith(args[1].toLowerCase())).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
