package sc.sacra.economy;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import sc.sacra.economy.command.ShopCommand;
import sc.sacra.economy.db.MySqlEconomyStore;

import java.math.BigDecimal;

public final class ShopListener implements Listener {
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
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) {
            return;
        }

        if (event.getClickedInventory() == event.getView().getTopInventory()) {
            sellAll(player, clicked.getType());
            return;
        }

        if (event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT) {
            sellAll(player, clicked.getType());
        }
    }

    private void sellAll(Player player, Material material) {
        BigDecimal price = priceOf(material);
        if (price.signum() <= 0) {
            feedback.send(player, "このアイテムは売却できません。");
            return;
        }
        int amount = removeAll(player, material);
        if (amount <= 0) {
            feedback.send(player, material.name() + "を所持していません。");
            return;
        }
        BigDecimal total = price.multiply(BigDecimal.valueOf(amount)).setScale(2, java.math.RoundingMode.HALF_UP);
        feedback.handle(player, store.addSystemReward(player.getName(), total, "SHOP_SELL", "ショップ売却: %s x%d".formatted(material.name(), amount)));
    }

    private BigDecimal priceOf(Material material) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("shop.sell-prices");
        if (section == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        String raw = section.getString(material.name());
        if (raw == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        try {
            return MySqlEconomyStore.money(raw);
        } catch (NumberFormatException exception) {
            plugin.getLogger().warning("shop.sell-prices.%s が不正です: %s".formatted(material.name(), raw));
            return BigDecimal.ZERO.setScale(2);
        }
    }

    private int removeAll(Player player, Material material) {
        int removed = 0;
        var contents = player.getInventory().getStorageContents();
        for (int index = 0; index < contents.length; index++) {
            ItemStack item = contents[index];
            if (item == null || item.getType() != material) {
                continue;
            }
            removed += item.getAmount();
            contents[index] = null;
        }
        player.getInventory().setStorageContents(contents);
        return removed;
    }
}
