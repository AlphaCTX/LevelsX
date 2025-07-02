package com.alphactx;

import com.alphactx.model.PlayerData;
import com.alphactx.model.Skill;
import com.alphactx.model.Stats;
import com.alphactx.model.ChallengeType;
import com.alphactx.model.ScoreField;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import com.alphactx.storage.DataStorage;
import com.alphactx.storage.SqliteStorage;
import com.alphactx.storage.MySqlStorage;
import com.alphactx.storage.DataUtil;

import net.milkbowl.vault.economy.Economy;

import org.bukkit.ChatColor;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.util.*;
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
    private int autosaveTask = -1;

    private void initChallenges() {
        FileConfiguration cfg = getConfig();
        for (ChallengeType type : ChallengeType.values()) {
            challengeGoals.put(type, cfg.getDouble("challengeGoals." + type.name(), getDefaultGoal(type)));
        }
    }

    private double getDefaultGoal(ChallengeType type) {
        switch (type) {
            case MOB_KILLS: return 10.0;
            case DAMAGE_TAKEN: return 500.0;
            case MONEY_EARNED:
            case MONEY_SPENT: return 1000.0;
            case KILOMETERS_TRAVELED: return 5.0;
            default: return 0;
        }
    }

    private void selectDailyChallenges() {
        activeDaily.clear();
        List<ChallengeType> list = new ArrayList<>(Arrays.asList(ChallengeType.values()));
        Collections.shuffle(list);
        for (int i = 0; i < 3 && i < list.size(); i++) activeDaily.add(list.get(i));
    }

    private void selectWeeklyChallenges() {
        activeWeekly.clear();
        List<ChallengeType> list = new ArrayList<>(Arrays.asList(ChallengeType.values()));
        Collections.shuffle(list);
        for (int i = 0; i < 3 && i < list.size(); i++) activeWeekly.add(list.get(i));
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        scoreboardManager = Bukkit.getScoreboardManager();
        levelCap = Math.min(1000, Math.max(1, getConfig().getInt("levelCap", 100)));
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
        int minutes = Math.max(1, getConfig().getInt("autosave", 5));
        autosaveTask = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, this::saveData, 20L*60*minutes, 20L*60*minutes);
        getLogger().info("LevelsX enabled");
    }

    @Override
    public void onDisable() {
        checkBalances();
        saveData();
        if (autosaveTask != -1) Bukkit.getScheduler().cancelTask(autosaveTask);
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
        if (now - data.getLastDailyReset() > 86400000L) data.setLastDailyReset(now);
        if (now - data.getLastWeeklyReset() > 604800000L) data.setLastWeeklyReset(now);
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
            double money = 0.0;
            if (event.getEntityType() == EntityType.PLAYER) {
                data.getStats().addKill();
                if (getConfig().getBoolean("killRewards.players.enabled", true)) {
                    xp = getConfig().getInt("killRewards.players.xp", 25);
                    money = getConfig().getDouble("killRewards.players.money", 0.0);
                }
            } else {
                data.getStats().addMobKill();
                if (getConfig().getBoolean("killRewards.mobs.enabled", true)) {
                    xp = getConfig().getInt("killRewards.mobs.xp", 10);
                    money = getConfig().getDouble("killRewards.mobs.money", 0.0);
                }
                addProgress(killer, data, ChallengeType.MOB_KILLS, 1);
            }
            applyHealing(killer, data);
            if (xp > 0) awardXp(killer, xp);
            if (money > 0) {
                economy.depositPlayer(killer, money);
                msg(killer, "You earned $" + money);
                data.getStats().addMoneyEarned(money);
                data.setLastBalance(economy.getBalance(killer));
            }
        }
    }

    private void applyHealing(Player killer, PlayerData data) {
        int healLvl = data.getSkillLevel(Skill.HEALING);
        if (healLvl > 0) {
            double amount = healLvl * 2.0;
            killer.setHealth(Math.min(killer.getMaxHealth(), killer.getHealth() + amount));
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
            handlePlayerDamageDealt((Player) event.getDamager(), event);
        }
        if (event.getEntity() instanceof Player) {
            handlePlayerDamageTaken((Player) event.getEntity(), event);
        }
    }

    private void handlePlayerDamageDealt(Player player, EntityDamageByEntityEvent event) {
        PlayerData data = getData(player.getUniqueId());
        data.getStats().addDamageDealt(event.getDamage());
        int dmgLvl = data.getSkillLevel(Skill.DAMAGE);
        if (dmgLvl > 0) event.setDamage(event.getDamage() * (1 + 0.05 * dmgLvl));
        applyLifesteal(player, event, data);
    }

    private void applyLifesteal(Player player, EntityDamageByEntityEvent event, PlayerData data) {
        int steal = data.getSkillLevel(Skill.LIFESTEAL);
        if (steal > 0) {
            double heal = event.getDamage() * 0.05 * steal;
            player.setHealth(Math.min(player.getMaxHealth(), player.getHealth() + heal));
        }
    }

    private void handlePlayerDamageTaken(Player player, EntityDamageByEntityEvent event) {
        PlayerData data = getData(player.getUniqueId());
        data.getStats().addDamageTaken(event.getDamage());
        int reduce = data.getSkillLevel(Skill.DAMAGE_REDUCTION);
        if (reduce > 0) event.setDamage(event.getDamage() * (1 - 0.05 * reduce));
        addProgress(player, data, ChallengeType.DAMAGE_TAKEN, event.getDamage());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!event.getFrom().getWorld().equals(event.getTo().getWorld())) return;
        double distance = event.getFrom().distance(event.getTo());
        PlayerData data = getData(event.getPlayer().getUniqueId());
        double km = distance / 1000.0;
        data.getStats().addKilometersTraveled(km);
        addProgress(event.getPlayer(), data, ChallengeType.KILOMETERS_TRAVELED, km);
        int xp = (int) Math.round(km * 5);
        if (xp > 0) awardXp(event.getPlayer(), xp);
        if (data.isScoreboardEnabled() && data.isFieldEnabled(ScoreField.KM)) {
            updateScoreboard(event.getPlayer());
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        String title = event.getView().getTitle();

        // Determine if this is one of our GUIs
        boolean custom = title.equals("Skills") || title.equals("Admin Menu") || title.equals("Level Config") ||
                title.equals("Challenge Config") || title.startsWith("Challenge ") || title.equals("Kill Config") ||
                title.equals("Stats") || title.equals("Daily") || title.equals("Weekly") ||
                title.equals("Config") || title.equals("Menu") || title.equals("Scoreboard");

        if (custom) {
            boolean picking = pendingRewardLevel.containsKey(player.getUniqueId());
            if (!picking || event.getClickedInventory() != player.getInventory() || event.isShiftClick()) {
                event.setCancelled(true);
            }
        } else {
            return;
        }

        // Haal één keer ClickType op
        ClickType click = event.getClick();

        // MAIN MENU
        if (title.equals("Menu")) {
            switch (event.getRawSlot()) {
                case 0: openSkillGui(player); break;
                case 1: openDailyGui(player); break;
                case 2: openWeeklyGui(player); break;
                case 3: openStatsGui(player); break;
                case 4: openConfigGui(player); break;
            }
            return;
        }

        // SKILLS GUI
        if (title.equals("Skills")) {
            int slot = event.getRawSlot();
            if (slot >= 0 && slot < Skill.values().length) {
                Skill skill = Skill.values()[slot];
                PlayerData data = getData(player.getUniqueId());
                data.levelSkill(skill);
                updateLungCapacity(player, data);
                openSkillGui(player);
            } else if (slot == 7) {
                openMainGui(player);
            }
            return;
        }

        // ADMIN MENUS
        if (title.equals("Admin Menu")) {
            switch (event.getRawSlot()) {
                case 0: openLevelConfigGui(player); break;
                case 1: openChallengeConfigGui(player); break;
                case 2: openKillConfigGui(player); break;
                case 8: player.closeInventory(); break;
            }
            return;
        }

        if (title.equals("Level Config")) {
            int rewardSlots = levelCap / 20;
            int extra = 4;
            int moneySlot = event.getInventory().getSize() - extra;
            int itemSlot = moneySlot + 1;
            int capSlot = itemSlot + 1;
            int backSlot = capSlot + 1;

            if (pendingRewardLevel.containsKey(player.getUniqueId()) && event.getClickedInventory() == player.getInventory()) {
                ItemStack current = event.getCurrentItem();
                if (current != null && !current.getType().isAir()) {
                    int lvl = pendingRewardLevel.remove(player.getUniqueId());
                    if (lvl == -1) getConfig().set("itemReward", current.getType().name());
                    else getConfig().set("itemRewards." + lvl, current.getType().name());
                    saveConfig();
                    msg(player, "Set reward for " + (lvl==-1?"default":("level " + lvl)) + " to " + current.getType());
                    openLevelConfigGui(player);
                }
                return;
            }

            if (event.getRawSlot() >= 0 && event.getRawSlot() < rewardSlots) {
                int level = (event.getRawSlot() + 1) * 20;
                pendingRewardLevel.put(player.getUniqueId(), level);
                msg(player, "Click an item in your inventory to set reward for level " + level);
                return;
            }

            if (event.getRawSlot() == moneySlot) {
                double money = getConfig().getDouble("moneyReward", 100.0);
                if (click == ClickType.LEFT) money = Math.max(0, money - (click.isShiftClick()?10:1));
                else if (click == ClickType.RIGHT) money += click.isShiftClick()?10:1;
                getConfig().set("moneyReward", money);
                saveConfig();
                openLevelConfigGui(player);
                return;
            }
            if (event.getRawSlot() == itemSlot) {
                pendingRewardLevel.put(player.getUniqueId(), -1);
                msg(player, "Click an item in your inventory to set the default reward item");
                return;
            }
            if (event.getRawSlot() == capSlot) {
                if (click == ClickType.LEFT) levelCap = Math.max(1, levelCap - 10);
                else if (click == ClickType.RIGHT) levelCap = Math.min(1000, levelCap + 10);
                getConfig().set("levelCap", levelCap);
                saveConfig();
                openLevelConfigGui(player);
                return;
            }
            if (event.getRawSlot() == backSlot) { openAdminMenu(player); }
            return;
        }

        if (title.equals("Challenge Config")) {
            if (event.getRawSlot() < ChallengeType.values().length) {
                openChallengeTypeGui(player, ChallengeType.values()[event.getRawSlot()]);
            } else if (event.getRawSlot() == 8) {
                openAdminMenu(player);
            }
            return;
        }

        if (title.startsWith("Challenge ")) {
            ChallengeType type = ChallengeType.valueOf(title.substring(10));
            String base = "challengeRewards.";
            if (event.getRawSlot() == 0) {
                int xp = getConfig().getInt(base+"daily.types."+type.name()+".xp", getConfig().getInt(base+"daily.xp",20));
                xp = Math.max(0, xp + (click == ClickType.LEFT?-1:1)*(click.isShiftClick()?10:1));
                getConfig().set(base+"daily.types."+type.name()+".xp", xp);
                saveConfig();
                openChallengeTypeGui(player,type);
            } else if (event.getRawSlot() == 1) {
                double money = getConfig().getDouble(base+"daily.types."+type.name()+".money", getConfig().getDouble(base+"daily.money",0.0));
                money = Math.max(0, money + (click == ClickType.LEFT?-1:1)*(click.isShiftClick()?10:1));
                getConfig().set(base+"daily.types."+type.name()+".money", money);
                saveConfig();
                openChallengeTypeGui(player,type);
            } else if (event.getRawSlot() == 3) {
                int xp = getConfig().getInt(base+"weekly.types."+type.name()+".xp", getConfig().getInt(base+"weekly.xp",50));
                xp = Math.max(0, xp + (click == ClickType.LEFT?-1:1)*(click.isShiftClick()?10:1));
                getConfig().set(base+"weekly.types."+type.name()+".xp", xp);
                saveConfig();
                openChallengeTypeGui(player,type);
            } else if (event.getRawSlot() == 4) {
                double money = getConfig().getDouble(base+"weekly.types."+type.name()+".money", getConfig().getDouble(base+"weekly.money",0.0));
                money = Math.max(0, money + (click == ClickType.LEFT?-1:1)*(click.isShiftClick()?10:1));
                getConfig().set(base+"weekly.types."+type.name()+".money", money);
                saveConfig();
                openChallengeTypeGui(player,type);
            } else if (event.getRawSlot() == 8) {
                openChallengeConfigGui(player);
            }
            return;
        }

        if (title.equals("Kill Config")) {
            if (event.getRawSlot() == 0) {
                int xp = getConfig().getInt("killRewards.players.xp",25);
                xp = Math.max(0, xp + (click == ClickType.LEFT?-1:1)*(click.isShiftClick()?10:1));
                getConfig().set("killRewards.players.xp", xp);
                saveConfig();
                openKillConfigGui(player);
                return;
            } else if (event.getRawSlot() == 1) {
                double money = getConfig().getDouble("killRewards.players.money",0.0);
                money = Math.max(0, money + (click == ClickType.LEFT?-1:1)*(click.isShiftClick()?10:1));
                getConfig().set("killRewards.players.money", money);
                saveConfig();
                openKillConfigGui(player);
                return;
            } else if (event.getRawSlot() == 3) {
                int xp = getConfig().getInt("killRewards.mobs.xp",10);
                xp = Math.max(0, xp + (click == ClickType.LEFT?-1:1)*(click.isShiftClick()?10:1));
                getConfig().set("killRewards.mobs.xp", xp);
                saveConfig();
                openKillConfigGui(player);
                return;
            } else if (event.getRawSlot() == 4) {
                double money = getConfig().getDouble("killRewards.mobs.money",0.0);
                money = Math.max(0, money + (click == ClickType.LEFT?-1:1)*(click.isShiftClick()?10:1));
                getConfig().set("killRewards.mobs.money", money);
                saveConfig();
                openKillConfigGui(player);
                return;
            } else if (event.getRawSlot() == 8) {
                openAdminMenu(player);
            }
            return;
        }

        // STATS GUI
        if (title.equals("Stats")) {
            if (event.getRawSlot() == 26) openMainGui(player);
            return;
        }

        // DAILY GUI
        if (title.equals("Daily")) {
            if (event.getRawSlot() == 17) openMainGui(player);
            return;
        }

        // WEEKLY GUI
        if (title.equals("Weekly")) {
            if (event.getRawSlot() == 17) openMainGui(player);
            return;
        }

        // SCOREBOARD GUI
        if (title.equals("Scoreboard")) {
            PlayerData data = getData(player.getUniqueId());
            int back = event.getInventory().getSize()-1;
            if (event.getRawSlot() == back) { openConfigGui(player); return; }
            int index = event.getRawSlot();
            if (index >=0 && index < data.getBoardOrder().size()) {
                ScoreField f = data.getBoardOrder().get(index);
                boolean en = data.isFieldEnabled(f);
                data.setFieldEnabled(f, !en);
                if (f == ScoreField.BALANCE) data.setShowBalance(!en);
                openScoreboardGui(player);
                updateScoreboard(player);
            }
            return;
        }

        // CONFIG GUI
        if (title.equals("Config")) {
            PlayerData data = getData(player.getUniqueId());
            if (event.getRawSlot() == 0) {
                data.setScoreboardEnabled(!data.isScoreboardEnabled());
                if (data.isScoreboardEnabled()) updateScoreboard(player); else player.setScoreboard(scoreboardManager.getNewScoreboard());
                openConfigGui(player);
            } else if (event.getRawSlot() == 1) {
                openScoreboardGui(player);
            } else if (event.getRawSlot() == 8) {
                openMainGui(player);
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        String title = event.getView().getTitle();
        boolean custom = title.equals("Skills") || title.equals("Admin Menu") || title.equals("Level Config") ||
                title.equals("Challenge Config") || title.startsWith("Challenge") || title.equals("Kill Config") ||
                title.equals("Stats") || title.equals("Daily") || title.equals("Weekly") ||
                title.equals("Config") || title.equals("Menu") || title.equals("Scoreboard");
        if (custom) {
            boolean picking = pendingRewardLevel.containsKey(player.getUniqueId());
            if (!picking || event.getRawSlots().stream().anyMatch(s -> s < event.getView().getTopInventory().getSize())) {
                event.setCancelled(true);
            }
        }
    }

    private void awardXp(Player player, int amount) {
        PlayerData data = getData(player.getUniqueId());
        data.addXp(amount);
        if (data.getLevel() < levelCap && data.tryLevelUp()) {
            msg(player, "Leveled up to " + data.getLevel());
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
            giveLevelReward(player, data);
        } else if (data.getLevel() >= levelCap) {
            int needed = data.getLevel() * 100;
            data.setXp(Math.min(data.getXp(), needed));
        }
        updateScoreboard(player);
    }

    private void giveLevelReward(Player player, PlayerData data) {
        int lvl = data.getLevel();
        if (lvl % 20 == 0) {
            String matName = getConfig().getString("itemRewards." + lvl, getConfig().getString("itemReward", "DIAMOND"));
            Material mat = Material.matchMaterial(matName);
            if (mat != null && mat != Material.AIR) player.getInventory().addItem(new ItemStack(mat));
        } else if (lvl % 5 != 0) {
            double money = getConfig().getDouble("moneyReward", 100.0);
            economy.depositPlayer(player, money);
            msg(player, "You earned $" + money);
            PlayerData data2 = getData(player.getUniqueId());
            data2.getStats().addMoneyEarned(money);
            addProgress(player, data2, ChallengeType.MONEY_EARNED, money);
            data2.setLastBalance(economy.getBalance(player));
        }
    }

    private void openMainGui(Player player) {
        Inventory inv = Bukkit.createInventory(player, 9, "Menu");
        inv.setItem(0, createItem(Material.ENCHANTED_BOOK, "Skills"));
        inv.setItem(1, createItem(Material.PAPER, "Daily"));
        inv.setItem(2, createItem(Material.MAP, "Weekly"));
        inv.setItem(3, createItem(Material.BOOK, "Stats"));
        inv.setItem(4, createItem(Material.COMPARATOR, "Config"));
        player.openInventory(inv);
    }

    private void openAdminMenu(Player player) {
        Inventory inv = Bukkit.createInventory(player, 9, "Admin Menu");
        inv.setItem(0, createItem(Material.EXPERIENCE_BOTTLE, "Level Config"));
        inv.setItem(1, createItem(Material.PAPER, "Challenges Config"));
        inv.setItem(2, createItem(Material.DIAMOND_SWORD, "Kill Config"));
        inv.setItem(8, createItem(Material.BARRIER, "Close"));
        player.openInventory(inv);
    }

    private void openLevelConfigGui(Player player) {
        int rewardSlots = levelCap / 20;
        int extra = 4;
        int total = rewardSlots + extra;
        int size = ((total + 8) / 9) * 9;
        Inventory inv = Bukkit.createInventory(player, size, "Level Config");

        for (int i = 0; i < rewardSlots; i++) {
            int lvl = (i + 1) * 20;
            String matName = getConfig().getString("itemRewards." + lvl, getConfig().getString("itemReward", "DIAMOND"));
            Material mat = Material.matchMaterial(matName);
            ItemStack it = new ItemStack(mat == null ? Material.DIRT : mat);
            ItemMeta im = it.getItemMeta();
            im.setDisplayName("Level " + lvl + " reward");
            it.setItemMeta(im);
            inv.setItem(i, it);
        }

        int moneySlot = size - extra;
        int itemSlot = moneySlot + 1;
        int capSlot = itemSlot + 1;
        int backSlot = capSlot + 1;

        inv.setItem(moneySlot, createItem(Material.GOLD_INGOT, "Money per level: $" + getConfig().getDouble("moneyReward", 100.0)));
        inv.setItem(itemSlot, createItem(Material.CHEST, "Item reward: " + getConfig().getString("itemReward", "DIAMOND")));
        inv.setItem(capSlot, createItem(Material.EXPERIENCE_BOTTLE, "Level Cap: " + levelCap));
        inv.setItem(backSlot, createItem(Material.ARROW, "Back"));

        player.openInventory(inv);
    }

    private void openChallengeConfigGui(Player player) {
        Inventory inv = Bukkit.createInventory(player, 9, "Challenge Config");
        int slot = 0;
        for (ChallengeType type : ChallengeType.values()) {
            inv.setItem(slot++, createItem(Material.PAPER, type.name()));
        }
        inv.setItem(8, createItem(Material.ARROW, "Back"));
        player.openInventory(inv);
    }

    private void openChallengeTypeGui(Player player, ChallengeType type) {
        Inventory inv = Bukkit.createInventory(player, 9, "Challenge " + type.name());
        String base = "challengeRewards.";
        int dXp = getConfig().getInt(base + "daily.types." + type.name() + ".xp", getConfig().getInt(base + "daily.xp", 20));
        double dMoney = getConfig().getDouble(base + "daily.types." + type.name() + ".money", getConfig().getDouble(base + "daily.money", 0.0));
        int wXp = getConfig().getInt(base + "weekly.types." + type.name() + ".xp", getConfig().getInt(base + "weekly.xp", 50));
        double wMoney = getConfig().getDouble(base + "weekly.types." + type.name() + ".money", getConfig().getDouble(base + "weekly.money", 0.0));
        inv.setItem(0, createItem(Material.EXPERIENCE_BOTTLE, "Daily XP: " + dXp));
        inv.setItem(1, createItem(Material.GOLD_INGOT, "Daily Money: $" + dMoney));
        inv.setItem(3, createItem(Material.EXPERIENCE_BOTTLE, "Weekly XP: " + wXp));
        inv.setItem(4, createItem(Material.GOLD_INGOT, "Weekly Money: $" + wMoney));
        inv.setItem(8, createItem(Material.ARROW, "Back"));
        player.openInventory(inv);
    }

    private void openKillConfigGui(Player player) {
        Inventory inv = Bukkit.createInventory(player, 9, "Kill Config");
        inv.setItem(0, createItem(Material.EXPERIENCE_BOTTLE, "Player XP: " + getConfig().getInt("killRewards.players.xp",25)));
        inv.setItem(1, createItem(Material.GOLD_INGOT, "Player Money: $" + getConfig().getDouble("killRewards.players.money",0.0)));
        inv.setItem(3, createItem(Material.EXPERIENCE_BOTTLE, "Mob XP: " + getConfig().getInt("killRewards.mobs.xp",10)));
        inv.setItem(4, createItem(Material.GOLD_INGOT, "Mob Money: $" + getConfig().getDouble("killRewards.mobs.money",0.0)));
        inv.setItem(8, createItem(Material.ARROW, "Back"));
        player.openInventory(inv);
    }

    private void openDailyGui(Player player) {
        Inventory inv = Bukkit.createInventory(player, 18, "Daily");
        PlayerData data = getData(player.getUniqueId());
        int i = 0;
        for (ChallengeType type : activeDaily) {
            double prog = data.getDailyProgress().get(type);
            double goal = challengeGoals.get(type);
            int xp = getConfig().getInt("challengeRewards.daily.types." + type.name() + ".xp", getConfig().getInt("challengeRewards.daily.xp", 20));
            double money = getConfig().getDouble("challengeRewards.daily.types." + type.name() + ".money", getConfig().getDouble("challengeRewards.daily.money", 0.0));
            inv.setItem(i++, createItem(Material.PAPER, type.name(), String.format("%.1f/%.1f", prog, goal), "Reward: " + xp + " XP, $" + money));
        }
        inv.setItem(17, createItem(Material.ARROW, "Back"));
        player.openInventory(inv);
    }

    private void openWeeklyGui(Player player) {
        Inventory inv = Bukkit.createInventory(player, 18, "Weekly");
        PlayerData data = getData(player.getUniqueId());
        int i = 0;
        for (ChallengeType type : activeWeekly) {
            double prog = data.getWeeklyProgress().get(type);
            double goal = challengeGoals.get(type);
            int xp = getConfig().getInt("challengeRewards.weekly.types." + type.name() + ".xp", getConfig().getInt("challengeRewards.weekly.xp", 50));
            double money = getConfig().getDouble("challengeRewards.weekly.types." + type.name() + ".money", getConfig().getDouble("challengeRewards.weekly.money", 0.0));
            inv.setItem(i++, createItem(Material.MAP, type.name(), String.format("%.1f/%.1f", prog, goal), "Reward: " + xp + " XP, $" + money));
        }
        inv.setItem(17, createItem(Material.ARROW, "Back"));
        player.openInventory(inv);
    }

    private void openSkillGui(Player player) {
        Inventory inv = Bukkit.createInventory(player, 9, "Skills");
        PlayerData data = getData(player.getUniqueId());
        int slot = 0;
        for (Skill skill : Skill.values()) {
            int lvl = data.getSkillLevel(skill);
            List<String> lore = new ArrayList<>();
            lore.add("Level " + lvl);
            lore.add(getSkillInfo(skill, lvl));
            lore.add("Max 10");
            inv.setItem(slot++, createItem(Material.BOOK, skill.name(), lore.toArray(new String[0])));
        }
        inv.setItem(7, createItem(Material.ARROW, "Back"));
        inv.setItem(8, createItem(Material.EXPERIENCE_BOTTLE, "Skill Points: " + data.getSkillPoints()));
        player.openInventory(inv);
    }

    private void openConfigGui(Player player) {
        Inventory inv = Bukkit.createInventory(player, 9, "Config");
        PlayerData data = getData(player.getUniqueId());
        inv.setItem(0, createItem(Material.COMPARATOR, "Scoreboard", data.isScoreboardEnabled()?"ON":"OFF"));
        inv.setItem(1, createItem(Material.PAPER, "Scoreboard Fields"));
        inv.setItem(8, createItem(Material.ARROW, "Back"));
        player.openInventory(inv);
    }

    private void openScoreboardGui(Player player) {
        PlayerData data = getData(player.getUniqueId());
        int size = ((ScoreField.values().length + 1) / 9 + 1) * 9;
        Inventory inv = Bukkit.createInventory(player, size, "Scoreboard");
        int slot = 0;
        for (ScoreField f : data.getBoardOrder()) {
            Material m = data.isFieldEnabled(f) ? Material.LIME_WOOL : Material.RED_WOOL;
            inv.setItem(slot++, createItem(m, f.getLabel()));
        }
        inv.setItem(size-1, createItem(Material.ARROW, "Back"));
        player.openInventory(inv);
    }


    private void openStatsGui(Player player) {
        Inventory inv = Bukkit.createInventory(player, 27, "Stats");
        PlayerData data = getData(player.getUniqueId());
        Stats stats = data.getStats();
        inv.setItem(0, createItem(Material.EXPERIENCE_BOTTLE, "Level " + data.getLevel(),
                "XP: " + data.getXp(), "Skill points: " + data.getSkillPoints()));
        inv.setItem(1, createItem(Material.IRON_SWORD, "Kills", String.valueOf(stats.getKills())));
        inv.setItem(2, createItem(Material.ZOMBIE_HEAD, "Mob Kills", String.valueOf(stats.getMobKills())));
        inv.setItem(3, createItem(Material.SKELETON_SKULL, "Deaths", String.valueOf(stats.getDeaths())));
        inv.setItem(4, createItem(Material.DIAMOND_SWORD, "Damage Dealt", String.format("%.1f", stats.getDamageDealt())));
        inv.setItem(5, createItem(Material.SHIELD, "Damage Taken", String.format("%.1f", stats.getDamageTaken())));
        inv.setItem(6, createItem(Material.EMERALD, "Money Earned", String.format("%.2f", stats.getMoneyEarned())));
        inv.setItem(7, createItem(Material.GOLD_INGOT, "Money Spent", String.format("%.2f", stats.getMoneySpent())));
        inv.setItem(8, createItem(Material.COMPASS, "Kilometers Traveled", String.format("%.2f", stats.getKilometersTraveled())));
        inv.setItem(9, createItem(Material.CLOCK, "Time Online", String.format("%.1f h", stats.getTimeOnline() / 3600000.0)));
        inv.setItem(26, createItem(Material.ARROW, "Back"));
        player.openInventory(inv);
    }

    private void showLeaderboard(Player player, String stat) {
        List<Map.Entry<UUID, PlayerData>> sorted = players.entrySet().stream()
                .sorted((a,b) -> Double.compare(
                        getStatValue(b.getValue().getStats(), stat),
                        getStatValue(a.getValue().getStats(), stat)))
                .limit(10)
                .collect(Collectors.toList());
        msg(player, "Leaderboard for " + stat + ":");
        int i = 1;
        for (Map.Entry<UUID,PlayerData> e : sorted) {
            String name = Optional.ofNullable(Bukkit.getOfflinePlayer(e.getKey()).getName()).orElse("Unknown");
            msg(player, i++ + ". " + name + " - " + getStatValue(e.getValue().getStats(), stat));
        }
    }

    private void checkChallenge(Player player, PlayerData data, ChallengeType type, boolean daily) {
        double prog = daily ? data.getDailyProgress().get(type) : data.getWeeklyProgress().get(type);
        double goal = challengeGoals.getOrDefault(type, Double.MAX_VALUE);
        if (prog >= goal) {
            msg(player, (daily ? "Daily" : "Weekly") + " challenge completed: " + type.name().toLowerCase());
            distributeChallengeReward(player, data, type, daily);
            if (daily) data.getDailyProgress().put(type, goal);
            else     data.getWeeklyProgress().put(type, goal);
        }
    }

    private void distributeChallengeReward(Player player, PlayerData data, ChallengeType type, boolean daily) {
        String path = "challengeRewards." + (daily?"daily":"weekly") + ".types." + type.name();
        int xp = getConfig().getInt(path + ".xp", getConfig().getInt("challengeRewards." + (daily?"daily":"weekly") + ".xp", daily?20:50));
        double money = getConfig().getDouble(path + ".money", getConfig().getDouble("challengeRewards." + (daily?"daily":"weekly") + ".money", 0.0));
        if (xp > 0) awardXp(player, xp);
        if (money > 0) {
            economy.depositPlayer(player, money);
            msg(player, "You earned $" + money);
            data.getStats().addMoneyEarned(money);
            data.setLastBalance(economy.getBalance(player));
        }
    }

    private void addProgress(Player player, PlayerData data, ChallengeType type, double amount) {
        if (activeDaily.contains(type)) {
            double prog = data.getDailyProgress().get(type);
            double goal = challengeGoals.get(type);
            if (prog < goal) {
                double next = Math.min(goal, prog + amount);
                data.getDailyProgress().put(type, next);
                if (next >= goal) checkChallenge(player, data, type, true);
            }
        }
        if (activeWeekly.contains(type)) {
            double prog = data.getWeeklyProgress().get(type);
            double goal = challengeGoals.get(type);
            if (prog < goal) {
                double next = Math.min(goal, prog + amount);
                data.getWeeklyProgress().put(type, next);
                if (next >= goal) checkChallenge(player, data, type, false);
            }
        }
    }

    private double getStatValue(Stats stats, String stat) {
        switch (stat.toLowerCase()) {
            case "kills": return stats.getKills();
            case "mobkills": return stats.getMobKills();
            case "deaths": return stats.getDeaths();
            case "damage": return stats.getDamageDealt();
            case "distance": return stats.getKilometersTraveled();
            default: return 0;
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
            openMainGui(player);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "help":
                msg(player, "/skill - open GUI");
                msg(player, "/skill admin - admin GUI");
                msg(player, "/skill leaderboard <stat>");
                msg(player, "/skill stats");
                msg(player, "/skill challenges");
                msg(player, "/skill spend <amount>");
                msg(player, "/skill scoreboard");
                msg(player, "/skill backup <sql|sqlite>");
                msg(player, "/skill reload");
                msg(player, "/skill setlevel <player> <level>");
                msg(player, "/skill setskillpoints <player> <amount>");
                break;
            case "admin":
                if (!player.hasPermission("skill.admin")) msg(player, "No permission");
                else openAdminMenu(player);
                break;
            case "leaderboard":
                showLeaderboard(player, args.length>1?args[1]:"kills");
                break;
            case "stats":
                openStatsGui(player);
                break;
            case "challenges":
                openDailyGui(player);
                break;
            case "scoreboard":
                toggleScoreboard(player);
                break;
            case "reload":
                if (!player.hasPermission("skill.admin")) msg(player, "No permission");
                else {
                    reloadPlugin();
                    msg(player, "Config reloaded");
                }
                break;
            case "backup":
                if (!player.hasPermission("skill.admin")) msg(player, "No permission");
                else if (args.length>1) doBackup(player, args[1]);
                else msg(player,"Specify sql of sqlite");
                break;
            case "setlevel":
                if (!player.hasPermission("skill.admin")) { msg(player, "No permission"); }
                else if (args.length < 3) { msg(player, "Use /skill setlevel <player> <level>"); }
                else {
                    Player target = Bukkit.getPlayer(args[1]);
                    if (target == null) { msg(player, "Player not online"); }
                    else {
                        try {
                            int lvl = Integer.parseInt(args[2]);
                            PlayerData d = getData(target.getUniqueId());
                            d.setLevel(Math.max(1, Math.min(levelCap, lvl)));
                            updateScoreboard(target);
                            msg(player, "Level set to " + lvl + " for " + target.getName());
                        } catch (NumberFormatException e) { msg(player, "Invalid level"); }
                    }
                }
                break;
            case "setskillpoints":
                if (!player.hasPermission("skill.admin")) { msg(player, "No permission"); }
                else if (args.length < 3) { msg(player, "Use /skill setskillpoints <player> <amount>"); }
                else {
                    Player target = Bukkit.getPlayer(args[1]);
                    if (target == null) { msg(player, "Player not online"); }
                    else {
                        try {
                            int amt = Integer.parseInt(args[2]);
                            PlayerData d = getData(target.getUniqueId());
                            d.setSkillPoints(Math.max(0, amt));
                            msg(player, "Skill points set to " + amt + " for " + target.getName());
                        } catch (NumberFormatException e) { msg(player, "Invalid amount"); }
                    }
                }
                break;
            case "spend":
                if (args.length>1) doSpend(player,args[1]);
                else msg(player,"Use /skill spend <amount>");
                break;
            default:
                msg(player, "Use /skill help for commands");
        }
        return true;
    }

    private void doBackup(Player player, String dest) {
        try {
            if (dest.equalsIgnoreCase("sql")) {
                FileConfiguration cfg = getConfig();
                MySqlStorage backup = new MySqlStorage(
                    cfg.getString("storage.mysql.host","localhost"),
                    cfg.getInt("storage.mysql.port",3306),
                    cfg.getString("storage.mysql.database","levelsx"),
                    cfg.getString("storage.mysql.user","root"),
                    cfg.getString("storage.mysql.password","")
                );
                backup.save(players);
                backup.close();
            } else {
                SqliteStorage backup = new SqliteStorage(new File(getDataFolder(),"backup"));
                backup.save(players);
                backup.close();
            }
            msg(player, "Backup completed");
        } catch (Exception e) {
            msg(player, "Backup failed: " + e.getMessage());
        }
    }

    private void doSpend(Player player, String arg) {
        try {
            double amount = Double.parseDouble(arg);
            PlayerData data = getData(player.getUniqueId());
            data.getStats().addMoneySpent(amount);
            addProgress(player, data, ChallengeType.MONEY_SPENT, amount);
            economy.withdrawPlayer(player, amount);
            msg(player, "You spent $" + amount);
            data.setLastBalance(economy.getBalance(player));
            int xp = (int)(amount/100);
            if (xp>0) awardXp(player,xp);
        } catch (NumberFormatException e) {
            msg(player, "Invalid amount");
        }
    }

    private void loadData() {
        try {
            if (storage != null) storage.load(players);
        } catch (Exception e) {
            getLogger().severe("Failed to load data: " + e.getMessage());
        }
    }

    private void saveData() {
        try {
            if (storage != null) storage.save(players);
        } catch (Exception e) {
            getLogger().severe("Failed to save data: " + e.getMessage());
        }
    }

    private ItemStack createItem(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (lore.length>0) meta.setLore(Arrays.asList(lore));
        item.setItemMeta(meta);
        return item;
    }

    private void msg(CommandSender target, String message) {
        target.sendMessage(ChatColor.GOLD + "[LevelsX] " + ChatColor.RESET + message);
    }

    private void updateLungCapacity(Player player, PlayerData data) {
        int lvl = data.getSkillLevel(Skill.LUNG_CAPACITY);
        player.setMaximumAir(300 + lvl*20);
    }

    private String createBar(double progress) {
        int filled = (int)Math.round(progress*10);
        StringBuilder bar = new StringBuilder();
        for (int i=0;i<10;i++) {
            bar.append(i<filled ? ChatColor.GREEN : ChatColor.DARK_GRAY).append("|");
        }
        return bar.toString();
    }

    private String getSkillInfo(Skill skill, int lvl) {
        switch (skill) {
            case DAMAGE: return "Damage +" + (lvl*5) + "%";
            case DAMAGE_REDUCTION: return "Damage taken -" + (lvl*5) + "%";
            case HEALING: return "Heal " + lvl + " hearts";
            case LIFESTEAL: return "Lifesteal " + (lvl*5) + "%";
            case LUNG_CAPACITY: return "+" + lvl + "s underwater";
            default: return "";
        }
    }

    private void updateScoreboard(Player player) {
        PlayerData data = getData(player.getUniqueId());
        if (!data.isScoreboardEnabled()) return;
        Scoreboard board = scoreboardManager.getNewScoreboard();
        Objective obj = board.registerNewObjective("levelsx","dummy",ChatColor.GREEN+"LevelsX");
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        int needed = data.getLevel()*100;
        int line = data.getBoardOrder().size();
        Stats stats = data.getStats();
        for (ScoreField f : data.getBoardOrder()) {
            if (!data.isFieldEnabled(f)) continue;
            switch (f) {
                case LEVEL:
                    obj.getScore(ChatColor.YELLOW+"Level: "+ChatColor.WHITE+data.getLevel()).setScore(line--);
                    break;
                case BALANCE:
                    double bal = economy.getBalance(player);
                    obj.getScore(ChatColor.GOLD+"Balance: "+ChatColor.WHITE+String.format("%.2f", bal)).setScore(line--);
                    break;
                case XP:
                    if (data.getLevel()>=levelCap) obj.getScore(ChatColor.AQUA+"MAX LEVEL").setScore(line--);
                    else obj.getScore(ChatColor.YELLOW+"XP: "+ChatColor.WHITE+data.getXp()+"/"+needed).setScore(line--);
                    break;
                case PROGRESS:
                    if (data.getLevel()<levelCap) obj.getScore(createBar((double)data.getXp()/needed)).setScore(line--);
                    break;
                case KILLS:
                    obj.getScore(ChatColor.RED+"Kills: "+ChatColor.WHITE+stats.getKills()).setScore(line--);
                    break;
                case MOB_KILLS:
                    obj.getScore(ChatColor.RED+"Mob Kills: "+ChatColor.WHITE+stats.getMobKills()).setScore(line--);
                    break;
                case DEATHS:
                    obj.getScore(ChatColor.GRAY+"Deaths: "+ChatColor.WHITE+stats.getDeaths()).setScore(line--);
                    break;
                case KM:
                    obj.getScore(ChatColor.BLUE+"KM: "+ChatColor.WHITE+String.format("%.1f", stats.getKilometersTraveled())).setScore(line--);
                    break;
            }
        }
        player.setScoreboard(board);
    }

    private void checkBalances() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData data = getData(p.getUniqueId());
            double cur = economy.getBalance(p);
            double diff = cur - data.getLastBalance();
            if (Math.abs(diff)>0.01) {
                if (diff>0) {
                    data.getStats().addMoneyEarned(diff);
                    addProgress(p,data,ChallengeType.MONEY_EARNED,diff);
                } else {
                    diff = -diff;
                    data.getStats().addMoneySpent(diff);
                    addProgress(p,data,ChallengeType.MONEY_SPENT,diff);
                }
                data.setLastBalance(cur);
            }
        }
    }

    private void checkChallengeResets() {
        long now = System.currentTimeMillis();
        if (now - lastDailySelect > 86400000L) {
            lastDailySelect = now;
            selectDailyChallenges();
            for (PlayerData d : players.values()) d.setLastDailyReset(now);
        }
        if (now - lastWeeklySelect > 604800000L) {
            lastWeeklySelect = now;
            selectWeeklyChallenges();
            for (PlayerData d : players.values()) d.setLastWeeklyReset(now);
        }
    }

    private void toggleScoreboard(Player player) {
        PlayerData data = getData(player.getUniqueId());
        data.setScoreboardEnabled(!data.isScoreboardEnabled());
        if (data.isScoreboardEnabled()) {
            updateScoreboard(player);
            msg(player,"Scoreboard enabled");
        } else {
            player.setScoreboard(scoreboardManager.getNewScoreboard());
            msg(player,"Scoreboard disabled");
        }
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault")==null) return false;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp==null) return false;
        economy = rsp.getProvider();
        return economy!=null;
    }

    private void setupStorage() {
        FileConfiguration cfg = getConfig();
        String type = cfg.getString("storage.type","sqlite");
        try {
            if ("mysql".equalsIgnoreCase(type)) {
                storage = new MySqlStorage(
                    cfg.getString("storage.mysql.host","localhost"),
                    cfg.getInt("storage.mysql.port",3306),
                    cfg.getString("storage.mysql.database","levelsx"),
                    cfg.getString("storage.mysql.user","root"),
                    cfg.getString("storage.mysql.password","")
                );
            } else {
                storage = new SqliteStorage(getDataFolder());
            }
        } catch (Exception e) {
            getLogger().severe("Failed to init storage: " + e.getMessage());
        }
    }

    private void reloadPlugin() {
        reloadConfig();
        levelCap = Math.min(1000, Math.max(1, getConfig().getInt("levelCap", 100)));
        initChallenges();
        if (autosaveTask != -1) Bukkit.getScheduler().cancelTask(autosaveTask);
        int minutes = Math.max(1, getConfig().getInt("autosave", 5));
        autosaveTask = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, this::saveData, 20L*60*minutes, 20L*60*minutes);
        for (Player p : Bukkit.getOnlinePlayers()) updateScoreboard(p);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length==1) {
            return Arrays.asList("help","admin","leaderboard","stats","challenges","spend","scoreboard","backup","reload")
                    .stream().filter(s->s.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
        }
        if (args.length==2 && args[0].equalsIgnoreCase("leaderboard")) {
            return Arrays.asList("kills","mobkills","deaths","damage","distance")
                    .stream().filter(s->s.startsWith(args[1].toLowerCase())).collect(Collectors.toList());
        }
        if (args.length==2 && args[0].equalsIgnoreCase("backup")) {
            return Arrays.asList("sql","sqlite")
                    .stream().filter(s->s.startsWith(args[1].toLowerCase())).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
