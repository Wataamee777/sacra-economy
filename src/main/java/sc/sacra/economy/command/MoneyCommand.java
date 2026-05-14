package sc.sacra.economy.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import sc.sacra.economy.CommandFeedback;
import sc.sacra.economy.db.MySqlEconomyStore;

import java.math.BigDecimal;

public final class MoneyCommand implements CommandExecutor {
    private final MySqlEconomyStore store;
    private final CommandFeedback feedback;

    public MoneyCommand(MySqlEconomyStore store, CommandFeedback feedback) {
        this.store = store;
        this.feedback = feedback;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("このコマンドはプレイヤーのみ実行できます。");
            return true;
        }
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "pay" -> pay(sender, player, args);
            case "rank" -> rank(sender, player, args);
            case "giveaway" -> giveaway(sender, player, args);
            default -> usage(sender);
        }
        return true;
    }

    private void pay(CommandSender sender, Player player, String[] args) {
        if (args.length != 3) {
            feedback.send(sender, "§e/money pay [プレイヤーのMCID] [金額]");
            return;
        }
        CommandParsers.positiveMoney(args[2]).ifPresentOrElse(
                amount -> feedback.handle(sender, store.transfer(player.getName(), args[1], amount)),
                () -> feedback.send(sender, "金額は0より大きい数字で指定してください。"));
    }

    private void rank(CommandSender sender, Player player, String[] args) {
        if (args.length > 2) {
            feedback.send(sender, "§e/money rank §7または §e/money rank [プレイヤーのMCID]");
            return;
        }
        String target = args.length == 2 ? args[1] : player.getName();
        feedback.handle(sender, store.rankOf(target), entry -> entry
                .map(rank -> "%s の経済ランキング順位: %d位（残高: %s円）".formatted(rank.mcid(), rank.rank(), MySqlEconomyStore.format(rank.balance())))
                .orElse("対象プレイヤーの口座が見つかりません。"));
    }

    private void giveaway(CommandSender sender, Player player, String[] args) {
        if (args.length != 2) {
            feedback.send(sender, "§e/money giveaway [コード]");
            return;
        }
        feedback.handle(sender, store.redeemCode(player.getName(), args[1]));
    }

    private void usage(CommandSender sender) {
        feedback.send(sender, "§e/money pay [MCID] [金額] §7/ §e/money rank [MCID] §7/ §e/money giveaway [コード]");
    }
}
