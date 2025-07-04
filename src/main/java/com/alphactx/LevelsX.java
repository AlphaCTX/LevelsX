package com.alphactx;

import com.alphactx.model.PlayerData;
import com.alphactx.model.Skill;
import com.alphactx.model.Stats;
import com.alphactx.model.ChallengeType;
import com.alphactx.model.ScoreField;
import com.alphactx.storage.DataStorage;
import com.alphactx.storage.SqliteStorage;
import com.alphactx.storage.MySqlStorage;
import org.bstats.bukkit.Metrics;

import net.milkbowl.vault.economy.Economy;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
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
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.ChatColor;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

public class LevelsX extends JavaPlugin implements Listener, TabCompleter {
    private final Map<UUID, PlayerData> players = new HashMap<>();
    private final Map<ChallengeType, Double> dailyGoals = new EnumMap<>(ChallengeType.class);
    private final Map<ChallengeType, Double> weeklyGoals = new EnumMap<>(ChallengeType.class);
    private final List<ChallengeType> activeDaily = new ArrayList<>();
    private final List<ChallengeType> activeWeekly = new ArrayList<>();
    private long lastDailySelect, lastWeeklySelect;

    private ScoreboardManager scoreboardManager;
    private Economy economy;
    private final Map<UUID, Integer> pendingRewardLevel = new HashMap<>();
    private int levelCap;
    private DataStorage storage;
    private int autosaveTask = -1;

