package com.mira.daily;

import com.mira.core.api.MiraCore;
import com.mira.core.api.MiraCoreProvider;
import com.mira.core.api.ModuleHealth;
import com.mira.daily.api.event.DailyRewardClaimEvent;
import com.mira.leaderboards.MiraLeaderboardsPlugin.BoardScope;
import com.mira.leaderboards.MiraLeaderboardsPlugin.MiraLeaderboardsApi;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.time.*;
import java.util.*;

public final class MiraDailyPlugin extends JavaPlugin implements Listener {
    private static final String PREFIX = "&5&lMira &8>> &r";

    private MiraCore core;
    private DailyService daily;
    private MiraLeaderboardsApi leaderboards;
    private String guiTitle;
    private ZoneId zone;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        core = MiraCoreProvider.require();
        zone = parseZone(getConfig().getString("timezone", "UTC"));
        guiTitle = c(getConfig().getString("calendar-title", "&6Daily Rewards"));
        daily = new DailyService(this);

        getServer().getServicesManager().register(MiraDailyApi.class, daily, this, ServicePriority.Normal);
        core.modules().register(this, "MiraDaily");
        core.services().register(MiraDailyApi.class, daily);

        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("daily")).setExecutor(this);
        Objects.requireNonNull(getCommand("mdaily")).setExecutor(this);
        Objects.requireNonNull(getCommand("daily")).setTabCompleter(this);
        Objects.requireNonNull(getCommand("mdaily")).setTabCompleter(this);

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new DailyPlaceholders(this).register();
        }

        connectLeaderboards();
        if (leaderboards != null) syncAllLeaderboards();

        core.modules().setHealth(this, ModuleHealth.HEALTHY,
                leaderboards == null
                        ? "Daily calendar, streaks, statistics and milestones ready"
                        : "Daily calendar, streaks, milestones and Leaderboards publishing ready");
        getLogger().info("MiraDaily v" + getPluginMeta().getVersion() + " enabled using timezone " + zone + ".");
    }

    @Override
    public void onDisable() {
        if (daily != null) daily.save();
        getServer().getServicesManager().unregisterAll(this);
        if (core != null) {
            if (daily != null) core.services().unregister(MiraDailyApi.class, daily);
            core.modules().unregister(this);
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("daily")) {
            if (!(sender instanceof Player player)) {
                msg(sender, "&cMiraDaily player commands must be run in-game.");
                return true;
            }

            if (args.length == 0) {
                openCalendar(player);
                return true;
            }

            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "claim" -> claimAndRespond(player, false);
                case "streak" -> showStreak(player, player.getUniqueId(), player.getName());
                default -> msg(player, "&e/daily &7| &e/daily claim &7| &e/daily streak");
            }
            return true;
        }

        if (!sender.hasPermission("miradaily.admin")) {
            msg(sender, "&cYou do not have permission.");
            return true;
        }
        if (args.length == 0) {
            adminHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "protection" -> {
                if (args.length < 3) {
                    msg(sender, "&e/mdaily protection <player> <amount>");
                    return true;
                }
                OfflinePlayer target = resolve(args[1]);
                if (target == null) {
                    msg(sender, "&cPlayer not found.");
                    return true;
                }
                int amount;
                try {
                    amount = Integer.parseInt(args[2]);
                } catch (NumberFormatException exception) {
                    msg(sender, "&cAmount must be a whole number.");
                    return true;
                }
                daily.addProtections(target.getUniqueId(), amount);
                audit(sender, "STREAK_PROTECTION_CHANGED", target.getUniqueId().toString(),
                        Map.of("amount", Integer.toString(amount)));
                msg(sender, "&aUpdated streak protections for &f" + name(target) + "&a. New total: &f"
                        + daily.protections(target.getUniqueId()) + "&a.");
            }
            case "reset" -> {
                if (args.length < 2) {
                    msg(sender, "&e/mdaily reset <player>");
                    return true;
                }
                OfflinePlayer target = resolve(args[1]);
                if (target == null) {
                    msg(sender, "&cPlayer not found.");
                    return true;
                }
                daily.reset(target.getUniqueId());
                publish(target.getUniqueId());
                audit(sender, "DAILY_STATE_RESET", target.getUniqueId().toString(), Map.of());
                msg(sender, "&aDaily state reset for &f" + name(target) + "&a.");
            }
            case "status" -> {
                if (args.length < 2) {
                    msg(sender, "&e/mdaily status <player>");
                    return true;
                }
                OfflinePlayer target = resolve(args[1]);
                if (target == null) {
                    msg(sender, "&cPlayer not found.");
                    return true;
                }
                showStreak(sender, target.getUniqueId(), name(target));
            }
            case "reload" -> {
                reloadConfig();
                zone = parseZone(getConfig().getString("timezone", "UTC"));
                guiTitle = c(getConfig().getString("calendar-title", "&6Daily Rewards"));
                connectLeaderboards();
                msg(sender, "&aMiraDaily reloaded. &7Timezone: &f" + zone);
            }
            default -> adminHelp(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("daily")) {
            if (args.length == 1) return complete(args[0], List.of("claim", "streak"));
            return List.of();
        }
        if (!sender.hasPermission("miradaily.admin")) return List.of();
        if (args.length == 1) return complete(args[0], List.of("protection", "reset", "status", "reload"));
        if (args.length == 2 && Set.of("protection", "reset", "status").contains(args[0].toLowerCase(Locale.ROOT))) {
            return complete(args[1], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        }
        return List.of();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        daily.rememberName(event.getPlayer());
        if (leaderboards == null) connectLeaderboards();
        publish(event.getPlayer().getUniqueId());
    }

    private void openCalendar(Player player) {
        int trackDays = trackDays();
        DailyHolder holder = new DailyHolder();
        Inventory inventory = Bukkit.createInventory(holder, 54, guiTitle);
        holder.inventory = inventory;

        int current = daily.nextRewardDay(player.getUniqueId());
        int claimedThrough = daily.trackDay(player.getUniqueId());
        boolean canClaim = daily.canClaim(player.getUniqueId());

        for (int day = 1; day <= trackDays; day++) {
            Material statusMaterial;
            String status;
            if (day <= claimedThrough && !(canClaim && day == current)) {
                statusMaterial = Material.LIME_STAINED_GLASS_PANE;
                status = "&aClaimed";
            } else if (day == current && canClaim) {
                statusMaterial = Material.CHEST;
                status = "&eClick to claim";
            } else {
                statusMaterial = Material.GRAY_STAINED_GLASS_PANE;
                status = "&7Locked";
            }

            Material icon = configuredIcon(day, statusMaterial);
            ItemStack item = new ItemStack(icon);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(c(getConfig().getString("rewards." + day + ".display-name", "&6Day " + day)));

            List<String> lore = new ArrayList<>();
            lore.add(status);
            List<String> configuredLore = getConfig().getStringList("rewards." + day + ".lore");
            if (!configuredLore.isEmpty()) {
                lore.add("");
                lore.addAll(configuredLore);
            } else {
                lore.add("&7Daily reward " + day + " of " + trackDays + ".");
            }
            meta.setLore(lore.stream().map(MiraDailyPlugin::c).toList());
            item.setItemMeta(meta);
            inventory.setItem(day - 1, item);
        }

        ItemStack info = new ItemStack(Material.CLOCK);
        ItemMeta meta = info.getItemMeta();
        meta.setDisplayName(c("&6Your Daily Streak"));
        meta.setLore(List.of(
                c("&7Current: &f" + daily.streak(player.getUniqueId())),
                c("&7Best: &f" + daily.bestStreak(player.getUniqueId())),
                c("&7Total claims: &f" + daily.totalClaims(player.getUniqueId())),
                c("&7Protections: &f" + daily.protections(player.getUniqueId())),
                c("&7Reset in: &f" + formatDuration(Math.max(0L, daily.nextResetAt() - System.currentTimeMillis())))
        ));
        info.setItemMeta(meta);
        inventory.setItem(49, info);

        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof DailyHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= trackDays()) return;
        int day = slot + 1;
        if (!daily.canClaim(player.getUniqueId()) || day != daily.nextRewardDay(player.getUniqueId())) return;
        claimAndRespond(player, true);
    }

    private void claimAndRespond(Player player, boolean closeAfter) {
        UUID id = player.getUniqueId();
        if (!daily.canClaim(id)) {
            msg(player, getConfig().getString("messages.already-claimed",
                    "&eYou have already claimed today's reward."));
            return;
        }

        ClaimResult result;
        try {
            result = daily.claim(player);
        } catch (IllegalStateException exception) {
            msg(player, getConfig().getString("messages.already-claimed",
                    "&eYou have already claimed today's reward."));
            return;
        }

        msg(player, getConfig().getString("messages.claimed",
                        "&aDaily reward claimed! &7Streak: &f%streak%")
                .replace("%streak%", Integer.toString(result.streak()))
                .replace("%day%", Integer.toString(result.rewardDay())));

        if (result.protectionUsed()) {
            msg(player, getConfig().getString("messages.protection-used",
                    "&eA streak protection was used to preserve your streak."));
        }
        if (result.protectionEarned()) {
            msg(player, getConfig().getString("messages.protection-earned",
                    "&aYour streak earned you &f1 &astreak protection."));
        }

        awardMilestones(player, result);
        publish(id);
        Bukkit.getPluginManager().callEvent(new DailyRewardClaimEvent(player, result.rewardDay(), result.streak(),
                daily.bestStreak(id), daily.totalClaims(id), result.protectionUsed(), result.protectionEarned()));
        core.audit().record("MiraDaily", "DAILY_REWARD_CLAIMED", id, player.getName(),
                Integer.toString(result.rewardDay()), "Claimed daily reward",
                Map.of("streak", Integer.toString(result.streak()),
                        "bestStreak", Integer.toString(daily.bestStreak(id)),
                        "totalClaims", Integer.toString(daily.totalClaims(id)),
                        "protectionUsed", Boolean.toString(result.protectionUsed())));

        if (closeAfter) player.closeInventory();
    }

    private void awardMilestones(Player player, ClaimResult result) {
        if (!getConfig().getBoolean("milestones.enabled", true)) return;
        for (Integer streak : getConfig().getIntegerList("milestones.streaks")) {
            if (streak == null || streak <= 0 || result.streak() < streak) continue;
            core.milestones().award(player.getUniqueId(), "miradaily.streak_" + streak,
                    "MiraDaily", Map.of("streak", Integer.toString(streak)));
        }
        if (result.rewardDay() == trackDays()) {
            core.milestones().award(player.getUniqueId(), "miradaily.track_complete",
                    "MiraDaily", Map.of("days", Integer.toString(trackDays())));
        }
    }

    private void showStreak(CommandSender sender, UUID id, String displayName) {
        msg(sender, "&6" + displayName + " &7daily status:");
        msg(sender, "&7Current streak: &f" + daily.streak(id)
                + " &7| Best: &f" + daily.bestStreak(id));
        msg(sender, "&7Total claims: &f" + daily.totalClaims(id)
                + " &7| Protections: &f" + daily.protections(id));
        msg(sender, "&7Next reward day: &f" + daily.nextRewardDay(id)
                + " &7| Can claim: " + (daily.canClaim(id) ? "&aYes" : "&eNo"));
    }

    private void adminHelp(CommandSender sender) {
        msg(sender, "&e/mdaily protection <player> <amount>");
        msg(sender, "&e/mdaily reset <player>");
        msg(sender, "&e/mdaily status <player>");
        msg(sender, "&e/mdaily reload");
    }

    private void runReward(Player player, int day) {
        List<String> commands = getConfig().getStringList("rewards." + day + ".commands");
        if (commands.isEmpty()) commands = defaultReward(day);
        for (String raw : commands) {
            if (raw == null || raw.isBlank()) continue;
            String command = raw.trim();
            if (command.startsWith("/")) command = command.substring(1);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    command.replace("%player%", player.getName()).replace("%day%", Integer.toString(day)));
        }
    }

    private List<String> defaultReward(int day) {
        if (day % 30 == 0) return List.of("give %player% diamond 8");
        if (day % 7 == 0) return List.of("give %player% diamond 3");
        return List.of("give %player% experience_bottle " + Math.min(32, 4 + day));
    }

    private Material configuredIcon(int day, Material fallback) {
        String raw = getConfig().getString("rewards." + day + ".icon", "");
        Material material = raw == null || raw.isBlank() ? null : Material.matchMaterial(raw);
        return material == null || material.isAir() ? fallback : material;
    }

    private int trackDays() {
        return Math.max(1, Math.min(45, getConfig().getInt("track-days", 30)));
    }

    private void connectLeaderboards() {
        if (!getConfig().getBoolean("leaderboards.enabled", true)
                || !Bukkit.getPluginManager().isPluginEnabled("MiraLeaderboards")) {
            leaderboards = null;
            return;
        }
        RegisteredServiceProvider<MiraLeaderboardsApi> registration =
                Bukkit.getServicesManager().getRegistration(MiraLeaderboardsApi.class);
        leaderboards = registration == null ? null : registration.getProvider();
        if (leaderboards != null) {
            leaderboards.configure(streakBoard(), BoardScope.ALL_TIME, "");
            leaderboards.configure(bestStreakBoard(), BoardScope.ALL_TIME, "");
            leaderboards.configure(totalClaimsBoard(), BoardScope.ALL_TIME, "");
        }
    }

    private void syncAllLeaderboards() {
        if (leaderboards == null) return;
        for (UUID id : daily.playerIds()) publish(id);
    }

    private void publish(UUID id) {
        if (leaderboards == null || id == null) return;
        String displayName = daily.name(id);
        leaderboards.publish("miradaily", streakBoard(), id.toString(), displayName, daily.streak(id));
        leaderboards.publish("miradaily", bestStreakBoard(), id.toString(), displayName, daily.bestStreak(id));
        leaderboards.publish("miradaily", totalClaimsBoard(), id.toString(), displayName, daily.totalClaims(id));
    }

    private String streakBoard() {
        return getConfig().getString("leaderboards.streak-board", "daily_streak");
    }

    private String bestStreakBoard() {
        return getConfig().getString("leaderboards.best-streak-board", "daily_best_streak");
    }

    private String totalClaimsBoard() {
        return getConfig().getString("leaderboards.total-claims-board", "daily_total_claims");
    }

    private OfflinePlayer resolve(String raw) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(raw);
        if (player.getName() == null && !player.hasPlayedBefore() && !player.isOnline()) return null;
        return player;
    }

    private String name(OfflinePlayer player) {
        return player.getName() == null ? player.getUniqueId().toString() : player.getName();
    }

    private void audit(CommandSender sender, String action, String target, Map<String, String> metadata) {
        core.audit().record("MiraDaily", action,
                sender instanceof Player player ? player.getUniqueId() : null,
                sender.getName(), target, action.replace('_', ' ').toLowerCase(Locale.ROOT), metadata);
    }

    private ZoneId parseZone(String raw) {
        try {
            return ZoneId.of(raw == null || raw.isBlank() ? "UTC" : raw.trim());
        } catch (DateTimeException exception) {
            getLogger().warning("Invalid MiraDaily timezone '" + raw + "'. Falling back to UTC.");
            return ZoneOffset.UTC;
        }
    }

    private long today() {
        return LocalDate.now(zone).toEpochDay();
    }

    private long nextResetAtInternal() {
        LocalDate tomorrow = LocalDate.now(zone).plusDays(1);
        return tomorrow.atStartOfDay(zone).toInstant().toEpochMilli();
    }

    private void msg(CommandSender sender, String raw) {
        core.messages().send(sender, raw);
    }

    static String c(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }

    static String formatDuration(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m";
    }

    private static List<String> complete(String prefix, Collection<String> values) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower))
                .distinct().sorted().toList();
    }

    private static final class DailyHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public @NotNull Inventory getInventory() {
            return Objects.requireNonNull(inventory, "Daily inventory not initialized");
        }
    }

    public record ClaimResult(int rewardDay, int streak, boolean protectionUsed, boolean protectionEarned) {}

    public interface MiraDailyApi {
        boolean canClaim(UUID player);
        int streak(UUID player);
        int bestStreak(UUID player);
        int totalClaims(UUID player);
        int protections(UUID player);
        int trackDay(UUID player);
        int nextRewardDay(UUID player);
        long lastClaimEpochDay(UUID player);
        long nextResetAt();
        ZoneId timezone();
        void addProtections(UUID player, int amount);
    }

    final class DailyService implements MiraDailyApi {
        private final MiraDailyPlugin plugin;
        private final File file;
        private YamlConfiguration data;

        DailyService(MiraDailyPlugin plugin) {
            this.plugin = plugin;
            this.file = new File(plugin.getDataFolder(), "daily.yml");
            this.data = YamlConfiguration.loadConfiguration(file);
            pruneHistories();
        }

        private String root(UUID uuid) {
            return "players." + uuid;
        }

        synchronized void rememberName(OfflinePlayer player) {
            if (player.getName() != null) data.set(root(player.getUniqueId()) + ".name", player.getName());
        }

        synchronized String name(UUID uuid) {
            return data.getString(root(uuid) + ".name",
                    Optional.ofNullable(Bukkit.getOfflinePlayer(uuid).getName()).orElse(uuid.toString()));
        }

        synchronized Set<UUID> playerIds() {
            ConfigurationSection section = data.getConfigurationSection("players");
            if (section == null) return Set.of();
            Set<UUID> ids = new LinkedHashSet<>();
            for (String raw : section.getKeys(false)) {
                try { ids.add(UUID.fromString(raw)); }
                catch (IllegalArgumentException ignored) {}
            }
            return ids;
        }

        @Override
        public synchronized boolean canClaim(UUID uuid) {
            return lastClaimEpochDay(uuid) < today();
        }

        @Override
        public synchronized int streak(UUID uuid) {
            return Math.max(0, data.getInt(root(uuid) + ".streak"));
        }

        @Override
        public synchronized int bestStreak(UUID uuid) {
            return Math.max(streak(uuid), data.getInt(root(uuid) + ".best-streak"));
        }

        @Override
        public synchronized int totalClaims(UUID uuid) {
            return Math.max(0, data.getInt(root(uuid) + ".total-claims"));
        }

        @Override
        public synchronized int protections(UUID uuid) {
            String path = root(uuid) + ".protections";
            if (!data.contains(path)) {
                data.set(path, Math.max(0, getConfig().getInt("starting-streak-protections", 0)));
            }
            return Math.max(0, data.getInt(path));
        }

        @Override
        public synchronized int trackDay(UUID uuid) {
            return Math.max(0, data.getInt(root(uuid) + ".track-day"));
        }

        @Override
        public synchronized int nextRewardDay(UUID uuid) {
            int max = trackDays();
            long last = lastClaimEpochDay(uuid);
            int track = trackDay(uuid);
            if (last == Long.MIN_VALUE) return 1;

            long gap = today() - last;
            if (gap <= 1L) return track >= max ? 1 : track + 1;

            int needed = (int) Math.max(0L, gap - 1L);
            if (protections(uuid) >= needed) return track >= max ? 1 : track + 1;
            return 1;
        }

        @Override
        public synchronized long lastClaimEpochDay(UUID uuid) {
            return data.getLong(root(uuid) + ".last-claim", Long.MIN_VALUE);
        }

        @Override public long nextResetAt() { return nextResetAtInternal(); }
        @Override public ZoneId timezone() { return zone; }

        synchronized ClaimResult claim(Player player) {
            UUID uuid = player.getUniqueId();
            if (!canClaim(uuid)) throw new IllegalStateException("Already claimed");

            String root = root(uuid);
            long last = lastClaimEpochDay(uuid);
            long gap = last == Long.MIN_VALUE ? 0L : today() - last;
            int rewardDay = nextRewardDay(uuid);
            boolean protectionUsed = false;

            int newStreak;
            if (last == Long.MIN_VALUE) {
                newStreak = 1;
            } else if (gap == 1L) {
                newStreak = Math.max(1, streak(uuid) + 1);
            } else {
                int needed = (int) Math.max(0L, gap - 1L);
                if (needed > 0 && protections(uuid) >= needed) {
                    data.set(root + ".protections", protections(uuid) - needed);
                    newStreak = Math.max(1, streak(uuid) + 1);
                    protectionUsed = true;
                } else {
                    newStreak = 1;
                }
            }

            int newTotalClaims = totalClaims(uuid) + 1;
            int newBest = Math.max(bestStreak(uuid), newStreak);

            data.set(root + ".name", player.getName());
            data.set(root + ".last-claim", today());
            data.set(root + ".streak", newStreak);
            data.set(root + ".best-streak", newBest);
            data.set(root + ".total-claims", newTotalClaims);
            data.set(root + ".track-day", rewardDay);

            boolean protectionEarned = false;
            int awardEvery = Math.max(0, getConfig().getInt("streak-protection.award-every", 7));
            if (awardEvery > 0 && newStreak > 0 && newStreak % awardEvery == 0) {
                data.set(root + ".protections", protections(uuid) + 1);
                protectionEarned = true;
            }

            String history = root + ".history." + today();
            data.set(history + ".reward-day", rewardDay);
            data.set(history + ".streak", newStreak);
            data.set(history + ".protection-used", protectionUsed);
            data.set(history + ".claimed-at", System.currentTimeMillis());
            pruneHistory(uuid);

            save();
            runReward(player, rewardDay);
            return new ClaimResult(rewardDay, newStreak, protectionUsed, protectionEarned);
        }

        @Override
        public synchronized void addProtections(UUID uuid, int amount) {
            data.set(root(uuid) + ".protections", Math.max(0, protections(uuid) + amount));
            save();
        }

        synchronized void reset(UUID uuid) {
            data.set(root(uuid), null);
            save();
        }

        private synchronized void pruneHistories() {
            for (UUID id : playerIds()) pruneHistory(id);
        }

        private synchronized void pruneHistory(UUID uuid) {
            ConfigurationSection history = data.getConfigurationSection(root(uuid) + ".history");
            if (history == null) return;
            int keep = Math.max(1, getConfig().getInt("claim-history-limit", 90));
            List<Long> days = history.getKeys(false).stream().map(key -> {
                try { return Long.parseLong(key); }
                catch (NumberFormatException ignored) { return Long.MIN_VALUE; }
            }).filter(day -> day != Long.MIN_VALUE).sorted().toList();

            int remove = Math.max(0, days.size() - keep);
            for (int i = 0; i < remove; i++) {
                data.set(root(uuid) + ".history." + days.get(i), null);
            }
        }

        synchronized void save() {
            try {
                data.save(file);
            } catch (IOException exception) {
                plugin.getLogger().severe("Failed to save daily.yml: " + exception.getMessage());
            }
        }
    }

    static final class DailyPlaceholders extends PlaceholderExpansion {
        private final MiraDailyPlugin plugin;

        DailyPlaceholders(MiraDailyPlugin plugin) {
            this.plugin = plugin;
        }

        @Override public @NotNull String getIdentifier() { return "miradaily"; }
        @Override public @NotNull String getAuthor() { return "FiveS"; }
        @Override public @NotNull String getVersion() { return plugin.getPluginMeta().getVersion(); }
        @Override public boolean persist() { return true; }

        @Override
        public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
            if (player == null) return null;
            UUID id = player.getUniqueId();
            return switch (params.toLowerCase(Locale.ROOT)) {
                case "streak" -> Integer.toString(plugin.daily.streak(id));
                case "best_streak" -> Integer.toString(plugin.daily.bestStreak(id));
                case "total_claims" -> Integer.toString(plugin.daily.totalClaims(id));
                case "protections" -> Integer.toString(plugin.daily.protections(id));
                case "track_day" -> Integer.toString(plugin.daily.trackDay(id));
                case "next_day" -> Integer.toString(plugin.daily.nextRewardDay(id));
                case "can_claim" -> Boolean.toString(plugin.daily.canClaim(id));
                case "last_claim_epoch_day" -> Long.toString(plugin.daily.lastClaimEpochDay(id));
                case "next_reset_seconds" -> Long.toString(Math.max(0L,
                        (plugin.daily.nextResetAt() - System.currentTimeMillis()) / 1000L));
                default -> null;
            };
        }
    }
}
