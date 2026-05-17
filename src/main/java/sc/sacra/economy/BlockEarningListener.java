package sc.sacra.economy;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import sc.sacra.economy.db.MySqlEconomyStore;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class BlockEarningListener implements Listener {
    private final JavaPlugin plugin;
    private final MySqlEconomyStore store;
    private final NamespacedKey placedBlocksKey;

    public BlockEarningListener(JavaPlugin plugin, MySqlEconomyStore store) {
        this.plugin = plugin;
        this.store = store;
        this.placedBlocksKey = new NamespacedKey(plugin, "is_placed_blocks");
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockPlace(BlockPlaceEvent event) {
        markPlaced(event.getBlockPlaced(), true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (isPlaced(block)) {
            markPlaced(block, false);
            return;
        }

        rewardFor(block.getType()).ifPresent(reward -> store.addSystemReward(
                event.getPlayer().getName(),
                reward,
                "BLOCK_BREAK_REWARD",
                "ブロック破壊報酬: " + block.getType().name()));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        movePlacedMarks(event.getBlocks(), event.getDirection());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        movePlacedMarks(event.getBlocks(), event.getDirection());
    }

    private java.util.Optional<BigDecimal> rewardFor(Material material) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("block-rewards.break");
        if (section == null) {
            return java.util.Optional.empty();
        }
        String raw = section.getString(material.name());
        if (raw == null) {
            return java.util.Optional.empty();
        }
        try {
            BigDecimal reward = MySqlEconomyStore.money(raw);
            return reward.signum() > 0 ? java.util.Optional.of(reward) : java.util.Optional.empty();
        } catch (NumberFormatException exception) {
            plugin.getLogger().warning("block-rewards.break.%s が不正です: %s".formatted(material.name(), raw));
            return java.util.Optional.empty();
        }
    }

    private void movePlacedMarks(java.util.List<Block> blocks, BlockFace direction) {
        Set<Block> placed = new HashSet<>();
        for (Block block : blocks) {
            if (isPlaced(block)) {
                placed.add(block);
            }
        }
        for (Block block : placed) {
            markPlaced(block, false);
        }
        for (Block block : placed) {
            markPlaced(block.getRelative(direction), true);
        }
    }

    private boolean isPlaced(Block block) {
        return placedSet(block.getChunk()).contains(encoded(block));
    }

    private void markPlaced(Block block, boolean placed) {
        Chunk chunk = block.getChunk();
        Set<String> values = placedSet(chunk);
        String encoded = encoded(block);
        if (placed) {
            values.add(encoded);
        } else {
            values.remove(encoded);
        }
        savePlacedSet(chunk, values);
    }

    private Set<String> placedSet(Chunk chunk) {
        PersistentDataContainer container = chunk.getPersistentDataContainer();
        String raw = container.get(placedBlocksKey, PersistentDataType.STRING);
        Set<String> values = new HashSet<>();
        if (raw == null || raw.isBlank()) {
            return values;
        }
        for (String value : raw.split(";")) {
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private void savePlacedSet(Chunk chunk, Set<String> values) {
        PersistentDataContainer container = chunk.getPersistentDataContainer();
        if (values.isEmpty()) {
            container.remove(placedBlocksKey);
            return;
        }
        container.set(placedBlocksKey, PersistentDataType.STRING, String.join(";", values));
    }

    private String encoded(Block block) {
        return "%d,%d,%d".formatted(block.getX() & 15, block.getY(), block.getZ() & 15).toLowerCase(Locale.ROOT);
    }
}
