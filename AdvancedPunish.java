package com.seuprojeto.advancedpunish;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class AdvancedPunish extends JavaPlugin implements Listener {
    private final Map<UUID, Punishment> bans = new ConcurrentHashMap<>();
    private final Map<UUID, Punishment> mutes = new ConcurrentHashMap<>();
    private File dataFile;
    private YamlConfiguration data;
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacyAmpersand();

    record Punishment(String reason, String staff, long created, long expires, String id) {
        boolean permanent() { return expires <= 0; }
        boolean active() { return permanent() || expires > System.currentTimeMillis(); }
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "punishments.yml");
        loadData();
        getServer().getPluginManager().registerEvents(this, this);
        for (String cmd : List.of("ban","tempban","unban","mute","tempmute","unmute","punishreload")) {
            PluginCommand pc = getCommand(cmd);
            if (pc != null) pc.setExecutor(this::execute);
        }
        getLogger().warning("AdvancedPunish base initialized. This is not production-complete.");
    }

    @Override public void onDisable() { saveData(); }

    private void loadData() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        data = YamlConfiguration.loadConfiguration(dataFile);
        loadMap("bans", bans); loadMap("mutes", mutes);
    }

    private void loadMap(String section, Map<UUID, Punishment> map) {
        if (data.getConfigurationSection(section) == null) return;
        for (String key : data.getConfigurationSection(section).getKeys(false)) {
            try {
                String p = section + "." + key + ".";
                map.put(UUID.fromString(key), new Punishment(
                    data.getString(p+"reason","N/A"), data.getString(p+"staff","Console"),
                    data.getLong(p+"created"), data.getLong(p+"expires"), data.getString(p+"id","0")));
            } catch (Exception ex) { getLogger().warning("Ignoring invalid record: " + key); }
        }
    }

    private void saveData() {
        data = new YamlConfiguration();
        saveMap("bans", bans); saveMap("mutes", mutes);
        try { data.save(dataFile); } catch (IOException e) { getLogger().severe("Could not save punishment data: " + e.getMessage()); }
    }

    private void saveMap(String section, Map<UUID, Punishment> map) {
        map.forEach((uuid, p) -> {
            String path = section + "." + uuid + ".";
            data.set(path+"reason", p.reason()); data.set(path+"staff", p.staff());
            data.set(path+"created", p.created()); data.set(path+"expires", p.expires());
            data.set(path+"id", p.id());
        });
    }

    private boolean execute(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("punishreload")) {
            if (!sender.hasPermission("advancedpunish.reload")) return fail(sender, "no-permission");
            reloadConfig(); sender.sendMessage(msg("Configuração recarregada.")); return true;
        }
        String perm = "advancedpunish." + name;
        if (!sender.hasPermission(perm) && !sender.hasPermission("advancedpunish.admin")) return fail(sender, "no-permission");

        if (name.equals("unban") || name.equals("unmute")) {
            if (args.length < 1) return usage(sender);
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
            Map<UUID, Punishment> map = name.equals("unban") ? bans : mutes;
            if (map.remove(target.getUniqueId()) == null) return fail(sender, "no-active-punishment");
            saveData();
            sender.sendMessage(msg(name.equals("unban") ? "Ban removido." : "Mute removido."));
            return true;
        }

        boolean temp = name.equals("tempban") || name.equals("tempmute");
        boolean ban = name.equals("ban") || name.equals("tempban");
        if ((!temp && args.length < 2) || (temp && args.length < 3)) return usage(sender);

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        if (!target.hasPlayedBefore() && !target.isOnline()) return fail(sender, "player-not-found");
        long expiry = 0;
        int reasonStart = 1;
        if (temp) {
            try { expiry = System.currentTimeMillis() + parseDuration(args[1]); }
            catch (IllegalArgumentException ex) { sender.sendMessage(msg("Tempo inválido. Exemplos: 30m, 2h, 7d.")); return true; }
            reasonStart = 2;
        }
        String reason = String.join(" ", Arrays.copyOfRange(args, reasonStart, args.length));
        String staff = sender instanceof Player p ? p.getName() : "Console";
        Punishment punishment = new Punishment(reason, staff, System.currentTimeMillis(), expiry,
                UUID.randomUUID().toString().substring(0, 8));
        Map<UUID, Punishment> map = ban ? bans : mutes;
        map.put(target.getUniqueId(), punishment); saveData();

        if (ban && target.isOnline()) ((Player) target).kick(banComponent(punishment));
        sender.sendMessage(msg("Punição aplicada a " + args[0] + ". Motivo: " + reason));
        return true;
    }

    private Component banComponent(Punishment p) {
        List<String> lines = getConfig().getStringList("ban-message");
        String duration = p.permanent() ? "Permanente" : human(p.expires()-System.currentTimeMillis());
        String joined = String.join("\n", lines).replace("{reason}", p.reason()).replace("{staff}", p.staff())
                .replace("{duration}", duration).replace("{id}", p.id()).replace("{server}", Bukkit.getServer().getName());
        return legacy.deserialize(joined);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(PlayerLoginEvent event) {
        Punishment p = bans.get(event.getPlayer().getUniqueId());
        if (p == null) return;
        if (!p.active()) { bans.remove(event.getPlayer().getUniqueId()); saveData(); return; }
        event.disallow(PlayerLoginEvent.Result.KICK_BANNED, banComponent(p));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Punishment p = mutes.get(event.getPlayer().getUniqueId());
        if (p == null) return;
        if (!p.active()) { mutes.remove(event.getPlayer().getUniqueId()); saveData(); return; }
        event.setCancelled(true);
        event.getPlayer().sendMessage(msg("Você está mutado. Motivo: " + p.reason()));
    }

    private long parseDuration(String input) {
        long total = 0; StringBuilder number = new StringBuilder();
        for (char c : input.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isDigit(c)) { number.append(c); continue; }
            if (number.length() == 0) throw new IllegalArgumentException();
            long n = Long.parseLong(number.toString()); number.setLength(0);
            long factor = switch (c) {
                case 's' -> 1000L; case 'm' -> 60_000L; case 'h' -> 3_600_000L;
                case 'd' -> 86_400_000L; case 'w' -> 604_800_000L;
                default -> throw new IllegalArgumentException();
            };
            total = Math.addExact(total, Math.multiplyExact(n, factor));
        }
        if (number.length() > 0 || total <= 0) throw new IllegalArgumentException();
        return total;
    }

    private String human(long ms) {
        long mins = Math.max(0, ms / 60000);
        long days = mins / 1440; mins %= 1440; long hours = mins / 60; mins %= 60;
        return (days > 0 ? days+"d " : "") + (hours > 0 ? hours+"h " : "") + mins+"m";
    }
    private boolean usage(CommandSender s) { s.sendMessage(msg("Uso incorreto. Consulte /help AdvancedPunish.")); return true; }
    private boolean fail(CommandSender s, String key) { s.sendMessage(msg(getConfig().getString("messages."+key, "&cErro."))); return true; }
    private Component msg(String text) { return legacy.deserialize(getConfig().getString("prefix","&8[&cAdvancedPunish&8] ") + text); }
}
