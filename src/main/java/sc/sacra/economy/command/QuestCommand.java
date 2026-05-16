package sc.sacra.economy.command;

import io.papermc.paper.advancement.AdvancementDisplay;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import sc.sacra.economy.CommandFeedback;
import sc.sacra.economy.db.MySqlEconomyStore;
import sc.sacra.economy.model.QuestReward;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class QuestCommand implements CommandExecutor {
    private static final String CONFIG_PREFIX = "quest.rewards";

    private final JavaPlugin plugin;
    private final MySqlEconomyStore store;
    private final CommandFeedback feedback;

    public QuestCommand(JavaPlugin plugin, MySqlEconomyStore store, CommandFeedback feedback) {
        this.plugin = plugin;
        this.store = store;
        this.feedback = feedback;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("このコマンドはプレイヤーのみ実行できます。");
            return true;
        }
        if (args.length < 1 || args.length > 2 || !args[0].equalsIgnoreCase("claim")) {
            usage(sender);
            return true;
        }

        if (args.length == 1) {
            claimAll(sender, player);
            return true;
        }

        Optional<Advancement> advancement = resolveAdvancement(args[1]);
        if (advancement.isEmpty()) {
            feedback.send(sender, "実績IDが見つかりません: " + args[1]);
            return true;
        }

        Optional<QuestReward> reward = rewardFor(player, advancement.get());
        if (reward.isEmpty()) {
            feedback.send(sender, "その実績は未解除、または報酬対象外です: " + advancement.get().getKey());
            return true;
        }

        QuestReward questReward = reward.get();
        feedback.handle(sender, store.claimQuestReward(player.getName(), questReward.advancementKey(), questReward.level(), questReward.reward()));
        return true;
    }

    private void claimAll(CommandSender sender, Player player) {
        List<QuestReward> rewards = new ArrayList<>();
        Iterator<Advancement> iterator = Bukkit.advancementIterator();
        while (iterator.hasNext()) {
            rewardFor(player, iterator.next()).ifPresent(rewards::add);
        }

        if (rewards.isEmpty()) {
            feedback.send(sender, "受け取り可能な解除済み実績報酬はありません。");
            return;
        }
        feedback.handle(sender, store.claimQuestRewards(player.getName(), rewards));
    }

    private Optional<QuestReward> rewardFor(Player player, Advancement advancement) {
        AdvancementProgress progress = player.getAdvancementProgress(advancement);
        if (!progress.isDone()) {
            return Optional.empty();
        }

        AdvancementDisplay display = advancement.getDisplay();
        if (display == null) {
            return Optional.empty();
        }

        AdvancementDisplay.Frame frame = display.frame();
        BigDecimal reward = rewardFor(frame);
        if (reward.signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(new QuestReward(advancement.getKey().asString(), frame.name(), reward));
    }

    private Optional<Advancement> resolveAdvancement(String rawId) {
        NamespacedKey directKey = NamespacedKey.fromString(rawId);
        if (directKey != null) {
            Advancement direct = Bukkit.getAdvancement(directKey);
            if (direct != null) {
                return Optional.of(direct);
            }
        }

        NamespacedKey minecraftKey = NamespacedKey.minecraft(rawId);
        Advancement directMinecraft = Bukkit.getAdvancement(minecraftKey);
        if (directMinecraft != null) {
            return Optional.of(directMinecraft);
        }

        Iterator<Advancement> iterator = Bukkit.advancementIterator();
        while (iterator.hasNext()) {
            Advancement advancement = iterator.next();
            String key = advancement.getKey().getKey();
            if (key.equalsIgnoreCase(rawId) || key.endsWith("/" + rawId)) {
                return Optional.of(advancement);
            }
        }
        return Optional.empty();
    }

    private BigDecimal rewardFor(AdvancementDisplay.Frame frame) {
        FileConfiguration config = plugin.getConfig();
        String key = switch (frame) {
            case TASK -> "task";
            case GOAL -> "goal";
            case CHALLENGE -> "challenge";
        };
        String defaultValue = switch (frame) {
            case TASK -> "100";
            case GOAL -> "500";
            case CHALLENGE -> "1000";
        };
        try {
            return MySqlEconomyStore.money(config.getString(CONFIG_PREFIX + "." + key, defaultValue));
        } catch (NumberFormatException exception) {
            plugin.getLogger().warning("quest.rewards.%s が不正です。デフォルト%s円を使用します。".formatted(key, defaultValue));
            return MySqlEconomyStore.money(defaultValue);
        }
    }

    private void usage(CommandSender sender) {
        feedback.send(sender, "§e/quest claim [実績ID] §7または §e/quest claim §7で全解除済み実績を一括受取");
    }
}
