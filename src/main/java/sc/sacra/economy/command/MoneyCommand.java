package sc.sacra.economy.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import sc.sacra.economy.CommandFeedback;
import sc.sacra.economy.db.MySqlEconomyStore;
import sc.sacra.economy.model.OperationLogEntry;
import sc.sacra.economy.model.RankEntry;

import java.time.format.DateTimeFormatter;
import java.util.List;

public final class MoneyCommand implements CommandExecutor {
    private static final int DEFAULT_HISTORY_LIMIT = 5;
    private static final int MAX_HISTORY_LIMIT = 50;
    private static final DateTimeFormatter HISTORY_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

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
            case "ranking" -> ranking(sender, args);
            case "history" -> history(sender, player, args);
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

    private void ranking(CommandSender sender, String[] args) {
        if (args.length != 1) {
            feedback.send(sender, "§e/money ranking");
            return;
        }
        feedback.handle(sender, store.topRankings(5), this::formatRanking);
    }

    private void history(CommandSender sender, Player player, String[] args) {
        if (args.length > 2) {
            feedback.send(sender, "§e/money history [件数]");
            return;
        }
        parseHistoryLimit(args).ifPresentOrElse(
                limit -> feedback.handle(sender, store.historyOf(player.getName(), limit), history -> formatHistory(player.getName(), history, limit)),
                () -> feedback.send(sender, "件数は1〜%dの整数で指定してください。".formatted(MAX_HISTORY_LIMIT)));
    }

    private java.util.Optional<Integer> parseHistoryLimit(String[] args) {
        if (args.length == 1) {
            return java.util.Optional.of(DEFAULT_HISTORY_LIMIT);
        }
        try {
            int limit = Integer.parseInt(args[1]);
            return limit >= 1 && limit <= MAX_HISTORY_LIMIT ? java.util.Optional.of(limit) : java.util.Optional.empty();
        } catch (NumberFormatException exception) {
            return java.util.Optional.empty();
        }
    }

    private void giveaway(CommandSender sender, Player player, String[] args) {
        if (args.length != 2) {
            feedback.send(sender, "§e/money giveaway [コード]");
            return;
        }
        feedback.handle(sender, store.redeemCode(player.getName(), args[1]));
    }

    private String formatRanking(List<RankEntry> rankings) {
        if (rankings.isEmpty()) {
            return "経済ランキングに表示できる口座がありません。";
        }
        StringBuilder builder = new StringBuilder("§a経済ランキング 上位5位");
        for (RankEntry ranking : rankings) {
            builder.append("\n§e")
                    .append(ranking.rank())
                    .append("位 §f")
                    .append(ranking.mcid())
                    .append(" §7- §b")
                    .append(MySqlEconomyStore.format(ranking.balance()))
                    .append("円");
        }
        return builder.toString();
    }

    private String formatHistory(String mcid, List<OperationLogEntry> history, int limit) {
        if (history.isEmpty()) {
            return "%s の操作ログはありません。".formatted(mcid);
        }
        StringBuilder builder = new StringBuilder("§a%s の操作ログ 最新%d件".formatted(mcid, limit));
        for (OperationLogEntry entry : history) {
            builder.append("\n§7[")
                    .append(entry.createdAt().format(HISTORY_TIME_FORMAT))
                    .append("] §e")
                    .append(entry.action())
                    .append(" §f")
                    .append(entry.amount() == null ? "-" : MySqlEconomyStore.format(entry.amount()) + "円")
                    .append(" §7実行者: ")
                    .append(entry.actorMcid())
                    .append(" / 対象: ")
                    .append(entry.targetMcid() == null ? "-" : entry.targetMcid())
                    .append(" / 名目: ")
                    .append(entry.reason());
        }
        return builder.toString();
    }

    private void usage(CommandSender sender) {
        feedback.send(sender, "§e/money pay [MCID] [金額] §7/ §e/money rank [MCID] §7/ §e/money ranking §7/ §e/money history [件数] §7/ §e/money giveaway [コード]");
    }
}
