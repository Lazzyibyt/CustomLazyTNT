package me.lazzyibyt.customlazzytnt;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.util.Vector;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class CustomLazyTNT extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private NamespacedKey typeKey;
    private NamespacedKey wandKey;
    private FileConfiguration messages;
    private final Map<String, TempBlock> temporaryBlocks = new HashMap<>();
    private final Map<String, String> placedTnt = new HashMap<>();
    private final Set<UUID> customTnt = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("messages.yml", false);
        messages = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml"));
        typeKey = new NamespacedKey(this, "tnt_type");
        wandKey = new NamespacedKey(this, "snow_wand");
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("customlazytnt") != null) {
            getCommand("customlazytnt").setExecutor(this);
            getCommand("customlazytnt").setTabCompleter(this);
        }
        getLogger().info("CustomLazyTNT enabled");
    }

    @Override
    public void onDisable() {
        temporaryBlocks.clear();
        placedTnt.clear();
        customTnt.clear();
    }

    // че ты тут ищеш
    // тут обфускации нет можеш не искать
    // если сломал сам чини
    // не ставь радиус 999 потом сервер не вини
    // это просто тнт не магия
    // не трогай если не знаеш что это
    // блин опять кто то полез в код
    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        String type = getType(item);
        if (type == null || !hasUsePermission(event.getPlayer(), type)) return;

        placedTnt.put(key(event.getBlock()), type);
    }

    private void igniteBlock(Block block, String type) {
        placedTnt.remove(key(block));
        block.setType(Material.AIR, false);
        Location location = block.getLocation().add(0.5, 0.0, 0.5);
        TNTPrimed tnt = location.getWorld().spawn(location, TNTPrimed.class);
        tnt.setFuseTicks(Math.max(1, getConfig().getInt("tnt-types." + type + ".fuse-ticks", 100)));
        tnt.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type);
        customTnt.add(tnt.getUniqueId());
        location.getWorld().spawnParticle(Particle.SMOKE, location.clone().add(0, 0.4, 0), 4, 0.15, 0.15, 0.15, 0.01);
    }

    @EventHandler
    public void onRedstone(BlockRedstoneEvent event) {
        String type = placedTnt.get(key(event.getBlock()));
        if (type == null || event.getNewCurrent() <= 0) return;
        igniteBlock(event.getBlock(), type);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        placedTnt.remove(key(event.getBlock()));
    }

    @EventHandler
    public void onExplode(EntityExplodeEvent event) {
        if (!(event.getEntity() instanceof TNTPrimed tnt)) return;
        String type = tnt.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        if (type == null) return;
        customTnt.remove(tnt.getUniqueId());

        Location center = tnt.getLocation();
        if (type.equalsIgnoreCase("snow")) {
            event.setCancelled(true);
            makeSnowSphere(center, "tnt-types.snow");
            return;
        }

        String path = "tnt-types." + type;
        boolean breakBlocks = getConfig().getBoolean(path + ".break-blocks", true);
        if (!breakBlocks) {
            event.blockList().clear();
        }

        boolean transform = getConfig().getBoolean(path + ".transform-crying-obsidian", true);
        Set<Block> crying = new HashSet<>();
        if (transform) {
            for (Block block : new ArrayList<>(event.blockList())) {
                if (block.getType() == Material.CRYING_OBSIDIAN) {
                    event.blockList().remove(block);
                    crying.add(block);
                }
            }
        }

        if (getConfig().getBoolean(path + ".break-obsidian", false)) {
            int radius = Math.max(1, (int) Math.ceil(getConfig().getDouble(path + ".explosion-power", 4.0)));
            addObsidianToExplosion(event.blockList(), center, radius, transform, crying);
        }

        if (!crying.isEmpty()) {
            Bukkit.getScheduler().runTask(this, () -> {
                for (Block block : crying) {
                    if (block.getType() == Material.CRYING_OBSIDIAN) block.setType(Material.OBSIDIAN, false);
                }
            });
        }

        if (type.equalsIgnoreCase("destructive")) {
            double damage = getConfig().getDouble(path + ".damage", 40.0);
            double radius = getConfig().getDouble(path + ".damage-radius", 4.0);
            boolean scaling = getConfig().getBoolean(path + ".distance-scaling", true);
            boolean players = getConfig().getBoolean(path + ".damage-players", true);
            boolean mobs = getConfig().getBoolean(path + ".damage-mobs", true);
            Bukkit.getScheduler().runTask(this, () -> damageEntities(center, damage, radius, scaling, players, mobs, tnt));
        }
    }

    // тут блоки либо взрываются либо делают вид что ничего не произошло
    // не еби этот код если не понимаеш что делаеш
    private void addObsidianToExplosion(List<Block> list, Location center, int radius, boolean transform, Set<Block> crying) {
        World world = center.getWorld();
        if (world == null) return;
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int y = Math.max(world.getMinHeight(), cy - radius); y <= Math.min(world.getMaxHeight() - 1, cy + radius); y++) {
                for (int z = cz - radius; z <= cz + radius; z++) {
                    if (center.distanceSquared(new Location(world, x + 0.5, y + 0.5, z + 0.5)) > radius * radius) continue;
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() == Material.OBSIDIAN && !list.contains(block)) list.add(block);
                    if (transform && block.getType() == Material.CRYING_OBSIDIAN) {
                        list.remove(block);
                        crying.add(block);
                    }
                }
            }
        }
    }

    // если сервер упал значит радиус большой
    private void damageEntities(Location center, double amount, double radius, boolean scaling, boolean players, boolean mobs, Entity source) {
        World world = center.getWorld();
        if (world == null) return;
        for (Entity entity : world.getNearbyEntities(center, radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living)) continue;
            boolean allowed = entity instanceof Player ? players : mobs;
            if (!allowed) continue;
            double distance = entity.getLocation().distance(center);
            double finalDamage = scaling ? amount * Math.max(0.15, 1.0 - distance / radius) : amount;
            living.damage(finalDamage, source);
            Vector knockback = entity.getLocation().toVector().subtract(center.toVector());
            if (knockback.lengthSquared() > 0) living.setVelocity(knockback.normalize().multiply(0.8).setY(0.45));
        }
    }

    private void makeSnowSphere(Location center, String path) {
        int radius = Math.min(getConfig().getInt(path + ".sphere-radius", 3), getConfig().getInt("settings.max-sphere-radius", 10));
        int duration = getConfig().getInt(path + ".ice-duration-ticks", 100);
        Material ice = material(getConfig().getString(path + ".ice-material", "ICE"), Material.ICE);
        boolean air = getConfig().getBoolean(path + ".replace-air", true);
        boolean water = getConfig().getBoolean(path + ".replace-water", true);
        createSphere(center, radius, duration, ice, air, water, true);
        playExplosion(center, false);
    }

    private void createSphere(Location center, int radius, int duration, Material newMaterial, boolean replaceAir, boolean replaceWater, boolean sound) {
        World world = center.getWorld();
        if (world == null) return;
        int count = 0;
        int max = getConfig().getInt("settings.max-sphere-blocks", 5000);
        for (int x = -radius; x <= radius && count < max; x++) {
            for (int y = -radius; y <= radius && count < max; y++) {
                for (int z = -radius; z <= radius && count < max; z++) {
                    if (x * x + y * y + z * z > radius * radius) continue;
                    Block block = world.getBlockAt(center.getBlockX() + x, center.getBlockY() + y, center.getBlockZ() + z);
                    Material old = block.getType();
                    if ((old.isAir() && replaceAir) || ((old == Material.WATER || old == Material.KELP || old == Material.SEAGRASS) && replaceWater)) {
                        block.setType(newMaterial, false);
                        temporaryBlocks.put(key(block), new TempBlock(block, newMaterial, sound));
                        count++;
                    }
                }
            }
        }
        playExplosion(center, false);
        Bukkit.getScheduler().runTaskLater(this, () -> clearTemporaryBlocks(), Math.max(1, duration));
    }

    private void clearTemporaryBlocks() {
        List<String> done = new ArrayList<>();
        for (Map.Entry<String, TempBlock> entry : temporaryBlocks.entrySet()) {
            TempBlock temp = entry.getValue();
            if (temp.block.getType() == temp.created) {
                temp.block.setType(Material.AIR, false);
                if (temp.sound) temp.block.getWorld().playSound(temp.block.getLocation(), Sound.BLOCK_GLASS_BREAK, 0.8f, 1.0f);
            }
            done.add(entry.getKey());
        }
        for (String key : done) temporaryBlocks.remove(key);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block clicked = event.getClickedBlock();
        if (clicked != null && clicked.getType() == Material.TNT) {
            String type = placedTnt.get(key(clicked));
            if (type != null && isIgnitionItem(event.getItem())) {
                event.setCancelled(true);
                igniteBlock(clicked, type);
                consumeIgnition(event.getItem());
                return;
            }
        }
        ItemStack item = event.getItem();
        if (item == null || !isWand(item)) return;
        if (!event.getPlayer().hasPermission("customlazytnt.wand")) return;
        event.setCancelled(true);
        String path = "snow-wand";
        Location target = event.getClickedBlock().getLocation().add(0.5, 1.0, 0.5);
        int radius = Math.min(getConfig().getInt(path + ".radius", 3), getConfig().getInt("settings.max-sphere-radius", 10));
        createSphere(target, radius, getConfig().getInt(path + ".duration-ticks", 100), material(getConfig().getString(path + ".ice-material", "ICE"), Material.ICE), getConfig().getBoolean(path + ".replace-air", true), getConfig().getBoolean(path + ".replace-water", true), true);
    }

    private boolean isIgnitionItem(ItemStack item) {
        return item != null && (item.getType() == Material.FLINT_AND_STEEL || item.getType() == Material.FIRE_CHARGE);
    }

    private void consumeIgnition(ItemStack item) {
        if (item == null) return;
        if (item.getType() == Material.FIRE_CHARGE) {
            item.setAmount(item.getAmount() - 1);
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof Damageable damageable) {
            damageable.setDamage(damageable.getDamage() + 1);
            item.setItemMeta(meta);
        }
    }

    private void playExplosion(Location location, boolean fire) {
        World world = location.getWorld();
        if (world == null) return;
        world.spawnParticle(Particle.EXPLOSION_EMITTER, location, 1);
        world.playSound(location, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.0f);
        if (fire) world.spawnParticle(Particle.FLAME, location, 12, 0.4, 0.4, 0.4, 0.02);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(color(msg("usage")));
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("customlazytnt.admin")) return noPermission(sender);
            reloadConfig();
            sender.sendMessage(color(msg("reload")));
            return true;
        }
        if (args[0].equalsIgnoreCase("list")) {
            sender.sendMessage(color("&bfragment &7snow &4destructive"));
            return true;
        }
        if (args[0].equalsIgnoreCase("wand")) {
            if (!(sender instanceof Player player)) return playerOnly(sender);
            if (!player.hasPermission("customlazytnt.wand")) return noPermission(sender);
            player.getInventory().addItem(createWand());
            player.sendMessage(color(msg("wand-given")));
            return true;
        }
        if (args[0].equalsIgnoreCase("give")) {
            if (!sender.hasPermission("customlazytnt.admin")) return noPermission(sender);
            if (args.length < 3) {
                sender.sendMessage(color(msg("usage")));
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            String type = args[2].toLowerCase();
            if (target == null || !isKnownType(type)) {
                sender.sendMessage(color(msg("unknown-type")));
                return true;
            }
            int amount = args.length >= 4 ? parseAmount(args[3]) : 1;
            target.getInventory().addItem(createTnt(type, amount));
            sender.sendMessage(color(msg("given").replace("{amount}", String.valueOf(amount)).replace("{type}", type)));
            return true;
        }
        sender.sendMessage(color(msg("usage")));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("give", "list", "reload", "wand");
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) return List.of("fragment", "snow", "destructive");
        return List.of();
    }

    private ItemStack createTnt(String type, int amount) {
        String path = "tnt-types." + type;
        ItemStack item = new ItemStack(material(getConfig().getString(path + ".material", "TNT"), Material.TNT), Math.max(1, Math.min(amount, 64)));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(color(getConfig().getString(path + ".name", type)));
        List<String> lore = new ArrayList<>();
        for (String line : getConfig().getStringList(path + ".lore")) lore.add(color(line));
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createWand() {
        ItemStack item = new ItemStack(Material.STICK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(getConfig().getString("snow-wand.name", "&bСнежная кисть")));
            meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    private String getType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
    }

    private boolean isWand(ItemStack item) {
        return item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    private boolean isKnownType(String type) {
        return getConfig().getBoolean("tnt-types." + type + ".enabled", false);
    }

    private boolean hasUsePermission(Player player, String type) {
        return player.hasPermission("customlazytnt.admin") || player.hasPermission("customlazytnt.use." + type);
    }

    private String msg(String key) {
        return messages == null ? key : messages.getString(key, key);
    }

    private boolean noPermission(CommandSender sender) {
        sender.sendMessage(color(msg("no-permission")));
        return true;
    }

    private boolean playerOnly(CommandSender sender) {
        sender.sendMessage(color(msg("player-only")));
        return true;
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }

    private int parseAmount(String value) {
        try { return Math.max(1, Math.min(64, Integer.parseInt(value))); }
        catch (NumberFormatException ignored) { return 1; }
    }

    private Material material(String value, Material fallback) {
        Material result = Material.matchMaterial(value == null ? "" : value);
        return result == null ? fallback : result;
    }

    private String key(Block block) {
        Location location = block.getLocation();
        return location.getWorld().getUID() + ":" + location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ();
    }

    private static final class TempBlock {
        private final Block block;
        private final Material created;
        private final boolean sound;

        private TempBlock(Block block, Material created, boolean sound) {
            this.block = block;
            this.created = created;
            this.sound = sound;
        }
    }
}
