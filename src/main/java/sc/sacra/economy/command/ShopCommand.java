package sc.sacra.economy.command;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import java.util.ArrayList;
import java.util.List;

public final class ShopCommand implements CommandExecutor {
    public static final String TITLE = "サーバーショップ";
    public static final int SIZE = 54;
    public static final int CONFIRM_SLOT = 45;
    public static final int CANCEL_SLOT = 53;
    public static final int INFO_SLOT = 49;

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("このコマンドはプレイヤーのみ実行できます。");
            return true;
        }
        Inventory inventory = Bukkit.createInventory(player, SIZE, TITLE);
        inventory.setItem(CONFIRM_SLOT, button(Material.GREEN_WOOL, "§a売却確定", "§7投入したアイテムを全て売却"));
        inventory.setItem(CANCEL_SLOT, button(Material.RED_WOOL, "§cキャンセル", "§7投入アイテムを返却して閉じる"));
        inventory.setItem(INFO_SLOT, button(Material.BOOK, "§e売却ガイド", "§7上段5行に売りたいアイテムを置いてください", "§7価格設定は config.yml の shop.sell-prices です"));
        player.openInventory(inventory);
        return true;
    }

    private ItemStack button(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(List.of(lore));
        item.setItemMeta(meta);
        return item;
    }
}
