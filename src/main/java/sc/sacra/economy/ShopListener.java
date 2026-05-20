package sc.sacra.economy;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import sc.sacra.economy.command.ShopCommand;
import sc.sacra.economy.db.MySqlEconomyStore;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.Set;

public final class ShopListener implements Listener {
    private static final Set<Integer> SELL_SLOTS = Set.of(
            0, 1, 2, 3, 4, 5, 6, 7, 8,
            9, 10, 11, 12, 13, 14, 15, 16, 17,
            18, 19, 20, 21, 22, 23, 24, 25, 26,
            27, 28, 29, 30, 31, 32, 33, 34, 35,
            36, 37, 38, 39, 40, 41, 42, 43, 44
    );

    private final JavaPlugin plugin;
    private final MySqlEconomyStore store;
    private final CommandFeedback feedback;

    public ShopListener(JavaPlugin plugin, MySqlEconomyStore store, CommandFeedback feedback) {
        this.plugin = plugin;
        this.store = store;
        this.feedback = feedback;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!ShopCommand.TITLE.equals(event.getView().getTitle())) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        int rawSlot = event.getRawSlot();
        if (rawSlot >= ShopCommand.SIZE) {
            return;
        }

        if (rawSlot == ShopCommand.CONFIRM_SLOT) {
            event.setCancelled(true);
            sellAll(player, event.getView().getTopInventory());
            return;
        }
        if (rawSlot == ShopCommand.CANCEL_SLOT) {
            event.setCancelled(true);
            player.closeInventory();
            feedback.send(player, "売却をキャンセルしました。投入アイテムを返却します。");
            return;
        }

        if (!SELL_SLOTS.contains(rawSlot)) {
            event.setCancelled(true);
            return;
        }

        if (event.getClickedInventory() == event.getView().getTopInventory()) {
            event.setCancelled(false);
            return;
        }

        if (event.isShiftClick()) {
            event.setCancelled(true);
            ItemStack item = event.getCurrentItem();
            if (item != null && !item.getType().isAir()) {
                moveOneStackToShop(event.getView().getTopInventory(), item.clone());
                item.setAmount(0);
            }
            return;
        }
        event.setCancelled(false);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!ShopCommand.TITLE.equals(event.getView().getTitle())) {
            return;
        }
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < ShopCommand.SIZE && !SELL_SLOTS.contains(rawSlot)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!ShopCommand.TITLE.equals(event.getView().getTitle())) {
            return;
        }
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        for (int slot : SELL_SLOTS) {
            ItemStack item = top.getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            var leftover = player.getInventory().addItem(item);
            for (ItemStack drop : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
            top.setItem(slot, null);
        }
    }

    private void moveOneStackToShop(Inventory shopInventory, ItemStack stack) {
        for (int slot : SELL_SLOTS) {
            ItemStack current = shopInventory.getItem(slot);
            if (current == null || current.getType().isAir()) {
                shopInventory.setItem(slot, stack);
                return;
            }
        }
    }

    private void sellAll(Player player, Inventory shopInventory) {
        BigDecimal total = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        int soldStacks = 0;
        for (int slot : SELL_SLOTS) {
            ItemStack item = shopInventory.getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            BigDecimal unitPrice = priceOf(item.getType());
            if (unitPrice.signum() <= 0) {
                continue;
            }
            total = total.add(unitPrice.multiply(BigDecimal.valueOf(item.getAmount())));
            soldStacks += item.getAmount();
            shopInventory.setItem(slot, null);
        }
        if (soldStacks <= 0 || total.signum() <= 0) {
            feedback.send(player, "売却可能なアイテムがありません。価格未設定アイテムは返却されます。");
            return;
        }
        BigDecimal rounded = total.setScale(2, RoundingMode.HALF_UP);
        feedback.handle(player, store.addSystemReward(player.getName(), rounded, "SHOP_SELL", "ショップ売却 合計数量: " + soldStacks));
    }

    private BigDecimal priceOf(Material material) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("shop.sell-prices");
        String raw = section == null ? null : section.getString(material.name());
        if (raw == null) {
            raw = plugin.getConfig().getString("shop.default-sell-price", "0.1");
        }
        try {
            return MySqlEconomyStore.money(raw);
        } catch (NumberFormatException exception) {
            plugin.getLogger().warning("shop price が不正です material=%s value=%s".formatted(material.name(), raw));
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
    }
}
