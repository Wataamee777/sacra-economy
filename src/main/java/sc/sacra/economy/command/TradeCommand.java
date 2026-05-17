package sc.sacra.economy.command;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class TradeCommand implements CommandExecutor, Listener {
    private static final String TITLE = "セーフティ取引";
    private static final Set<Integer> LEFT_SLOTS = Set.of(0, 1, 2, 3, 9, 10, 11, 12, 18, 19, 20, 21);
    private static final Set<Integer> RIGHT_SLOTS = Set.of(5, 6, 7, 8, 14, 15, 16, 17, 23, 24, 25, 26);
    private static final Set<Integer> BORDER_SLOTS = Set.of(4, 13, 22, 31, 40, 49);
    private static final int CONFIRM_SLOT = 45;
    private static final int CANCEL_SLOT = 53;
    private static final int STATUS_SELF_SLOT = 46;
    private static final int STATUS_OTHER_SLOT = 52;

    private final JavaPlugin plugin;
    private final Map<UUID, UUID> requests = new HashMap<>();
    private final Map<UUID, TradeSession> sessions = new HashMap<>();

    public TradeCommand(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("このコマンドはプレイヤーのみ実行できます。");
            return true;
        }
        if (args.length != 1) {
            player.sendMessage("§e/trade [プレイヤー名]");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !target.isOnline()) {
            player.sendMessage("§c対象プレイヤーが見つかりません。");
            return true;
        }
        if (target.equals(player)) {
            player.sendMessage("§c自分自身とは取引できません。");
            return true;
        }
        if (sessions.containsKey(player.getUniqueId()) || sessions.containsKey(target.getUniqueId())) {
            player.sendMessage("§cどちらかが既に取引中です。");
            return true;
        }
        if (player.getUniqueId().equals(requests.get(target.getUniqueId()))) {
            requests.remove(target.getUniqueId());
            openSession(player, target);
            return true;
        }
        requests.put(player.getUniqueId(), target.getUniqueId());
        player.sendMessage("§a" + target.getName() + " に取引を申し込みました。");
        target.sendMessage("§e" + player.getName() + " から取引申請があります。§a/trade " + player.getName() + " §eで承認できます。");
        return true;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        TradeSession session = sessions.get(event.getWhoClicked().getUniqueId());
        if (session == null || !TITLE.equals(event.getView().getTitle())) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            if (event.isShiftClick()) {
                event.setCancelled(true);
            } else {
                event.setCancelled(false);
            }
            return;
        }

        int slot = event.getRawSlot();
        if (slot == CONFIRM_SLOT) {
            confirm(session, player);
            return;
        }
        if (slot == CANCEL_SLOT) {
            cancel(session, "取引がキャンセルされました。");
            return;
        }
        if (LEFT_SLOTS.contains(slot)) {
            event.setCancelled(false);
            Bukkit.getScheduler().runTask(plugin, () -> {
                resetConfirmations(session);
                mirror(session);
            });
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        TradeSession session = sessions.get(event.getWhoClicked().getUniqueId());
        if (session == null || !TITLE.equals(event.getView().getTitle())) {
            return;
        }
        for (int slot : event.getRawSlots()) {
            if (slot < 54 && !LEFT_SLOTS.contains(slot)) {
                event.setCancelled(true);
                return;
            }
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            resetConfirmations(session);
            mirror(session);
        });
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        TradeSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null || session.closing) {
            return;
        }
        cancel(session, "取引画面が閉じられたため取引を中止しました。");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        TradeSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null) {
            cancel(session, "相手がログアウトしたため取引を中止しました。");
        }
        requests.entrySet().removeIf(entry -> entry.getKey().equals(event.getPlayer().getUniqueId()) || entry.getValue().equals(event.getPlayer().getUniqueId()));
    }

    private void openSession(Player first, Player second) {
        TradeSession session = new TradeSession(first, second, createInventory(first, second), createInventory(second, first));
        sessions.put(first.getUniqueId(), session);
        sessions.put(second.getUniqueId(), session);
        first.openInventory(session.inventoryOf(first));
        second.openInventory(session.inventoryOf(second));
        first.sendMessage("§a取引を開始しました。");
        second.sendMessage("§a取引を開始しました。");
    }

    private Inventory createInventory(Player self, Player other) {
        Inventory inventory = Bukkit.createInventory(self, 54, TITLE);
        ItemStack border = named(Material.BLACK_STAINED_GLASS_PANE, "§8境界線");
        for (int slot : BORDER_SLOTS) {
            inventory.setItem(slot, border);
        }
        inventory.setItem(CONFIRM_SLOT, named(Material.GREEN_WOOL, "§a取引確定"));
        inventory.setItem(CANCEL_SLOT, named(Material.RED_WOOL, "§cキャンセル"));
        inventory.setItem(STATUS_SELF_SLOT, named(Material.RED_WOOL, "§cあなた: 未確定"));
        inventory.setItem(STATUS_OTHER_SLOT, named(Material.RED_WOOL, "§c相手: 未確定"));
        inventory.setItem(48, named(Material.PAPER, "§fあなた: " + self.getName()));
        inventory.setItem(50, named(Material.PAPER, "§f相手: " + other.getName()));
        return inventory;
    }

    private void confirm(TradeSession session, Player player) {
        if (session.first.equals(player)) {
            session.firstConfirmed = true;
        } else {
            session.secondConfirmed = true;
        }
        updateStatus(session);
        if (session.firstConfirmed && session.secondConfirmed) {
            complete(session);
        }
    }

    private void complete(TradeSession session) {
        List<ItemStack> firstItems = items(session.firstInventory, LEFT_SLOTS);
        List<ItemStack> secondItems = items(session.secondInventory, LEFT_SLOTS);
        clear(session.firstInventory, LEFT_SLOTS);
        clear(session.secondInventory, LEFT_SLOTS);
        session.closing = true;
        sessions.remove(session.first.getUniqueId());
        sessions.remove(session.second.getUniqueId());
        session.first.closeInventory();
        session.second.closeInventory();
        give(session.second, firstItems);
        give(session.first, secondItems);
        session.first.sendMessage("§a取引が成立しました。");
        session.second.sendMessage("§a取引が成立しました。");
    }

    private void cancel(TradeSession session, String message) {
        List<ItemStack> firstItems = items(session.firstInventory, LEFT_SLOTS);
        List<ItemStack> secondItems = items(session.secondInventory, LEFT_SLOTS);
        clear(session.firstInventory, LEFT_SLOTS);
        clear(session.secondInventory, LEFT_SLOTS);
        session.closing = true;
        sessions.remove(session.first.getUniqueId());
        sessions.remove(session.second.getUniqueId());
        session.first.closeInventory();
        session.second.closeInventory();
        give(session.first, firstItems);
        give(session.second, secondItems);
        session.first.sendMessage("§c" + message);
        session.second.sendMessage("§c" + message);
    }

    private void mirror(TradeSession session) {
        copy(session.firstInventory, LEFT_SLOTS, session.secondInventory, RIGHT_SLOTS);
        copy(session.secondInventory, LEFT_SLOTS, session.firstInventory, RIGHT_SLOTS);
    }

    private void resetConfirmations(TradeSession session) {
        session.firstConfirmed = false;
        session.secondConfirmed = false;
        updateStatus(session);
    }

    private void updateStatus(TradeSession session) {
        session.firstInventory.setItem(STATUS_SELF_SLOT, named(session.firstConfirmed ? Material.GREEN_WOOL : Material.RED_WOOL, session.firstConfirmed ? "§aあなた: 確定" : "§cあなた: 未確定"));
        session.firstInventory.setItem(STATUS_OTHER_SLOT, named(session.secondConfirmed ? Material.GREEN_WOOL : Material.RED_WOOL, session.secondConfirmed ? "§a相手: 確定" : "§c相手: 未確定"));
        session.secondInventory.setItem(STATUS_SELF_SLOT, named(session.secondConfirmed ? Material.GREEN_WOOL : Material.RED_WOOL, session.secondConfirmed ? "§aあなた: 確定" : "§cあなた: 未確定"));
        session.secondInventory.setItem(STATUS_OTHER_SLOT, named(session.firstConfirmed ? Material.GREEN_WOOL : Material.RED_WOOL, session.firstConfirmed ? "§a相手: 確定" : "§c相手: 未確定"));
    }

    private void copy(Inventory from, Set<Integer> fromSlots, Inventory to, Set<Integer> toSlots) {
        List<Integer> source = new ArrayList<>(fromSlots);
        List<Integer> target = new ArrayList<>(toSlots);
        for (int index = 0; index < source.size(); index++) {
            ItemStack item = from.getItem(source.get(index));
            to.setItem(target.get(index), item == null ? null : item.clone());
        }
    }

    private List<ItemStack> items(Inventory inventory, Set<Integer> slots) {
        List<ItemStack> items = new ArrayList<>();
        for (int slot : slots) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && !item.getType().isAir()) {
                items.add(item.clone());
            }
        }
        return items;
    }

    private void clear(Inventory inventory, Set<Integer> slots) {
        for (int slot : slots) {
            inventory.setItem(slot, null);
        }
        for (int slot : RIGHT_SLOTS) {
            inventory.setItem(slot, null);
        }
    }

    private void give(Player player, List<ItemStack> items) {
        for (ItemStack item : items) {
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
            for (ItemStack drop : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }
    }

    private ItemStack named(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }

    private static final class TradeSession {
        private final Player first;
        private final Player second;
        private final Inventory firstInventory;
        private final Inventory secondInventory;
        private boolean firstConfirmed;
        private boolean secondConfirmed;
        private boolean closing;

        private TradeSession(Player first, Player second, Inventory firstInventory, Inventory secondInventory) {
            this.first = first;
            this.second = second;
            this.firstInventory = firstInventory;
            this.secondInventory = secondInventory;
        }

        private Inventory inventoryOf(Player player) {
            return first.equals(player) ? firstInventory : secondInventory;
        }
    }
}
