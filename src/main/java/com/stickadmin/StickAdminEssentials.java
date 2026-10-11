package com.stickadmin;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
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
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.ShulkerBox;
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
        for (String name : List.of("banitem", "unbanitem", "banlist", "stickadmin", "chest")) {
            getCommand(name).setExecutor(this);
            getCommand(name).setTabCompleter(this);
        }
        long seconds = Math.max(1, getConfig().getLong("scan-seconds", 1));
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
        return false; // banned items apply to everyone, including operators
    }

    private void notifyBlocked(Player p, ItemStack it) {
        if (getConfig().getBoolean("notify-on-block", true)) {
            p.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "Banned item: " + ChatColor.RESET + ChatColor.RED + pretty(it.getType()));
        }
    }

    private static String pretty(Material m) {
        StringBuilder sb = new StringBuilder();
        for (String word : m.name().toLowerCase(Locale.ROOT).split("_")) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }

    // ---------- Periodic cleanup ----------

    /** Removes banned items from an array, including inside shulker boxes. Returns how many were removed. */
    private int purge(ItemStack[] items, List<String> found) {
        int changed = 0;
        for (int i = 0; i < items.length; i++) {
            ItemStack it = items[i];
            if (it == null || it.getType().isAir()) continue;
            if (banned.contains(it.getType())) {
                found.add(pretty(it.getType()));
                items[i] = null;
                changed++;
                continue;
            }
            if (it.getItemMeta() instanceof BlockStateMeta bsm && bsm.getBlockState() instanceof ShulkerBox sb) {
                ItemStack[] inside = sb.getInventory().getContents();
                int inner = purge(inside, found);
                if (inner > 0) {
                    sb.getInventory().setContents(inside);
                    bsm.setBlockState(sb);
                    it.setItemMeta(bsm);
                    changed += inner;
                }
            }
        }
        return changed;
    }

    private void scan() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            List<String> found = new ArrayList<>();
            PlayerInventory inv = p.getInventory();

            ItemStack[] contents = inv.getContents();
            if (purge(contents, found) > 0) inv.setContents(contents);

            ItemStack[] armor = inv.getArmorContents();
            if (purge(armor, found) > 0) inv.setArmorContents(armor);

            ItemStack[] off = {inv.getItemInOffHand()};
            if (purge(off, found) > 0) inv.setItemInOffHand(off[0]);

            ItemStack[] cursor = {p.getItemOnCursor()};
            if (purge(cursor, found) > 0) p.setItemOnCursor(cursor[0]);

            ItemStack[] ender = p.getEnderChest().getContents();
            if (purge(ender, found) > 0) p.getEnderChest().setContents(ender);

            for (String name : found) {
                p.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + name + " is not allowed on this server. It has been deleted.");
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
            case "chest" -> {
                if (!admin(sender)) return true;
                if (args.length >= 1 && args[0].equalsIgnoreCase("lookup")) {
                    chestLookup(sender, args);
                } else {
                    sender.sendMessage(ChatColor.YELLOW + "/chest lookup <item>" + ChatColor.GRAY + " - find chests, barrels and shulker boxes holding an item");
                }
            }
            default -> { }
        }
        return true;
    }

    /** Counts an item in a list of stacks, including inside shulker boxes. */
    private int countItem(ItemStack[] items, Material target) {
        int total = 0;
        for (ItemStack it : items) {
            if (it == null || it.getType().isAir()) continue;
            if (it.getType() == target) total += it.getAmount();
            if (it.getItemMeta() instanceof BlockStateMeta bsm && bsm.getBlockState() instanceof ShulkerBox sb) {
                total += countItem(sb.getInventory().getContents(), target);
            }
        }
        return total;
    }

    private void chestLookup(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /chest lookup <item>");
            return;
        }
        Material target = Material.matchMaterial(args[1]);
        if (target == null || !target.isItem()) {
            sender.sendMessage(ChatColor.RED + "Unknown item: " + args[1]);
            return;
        }
        sender.sendMessage(ChatColor.GOLD + "Searching loaded chunks for " + pretty(target) + "...");
        int containers = 0;
        int total = 0;
        for (World w : Bukkit.getWorlds()) {
            for (Chunk ch : w.getLoadedChunks()) {
                for (BlockState bs : ch.getTileEntities(false)) {
                    if (!(bs instanceof Container box)) continue;
                    int count = countItem(box.getInventory().getContents(), target);
                    if (count <= 0) continue;
                    containers++;
                    total += count;
                    if (containers <= 25) {
                        sender.sendMessage(ChatColor.YELLOW + pretty(bs.getType()) + ChatColor.GRAY + " at "
                                + ChatColor.WHITE + w.getName() + " " + bs.getX() + ", " + bs.getY() + ", " + bs.getZ()
                                + ChatColor.GRAY + " (" + count + "x)");
                    }
                }
            }
        }
        if (containers == 0) {
            sender.sendMessage(ChatColor.GREEN + "No loaded chests, barrels or shulker boxes hold " + pretty(target) + ".");
        } else {
            sender.sendMessage(ChatColor.GOLD + "Found " + total + "x " + pretty(target) + " in " + containers + " container(s)."
                    + (containers > 25 ? " Showing the first 25." : ""));
        }
        sender.sendMessage(ChatColor.GRAY + "Only loaded chunks are searched. Walk near other areas to load them.");
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
        if (cmd.getName().equalsIgnoreCase("chest")) {
            if (args.length == 1) return List.of("lookup").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
            if (args.length == 2 && args[0].equalsIgnoreCase("lookup")) {
                String prefix = args[1].toUpperCase(Locale.ROOT);
                List<String> items = new ArrayList<>();
                for (Material m : Material.values()) {
                    if (m.isItem() && m.name().startsWith(prefix) && items.size() < 50) items.add(m.name().toLowerCase(Locale.ROOT));
                }
                return items;
            }
            return List.of();
        }
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
