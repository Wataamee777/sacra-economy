package sc.sacra.economy.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import sc.sacra.economy.CommandFeedback;
import sc.sacra.economy.db.MySqlEconomyStore;

public final class CompanyCommand implements CommandExecutor {
    private final MySqlEconomyStore store;
    private final CommandFeedback feedback;

    public CompanyCommand(MySqlEconomyStore store, CommandFeedback feedback) {
        this.store = store;
        this.feedback = feedback;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("このコマンドはプレイヤーのみ実行できます。");
            return true;
        }
        if (args.length != 2) {
            feedback.send(sender, "§e/company new [会社名] §7または §e/company del [会社名]");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "new" -> feedback.handle(sender, store.createCompany(player.getName(), args[1]));
            case "del", "delete" -> feedback.handle(sender, store.deleteCompany(args[1]));
            default -> feedback.send(sender, "§e/company new [会社名] §7または §e/company del [会社名]");
        }
        return true;
    }
}
