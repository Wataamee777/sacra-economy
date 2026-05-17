package sc.sacra.economy;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import sc.sacra.economy.command.CompanyCommand;
import sc.sacra.economy.command.LicenseCommand;
import sc.sacra.economy.command.MoneyCommand;
import sc.sacra.economy.command.QuestCommand;
import sc.sacra.economy.command.ShopCommand;
import sc.sacra.economy.command.TradeCommand;
import sc.sacra.economy.command.AdminCommand;
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
        TradeCommand tradeCommand = new TradeCommand(this);

        try {
            // 1. MySQLの初期化を同期的に待機（非同期の whenComplete を撤廃）
            // initialize() が CompletableFuture を返す場合は .join() で完了を待ちます
            store.initialize().join();
        } catch (Exception throwable) {
            getLogger().severe("MySQL初期化に失敗したため、プラグインを無効化します: " + throwable.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // 2. データベース準備完了後、即座にVaultへ登録（これで他のプラグインのロードに確実に間に合います）
        vaultEconomy = new SacraVaultEconomy(store);
        getServer().getServicesManager().register(Economy.class, vaultEconomy, this, ServicePriority.Highest);

        // 3. コマンドの登録
        registerCommand("company", new CompanyCommand(store, feedback));
        registerCommand("license", new LicenseCommand(store, feedback));
        registerCommand("money", new MoneyCommand(store, feedback));
        registerCommand("quest", new QuestCommand(this, store, feedback));
        registerCommand("shop", new ShopCommand(this));
        registerCommand("trade", tradeCommand);
        registerCommand("admin", new AdminCommand(store, feedback));

        // 4. イベントリスナーの登録
        getServer().getPluginManager().registerEvents(new DailyLoginBonusListener(this, store, feedback), this);
        getServer().getPluginManager().registerEvents(new BlockEarningListener(this, store), this);
        getServer().getPluginManager().registerEvents(new ShopListener(this, store, feedback), this);
        getServer().getPluginManager().registerEvents(tradeCommand, this);

        getLogger().info("SacraEconomy has been enabled as the Vault economy provider.");
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
