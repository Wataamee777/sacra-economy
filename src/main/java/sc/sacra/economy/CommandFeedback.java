package sc.sacra.economy;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import sc.sacra.economy.model.CommandResult;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public final class CommandFeedback {
    private static final String PREFIX = "§6[SacraEconomy] §r";
    private final Plugin plugin;

    public CommandFeedback(Plugin plugin) {
        this.plugin = plugin;
    }

    public void send(CommandSender sender, String message) {
        Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(PREFIX + message));
    }

    public void handle(CommandSender sender, CompletableFuture<CommandResult> future) {
        handle(sender, future, CommandResult::message);
    }

    public <T> void handle(CommandSender sender, CompletableFuture<T> future, Function<T, String> formatter) {
        future.whenComplete((value, throwable) -> {
            if (throwable != null) {
                plugin.getLogger().warning(throwable.getMessage());
                send(sender, "§cDB処理中にエラーが発生しました。管理者へ連絡してください。");
                return;
            }
            send(sender, formatter.apply(value));
        });
    }
}
