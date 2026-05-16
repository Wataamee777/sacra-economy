package sc.sacra.economy;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import sc.sacra.economy.db.MySqlEconomyStore;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;

public final class DailyLoginBonusListener implements Listener {
    private static final String CONFIG_PREFIX = "daily-login-bonus";

    private final JavaPlugin plugin;
    private final MySqlEconomyStore store;
    private final CommandFeedback feedback;

    public DailyLoginBonusListener(JavaPlugin plugin, MySqlEconomyStore store, CommandFeedback feedback) {
        this.plugin = plugin;
        this.store = store;
        this.feedback = feedback;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        FileConfiguration config = plugin.getConfig();
        if (!config.getBoolean(CONFIG_PREFIX + ".enabled", true)) {
            return;
        }

        BigDecimal amount = configuredAmount(config);
        if (amount.signum() <= 0) {
            return;
        }

        LocalDate today = LocalDate.now(resolveZone(config.getString(CONFIG_PREFIX + ".timezone", "Asia/Tokyo")));
        store.claimDailyLoginBonus(event.getPlayer().getName(), amount, today).whenComplete((claimed, throwable) -> {
            if (throwable != null) {
                plugin.getLogger().warning("デイリーボーナス処理に失敗しました: " + throwable.getMessage());
                return;
            }
            if (claimed) {
                feedback.send(event.getPlayer(), "§aログインボーナスとして %s円 を受け取りました。".formatted(MySqlEconomyStore.format(amount)));
            }
        });
    }

    private BigDecimal configuredAmount(FileConfiguration config) {
        String rawAmount = config.getString(CONFIG_PREFIX + ".amount", "100");
        try {
            return MySqlEconomyStore.money(rawAmount);
        } catch (NumberFormatException exception) {
            plugin.getLogger().warning("daily-login-bonus.amount が不正です。100円を使用します: " + rawAmount);
            return MySqlEconomyStore.money("100");
        }
    }

    private ZoneId resolveZone(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException exception) {
            plugin.getLogger().warning("daily-login-bonus.timezone が不正です。Asia/Tokyoを使用します: " + timezone);
            return ZoneId.of("Asia/Tokyo");
        }
    }
}
