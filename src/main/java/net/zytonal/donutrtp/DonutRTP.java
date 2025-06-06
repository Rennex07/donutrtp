package net.zytonal.donutrtp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.ChatColor;
import org.bukkit.scheduler.BukkitRunnable;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import net.zytonal.donutrtp.commands.DonutRTPTabCompleter;
import net.zytonal.donutrtp.world.WorldType;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class DonutRTP extends JavaPlugin implements Listener {
    private final Random random = new Random();
    private FileConfiguration config;
    private final Map<String, WorldSettings> worldSettings = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, TeleportTask> teleportTasks = new ConcurrentHashMap<>();
    private int cooldownSeconds;
    private int waitingTime;
    private int maxAttempts;
    private Map<String, String> messages = new HashMap<>();

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        TeleportTask task = teleportTasks.get(player.getUniqueId());
        if (task != null && task.countdown > 0) {
            // Check if player actually moved a block
            if (event.getFrom().getBlockX() != event.getTo().getBlockX() || 
                event.getFrom().getBlockY() != event.getTo().getBlockY() || 
                event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
                cancelTeleportTask(player.getUniqueId(), "§cTeleport cancelled - you moved!");
            }
        }
    }

    @EventHandler
    public void onPlayerDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            cancelTeleportTask(player.getUniqueId(), "§cTeleport cancelled - you took damage!");
        }
    }

    @EventHandler
    public void onPlayerAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player) {
            Player player = (Player) event.getDamager();
            cancelTeleportTask(player.getUniqueId(), "§cTeleport cancelled - you attacked someone!");
        }
    }

    private static class TeleportTask {
        final Player player;
        final World world;
        final WorldSettings settings;
        Location safeLocation;
        boolean searching = true;
        int taskId = -1;
        int countdown;
        ScheduledTask foliaTask;

        TeleportTask(Player player, World world, WorldSettings settings, int countdown) {
            this.player = player;
            this.world = world;
            this.settings = settings;
            this.countdown = countdown;
        }
    }

    public static class WorldSettings {
        private final int minRange;
        private final int maxRange;

        public WorldSettings(int minRange, int maxRange) {
            this.minRange = minRange;
            this.maxRange = maxRange;
        }

        public int getMinRange() { return minRange; }
        public int getMaxRange() { return maxRange; }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        
        // Register command executor and tab completer
        getCommand("donutrtp").setExecutor(this);
        getCommand("donutrtp").setTabCompleter(new DonutRTPTabCompleter(this));
        
        // Register event listeners
        getServer().getPluginManager().registerEvents(this, this);
        
        getLogger().info("DonutRTP has been enabled!");
    }

    private void loadConfig() {
        reloadConfig();
        config = getConfig();
        cooldownSeconds = config.getInt("cooldown-in-seconds", 10);
        waitingTime = config.getInt("waiting-time", 5);
        maxAttempts = config.getInt("max-attempts", 50);
        loadMessages();
        worldSettings.clear();
        ConfigurationSection worldsSection = config.getConfigurationSection("worlds");
        if (worldsSection != null) {
            for (String worldName : worldsSection.getKeys(false)) {
                ConfigurationSection settings = worldsSection.getConfigurationSection(worldName);
                if (settings != null) {
                    int minRange = getIntFromObject(settings.get("min-range"), 100);
                    int maxRange = getIntFromObject(settings.get("max-range"), 1500);
                    worldSettings.put(worldName.toLowerCase(), new WorldSettings(minRange, maxRange));
                }
            }
        }
        if (worldSettings.isEmpty() && config.contains("world")) {
            List<Map<?, ?>> worldList = config.getMapList("world");
            for (Map<?, ?> worldMap : worldList) {
                for (Map.Entry<?, ?> entry : worldMap.entrySet()) {
                    String worldName = entry.getKey().toString();
                    Map<?, ?> settings = (Map<?, ?>) entry.getValue();
                    if (settings != null) {
                        int minRange = getIntFromObject(settings.get("min-range"), 100);
                        int maxRange = getIntFromObject(settings.get("max-range"), 1500);
                        worldSettings.put(worldName.toLowerCase(), new WorldSettings(minRange, maxRange));
                    }
                }
            }
        }
    }

    private void loadMessages() {
        messages = new HashMap<>();
        ConfigurationSection messagesSection = config.getConfigurationSection("messages");
        if (messagesSection != null) {
            for (String key : messagesSection.getKeys(false)) {
                messages.put(key, messagesSection.getString(key, ""));
            }
        }
        messages.putIfAbsent("usage", "&cUsage: /donutrtp <world>");
        messages.putIfAbsent("player-only", "&cThis command can only be used by players!");
        messages.putIfAbsent("no-permission", "&cYou don't have permission to do that!");
        messages.putIfAbsent("config-reloaded", "&aConfiguration reloaded successfully!");
        messages.putIfAbsent("world-not-found", "&cWorld not found: {world}");
        messages.putIfAbsent("world-not-configured", "&cWorld not configured: {world}");
        messages.putIfAbsent("cooldown-message", "&cYou must wait {time} seconds before using this command again!");
        messages.putIfAbsent("cooldown-bypass", "&aCooldown bypassed!");
        messages.putIfAbsent("teleporting", "&aTeleporting in {time} seconds...");
        messages.putIfAbsent("teleport-success", "&aYou have been teleported!");
        messages.putIfAbsent("teleport-failed", "&cFailed to find a safe location!");
        messages.putIfAbsent("searching", "&aSearching for a safe location...");
    }

    private int getIntFromObject(Object obj, int defaultValue) {
        if (obj == null) return defaultValue;
        if (obj instanceof Number) return ((Number) obj).intValue();
        try { return Integer.parseInt(obj.toString()); } 
        catch (NumberFormatException e) { return defaultValue; }
    }

    private String getMessage(String key, String... replacements) {
        String message = messages.getOrDefault(key, "&cMessage not found: " + key);
        for (int i = 0; i < replacements.length; i += 2) {
            if (i + 1 < replacements.length) {
                message = message
                    .replace("%" + replacements[i] + "%", replacements[i + 1])
                    .replace("{" + replacements[i] + "}", replacements[i + 1]);
            }
        }
        return ChatColor.translateAlternateColorCodes('&', message);
    }
    
    private void sendMessage(CommandSender sender, String message) {
        if (sender instanceof Player) {
            sendActionBar((Player) sender, message);
        } else {
            sender.sendMessage(ChatColor.stripColor(message));
        }
    }

    private void sendActionBar(Player player, String message) {
        try { player.sendActionBar(message.replace("&", "§")); }
        catch (Exception e) { player.sendMessage(message); }
    }

    private void runAsync(Runnable runnable) {
        if (getServer().getPluginManager().isPluginEnabled("Folia")) {
            getServer().getAsyncScheduler().runNow(this, task -> runnable.run());
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(this, runnable);
        }
    }

    private void runSync(Runnable runnable) {
        if (getServer().getPluginManager().isPluginEnabled("Folia")) {
            getServer().getGlobalRegionScheduler().run(this, task -> runnable.run());
        } else {
            Bukkit.getScheduler().runTask(this, runnable);
        }
    }

    private void runSync(Player player, Runnable runnable) {
        if (getServer().getPluginManager().isPluginEnabled("Folia")) {
            player.getScheduler().run(this, task -> runnable.run(), null);
        } else {
            Bukkit.getScheduler().runTask(this, runnable);
        }
    }

    private void startTeleport(Player player, World world, WorldSettings settings) {
        UUID playerId = player.getUniqueId();
        if (teleportTasks.containsKey(playerId)) {
            sendActionBar(player, getMessage("already-teleporting"));
            return;
        }

        if (cooldowns.containsKey(playerId)) {
            long cooldownTime = cooldowns.get(playerId);
            if (cooldownTime > System.currentTimeMillis()) {
                long remaining = (cooldownTime - System.currentTimeMillis()) / 1000;
                sendActionBar(player, getMessage("cooldown-message", "time", String.valueOf(remaining)));
                return;
            }
        }

        int countdown = getConfig().getInt("waiting-time", 5);
        TeleportTask task = new TeleportTask(player, world, settings, countdown);
        teleportTasks.put(playerId, task);

        startCountdown(task);

        runAsync(() -> {
            Location safeLoc = findSafeLocation(world, settings);
            if (safeLoc != null) {
                task.safeLocation = safeLoc;
                getLogger().info("Found safe location for " + player.getName() + " at " + safeLoc);
            } else {
                runSync(player, () -> {
                    sendActionBar(player, getMessage("teleport-failed"));
                    teleportTasks.remove(playerId);
                });
            }
        });
    }

    private void startCountdown(TeleportTask task) {
        Player player = task.player;
        if (player == null || !player.isOnline()) return;
        
        if (getServer().getPluginManager().isPluginEnabled("Folia")) {
            task.foliaTask = getServer().getAsyncScheduler().runAtFixedRate(this, task2 -> {
                if (!player.isOnline() || !teleportTasks.containsKey(player.getUniqueId())) {
                    if (task.foliaTask != null) task.foliaTask.cancel();
                    return;
                }
                
                if (task.countdown <= 0) {
                    completeTeleport(task);
                    if (task.foliaTask != null) task.foliaTask.cancel();
                    return;
                }
                sendActionBar(player, "§7Teleporting in §b" + task.countdown + "s");
                task.countdown--;
            }, 0, 1, TimeUnit.SECONDS);
            task.taskId = 1;
        } else {
            task.taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
                if (!player.isOnline() || !teleportTasks.containsKey(player.getUniqueId())) {
                    Bukkit.getScheduler().cancelTask(task.taskId);
                    return;
                }
                
                if (task.countdown <= 0) {
                    completeTeleport(task);
                    return;
                }
                sendActionBar(player, "§7Teleporting in §b" + task.countdown + "s");
                task.countdown--;
            }, 0L, 20L);
        }
    }

    private void completeTeleport(TeleportTask task) {
        if (task == null || task.player == null) return;
        
        if (task.taskId != -1 && !getServer().getPluginManager().isPluginEnabled("Folia")) {
            Bukkit.getScheduler().cancelTask(task.taskId);
        }
        
        task.searching = false;
        
        if (task.safeLocation == null) {
            sendActionBar(task.player, getMessage("teleport-failed"));
            teleportTasks.remove(task.player.getUniqueId());
            return;
        }

        runSync(task.player, () -> {
            if (task.player == null || !task.player.isOnline()) {
                teleportTasks.remove(task.player.getUniqueId());
                return;
            }

            try {
                task.player.teleport(task.safeLocation);
                sendActionBar(task.player, getMessage("teleport-success"));
                cooldowns.put(task.player.getUniqueId(), System.currentTimeMillis() + (cooldownSeconds * 1000L));
            } catch (Exception e) {
                sendActionBar(task.player, "§cAn error occurred during teleportation.");
                getLogger().severe("Error teleporting player " + task.player.getName() + ": " + e.getMessage());
            } finally {
                teleportTasks.remove(task.player.getUniqueId());
            }
        });
    }

    private void cancelTeleportTask(UUID playerId, String message) {
        TeleportTask task = teleportTasks.remove(playerId);
        if (task != null) {
            if (task.taskId != -1 && !getServer().getPluginManager().isPluginEnabled("Folia")) {
                Bukkit.getScheduler().cancelTask(task.taskId);
            } else if (task.foliaTask != null) {
                task.foliaTask.cancel();
            }
            
            // Send cancellation message if player is online and message is provided
            if (task.player != null && task.player.isOnline() && message != null) {
                sendActionBar(task.player, message);
            }
        }
    }
    
    private void cancelTeleportTask(UUID playerId) {
        cancelTeleportTask(playerId, null);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("donutrtp.reload")) {
                sendMessage(sender, getMessage("no-permission"));
                return true;
            }
            for (TeleportTask task : teleportTasks.values()) {
                if (task.taskId != -1) {
                    Bukkit.getScheduler().cancelTask(task.taskId);
                }
            }
            reloadConfig();
            loadConfig();
            sendMessage(sender, getMessage("config-reloaded"));
            return true;
        }
        
        if (!(sender instanceof Player)) {
            sendMessage(sender, getMessage("player-only"));
            return true;
        }
        
        Player player = (Player) sender;
        return handleRTPCommand(player, args);
    }

    private boolean handleRTPCommand(Player player, String[] args) {
        if (args.length == 0) {
            sendActionBar(player, getMessage("usage"));
            return true;
        }
        String worldName = args[0];
        World world = getServer().getWorld(worldName);
        if (world == null) {
            sendActionBar(player, getMessage("world-not-found", "world", worldName));
            return true;
        }
        WorldSettings settings = worldSettings.get(world.getName().toLowerCase());
        if (settings == null) {
            sendActionBar(player, getMessage("world-not-configured", "world", worldName));
            return true;
        }
        startTeleport(player, world, settings);
        return true;
    }

    private Location findSafeLocation(World world, WorldSettings settings) {
        if (world == null) return null;
        
        int min = settings.getMinRange();
        int max = settings.getMaxRange();
        
        for (int i = 0; i < 20; i++) {
            double angle = random.nextDouble() * 2 * Math.PI;
            double radius = min + (max - min) * Math.sqrt(random.nextDouble());
            int x = (int) (Math.cos(angle) * radius);
            int z = (int) (Math.sin(angle) * radius);
            
            if (Math.abs(x) < 100 && Math.abs(z) < 100) continue;
            
            if (world.getEnvironment() == World.Environment.NETHER) {
                int maxY = 120;
                int minY = 32;
                
                for (int y = maxY; y >= minY; y--) {
                    Location location = new Location(world, x, y, z);
                    Block feet = location.getBlock();
                    Block head = feet.getRelative(BlockFace.UP);
                    Block ground = feet.getRelative(BlockFace.DOWN);
                    
                    if (feet.getType().isAir() && head.getType().isAir() && ground.getType().isSolid()) {
                        return location.add(0.5, 0, 0.5);
                    }
                }
            } else {
                Location location = new Location(world, x, 0, z);
                location.setY(world.getHighestBlockYAt(location) + 1);
                
                if (isLocationSafe(location)) {
                    Block blockBelow = location.getBlock().getRelative(BlockFace.DOWN);
                    if (!blockBelow.isPassable() && !blockBelow.isLiquid() && blockBelow.getType().isSolid()) {
                        return location;
                    }
                }
            }
        }
        return null;
    }

    private boolean isBadBlock(Material type) {
        if (type == null) return false;
        String name = type.name().toLowerCase();
        return name.contains("lava") || name.contains("fire") || name.contains("cactus") || 
               name.contains("magma") || name.contains("void") || name.contains("water");
    }
    
    private boolean isLiquid(Material type) {
        if (type == null) return false;
        return type == Material.WATER || type == Material.LAVA || 
               type.name().contains("WATER") || type.name().contains("LAVA");
    }

    private boolean isLocationSafe(Location location) {
        if (location == null) return false;
        
        Block block = location.getBlock();
        Block blockAbove = block.getRelative(BlockFace.UP);
        Block blockBelow = block.getRelative(BlockFace.DOWN);
        
        if (blockBelow.getType().isAir() || blockBelow.isLiquid() || !blockBelow.getType().isSolid()) {
            return false;
        }
        
        if (block.getType().isSolid() || blockAbove.getType().isSolid()) {
            return false;
        }
        
        Material type = block.getType();
        return type != Material.LAVA && type != Material.FIRE && type != Material.CACTUS && 
               type != Material.MAGMA_BLOCK && !type.toString().contains("LAVA") && 
               !type.toString().contains("FIRE");
    }

    public Map<String, WorldSettings> getWorldSettings() {
        return worldSettings;
    }
}
