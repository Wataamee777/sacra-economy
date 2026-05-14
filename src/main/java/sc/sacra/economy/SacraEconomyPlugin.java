package sc.sacra.economy;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import sc.sacra.economy.command.CompanyCommand;
import sc.sacra.economy.command.LicenseCommand;
import sc.sacra.economy.command.MoneyCommand;
import sc.sacra.economy.command.OpCommand;
import sc.sacra.economy.db.MySqlEconomyStore;
import sc.sacra.economy.vault.SacraVaultEconomy;

public final class SacraEconomyPlugin extends JavaPlugin {
    private MySqlEconomyStore store;
    private SacraVaultEconomy vaultEconomy;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        store = new MySqlEconomyStore(this);
        CommandFeedback feedback = new CommandFeedback(this);

        registerCommand("company", new CompanyCommand(store, feedback));
        registerCommand("license", new LicenseCommand(store, feedback));
        registerCommand("money", new MoneyCommand(store, feedback));
        registerCommand("op", new OpCommand(store, feedback));

        store.initialize().whenComplete((ignored, throwable) -> Bukkit.getScheduler().runTask(this, () -> {
            if (throwable != null) {
                getLogger().severe("MySQL初期化に失敗しました: " + throwable.getMessage());
                Bukkit.getPluginManager().disablePlugin(this);
                return;
            }
            vaultEconomy = new SacraVaultEconomy(store);
            getServer().getServicesManager().register(Economy.class, vaultEconomy, this, ServicePriority.Highest);
            getLogger().info("SacraEconomy has been enabled as the Vault economy provider.");
        }));
    }

    @Override
    public void onDisable() {
        if (vaultEconomy != null) {
            vaultEconomy.disable();
            getServer().getServicesManager().unregister(Economy.class, vaultEconomy);
        }
        if (store != null) {
            store.close();
        }
    }

    private void registerCommand(String name, org.bukkit.command.CommandExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            throw new IllegalStateException("plugin.ymlにコマンドが定義されていません: " + name);
        }
        command.setExecutor(executor);
    }
}
