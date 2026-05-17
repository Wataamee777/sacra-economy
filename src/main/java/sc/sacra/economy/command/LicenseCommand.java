package sc.sacra.economy.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import sc.sacra.economy.CommandFeedback;
import sc.sacra.economy.db.MySqlEconomyStore;
import sc.sacra.economy.model.LicenseCategory;

public final class LicenseCommand implements CommandExecutor {
    private final MySqlEconomyStore store;
    private final CommandFeedback feedback;

    public LicenseCommand(MySqlEconomyStore store, CommandFeedback feedback) {
        this.store = store;
        this.feedback = feedback;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("このコマンドはプレイヤーのみ実行できます。");
            return true;
        }
        if (args.length != 2 || !args[0].equalsIgnoreCase("buy")) {
            feedback.send(sender, "§e/license buy [1〜5]");
            return true;
        }
        try {
            LicenseCategory.fromId(Integer.parseInt(args[1]))
                    .ifPresentOrElse(category -> feedback.handle(sender, store.buyLicense(player.getName(), category)),
                            () -> feedback.send(sender, "類は 1〜5 の数字で指定してください。"));
        } catch (NumberFormatException exception) {
            feedback.send(sender, "類は 1〜5 の数字で指定してください。");
        }
        return true;
    }
}
