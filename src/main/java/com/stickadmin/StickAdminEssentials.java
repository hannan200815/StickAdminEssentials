package com.stickadmin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class StickAdminEssentials extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private final Set<Material> banned = EnumSet.noneOf(Material.class);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadBans();
        getServer().getPluginManager().registerEvents(this, this);
        for (String name : List.of("banitem", "unbanitem", "banlist", "stickadmin")) {
            getCommand(name).setExecutor(this);
            getCommand(name).setTabCompleter(this);
        }
        long seconds = Math.max(1, getConfig().getLong("scan-seconds", 2));
        Bukkit.getScheduler().runTaskTimer(this, this::scan, 40L, 20L * seconds);
        getLogger().info("StickAdminEssentials loaded " + banned.size() + " banned items.");
    }

    // ---------- Ban list storage ----------

    private void loadBans() {
        banned.clear();
        for (String s : getConfig().getStringList("banned-items")) {
            Material m = Material.matchMaterial(s);
            if (m != null && m.isItem()) banned.add(m);
            else getLogger().warning("Unknown item in banned-items: " + s);
        }
    }

    private void saveBans() {
        List<String> names = new ArrayList<>();
        for (Material m : banned) names.add(m.name());
        names.sort(String::compareTo);
        getConfig().set("banned-items", names);
        saveConfig();
    }

    private boolean isBanned(ItemStack it) {
        return it != null && !it.getType().isAir() && banned.contains(it.getType());
    }

    private boolean exempt(Player p) {
        return p.hasPermission("stickadmin.bypass");
    }

    private void notifyBlocked(Player p, ItemStack it) {
        if (getConfig().getBoolean("notify-on-block", true)) {
            p.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "Banned item: " + ChatColor.RESET + ChatColor.RED + pretty(it.getType()));
        }
    }

    private static String pretty(Material m) {
        return m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    // ---------- Periodic cleanup ----------

    private void scan() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (exempt(p)) continue;
            int removed = 0;
            PlayerInventory inv = p.getInventory();

            ItemStack[] contents = inv.getContents();
            for (int i = 0; i < contents.length; i++) {
                if (isBanned(contents[i])) { contents[i] = null; removed++; }
            }
            inv.setContents(contents);

            ItemStack[] armor = inv.getArmorContents();
            for (int i = 0; i < armor.length; i++) {
                if (isBanned(armor[i])) { armor[i] = null; removed++; }
            }
            inv.setArmorContents(armor);

            if (isBanned(inv.getItemInOffHand())) { inv.setItemInOffHand(null); removed++; }
            if (isBanned(p.getItemOnCursor())) { p.setItemOnCursor(null); removed++; }

            ItemStack[] ender = p.getEnderChest().getContents();
            boolean enderChanged = false;
            for (int i = 0; i < ender.length; i++) {
                if (isBanned(ender[i])) { ender[i] = null; removed++; enderChanged = true; }
            }
            if (enderChanged) p.getEnderChest().setContents(ender);

            if (removed > 0) {
                p.sendMessage(ChatColor.RED + "Removed " + removed + " banned item stack(s) from your inventory.");
            }
        }
        if (getConfig().getBoolean("remove-ground-items", true)) {
            for (World w : Bukkit.getWorlds()) {
                for (Item item : w.getEntitiesByClass(Item.class)) {
                    if (isBanned(item.getItemStack())) item.remove();
                }
            }
        }
    }

    // ---------- Blocking events ----------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent e) {
        if (isBanned(e.getEntity().getItemStack())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerPickup(PlayerPickupItemEvent e) {
        if (!isBanned(e.getItem().getItemStack())) return;
        if (exempt(e.getPlayer())) return;
        e.setCancelled(true);
        e.getItem().remove();
        notifyBlocked(e.getPlayer(), e.getItem().getItemStack());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMobPickup(EntityPickupItemEvent e) {
        if (isBanned(e.getItem().getItemStack())) {
            e.setCancelled(true);
            e.getItem().remove();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (isBanned(e.getItemDrop().getItemStack()) && !exempt(e.getPlayer())) {
            e.setCancelled(true);
            notifyBlocked(e.getPlayer(), e.getItemDrop().getItemStack());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || exempt(p)) return;
        if (isBanned(e.getCurrentItem()) || isBanned(e.getCursor())) {
            e.setCancelled(true);
            notifyBlocked(p, isBanned(e.getCurrentItem()) ? e.getCurrentItem() : e.getCursor());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || exempt(p)) return;
        if (isBanned(e.getOldCursor())) {
            e.setCancelled(true);
            notifyBlocked(p, e.getOldCursor());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || exempt(p)) return;
        if (e.getRecipe() != null && isBanned(e.getRecipe().getResult())) {
            e.setCancelled(true);
            notifyBlocked(p, e.getRecipe().getResult());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (exempt(e.getPlayer())) return;
        if (isBanned(e.getItem())) {
            e.setCancelled(true);
            notifyBlocked(e.getPlayer(), e.getItem());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (isBanned(e.getItemInHand()) && !exempt(e.getPlayer())) {
            e.setCancelled(true);
            notifyBlocked(e.getPlayer(), e.getItemInHand());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        if (isBanned(e.getItemStack()) && !exempt(e.getPlayer())) {
            e.setCancelled(true);
            notifyBlocked(e.getPlayer(), e.getItemStack());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        if (isBanned(e.getItem()) && !exempt(e.getPlayer())) {
            e.setCancelled(true);
            notifyBlocked(e.getPlayer(), e.getItem());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent e) {
        if (isBanned(e.getItem())) e.setCancelled(true);
    }

    // ---------- Commands ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "banitem" -> {
                if (!admin(sender)) return true;
                Material m = resolve(sender, args);
                if (m == null) return true;
                if (banned.contains(m)) {
                    sender.sendMessage(ChatColor.YELLOW + pretty(m) + " is already banned.");
                    return true;
                }
                banned.add(m);
                saveBans();
                sender.sendMessage(ChatColor.GREEN + "Banned " + pretty(m) + ". Copies will be removed.");
            }
            case "unbanitem" -> {
                if (!admin(sender)) return true;
                Material m = resolve(sender, args);
                if (m == null) return true;
                if (!banned.remove(m)) {
                    sender.sendMessage(ChatColor.YELLOW + pretty(m) + " is not banned.");
                    return true;
                }
                saveBans();
                sender.sendMessage(ChatColor.GREEN + "Unbanned " + pretty(m) + ".");
            }
            case "banlist" -> {
                if (!admin(sender)) return true;
                if (banned.isEmpty()) {
                    sender.sendMessage(ChatColor.GRAY + "No items are banned.");
                    return true;
                }
                sender.sendMessage(ChatColor.GOLD + "Banned items (" + banned.size() + "):");
                List<String> names = new ArrayList<>();
                for (Material m : banned) names.add(m.name());
                names.sort(String::compareTo);
                sender.sendMessage(ChatColor.GRAY + String.join(", ", names));
            }
            case "stickadmin" -> {
                if (!admin(sender)) return true;
                String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "help";
                switch (sub) {
                    case "reload" -> {
                        reloadConfig();
                        loadBans();
                        sender.sendMessage(ChatColor.GREEN + "Reloaded. " + banned.size() + " banned items.");
                    }
                    case "scan" -> {
                        scan();
                        sender.sendMessage(ChatColor.GREEN + "Scan complete.");
                    }
                    default -> {
                        sender.sendMessage(ChatColor.GOLD + "StickAdminEssentials");
                        sender.sendMessage(ChatColor.YELLOW + "/banitem [item|hand]" + ChatColor.GRAY + " - ban an item (default: held item)");
                        sender.sendMessage(ChatColor.YELLOW + "/unbanitem [item|hand]" + ChatColor.GRAY + " - unban an item");
                        sender.sendMessage(ChatColor.YELLOW + "/banlist" + ChatColor.GRAY + " - show banned items");
                        sender.sendMessage(ChatColor.YELLOW + "/stickadmin reload|scan" + ChatColor.GRAY + " - reload config or run an immediate scan");
                    }
                }
            }
            default -> { }
        }
        return true;
    }

    private boolean admin(CommandSender s) {
        if (!s.hasPermission("stickadmin.admin")) {
            s.sendMessage(ChatColor.RED + "You do not have permission.");
            return false;
        }
        return true;
    }

    private Material resolve(CommandSender sender, String[] args) {
        Material m;
        if (args.length == 0 || args[0].equalsIgnoreCase("hand")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(ChatColor.RED + "Give an item name from the console.");
                return null;
            }
            m = p.getInventory().getItemInMainHand().getType();
            if (m.isAir()) {
                sender.sendMessage(ChatColor.RED + "Hold an item or give an item name.");
                return null;
            }
        } else {
            m = Material.matchMaterial(args[0]);
            if (m == null || !m.isItem()) {
                sender.sendMessage(ChatColor.RED + "Unknown item: " + args[0]);
                return null;
            }
        }
        return m;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toUpperCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (cmd.getName().equalsIgnoreCase("stickadmin")) {
            out.addAll(List.of("reload", "scan"));
            return out.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (cmd.getName().equalsIgnoreCase("unbanitem")) {
            for (Material m : banned) out.add(m.name().toLowerCase(Locale.ROOT));
            out.add("hand");
            return out.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (cmd.getName().equalsIgnoreCase("banitem")) {
            out.add("hand");
            for (Material m : Material.values()) {
                if (m.isItem() && m.name().startsWith(prefix) && out.size() < 50) out.add(m.name().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}
