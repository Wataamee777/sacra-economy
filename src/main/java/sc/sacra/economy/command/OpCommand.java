package sc.sacra.economy.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import sc.sacra.economy.CommandFeedback;
import sc.sacra.economy.db.MySqlEconomyStore;

public final class OpCommand implements CommandExecutor {
    private final MySqlEconomyStore store;
    private final CommandFeedback feedback;

    public OpCommand(MySqlEconomyStore store, CommandFeedback feedback) {
        this.store = store;
        this.feedback = feedback;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!sender.isOp()) {
            feedback.send(sender, "§cこのコマンドはOP専用です。");
            return true;
        }
        if (args.length < 3) {
            usage(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "money" -> money(sender, args);
            case "company" -> company(sender, args);
            default -> usage(sender);
        }
        return true;
    }

    private void money(CommandSender sender, String[] args) {
        switch (args[1].toLowerCase()) {
            case "add" -> {
                if (args.length != 4) {
                    usage(sender);
                    return;
                }
                CommandParsers.positiveMoney(args[3]).ifPresentOrElse(
                        amount -> feedback.handle(sender, store.addMoney(args[2], amount)),
                        () -> feedback.send(sender, "金額は0より大きい数字で指定してください。"));
            }
            case "delete" -> {
                if (args.length == 3) {
                    feedback.handle(sender, store.deleteAccount(args[2]));
                } else {
                    usage(sender);
                }
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

    private void usage(CommandSender sender) {
        feedback.send(sender, "§e/op money add [MCID] [金額] §7/ §e/op money delete [MCID] §7/ §e/op company add [社長MCID] [会社名] §7/ §e/op company delete [会社名]");
    }
}
