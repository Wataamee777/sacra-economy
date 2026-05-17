package sc.sacra.economy;

import cz.woblex.qualityarmors.api.QualityArmors;
import org.bukkit.Material;
import org.bukkit.Tag;
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
import java.math.RoundingMode;

public final class ShopListener implements Listener {
    private final JavaPlugin plugin;
    private final MySqlEconomyStore store;
    private final CommandFeedback feedback;
    private final boolean hasQualityArmors;

    public ShopListener(JavaPlugin plugin, MySqlEconomyStore store, CommandFeedback feedback) {
        this.plugin = plugin;
        this.store = store;
        this.feedback = feedback;
        this.hasQualityArmors = plugin.getServer().getPluginManager().isPluginEnabled("QualityArmors");
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

        if (event.getClickedInventory() == event.getView().getTopInventory() ||
            event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT) {
            
            sellAll(player, clicked);
        }
    }

    private void sellAll(Player player, ItemStack itemStack) {
        String itemId = getItemIdentifier(itemStack);
        ItemGenre genre = detectGenre(itemStack);
        BigDecimal basePrice = calculateBasePrice(itemStack);

        if (basePrice.compareTo(BigDecimal.ZERO) <= 0 || genre == ItemGenre.UNKNOWN) {
            feedback.send(player, "§cこのアイテムはショップでは買い取れません。");
            return;
        }

        int amount = countItems(player, itemStack, itemId);
        if (amount <= 0) {
            feedback.send(player, "§c対象のアイテムを所持していません。");
            return;
        }

        // 1. DBから「累積売却数」と「コマンド等による個別補正倍率」を非同期で一括取得
        store.getSalesAndModifier(itemId).whenComplete((data, throwable) -> {
            if (throwable != null) {
                plugin.getServer().getScheduler().runTask(plugin, () -> feedback.send(player, "§c相場データの取得に失敗しました。"));
                return;
            }

            int currentSales = data.getSales();
            BigDecimal commandModifier = data.getModifier(); // コマンド側の補正

            // 2. 需要と供給、および発展段階（序盤・無限化）に応じた最終単価を計算
            BigDecimal finalUnitPrice = calculateFinalPrice(basePrice, genre, currentSales, commandModifier);
            BigDecimal totalReward = finalUnitPrice.multiply(BigDecimal.valueOf(amount)).setScale(2, RoundingMode.HALF_UP);

            // 3. お金追加 ＆ 売却カウントをDB上で進める
            store.addSystemReward(player.getName(), totalReward, "SHOP_SELL", "ショップ売却: %s x%d".formatted(itemId, amount))
                .thenCompose(result -> store.addSalesCount(itemId, amount))
                .whenComplete((finalResult, dbThrowable) -> {
                    
                    // Bukkitのインベントリ操作とメッセージ送信はメインスレッドへ
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (dbThrowable != null) {
                            feedback.send(player, "§c決済処理中にエラーが発生しました。アイテムは保護されました。");
                            return;
                        }

                        // 4. すべてのDB整合性が取れた後に、安全にアイテムを消去（増殖・ロスト防止）
                        int actualRemoved = executeRemoveAll(player, itemId);

                        feedback.send(player, "§a【%s】%s を %d 個売却しました。".formatted(genre.getDisplayName(), itemId, actualRemoved));
                        
                        // 補正状態に合わせたメッセージ分岐
                        String phaseText = currentSales < genre.getStartPhaseLimit() ? " §b(序盤ボーナス +10%)" : 
                                           currentSales > genre.getInfinitePhaseLimit() ? " §4(無限化ペナルティ -20%)" : "";
                        
                        feedback.send(player, "§e現在の買取単価: %s 円%s".formatted(finalUnitPrice.toPlainString(), phaseText));
                        feedback.send(player, "§6合計獲得金額: +%s 円".formatted(totalReward.toPlainString()));
                    });
                });
        });
    }

    /**
     * 変動相場 ＋ 発展段階フェーズ自動補正 ＋ コマンド補正のトリプル計算
     */
    private BigDecimal calculateFinalPrice(BigDecimal basePrice, ItemGenre genre, int currentSales, BigDecimal commandModifier) {
        // ① 基本の反比例変動相場: BasePrice * (HalfLife / (HalfLife + Sales))
        BigDecimal hLife = BigDecimal.valueOf(genre.getPriceHalfLife());
        BigDecimal sales = BigDecimal.valueOf(currentSales);
        BigDecimal denominator = hLife.add(sales);
        BigDecimal marketMultiplier = hLife.divide(denominator, 4, RoundingMode.HALF_UP);
        BigDecimal dynamicPrice = basePrice.multiply(marketMultiplier);

        // ② サーバーの資源状況（フェーズ）による自動補正
        BigDecimal phaseModifier = BigDecimal.ONE;
        if (currentSales < genre.getStartPhaseLimit()) {
            phaseModifier = BigDecimal.valueOf(1.10); // 序盤は+10%
        } else if (currentSales > genre.getInfinitePhaseLimit()) {
            phaseModifier = BigDecimal.valueOf(0.80); // 無限化（トラップ化）後は-20%
        }

        // ③ すべてを掛け合わせる (動的相場 × フェーズ補正 × コマンド個別補正)
        return dynamicPrice.multiply(phaseModifier).multiply(commandModifier).setScale(2, RoundingMode.HALF_UP);
    }

    private String getItemIdentifier(ItemStack item) {
        if (hasQualityArmors) {
            if (QualityArmors.isGun(item)) return "QA_" + QualityArmors.getGunObject(item).getName();
            if (QualityArmors.isArmor(item)) return "QA_" + QualityArmors.getArmorObject(item).getName();
        }
        return item.getType().name();
    }

    private BigDecimal calculateBasePrice(ItemStack item) {
        if (hasQualityArmors) {
            if (QualityArmors.isGun(item)) {
                return BigDecimal.valueOf(Math.max(0, QualityArmors.getGunObject(item).getPrice() - 100.0));
            }
            if (QualityArmors.isArmor(item)) {
                return BigDecimal.valueOf(Math.max(0, QualityArmors.getArmorObject(item).getPrice() - 100.0));
            }
        }

        // バニラアイテムの規定最高値（※環境に合わせて自由に変更、または外部configから呼ぶ形にしてください）
        Material mat = item.getType();
        if (mat == Material.DIAMOND) return BigDecimal.valueOf(1000.0);
        if (mat == Material.IRON_INGOT) return BigDecimal.valueOf(150.0);
        if (mat == Material.GOLD_INGOT) return BigDecimal.valueOf(300.0);
        if (mat.name().contains("LOG")) return BigDecimal.valueOf(20.0);
        if (mat.isEdible()) return BigDecimal.valueOf(10.0);

        return BigDecimal.ZERO;
    }

    private ItemGenre detectGenre(ItemStack item) {
        if (hasQualityArmors && (QualityArmors.isGun(item) || QualityArmors.isArmor(item))) {
            return ItemGenre.QUALITY_ARMORS;
        }

        Material mat = item.getType();
        if (mat.name().contains("ORE") || mat.name().contains("INGOT") || mat == Material.DIAMOND || mat == Material.EMERALD) {
            return ItemGenre.MINERAL;
        }
        if (mat.isEdible() || mat == Material.WHEAT || mat == Material.SUGAR_CANE || Tag.CROPS.isTagged(mat)) {
            return ItemGenre.FARM;
        }
        if (Tag.LOGS.isTagged(mat) || Tag.PLANKS.isTagged(mat) || mat == Material.COBBLESTONE) {
            return ItemGenre.BUILDING;
        }
        return ItemGenre.UNKNOWN;
    }

    private int countItems(Player player, ItemStack target, String targetId) {
        int count = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && getItemIdentifier(item).equals(targetId)) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private int executeRemoveAll(Player player, String targetId) {
        int removed = 0;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item != null && getItemIdentifier(item).equals(targetId)) {
                removed += item.getAmount();
                contents[i] = null;
            }
        }
        player.getInventory().setStorageContents(contents);
        
        if (player.getItemOnCursor() != null && getItemIdentifier(player.getItemOnCursor()).equals(targetId)) {
            removed += player.getItemOnCursor().getAmount();
            player.setItemOnCursor(null);
        }
        return removed;
    }

    /**
     * ジャンルごとの経済パラメータ定義
     */
    public enum ItemGenre {
        QUALITY_ARMORS("特製武具", 300, 10, 150),      // 武具: 流通が少ないためすぐ価格が落ちる
        MINERAL("鉱石素材", 2000, 500, 30000),         // 鉱石: 500個まで序盤ボーナス、3万個（トラップ化）でペナルティ
        FARM("農作物・食料", 8000, 1000, 100000),      // 農業: 自動化しやすいため許容量を高めに設定
        BUILDING("建築ブロック", 20000, 2000, 500000), // 建築: 超大量に出回る前提のバランス
        UNKNOWN("未分類", 0, 0, 0);

        private final String displayName;
        private final int priceHalfLife;      // 価格が半分になる累積売却数
        private final int startPhaseLimit;    // 序盤ボーナス（+10%）がもらえる上限売却数
        private final int infinitePhaseLimit; // 無限化ペナルティ（-20%）に突入する売却数

        ItemGenre(String displayName, int priceHalfLife, int startPhaseLimit, int infinitePhaseLimit) {
            this.displayName = displayName;
            this.priceHalfLife = priceHalfLife;
            this.startPhaseLimit = startPhaseLimit;
            this.infinitePhaseLimit = infinitePhaseLimit;
        }

        public String getDisplayName() { return displayName; }
        public int getPriceHalfLife() { return priceHalfLife; }
        public int getStartPhaseLimit() { return startPhaseLimit; }
        public int getInfinitePhaseLimit() { return infinitePhaseLimit; }
    }
}
