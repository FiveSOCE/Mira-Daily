package com.mira.daily;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
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
import java.time.LocalDate;
import java.util.*;

public final class MiraDailyPlugin extends JavaPlugin implements Listener {
    private static final String PREFIX = "&5&lMira &8>> &r";
    private DailyService daily;
    private String guiTitle;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        guiTitle = c(getConfig().getString("calendar-title", "&6Daily Rewards"));
        daily = new DailyService(this);
        getServer().getServicesManager().register(MiraDailyApi.class, daily, this, ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("daily")).setExecutor(this);
        Objects.requireNonNull(getCommand("mdaily")).setExecutor(this);
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) new DailyPlaceholders(this).register();
    }

    @Override public void onDisable() {
        if (daily != null) daily.save();
        getServer().getServicesManager().unregisterAll(this);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("daily")) {
            if (!(sender instanceof Player player)) return true;
            openCalendar(player);
            return true;
        }
        if (!sender.hasPermission("miradaily.admin")) { msg(sender, "&cYou do not have permission."); return true; }
        if (args.length == 0) {
            msg(sender, "&e/mdaily protection <player> <amount>");
            msg(sender, "&e/mdaily reset <player>");
            msg(sender, "&e/mdaily reload");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "protection" -> {
                if (args.length < 3) return false;
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                int amount;
                try { amount = Integer.parseInt(args[2]); } catch (NumberFormatException e) { return false; }
                daily.addProtections(target.getUniqueId(), amount);
                msg(sender, "&aUpdated streak protections for &f" + args[1] + "&a.");
            }
            case "reset" -> {
                if (args.length < 2) return false;
                daily.reset(Bukkit.getOfflinePlayer(args[1]).getUniqueId());
                msg(sender, "&aDaily state reset.");
            }
            case "reload" -> {
                reloadConfig();
                guiTitle = c(getConfig().getString("calendar-title", "&6Daily Rewards"));
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
            if (day <= claimedThrough && !(canClaim && day == current)) { mat = Material.LIME_STAINED_GLASS_PANE; status = "&aClaimed"; }
            else if (day == current && canClaim) { mat = Material.CHEST; status = "&eClick to claim"; }
            else { mat = Material.GRAY_STAINED_GLASS_PANE; status = "&7Locked"; }
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
        meta.setLore(List.of(c("&7Streak: &f" + daily.streak(player.getUniqueId())), c("&7Protections: &f" + daily.protections(player.getUniqueId()))));
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
        ClaimResult result = daily.claim(player);
        msg(player, getConfig().getString("messages.claimed", "&aDaily reward claimed! &7Streak: &f%streak%").replace("%streak%", Integer.toString(result.streak())));
        if (result.protectionUsed()) msg(player, getConfig().getString("messages.protection-used", "&eA streak protection was used."));
        player.closeInventory();
    }

    private void runReward(Player player, int day) {
        List<String> commands = getConfig().getStringList("rewards." + day + ".commands");
        if (commands.isEmpty()) commands = defaultReward(day);
        for (String raw : commands) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), raw.replace("%player%", player.getName()).replace("%day%", Integer.toString(day)));
    }

    private List<String> defaultReward(int day) {
        if (day % 30 == 0) return List.of("give %player% diamond 8");
        if (day % 7 == 0) return List.of("give %player% diamond 3");
        return List.of("give %player% experience_bottle " + Math.min(32, 4 + day));
    }

    private void msg(CommandSender sender, String raw) { sender.sendMessage(c(getConfig().getString("messages.prefix", PREFIX) + raw)); }
    static String c(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
    static long today() { return LocalDate.now().toEpochDay(); }

    public record ClaimResult(int rewardDay, int streak, boolean protectionUsed) {}
    public interface MiraDailyApi {
        boolean canClaim(UUID player);
        int streak(UUID player);
        int protections(UUID player);
        int trackDay(UUID player);
        int nextRewardDay(UUID player);
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
        @Override public synchronized boolean canClaim(UUID uuid) { return data.getLong(root(uuid) + ".last-claim", Long.MIN_VALUE) != today(); }
        @Override public synchronized int streak(UUID uuid) { return data.getInt(root(uuid) + ".streak"); }
        @Override public synchronized int protections(UUID uuid) {
            String p = root(uuid) + ".protections";
            if (!data.contains(p)) data.set(p, getConfig().getInt("starting-streak-protections", 0));
            return data.getInt(p);
        }
        @Override public synchronized int trackDay(UUID uuid) { return data.getInt(root(uuid) + ".track-day"); }
        @Override public synchronized int nextRewardDay(UUID uuid) {
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
            String r = root(uuid);
            long last = data.getLong(r + ".last-claim", Long.MIN_VALUE);
            long gap = last == Long.MIN_VALUE ? 0 : today() - last;
            boolean protectionUsed = false;
            int newStreak;
            int rewardDay = nextRewardDay(uuid);
            if (last == Long.MIN_VALUE) newStreak = 1;
            else if (gap == 1) newStreak = Math.max(1, data.getInt(r + ".streak") + 1);
            else {
                int needed = (int) Math.max(0, gap - 1);
                if (needed > 0 && protections(uuid) >= needed) {
                    data.set(r + ".protections", protections(uuid) - needed);
                    newStreak = Math.max(1, data.getInt(r + ".streak") + 1);
                    protectionUsed = true;
                } else newStreak = 1;
            }
            data.set(r + ".name", player.getName());
            data.set(r + ".last-claim", today());
            data.set(r + ".streak", newStreak);
            data.set(r + ".track-day", rewardDay);
            save();
            runReward(player, rewardDay);
            return new ClaimResult(rewardDay, newStreak, protectionUsed);
        }
        @Override public synchronized void addProtections(UUID uuid, int amount) { data.set(root(uuid) + ".protections", Math.max(0, protections(uuid) + amount)); save(); }
        synchronized void reset(UUID uuid) { data.set(root(uuid), null); save(); }
        synchronized void save() { try { data.save(file); } catch (IOException e) { plugin.getLogger().severe("Failed to save daily.yml: " + e.getMessage()); } }
    }

    static final class DailyPlaceholders extends PlaceholderExpansion {
        private final MiraDailyPlugin plugin;
        DailyPlaceholders(MiraDailyPlugin plugin) { this.plugin = plugin; }
        @Override public @NotNull String getIdentifier() { return "miradaily"; }
        @Override public @NotNull String getAuthor() { return "FiveS"; }
        @Override public @NotNull String getVersion() { return plugin.getDescription().getVersion(); }
        @Override public boolean persist() { return true; }
        @Override public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
            if (player == null) return null;
            UUID id = player.getUniqueId();
            return switch (params.toLowerCase(Locale.ROOT)) {
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
