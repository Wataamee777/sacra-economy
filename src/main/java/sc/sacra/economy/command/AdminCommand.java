package sc.sacra.economy.command;

import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import sc.sacra.economy.CommandFeedback;
import sc.sacra.economy.db.MySqlEconomyStore;
import java.math.BigDecimal;

public final class AdminCommand implements CommandExecutor {
    private final MySqlEconomyStore store;
    private final CommandFeedback feedback;

    public AdminCommand(MySqlEconomyStore store, CommandFeedback feedback) {
        this.store = store;
        this.feedback = feedback;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!sender.isOp()) {
            feedback.send(sender, "§cこのコマンドはOP専用です。");
            return true;
        }
        if (args.length < 2) {
            usage(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "money" -> money(sender, args);
            case "company" -> company(sender, args);
            case "code" -> code(sender, args);
            default -> usage(sender);
        }
        return true;
    }

    private void money(CommandSender sender, String[] args) {
        switch (args[1].toLowerCase()) {
            case "add" -> {
                if (args.length < 4) {
                    usage(sender);
                    return;
                }
                CommandParsers.positiveMoney(args[3]).ifPresentOrElse(
                        amount -> feedback.handle(sender, store.adminAddMoney(sender.getName(), args[2], amount, reason(args, 4))),
                        () -> feedback.send(sender, "金額は0より大きい数字で指定してください。"));
            }
            case "remove" -> {
                if (args.length < 4) {
                    usage(sender);
                    return;
                }
                CommandParsers.positiveMoney(args[3]).ifPresentOrElse(
                        amount -> feedback.handle(sender, store.adminRemoveMoney(sender.getName(), args[2], amount, reason(args, 4))),
                        () -> feedback.send(sender, "金額は0より大きい数字で指定してください。"));
            }
            default -> usage(sender);
        }
    }

    private void company(CommandSender sender, String[] args) {
        switch (args[1].toLowerCase()) {
            case "add" -> {
                if (args.length == 4) {
                    feedback.handle(sender, store.forceCreateCompany(args[2], args[3]));
                } else {
                    usage(sender);
                }
            }
            case "delete" -> {
                if (args.length == 3) {
                    feedback.handle(sender, store.deleteCompany(args[2]));
                } else {
                    usage(sender);
                }
            }
            default -> usage(sender);
        }
    }

    private void code(CommandSender sender, String[] args) {
        if (!args[1].equalsIgnoreCase("set") || args.length < 3) {
            usage(sender);
            return;
        }
        CommandParsers.positiveMoney(args[2]).ifPresentOrElse(
                amount -> feedback.handle(sender, store.createGiveawayCode(sender.getName(), amount, reason(args, 3))),
                () -> feedback.send(sender, "金額は0より大きい数字で指定してください。"));
    }

    private String reason(String[] args, int startIndex) {
        return args.length <= startIndex ? "理由なし" : String.join(" ", java.util.Arrays.copyOfRange(args, startIndex, args.length));
    }

    private void usage(CommandSender sender) {
        feedback.send(sender, "§e/admin money add [MCID] [金額] [名目] §7/ §e/admin money remove [MCID] [金額] [名目] §7/ §e/admin code set [金額] §7/ §e/admin company add [社長MCID] [会社名] §7/ §e/admin company delete [会社名]");
    }
}