    private void initChallenges() {
        FileConfiguration cfg = getConfig();
        for (ChallengeType type : ChallengeType.values()) {
            dailyGoals.put(type, cfg.getDouble("dailyGoals." + type.name(), getDefaultGoal(type)));
            weeklyGoals.put(type, cfg.getDouble("weeklyGoals." + type.name(), getDefaultGoal(type) * 5));
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
        selectDailyChallenges(); selectWeeklyChallenges();
        lastDailySelect = lastWeeklySelect = System.currentTimeMillis();
        loadData();
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("skill")).setTabCompleter(this);
        Bukkit.getScheduler().runTaskTimer(this, this::checkBalances, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::checkChallengeResets, 72000L, 72000L);
        int minutes = Math.max(1, getConfig().getInt("autosave", 5));
        autosaveTask = Bukkit.getScheduler()
            .scheduleSyncRepeatingTask(this, this::saveData, 20L*60*minutes, 20L*60*minutes);
        // Start bStats metrics
        new Metrics(this, 26371);
        // Register PlaceholderAPI expansion if available
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new LevelsXExpansion(this).register();
        }
        getLogger().info("LevelsX enabled");
    }

    @Override
    public void onDisable() {
        checkBalances(); saveData();
        if (autosaveTask != -1) Bukkit.getScheduler().cancelTask(autosaveTask);
        try { if (storage != null) storage.close(); }
        catch (Exception e) { getLogger().severe("Failed to close storage: " + e.getMessage()); }
    }

    private PlayerData getData(UUID uuid) {
        return players.computeIfAbsent(uuid, PlayerData::new);
    }

    /**
     * Public accessor for other integrations.
     */
    public PlayerData getPlayerData(UUID uuid) {
        return getData(uuid);
    }

    // === Events ===
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        PlayerData d = getData(p.getUniqueId());
        long now = System.currentTimeMillis();
        d.setLastJoin(now);
        d.setLastBalance(economy.getBalance(p));
        if (now - d.getLastDailyReset() > 86400000L) d.setLastDailyReset(now);
        if (now - d.getLastWeeklyReset() > 604800000L) d.setLastWeeklyReset(now);
        updateLungCapacity(p, d);
        updateScoreboard(p);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer(); PlayerData d = getData(p.getUniqueId());
        long session = System.currentTimeMillis() - d.getLastJoin();
        d.getStats().addTimeOnline(session);
        double cur = economy.getBalance(p), diff = cur - d.getLastBalance();
        if (Math.abs(diff) > 0.01) {
            if (diff > 0) {
                d.getStats().addMoneyEarned(diff);
                addProgress(p, d, ChallengeType.MONEY_EARNED, diff);
            } else {
                diff = -diff;
                d.getStats().addMoneySpent(diff);
                addProgress(p, d, ChallengeType.MONEY_SPENT, diff);
            }
            d.setLastBalance(cur);
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent e) {
        if (e.getEntity().getKiller() == null) return;
        Player k = e.getEntity().getKiller();
        PlayerData d = getData(k.getUniqueId());
        int xp = 0; double money = 0.0;
        if (e.getEntityType() == EntityType.PLAYER) {
            d.getStats().addKill();
            xp = getConfig().getInt("killRewards.players.xp",25);
            money = getConfig().getDouble("killRewards.players.money",0.0);
        } else {
            d.getStats().addMobKill();
            xp = getConfig().getInt("killRewards.mobs.xp",10);
            money = getConfig().getDouble("killRewards.mobs.money",0.0);
            addProgress(k, d, ChallengeType.MOB_KILLS, 1);
        }
        applyHealing(k, d);
        if (xp > 0) awardXp(k, xp);
        if (money > 0) {
            economy.depositPlayer(k, money);
            msg(k, "You earned $" + money);
            d.getStats().addMoneyEarned(money);
            d.setLastBalance(economy.getBalance(k));
        }
        if (d.isScoreboardEnabled() &&
            (d.isFieldEnabled(ScoreField.KILLS) || d.isFieldEnabled(ScoreField.MOB_KILLS))) {
            updateScoreboard(k);
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        PlayerData d = getData(p.getUniqueId());
        d.getStats().addDeath();
        if (d.isScoreboardEnabled() && d.isFieldEnabled(ScoreField.DEATHS)) {
            updateScoreboard(p);
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player) {
            handlePlayerDamageDealt((Player)e.getDamager(), e);
        }
        if (e.getEntity() instanceof Player) {
            handlePlayerDamageTaken((Player)e.getEntity(), e);
        }
    }

    private void handlePlayerDamageDealt(Player p, EntityDamageByEntityEvent e) {
        PlayerData d = getData(p.getUniqueId());
        d.getStats().addDamageDealt(e.getDamage());
        int lvl = d.getSkillLevel(Skill.DAMAGE);
        if (lvl > 0) e.setDamage(e.getDamage() * (1 + 0.05 * lvl));
        applyLifesteal(p, e, d);
    }

    private void applyLifesteal(Player p, EntityDamageByEntityEvent e, PlayerData d) {
        int lvl = d.getSkillLevel(Skill.LIFESTEAL);
        if (lvl > 0) {
            double heal = e.getDamage() * 0.05 * lvl;
            p.setHealth(Math.min(p.getMaxHealth(), p.getHealth() + heal));
        }
    }

    private void handlePlayerDamageTaken(Player p, EntityDamageByEntityEvent e) {
        PlayerData d = getData(p.getUniqueId());
        d.getStats().addDamageTaken(e.getDamage());
        int lvl = d.getSkillLevel(Skill.DAMAGE_REDUCTION);
        if (lvl > 0) e.setDamage(e.getDamage() * (1 - 0.05 * lvl));
        addProgress(p, d, ChallengeType.DAMAGE_TAKEN, e.getDamage());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!e.getFrom().getWorld().equals(e.getTo().getWorld())) return;
        double dist = e.getFrom().distance(e.getTo());
        Player p = e.getPlayer(); PlayerData d = getData(p.getUniqueId());
        double km = dist / 1000.0;
        d.getStats().addKilometersTraveled(km);
        addProgress(p, d, ChallengeType.KILOMETERS_TRAVELED, km);
        int xp = (int) Math.round(km * 5);
        if (xp > 0) awardXp(p, xp);
        if (d.isScoreboardEnabled() && d.isFieldEnabled(ScoreField.KM)) updateScoreboard(p);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player)e.getWhoClicked();
        String title = e.getView().getTitle();
        boolean custom = title.equals("Menu") || title.equals("Skills") || title.equals("Daily")
            || title.equals("Weekly") || title.equals("Stats") || title.equals("Config")
            || title.equals("Scoreboard") || title.equals("Admin Menu")
            || title.equals("Level Config") || title.equals("Challenge Config")
            || title.startsWith("Challenge ") || title.equals("Kill Config");
        if (!custom) return;

        // Voor alle custom GUIs: cancel except als speler juist in eigen inventory klikt bij set-item
        boolean picking = pendingRewardLevel.containsKey(p.getUniqueId());
        if (!picking || e.getClickedInventory() != p.getInventory() || e.isShiftClick()) {
            e.setCancelled(true);
        }

        ClickType click = e.getClick();
        int slot = e.getRawSlot();

        // --- MAIN MENU ---
        if (title.equals("Menu")) {
            switch (slot) {
                case 0: openSkillGui(p); break;
                case 1: openDailyGui(p); break;
                case 2: openWeeklyGui(p); break;
                case 3: openStatsGui(p); break;
                case 4: openConfigGui(p); break;
            }
            return;
        }

        // --- SKILLS ---
        if (title.equals("Skills")) {
            if (slot >= 0 && slot < Skill.values().length) {
                Skill s = Skill.values()[slot];
                PlayerData d = getData(p.getUniqueId());
                d.levelSkill(s);
                updateLungCapacity(p, d);
                openSkillGui(p);
            } else if (slot == 7) {
                openMainGui(p);
            }
            return;
        }

        // --- ADMIN MENU ---
        if (title.equals("Admin Menu")) {
            switch (slot) {
                case 0: openLevelConfigGui(p); break;
                case 1: openChallengeConfigGui(p); break;
                case 2: openKillConfigGui(p); break;
                case 8: p.closeInventory(); break;
            }
            return;
        }

        // --- LEVEL CONFIG ---
        if (title.equals("Level Config")) {
            int rewardSlots = levelCap / 20;
            int extra = 4;
            int moneySlot = ((rewardSlots + extra + 8)/9)*9 - extra;
            int itemSlot  = moneySlot + 1;
            int capSlot   = moneySlot + 2;
            int backSlot  = moneySlot + 3;

            // set reward item
            if (pendingRewardLevel.containsKey(p.getUniqueId()) && e.getClickedInventory() == p.getInventory()) {
                ItemStack cur = e.getCurrentItem();
                if (cur != null && !cur.getType().isAir()) {
                    int lvl = pendingRewardLevel.remove(p.getUniqueId());
                    if (lvl == -1) getConfig().set("itemReward", cur.getType().name());
                    else           getConfig().set("itemRewards." + lvl, cur.getType().name());
                    saveConfig();
                    msg(p, "Set reward for " + (lvl==-1?"default":"level "+lvl) + " to " + cur.getType());
                    openLevelConfigGui(p);
                }
                return;
            }

            if (slot < rewardSlots) {
                int lvl = (slot+1)*20;
                pendingRewardLevel.put(p.getUniqueId(), lvl);
                msg(p, "Klik een item in je inventory om reward voor level " + lvl + " in te stellen");
                return;
            }
            if (slot == moneySlot) {
                double m = getConfig().getDouble("moneyReward",100.0);
                m += (click==ClickType.RIGHT?1:-1)*(click.isShiftClick()?10:1);
                getConfig().set("moneyReward", Math.max(0,m));
                saveConfig(); openLevelConfigGui(p);
                return;
            }
            if (slot == itemSlot) {
                pendingRewardLevel.put(p.getUniqueId(), -1);
                msg(p, "Klik een item in je inventory voor default reward");
                return;
            }
            if (slot == capSlot) {
                levelCap += (click==ClickType.RIGHT?10:-10);
                levelCap = Math.max(1, Math.min(1000, levelCap));
                getConfig().set("levelCap", levelCap);
                saveConfig(); openLevelConfigGui(p);
                return;
            }
            if (slot == backSlot) {
                openAdminMenu(p);
                return;
            }
        }

        // --- CHALLENGE CONFIG ---
        if (title.equals("Challenge Config")) {
            if (slot < ChallengeType.values().length) {
                openChallengeTypeGui(p, ChallengeType.values()[slot]);
            } else if (slot == 8) {
                openAdminMenu(p);
            }
            return;
        }

        // --- CHALLENGE TYPE ---
        if (title.startsWith("Challenge ")) {
            ChallengeType type = ChallengeType.valueOf(title.substring(10));
            String base = "challengeRewards.";
            if (slot >= 0 && slot <= 5) {
                String key = slot < 3 ? "daily" : "weekly";
                int delta = click.isShiftClick() ? 10 : 1;
                if (click == ClickType.LEFT) delta = -delta;
                int idx = slot % 3;
                if (idx == 2) { // goal
                    String path = key + "Goals." + type.name();
                    double v = Math.max(0, getConfig().getDouble(path) + delta);
                    getConfig().set(path, v);
                    if (key.equals("daily")) dailyGoals.put(type, v); else weeklyGoals.put(type, v);
                } else {
                    String sub = idx == 0 ? "xp" : "money";
                    String path = base + key + ".types." + type.name() + "." + sub;
                    if (sub.equals("xp")) {
                        int v = Math.max(0, getConfig().getInt(path) + delta);
                        getConfig().set(path, v);
                    } else {
                        double v = Math.max(0, getConfig().getDouble(path) + delta);
                        getConfig().set(path, v);
                    }
                }
                saveConfig();
                openChallengeTypeGui(p, type);
            } else if (slot == 8) {
                openChallengeConfigGui(p);
            }
            return;
        }

        // --- KILL CONFIG ---
        if (title.equals("Kill Config")) {
            if (slot==0 || slot==1 || slot==3 || slot==4) {
                boolean isXP = (slot==0 || slot==3);
                String path = slot<2? "killRewards.players." : "killRewards.mobs.";
                String sub = isXP ? "xp" : "money";
                int delta = click.isShiftClick()?10:1;
                if (click==ClickType.LEFT) delta = -delta;
                if (isXP) {
                    int v = Math.max(0, getConfig().getInt(path+sub)+delta);
                    getConfig().set(path+sub, v);
                } else {
                    double v = Math.max(0, getConfig().getDouble(path+sub)+delta);
                    getConfig().set(path+sub, v);
                }
                saveConfig(); openKillConfigGui(p);
            } else if (slot==8) {
                openAdminMenu(p);
            }
            return;
        }

        // --- STATS ---
        if (title.equals("Stats")) {
            if (slot == 26) openMainGui(p);
            return;
        }

        // --- DAILY & WEEKLY & SCOREBOARD & CONFIG ---
        if (title.equals("Daily"))    { if (slot==17) openMainGui(p); return; }
        if (title.equals("Weekly"))   { if (slot==17) openMainGui(p); return; }
        if (title.equals("Scoreboard")) {
            if (slot == e.getView().getTopInventory().getSize()-1) {
                openConfigGui(p);
                return;
            }
            PlayerData d = getData(p.getUniqueId());
            int idx = slot;
            if (idx < d.getBoardOrder().size()) {
                if (e.isShiftClick()) {
                    int dir = e.isLeftClick()? -1:1;
                    int tgt = idx + dir;
                    if (tgt>=0 && tgt<d.getBoardOrder().size()) {
                        Collections.swap(d.getBoardOrder(), idx, tgt);
                    }
                } else {
                    ScoreField f = d.getBoardOrder().get(idx);
                    boolean on = d.isFieldEnabled(f);
                    d.setFieldEnabled(f, !on);
                    if (f == ScoreField.BALANCE) d.setShowBalance(!on);
                }
                openScoreboardGui(p);
                updateScoreboard(p);
            }
            return;
        }
        if (title.equals("Config")) {
            PlayerData d = getData(p.getUniqueId());
            if (slot==0) {
                d.setScoreboardEnabled(!d.isScoreboardEnabled());
                if (d.isScoreboardEnabled()) updateScoreboard(p);
                else p.setScoreboard(scoreboardManager.getNewScoreboard());
                openConfigGui(p);
            } else if (slot==1) {
                openScoreboardGui(p);
            } else if (slot==8) {
                openMainGui(p);
            }
            return;
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        String title = e.getView().getTitle();
        boolean custom = title.startsWith("Level Config") || title.startsWith("Admin Menu")
            || title.startsWith("Challenge") || title.startsWith("Kill Config")
            || title.startsWith("Daily") || title.startsWith("Weekly")
            || title.startsWith("Skills") || title.startsWith("Stats")
            || title.startsWith("Scoreboard") || title.startsWith("Config");
        if (custom) e.setCancelled(true);
    }

    // === Core mechanics: XP, levels, rewards, progress, scoreboard ===
    private void awardXp(Player p, int amt) {
        PlayerData d = getData(p.getUniqueId());
        d.addXp(amt);
        if (d.getLevel() < levelCap && d.tryLevelUp()) {
            msg(p, "Je bent geleveld naar level " + d.getLevel() + "!");
            p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
            giveLevelReward(p, d);
        } else if (d.getLevel() >= levelCap) {
            int capXp = d.getLevel() * 100;
            d.setXp(Math.min(d.getXp(), capXp));
        }
        updateScoreboard(p);
    }

    private void giveLevelReward(Player p, PlayerData d) {
        int lvl = d.getLevel();
        if (lvl % 20 == 0) {
            String matName = getConfig().getString("itemRewards." + lvl, getConfig().getString("itemReward", "DIAMOND"));
            Material m = Material.matchMaterial(matName);
            if (m!=null && m!=Material.AIR) p.getInventory().addItem(new ItemStack(m));
        } else if (lvl % 5 != 0) {
            double money = getConfig().getDouble("moneyReward", 100.0);
            economy.depositPlayer(p, money);
            msg(p, "You earned $" + money);
            d.getStats().addMoneyEarned(money);
            addProgress(p, d, ChallengeType.MONEY_EARNED, money);
            d.setLastBalance(economy.getBalance(p));
        }
    }

    private void addProgress(Player p, PlayerData d, ChallengeType type, double amount) {
        if (activeDaily.contains(type)) {
            double prog = d.getDailyProgress().get(type);
            double goal = dailyGoals.get(type);
            if (prog < goal) {
                double nxt = Math.min(goal, prog + amount);
                d.getDailyProgress().put(type, nxt);
                if (nxt >= goal) checkChallenge(p, d, type, true);
            }
        }
        if (activeWeekly.contains(type)) {
            double prog = d.getWeeklyProgress().get(type);
            double goal = weeklyGoals.get(type);
            if (prog < goal) {
                double nxt = Math.min(goal, prog + amount);
                d.getWeeklyProgress().put(type, nxt);
                if (nxt >= goal) checkChallenge(p, d, type, false);
            }
        }
    }

    private void checkChallenge(Player p, PlayerData d, ChallengeType type, boolean daily) {
        double prog = daily ? d.getDailyProgress().get(type) : d.getWeeklyProgress().get(type);
        double goal = daily ? dailyGoals.get(type) : weeklyGoals.get(type);
        if (prog >= goal) {
            msg(p, (daily?"Daily":"Weekly")+" challenge voltooid: "+type.name().toLowerCase());
            distributeChallengeReward(p, d, type, daily);
            if (daily) d.getDailyProgress().put(type, goal);
            else      d.getWeeklyProgress().put(type, goal);
        }
    }

    private void distributeChallengeReward(Player p, PlayerData d, ChallengeType type, boolean daily) {
        String path = "challengeRewards." + (daily?"daily":"weekly") + ".types." + type.name();
        int xp = getConfig().getInt(path + ".xp", getConfig().getInt("challengeRewards." + (daily?"daily":"weekly") + ".xp", daily?20:50));
        double money = getConfig().getDouble(path + ".money", getConfig().getDouble("challengeRewards." + (daily?"daily":"weekly") + ".money", 0.0));
        if (xp > 0) awardXp(p, xp);
        if (money > 0) {
            economy.depositPlayer(p, money);
            msg(p, "You earned $" + money);
            d.getStats().addMoneyEarned(money);
            d.setLastBalance(economy.getBalance(p));
        }
    }

    private void updateScoreboard(Player p) {
        PlayerData d = getData(p.getUniqueId());
        if (!d.isScoreboardEnabled()) return;
        Scoreboard board = scoreboardManager.getNewScoreboard();
        Objective obj = board.registerNewObjective("levelsx","dummy",ChatColor.GREEN+"LevelsX");
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        Stats s = d.getStats();
        long count = d.getBoardOrder().stream().filter(d::isFieldEnabled).count();
        int line = (int)count;
        int needed = d.getLevel()*100;

        for (ScoreField f : d.getBoardOrder()) {
            if (!d.isFieldEnabled(f)) continue;
            switch (f) {
                case LEVEL:
                    obj.getScore(ChatColor.YELLOW+"Level: "+ChatColor.WHITE+d.getLevel()).setScore(line--);
                    break;
                case BALANCE:
                    double bal = economy.getBalance(p);
                    obj.getScore(ChatColor.GOLD+"Balance: "+ChatColor.WHITE+String.format("%.2f", bal)).setScore(line--);
                    break;
                case XP:
                    obj.getScore(ChatColor.YELLOW+"XP: "+ChatColor.WHITE+d.getXp()+"/"+needed).setScore(line--);
                    break;
                case PROGRESS:
                    if (d.getLevel() >= levelCap) {
                        obj.getScore(ChatColor.AQUA+"MAX LEVEL").setScore(line--);
                    } else {
                        obj.getScore(createBar((double)d.getXp()/needed)).setScore(line--);
                    }
                    break;
                case KILLS:
                    obj.getScore(ChatColor.YELLOW+"Kills: "+ChatColor.WHITE+s.getKills()).setScore(line--);
                    break;
                case MOB_KILLS:
                    obj.getScore(ChatColor.YELLOW+"Mob Kills: "+ChatColor.WHITE+s.getMobKills()).setScore(line--);
                    break;
                case DEATHS:
                    obj.getScore(ChatColor.YELLOW+"Deaths: "+ChatColor.WHITE+s.getDeaths()).setScore(line--);
                    break;
                case KM:
                    obj.getScore(ChatColor.YELLOW+"KM: "+ChatColor.WHITE+String.format("%.1f", s.getKilometersTraveled())).setScore(line--);
                    break;
            }
        }
        p.setScoreboard(board);
    }

    private String createBar(double progress) {
        int filled = (int)Math.round(progress * 10);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append(i < filled ? ChatColor.GREEN+"|" : ChatColor.DARK_GRAY+"|");
        }
        return sb.toString();
    }

    private void checkBalances() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData d = getData(p.getUniqueId());
            double cur = economy.getBalance(p), diff = cur - d.getLastBalance();
            if (Math.abs(diff) > 0.01) {
                if (diff > 0) {
                    d.getStats().addMoneyEarned(diff);
                    addProgress(p, d, ChallengeType.MONEY_EARNED, diff);
                } else {
                    diff = -diff;
                    d.getStats().addMoneySpent(diff);
                    addProgress(p, d, ChallengeType.MONEY_SPENT, diff);
                }
                d.setLastBalance(cur);
            }
        }
    }

    private void checkChallengeResets() {
        long now = System.currentTimeMillis();
        if (now - lastDailySelect > 86400000L) {
            lastDailySelect = now; selectDailyChallenges();
            players.values().forEach(d -> d.setLastDailyReset(now));
        }
        if (now - lastWeeklySelect > 604800000L) {
            lastWeeklySelect = now; selectWeeklyChallenges();
            players.values().forEach(d -> d.setLastWeeklyReset(now));
        }
    }

    // === Commands & TabCompleter ===
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String lbl, String[] args) {
        if (!(sender instanceof Player)) {
            msg(sender, "Alleen spelers kunnen dit commando gebruiken");
            return true;
        }
        Player p = (Player)sender;
        if (args.length == 0) {
            openMainGui(p); return true;
        }
        switch (args[0].toLowerCase()) {
            case "help":
                msg(p, "/skill - open menu");
                msg(p, "/skill admin - admin menu");
                msg(p, "/skill leaderboard <stat>");
                msg(p, "/skill stats");
                msg(p, "/skill challenges");
                msg(p, "/skill spend <amount>");
                msg(p, "/skill scoreboard");
                msg(p, "/skill backup <sql|sqlite>");
                msg(p, "/skill reload");
                msg(p, "/skill setlevel <player> <level>");
                msg(p, "/skill setskillpoints <player> <amount>");
                break;
            case "admin":
                if (!p.hasPermission("skill.admin")) msg(p,"Geen permissie");
                else openAdminMenu(p);
                break;
            case "leaderboard":
                showLeaderboard(p, args.length>1?args[1]:"kills");
                break;
            case "stats":
                openStatsGui(p); break;
            case "challenges":
                openDailyGui(p); break;
            case "spend":
                if (args.length>1) doSpend(p,args[1]);
                else msg(p,"Gebruik /skill spend <amount>");
                break;
            case "scoreboard":
                toggleScoreboard(p); break;
            case "reload":
                if (!p.hasPermission("skill.admin")) msg(p,"Geen permissie");
                else {
                    reloadPlugin();
                    msg(p,"Config herladen");
                }
                break;
            case "backup":
                if (!p.hasPermission("skill.admin")) msg(p,"Geen permissie");
                else if (args.length>1) doBackup(p,args[1]);
                else msg(p,"Geef sql of sqlite op");
                break;
            case "setlevel":
                if (!p.hasPermission("skill.admin")) msg(p,"Geen permissie");
                else if (args.length<3) msg(p,"Gebruik /skill setlevel <player> <level>");
                else {
                    Player t = Bukkit.getPlayer(args[1]);
                    if (t==null) msg(p,"Speler niet online");
                    else {
                        try {
                            int lvl = Integer.parseInt(args[2]);
                            PlayerData d = getData(t.getUniqueId());
                            d.setLevel(Math.max(1, Math.min(levelCap, lvl)));
                            updateScoreboard(t);
                            msg(p,"Level voor "+t.getName()+" ingesteld op "+lvl);
                        } catch (NumberFormatException ex) { msg(p,"Ongeldig level"); }
                    }
                }
                break;
            case "setskillpoints":
                if (!p.hasPermission("skill.admin")) msg(p,"Geen permissie");
                else if (args.length<3) msg(p,"Gebruik /skill setskillpoints <player> <amount>");
                else {
                    Player t = Bukkit.getPlayer(args[1]);
                    if (t==null) msg(p,"Speler niet online");
                    else {
                        try {
                            int amt = Integer.parseInt(args[2]);
                            getData(t.getUniqueId()).setSkillPoints(Math.max(0, amt));
                            msg(p,"Skill points voor "+t.getName()+" ingesteld op "+amt);
                        } catch (NumberFormatException ex) { msg(p,"Ongeldige waarde"); }
                    }
                }
                break;
            default:
                msg(p,"Gebruik /skill help voor commando's");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        if (args.length==1) {
            return Arrays.asList("help","admin","leaderboard","stats","challenges","spend","scoreboard","backup","reload","setlevel","setskillpoints")
                    .stream().filter(x->x.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
        }
        if (args.length==2 && args[0].equalsIgnoreCase("leaderboard")) {
            return Arrays.asList("kills","mobkills","deaths","damage","distance").stream()
                    .filter(x->x.startsWith(args[1].toLowerCase())).collect(Collectors.toList());
        }
        if (args.length==2 && args[0].equalsIgnoreCase("backup")) {
            return Arrays.asList("sql","sqlite").stream()
                    .filter(x->x.startsWith(args[1].toLowerCase())).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    // === Utility methods ===
    private void doBackup(Player p, String dest) {
        try {
            if (dest.equalsIgnoreCase("sql")) {
                FileConfiguration cfg = getConfig();
                MySqlStorage b = new MySqlStorage(
                        cfg.getString("storage.mysql.host","localhost"),
                        cfg.getInt("storage.mysql.port",3306),
                        cfg.getString("storage.mysql.database","levelsx"),
                        cfg.getString("storage.mysql.user","root"),
                        cfg.getString("storage.mysql.password","")
                );
                b.save(players); b.close();
            } else {
                SqliteStorage b = new SqliteStorage(new File(getDataFolder(),"backup"));
                b.save(players); b.close();
            }
            msg(p,"Backup voltooid");
        } catch (Exception e) {
            msg(p,"Backup mislukt: "+e.getMessage());
        }
    }

    private void doSpend(Player p, String arg) {
        try {
            double amt = Double.parseDouble(arg);
            PlayerData d = getData(p.getUniqueId());
            d.getStats().addMoneySpent(amt);
            addProgress(p, d, ChallengeType.MONEY_SPENT, amt);
            economy.withdrawPlayer(p, amt);
            msg(p,"You spent $"+amt);
            d.setLastBalance(economy.getBalance(p));
            int xp = (int)(amt/100);
            if (xp>0) awardXp(p,xp);
        } catch (NumberFormatException e) {
            msg(p,"Ongeldige hoeveelheid");
        }
    }

    private void loadData() {
        try { if (storage != null) storage.load(players); }
        catch (Exception e) { getLogger().severe("Load failed: "+e.getMessage()); }
    }
    private void saveData() {
        try { if (storage != null) storage.save(players); }
        catch (Exception e) { getLogger().severe("Save failed: "+e.getMessage()); }
    }

    private void msg(CommandSender t, String m) {
        t.sendMessage(ChatColor.GOLD+"[LevelsX] "+ChatColor.RESET+m);
    }
    private ItemStack createItem(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta im = it.getItemMeta();
        im.setDisplayName(name);
        if (lore.length>0) im.setLore(Arrays.asList(lore));
        it.setItemMeta(im);
        return it;
    }
    private void openMainGui(Player p) {
        Inventory inv = Bukkit.createInventory(p,9,"Menu");
        inv.setItem(0, createItem(Material.ENCHANTED_BOOK,"Skills"));
        inv.setItem(1, createItem(Material.PAPER,"Daily"));
        inv.setItem(2, createItem(Material.MAP,"Weekly"));
        inv.setItem(3, createItem(Material.BOOK,"Stats"));
        inv.setItem(4, createItem(Material.COMPARATOR,"Config"));
        p.openInventory(inv);
    }
    private void openSkillGui(Player p) {
        Inventory inv = Bukkit.createInventory(p,9,"Skills");
        PlayerData d = getData(p.getUniqueId());
        int i=0;
        for (Skill s : Skill.values()) {
            List<String> lore = new ArrayList<>();
            lore.add("Level "+d.getSkillLevel(s));
            lore.add(getSkillInfo(s,d.getSkillLevel(s)));
            lore.add("Max 10");
            inv.setItem(i++, createItem(Material.BOOK,s.name(), lore.toArray(new String[0])));
        }
        inv.setItem(7, createItem(Material.ARROW,"Back"));
        inv.setItem(8, createItem(Material.EXPERIENCE_BOTTLE,"Skill Points: "+d.getSkillPoints()));
        p.openInventory(inv);
    }
    private void openDailyGui(Player p) {
        Inventory inv = Bukkit.createInventory(p,18,"Daily");
        PlayerData d = getData(p.getUniqueId());
        int i=0;
        for (ChallengeType t : activeDaily) {
            double prog = d.getDailyProgress().get(t), goal = dailyGoals.get(t);
            int xp = getConfig().getInt("challengeRewards.daily.types."+t.name()+".xp", getConfig().getInt("challengeRewards.daily.xp",20));
            double money = getConfig().getDouble("challengeRewards.daily.types."+t.name()+".money", getConfig().getDouble("challengeRewards.daily.money",0.0));
            inv.setItem(i++, createItem(Material.PAPER,t.name(), String.format("%.1f/%.1f",prog,goal), "Reward: "+xp+" XP, $"+money));
        }
        inv.setItem(17, createItem(Material.ARROW,"Back"));
        p.openInventory(inv);
    }
    private void openWeeklyGui(Player p) {
        Inventory inv = Bukkit.createInventory(p,18,"Weekly");
        PlayerData d = getData(p.getUniqueId());
        int i=0;
        for (ChallengeType t : activeWeekly) {
            double prog = d.getWeeklyProgress().get(t), goal = weeklyGoals.get(t);
            int xp = getConfig().getInt("challengeRewards.weekly.types."+t.name()+".xp", getConfig().getInt("challengeRewards.weekly.xp",50));
            double money = getConfig().getDouble("challengeRewards.weekly.types."+t.name()+".money", getConfig().getDouble("challengeRewards.weekly.money",0.0));
            inv.setItem(i++, createItem(Material.MAP,t.name(), String.format("%.1f/%.1f",prog,goal), "Reward: "+xp+" XP, $"+money));
        }
        inv.setItem(17, createItem(Material.ARROW,"Back"));
        p.openInventory(inv);
    }
    private void openStatsGui(Player p) {
        Inventory inv = Bukkit.createInventory(p,27,"Stats");
        PlayerData d = getData(p.getUniqueId()); Stats s = d.getStats();
        inv.setItem(0, createItem(Material.EXPERIENCE_BOTTLE,"Level "+d.getLevel(),"XP: "+d.getXp(),"Skill points: "+d.getSkillPoints()));
        inv.setItem(1, createItem(Material.IRON_SWORD,"Kills",String.valueOf(s.getKills())));
        inv.setItem(2, createItem(Material.ZOMBIE_HEAD,"Mob Kills",String.valueOf(s.getMobKills())));
        inv.setItem(3, createItem(Material.SKELETON_SKULL,"Deaths",String.valueOf(s.getDeaths())));
        inv.setItem(4, createItem(Material.DIAMOND_SWORD,"Damage Dealt",String.format("%.1f",s.getDamageDealt())));
        inv.setItem(5, createItem(Material.SHIELD,"Damage Taken",String.format("%.1f",s.getDamageTaken())));
        inv.setItem(6, createItem(Material.EMERALD,"Money Earned",String.format("%.2f",s.getMoneyEarned())));
        inv.setItem(7, createItem(Material.GOLD_INGOT,"Money Spent",String.format("%.2f",s.getMoneySpent())));
        inv.setItem(8, createItem(Material.COMPASS,"Kilometers Traveled",String.format("%.2f",s.getKilometersTraveled())));
        inv.setItem(9, createItem(Material.CLOCK,"Time Online",formatTime(s.getTimeOnline())));
        inv.setItem(26, createItem(Material.ARROW,"Back"));
        p.openInventory(inv);
    }
    private void openConfigGui(Player p) {
        Inventory inv = Bukkit.createInventory(p,9,"Config");
        PlayerData d = getData(p.getUniqueId());
        inv.setItem(0, createItem(Material.COMPARATOR,"Scoreboard", d.isScoreboardEnabled()?"ON":"OFF"));
        inv.setItem(1, createItem(Material.PAPER,"Scoreboard Settings"));
        inv.setItem(8, createItem(Material.ARROW,"Back"));
        p.openInventory(inv);
    }
    private void openScoreboardGui(Player p) {
        PlayerData d = getData(p.getUniqueId());
        int size = ((ScoreField.values().length+1)/9+1)*9;
        Inventory inv = Bukkit.createInventory(p,size,"Scoreboard");
        int slot = 0;
        for (ScoreField f : d.getBoardOrder()) {
            Material m = d.isFieldEnabled(f)? Material.LIME_WOOL : Material.RED_WOOL;
            inv.setItem(slot++, createItem(m,f.getLabel(),"Left: toggle","Shift+Left/Right: move"));
        }
        inv.setItem(size-1, createItem(Material.ARROW,"Back"));
        p.openInventory(inv);
    }
    private void openAdminMenu(Player p) {
        Inventory inv = Bukkit.createInventory(p,9,"Admin Menu");
        inv.setItem(0, createItem(Material.EXPERIENCE_BOTTLE,"Level Config"));
        inv.setItem(1, createItem(Material.PAPER,"Challenge Config"));
        inv.setItem(2, createItem(Material.DIAMOND_SWORD,"Kill Config"));
        inv.setItem(8, createItem(Material.BARRIER,"Close"));
        p.openInventory(inv);
    }
    private void openLevelConfigGui(Player p) {
        int rewardSlots = levelCap / 20;
        int extra = 4;
        int moneySlot = ((rewardSlots + extra + 8) / 9) * 9 - extra;
        int itemSlot  = moneySlot + 1;
        int capSlot   = moneySlot + 2;
        int backSlot  = moneySlot + 3;
        int size = backSlot + 1;

        Inventory inv = Bukkit.createInventory(p, size, "Level Config");

        for (int i = 0; i < rewardSlots; i++) {
            int lvl = (i + 1) * 20;
            String matName = getConfig().getString("itemRewards." + lvl,
                    getConfig().getString("itemReward", "DIAMOND"));
            Material m = Material.matchMaterial(matName);
            if (m == null) m = Material.BARRIER;
            inv.setItem(i, createItem(m, "Level " + lvl));
        }

        double money = getConfig().getDouble("moneyReward", 100.0);
        inv.setItem(moneySlot, createItem(Material.EMERALD, "Money Reward", "$" + money));

        String defItem = getConfig().getString("itemReward", "DIAMOND");
        Material m = Material.matchMaterial(defItem);
        if (m == null) m = Material.BARRIER;
        inv.setItem(itemSlot, createItem(m, "Default Item"));

        inv.setItem(capSlot, createItem(Material.BEDROCK, "Level Cap", String.valueOf(levelCap)));
        inv.setItem(backSlot, createItem(Material.ARROW, "Back"));

        p.openInventory(inv);
    }

    private void openChallengeConfigGui(Player p) {
        Inventory inv = Bukkit.createInventory(p, 9, "Challenge Config");
        int i = 0;
        for (ChallengeType t : ChallengeType.values()) {
            inv.setItem(i++, createItem(Material.PAPER, t.name()));
        }
        inv.setItem(8, createItem(Material.ARROW, "Back"));
        p.openInventory(inv);
    }

    private void openChallengeTypeGui(Player p, ChallengeType t) {
        Inventory inv = Bukkit.createInventory(p, 9, "Challenge " + t.name());
        String base = "challengeRewards.";

        int dxp = getConfig().getInt(base + "daily.types." + t.name() + ".xp",
                getConfig().getInt(base + "daily.xp", 20));
        double dmoney = getConfig().getDouble(base + "daily.types." + t.name() + ".money",
                getConfig().getDouble(base + "daily.money", 0.0));
        int wxp = getConfig().getInt(base + "weekly.types." + t.name() + ".xp",
                getConfig().getInt(base + "weekly.xp", 50));
        double wmoney = getConfig().getDouble(base + "weekly.types." + t.name() + ".money",
                getConfig().getDouble(base + "weekly.money", 0.0));

        double dgoal = getConfig().getDouble("dailyGoals." + t.name(), getDefaultGoal(t));
        double wgoal = getConfig().getDouble("weeklyGoals." + t.name(), getDefaultGoal(t) * 5);

        inv.setItem(0, createItem(Material.EXPERIENCE_BOTTLE, "Daily XP", String.valueOf(dxp)));
        inv.setItem(1, createItem(Material.EMERALD, "Daily Money", "$" + dmoney));
        inv.setItem(2, createItem(Material.TARGET, "Daily Goal", String.valueOf(dgoal)));
        inv.setItem(3, createItem(Material.EXPERIENCE_BOTTLE, "Weekly XP", String.valueOf(wxp)));
        inv.setItem(4, createItem(Material.EMERALD, "Weekly Money", "$" + wmoney));
        inv.setItem(5, createItem(Material.TARGET, "Weekly Goal", String.valueOf(wgoal)));
        inv.setItem(8, createItem(Material.ARROW, "Back"));

        p.openInventory(inv);
    }

    private void openKillConfigGui(Player p) {
        Inventory inv = Bukkit.createInventory(p, 9, "Kill Config");

        int pxp = getConfig().getInt("killRewards.players.xp", 25);
        double pmoney = getConfig().getDouble("killRewards.players.money", 0.0);
        int mxp = getConfig().getInt("killRewards.mobs.xp", 10);
        double mmoney = getConfig().getDouble("killRewards.mobs.money", 0.0);

        inv.setItem(0, createItem(Material.EXPERIENCE_BOTTLE, "Player XP", String.valueOf(pxp)));
        inv.setItem(1, createItem(Material.EMERALD, "Player Money", "$" + pmoney));
        inv.setItem(3, createItem(Material.EXPERIENCE_BOTTLE, "Mob XP", String.valueOf(mxp)));
        inv.setItem(4, createItem(Material.EMERALD, "Mob Money", "$" + mmoney));
        inv.setItem(8, createItem(Material.ARROW, "Back"));

        p.openInventory(inv);
    }

    private void toggleScoreboard(Player p) {
        PlayerData d = getData(p.getUniqueId());
        boolean on = !d.isScoreboardEnabled();
        d.setScoreboardEnabled(on);
        if (on) {
            updateScoreboard(p);
        } else {
            p.setScoreboard(scoreboardManager.getNewScoreboard());
        }
        msg(p, "Scoreboard " + (on ? "aan" : "uit"));
    }

    private void showLeaderboard(Player p, String stat) {
        List<Map.Entry<UUID, PlayerData>> list = new ArrayList<>(players.entrySet());
        Comparator<Map.Entry<UUID, PlayerData>> comp;
        switch (stat.toLowerCase()) {
            case "kills":
                comp = Comparator.comparingInt(e -> e.getValue().getStats().getKills());
                break;
            case "mobkills":
                comp = Comparator.comparingInt(e -> e.getValue().getStats().getMobKills());
                break;
            case "deaths":
                comp = Comparator.comparingInt(e -> e.getValue().getStats().getDeaths());
                break;
            case "damage":
                comp = Comparator.comparingDouble(e -> e.getValue().getStats().getDamageDealt());
                break;
            case "distance":
                comp = Comparator.comparingDouble(e -> e.getValue().getStats().getKilometersTraveled());
                break;
            default:
                msg(p, "Onbekende statistiek");
                return;
        }
        list.sort(comp.reversed());
        msg(p, "Leaderboard voor " + stat.toLowerCase() + ":");
        int max = Math.min(10, list.size());
        for (int i = 0; i < max; i++) {
            Map.Entry<UUID, PlayerData> e = list.get(i);
            String name = Bukkit.getOfflinePlayer(e.getKey()).getName();
            Stats s = e.getValue().getStats();
            double val;
            switch (stat.toLowerCase()) {
                case "kills": val = s.getKills(); break;
                case "mobkills": val = s.getMobKills(); break;
                case "deaths": val = s.getDeaths(); break;
                case "damage": val = s.getDamageDealt(); break;
                default: val = s.getKilometersTraveled(); break;
            }
            String formatted = (stat.equalsIgnoreCase("kills") || stat.equalsIgnoreCase("mobkills") || stat.equalsIgnoreCase("deaths"))
                    ? String.valueOf((int) val)
                    : String.format("%.1f", val);
            msg(p, (i + 1) + ". " + name + ": " + formatted);
        }
    }

    private void updateLungCapacity(Player p, PlayerData d) {
        int lvl = d.getSkillLevel(Skill.LUNG_CAPACITY);
        int base = 300; // default ticks of air
        p.setMaximumAir(base + lvl * 20);
        if (p.getRemainingAir() < base + lvl * 20) {
            p.setRemainingAir(base + lvl * 20);
        }
    }

    private void applyHealing(Player p, PlayerData d) {
        int lvl = d.getSkillLevel(Skill.HEALING);
        if (lvl > 0) {
            double heal = lvl * 2.0; // hearts to health
            p.setHealth(Math.min(p.getMaxHealth(), p.getHealth() + heal));
        }
    }

    // === Overige helpers ===
    private String formatTime(long ms) {
        long totMin = ms/60000;
        long days = totMin/(60*24);
        long hrs = (totMin%(60*24))/60;
        long mins = totMin%60;
        return String.format("%02d:%02d:%02d", days, hrs, mins);
    }
    private String getSkillInfo(Skill s, int lvl) {
        switch(s) {
            case DAMAGE: return "Damage +"+(lvl*5)+"%";
            case DAMAGE_REDUCTION: return "Damage taken -"+(lvl*5)+"%";
            case HEALING: return "Heal "+lvl+" hearts";
            case LIFESTEAL: return "Lifesteal "+(lvl*5)+"%";
            case LUNG_CAPACITY: return "+"+lvl+"s underwater";
            default: return "";
        }
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault")==null) return false;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp==null) return false;
        economy = rsp.getProvider();
        return economy != null;
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
            getLogger().severe("Storage init failed: "+e.getMessage());
        }
    }

    private void reloadPlugin() {
        reloadConfig();
        levelCap = Math.min(1000, Math.max(1, getConfig().getInt("levelCap",100)));
        initChallenges();
        if (autosaveTask != -1) Bukkit.getScheduler().cancelTask(autosaveTask);
        int minutes = Math.max(1, getConfig().getInt("autosave",5));
        autosaveTask = Bukkit.getScheduler()
            .scheduleSyncRepeatingTask(this, this::saveData, 20L*60*minutes, 20L*60*minutes);
        for (Player p : Bukkit.getOnlinePlayers()) updateScoreboard(p);
    }
}
