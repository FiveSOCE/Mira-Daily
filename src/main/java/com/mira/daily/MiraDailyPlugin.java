package com.mira.daily;

import com.mira.core.api.MiraCore;
import com.mira.core.api.MiraCoreProvider;
import com.mira.core.api.ModuleHealth;
import com.mira.daily.api.event.DailyRewardClaimEvent;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

public final class MiraDailyPlugin extends JavaPlugin implements Listener, TabExecutor {
    private static final String PREFIX = "&5&lMira &8>> &r";

    private MiraCore core;
    private DailyService daily;
    private String guiTitle;
    private ZoneId zoneId;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        core = MiraCoreProvider.require();
        refreshRuntimeSettings();
        daily = new DailyService(this);

        getServer().getServicesManager().register(MiraDailyApi.class, daily, this, ServicePriority.Normal);
        core.services().register(MiraDailyApi.class, daily);
        core.modules().register(this, "MiraDaily");
        core.modules().setHealth(this, ModuleHealth.HEALTHY,
                "Daily rewards, streak protection, timezone resets and milestones ready");

        getServer().getPluginManager().registerEvents(this, this);
        var dailyCommand = Objects.requireNonNull(getCommand("daily"), "daily command missing");
        dailyCommand.setExecutor(this);
        dailyCommand.setTabCompleter(this);
        var adminCommand = Objects.requireNonNull(getCommand("mdaily"), "mdaily command missing");
        adminCommand.setExecutor(this);
        adminCommand.setTabCompleter(this);

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new DailyPlaceholders(this).register();
        }
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

    private void refreshRuntimeSettings() {
        guiTitle = c(getConfig().getString("calendar-title", "&6Daily Rewards"));
        String configured = getConfig().getString("timezone", "Australia/Brisbane");
        try {
            zoneId = ZoneId.of(configured == null ? "Australia/Brisbane" : configured.trim());
        } catch (Exception ex) {
            zoneId = ZoneId.of("Australia/Brisbane");
            getLogger().warning("Invalid MiraDaily timezone '" + configured + "'. Using Australia/Brisbane.");
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("daily")) {
            if (!(sender instanceof Player player)) {
                msg(sender, "&cThis command must be run by a player.");
                return true;
            }
            if (!sender.hasPermission("miradaily.use")) {
                msg(sender, "&cYou do not have permission.");
                return true;
            }

            String action = args.length == 0 ? "open" : args[0].toLowerCase(Locale.ROOT);
            switch (action) {
                case "open", "gui" -> openCalendar(player);
                case "claim" -> claimAndRespond(player);
                case "streak" -> msg(player, getConfig().getString("messages.streak",
                                "&7Current daily streak: &f%streak% &8| &7Protections: &f%protections%")
                        .replace("%streak%", Integer.toString(daily.streak(player.getUniqueId())))
                        .replace("%protections%", Integer.toString(daily.protections(player.getUniqueId()))));
                default -> msg(player, "&eUsage: /daily [claim|streak]");
            }
            return true;
        }

        if (!sender.hasPermission("miradaily.admin")) {
            msg(sender, "&cYou do not have permission.");
            return true;
        }
        if (args.length == 0) {
            msg(sender, "&e/mdaily protection <player> <amount>");
            msg(sender, "&e/mdaily reset <player>");
            msg(sender, "&e/mdaily reload");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "protection" -> {
                if (args.length < 3) {
                    msg(sender, "&eUsage: /mdaily protection <player> <amount>");
                    return true;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                int amount;
                try {
                    amount = Integer.parseInt(args[2]);
                } catch (NumberFormatException ex) {
                    msg(sender, "&cAmount must be a whole number.");
                    return true;
                }
                daily.addProtections(target.getUniqueId(), amount);
                audit(sender, "STREAK_PROTECTION_CHANGED", target.getUniqueId().toString(),
                        Map.of("amount", Integer.toString(amount)));
                msg(sender, "&aUpdated streak protections for &f" + args[1] + "&a.");
            }
            case "reset" -> {
                if (args.length < 2) {
                    msg(sender, "&eUsage: /mdaily reset <player>");
                    return true;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                daily.reset(target.getUniqueId());
                audit(sender, "DAILY_STATE_RESET", target.getUniqueId().toString(), Map.of());
                msg(sender, "&aDaily state reset for &f" + name(target) + "&a.");
            }
            case "reload" -> {
                reloadConfig();
                refreshRuntimeSettings();
                audit(sender, "CONFIG_RELOADED", "MiraDaily", Map.of("timezone", zoneId.getId()));
                msg(sender, "&aMiraDaily reloaded.");
            }
            default -> msg(sender, "&cUnknown subcommand.");
        }
        return true;
    }

    private void openCalendar(Player player) {
        int trackDays = Math.max(1, Math.min(45, getConfig().getInt("track-days", 30)));
        Inventory inv = Bukkit.createInventory(null, 54, guiTitle);
        int current = daily.nextRewardDay(player.getUniqueId());
        int claimedThrough = daily.trackDay(player.getUniqueId());
        boolean canClaim = daily.canClaim(player.getUniqueId());

        for (int day = 1; day <= trackDays; day++) {
            Material mat;
            String status;
            if (day <= claimedThrough && !(canClaim && day == current)) {
                mat = Material.LIME_STAINED_GLASS_PANE;
                status = "&aClaimed";
            } else if (day == current && canClaim) {
                mat = Material.CHEST;
                status = "&eClick to claim";
            } else {
                mat = Material.GRAY_STAINED_GLASS_PANE;
                status = "&7Locked";
            }

            ItemStack item = new ItemStack(mat);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(c("&6Day " + day));
            meta.setLore(List.of(c(status), c("&7Reward commands are configurable.")));
            item.setItemMeta(meta);
            inv.setItem(day - 1, item);
        }

        ItemStack info = new ItemStack(Material.CLOCK);
        ItemMeta meta = info.getItemMeta();
        meta.setDisplayName(c("&6Your Daily Streak"));
        meta.setLore(List.of(
                c("&7Streak: &f" + daily.streak(player.getUniqueId())),
                c("&7Protections: &f" + daily.protections(player.getUniqueId())),
                c("&7Reset timezone: &f" + zoneId.getId())
        ));
        info.setItemMeta(meta);
        inv.setItem(49, info);
        player.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals(guiTitle)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 45) return;
        int day = slot + 1;
        if (!daily.canClaim(player.getUniqueId()) || day != daily.nextRewardDay(player.getUniqueId())) return;
        claimAndRespond(player);
    }

    private void claimAndRespond(Player player) {
        if (!daily.canClaim(player.getUniqueId())) {
            msg(player, getConfig().getString("messages.already-claimed",
                    "&eYou have already claimed today's reward."));
            return;
        }

        ClaimResult result = daily.claim(player);
        msg(player, getConfig().getString("messages.claimed", "&aDaily reward claimed! &7Streak: &f%streak%")
                .replace("%streak%", Integer.toString(result.streak())));
        if (result.protectionUsed()) {
            msg(player, getConfig().getString("messages.protection-used",
                    "&eA streak protection was used to preserve your streak."));
        }

        getServer().getPluginManager().callEvent(
                new DailyRewardClaimEvent(player, result.rewardDay(), result.streak(), result.protectionUsed()));
        core.audit().record("MiraDaily", "DAILY_REWARD_CLAIMED", player.getUniqueId(), player.getName(),
                Integer.toString(result.rewardDay()), "Claimed daily reward",
                Map.of("streak", Integer.toString(result.streak()),
                        "protectionUsed", Boolean.toString(result.protectionUsed())));
        awardStreakMilestones(player, result.streak());
        player.closeInventory();
    }

    private void awardStreakMilestones(Player player, int streak) {
        if (!getConfig().getBoolean("milestones.enabled", true)) return;
        for (Integer target : getConfig().getIntegerList("milestones.streaks")) {
            if (target == null || target <= 0 || streak < target) continue;
            core.milestones().award(player.getUniqueId(), "miradaily.streak_" + target,
                    "MiraDaily", Map.of("streak", Integer.toString(streak)));
        }
    }

    private void runReward(Player player, int day) {
        List<String> commands = getConfig().getStringList("rewards." + day + ".commands");
        if (commands.isEmpty()) commands = defaultReward(day);
        for (String raw : commands) {
            if (raw == null || raw.isBlank()) continue;
            String clean = raw.startsWith("/") ? raw.substring(1) : raw;
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), clean
                    .replace("%player%", player.getName())
                    .replace("%day%", Integer.toString(day)));
        }
    }

    private List<String> defaultReward(int day) {
        if (day % 30 == 0) return List.of("give %player% diamond 8");
        if (day % 7 == 0) return List.of("give %player% diamond 3");
        return List.of("give %player% experience_bottle " + Math.min(32, 4 + day));
    }

    private long today() {
        return LocalDate.now(zoneId).toEpochDay();
    }

    private long secondsUntilReset() {
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        ZonedDateTime next = now.toLocalDate().plusDays(1).atStartOfDay(zoneId);
        return Math.max(0L, Duration.between(now, next).getSeconds());
    }

    private void audit(CommandSender sender, String action, String target, Map<String, String> metadata) {
        UUID actor = sender instanceof Player player ? player.getUniqueId() : null;
        core.audit().record("MiraDaily", action, actor, sender.getName(), target, action, metadata);
    }

    private String name(OfflinePlayer player) {
        return player.getName() == null ? player.getUniqueId().toString() : player.getName();
    }

    private void msg(CommandSender sender, String raw) {
        sender.sendMessage(c(getConfig().getString("messages.prefix", PREFIX) + (raw == null ? "" : raw)));
    }

    static String c(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("daily")) {
            if (args.length == 1) return match(args[0], List.of("claim", "streak"));
            return List.of();
        }
        if (!sender.hasPermission("miradaily.admin")) return List.of();
        if (args.length == 1) return match(args[0], List.of("protection", "reset", "reload"));
        if (args.length == 2 && (args[0].equalsIgnoreCase("protection") || args[0].equalsIgnoreCase("reset"))) {
            return match(args[1], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        }
        return List.of();
    }

    private static List<String> match(String prefix, Collection<String> values) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).sorted().toList();
    }

    public record ClaimResult(int rewardDay, int streak, boolean protectionUsed) {}

    public interface MiraDailyApi {
        boolean canClaim(UUID player);
        int streak(UUID player);
        int protections(UUID player);
        int trackDay(UUID player);
        int nextRewardDay(UUID player);
        long secondsUntilReset();
        String timezone();
        void addProtections(UUID player, int amount);
    }

    final class DailyService implements MiraDailyApi {
        private final MiraDailyPlugin plugin;
        private final File file;
        private YamlConfiguration data;

        DailyService(MiraDailyPlugin plugin) {
            this.plugin = plugin;
            file = new File(plugin.getDataFolder(), "daily.yml");
            data = YamlConfiguration.loadConfiguration(file);
        }

        private String root(UUID uuid) { return "players." + uuid; }

        @Override
        public synchronized boolean canClaim(UUID uuid) {
            return data.getLong(root(uuid) + ".last-claim", Long.MIN_VALUE) != today();
        }

        @Override public synchronized int streak(UUID uuid) { return data.getInt(root(uuid) + ".streak"); }

        @Override
        public synchronized int protections(UUID uuid) {
            String path = root(uuid) + ".protections";
            if (!data.contains(path)) data.set(path, getConfig().getInt("starting-streak-protections", 0));
            return data.getInt(path);
        }

        @Override public synchronized int trackDay(UUID uuid) { return data.getInt(root(uuid) + ".track-day"); }

        @Override
        public synchronized int nextRewardDay(UUID uuid) {
            int max = Math.max(1, getConfig().getInt("track-days", 30));
            long last = data.getLong(root(uuid) + ".last-claim", Long.MIN_VALUE);
            int track = trackDay(uuid);
            if (last == Long.MIN_VALUE) return 1;
            long gap = today() - last;
            if (gap <= 1) return track >= max ? 1 : track + 1;
            int needed = (int) Math.max(0, gap - 1);
            if (protections(uuid) >= needed) return track >= max ? 1 : track + 1;
            return 1;
        }

        synchronized ClaimResult claim(Player player) {
            UUID uuid = player.getUniqueId();
            if (!canClaim(uuid)) throw new IllegalStateException("Already claimed");
            String root = root(uuid);
            long last = data.getLong(root + ".last-claim", Long.MIN_VALUE);
            long gap = last == Long.MIN_VALUE ? 0 : today() - last;
            boolean protectionUsed = false;
            int newStreak;
            int rewardDay = nextRewardDay(uuid);

            if (last == Long.MIN_VALUE) {
                newStreak = 1;
            } else if (gap == 1) {
                newStreak = Math.max(1, data.getInt(root + ".streak") + 1);
            } else {
                int needed = (int) Math.max(0, gap - 1);
                if (needed > 0 && protections(uuid) >= needed) {
                    data.set(root + ".protections", protections(uuid) - needed);
                    newStreak = Math.max(1, data.getInt(root + ".streak") + 1);
                    protectionUsed = true;
                } else {
                    newStreak = 1;
                }
            }

            data.set(root + ".name", player.getName());
            data.set(root + ".last-claim", today());
            data.set(root + ".streak", newStreak);
            data.set(root + ".track-day", rewardDay);
            save();
            runReward(player, rewardDay);
            return new ClaimResult(rewardDay, newStreak, protectionUsed);
        }

        @Override
        public synchronized void addProtections(UUID uuid, int amount) {
            data.set(root(uuid) + ".protections", Math.max(0, protections(uuid) + amount));
            save();
        }

        @Override public long secondsUntilReset() { return MiraDailyPlugin.this.secondsUntilReset(); }
        @Override public String timezone() { return zoneId.getId(); }

        synchronized void reset(UUID uuid) {
            data.set(root(uuid), null);
            save();
        }

        synchronized void save() {
            try {
                data.save(file);
            } catch (IOException ex) {
                plugin.getLogger().severe("Failed to save daily.yml: " + ex.getMessage());
            }
        }
    }

    static final class DailyPlaceholders extends PlaceholderExpansion {
        private final MiraDailyPlugin plugin;

        DailyPlaceholders(MiraDailyPlugin plugin) { this.plugin = plugin; }

        @Override public @NotNull String getIdentifier() { return "miradaily"; }
        @Override public @NotNull String getAuthor() { return "FiveS"; }
        @Override public @NotNull String getVersion() { return plugin.getPluginMeta().getVersion(); }
        @Override public boolean persist() { return true; }

        @Override
        public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
            String key = params.toLowerCase(Locale.ROOT);
            if (key.equals("reset_seconds")) return Long.toString(plugin.daily.secondsUntilReset());
            if (key.equals("timezone")) return plugin.daily.timezone();
            if (player == null) return null;
            UUID id = player.getUniqueId();
            return switch (key) {
                case "streak" -> Integer.toString(plugin.daily.streak(id));
                case "protections" -> Integer.toString(plugin.daily.protections(id));
                case "track_day" -> Integer.toString(plugin.daily.trackDay(id));
                case "next_day" -> Integer.toString(plugin.daily.nextRewardDay(id));
                case "can_claim" -> Boolean.toString(plugin.daily.canClaim(id));
                default -> null;
            };
        }
    }
}
