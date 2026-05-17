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
    private final JavaPlugin plugin;

    public ShopCommand(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("このコマンドはプレイヤーのみ実行できます。");
            return true;
        }
        Inventory inventory = Bukkit.createInventory(player, 9, TITLE);
        List<Material> materials = sellableMaterials();
        for (int slot = 0; slot < Math.min(9, materials.size()); slot++) {
            inventory.setItem(slot, displayItem(materials.get(slot)));
        }
        player.openInventory(inventory);
        return true;
    }

    private List<Material> sellableMaterials() {
        List<Material> materials = new ArrayList<>();
        var section = plugin.getConfig().getConfigurationSection("shop.sell-prices");
        if (section == null) {
            materials.add(Material.COBBLESTONE);
            return materials;
        }
        for (String key : section.getKeys(false)) {
            Material material = Material.matchMaterial(key);
            if (material != null && material.isItem()) {
                materials.add(material);
            }
        }
        if (!materials.contains(Material.COBBLESTONE)) {
            materials.addFirst(Material.COBBLESTONE);
        }
        return materials;
    }

    private ItemStack displayItem(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§a" + material.name());
        meta.setLore(List.of("§7クリックまたはシフトクリックで売却"));
        item.setItemMeta(meta);
        return item;
    }
}
