package sc.sacra.economy.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import sc.sacra.economy.model.CommandResult;
import sc.sacra.economy.model.Company;
import sc.sacra.economy.model.LicenseCategory;
import sc.sacra.economy.model.RankEntry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MySqlEconomyStore implements AutoCloseable {
    private final HikariDataSource dataSource;
    private final ExecutorService executor;

    // 設定値はインスタンス変数として管理（プラグインのリロードに対応しやすいため）
    public final String GOVERNMENT_WALLET = "Government_Wallet";
    public final BigDecimal COMPANY_CAPITAL;
    public final BigDecimal COMPANY_TAX;
    public final BigDecimal COMPANY_TOTAL_COST;
    public final BigDecimal LICENSE_COMPANY_DISCOUNT = new BigDecimal("0.90");

    public MySqlEconomyStore(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();

        // 数値の初期化
        this.COMPANY_CAPITAL = money(config.getInt("company.capital", 100000));
        this.COMPANY_TAX = money(config.getInt("company.tax", 1000));
        this.COMPANY_TOTAL_COST = COMPANY_CAPITAL.add(COMPANY_TAX);

        // HikariCPの設定
        String host = config.getString("mysql.host", "localhost");
        int port = config.getInt("mysql.port", 3306);
        String database = config.getString("mysql.database", "minecraft");
        boolean useSsl = config.getBoolean("mysql.useSSL", false);

        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl("jdbc:mysql://%s:%d/%s?useSSL=%s&allowPublicKeyRetrieval=true&characterEncoding=utf8&serverTimezone=UTC"
                .formatted(host, port, database, useSsl));
        
        hikari.setUsername(config.getString("mysql.username", "root"));
        hikari.setPassword(config.getString("mysql.password", ""));
        hikari.setMaximumPoolSize(config.getInt("mysql.pool-size", 10));
        hikari.setPoolName("SacraEconomyPool");

        // 接続の初期化
        try {
            this.dataSource = new HikariDataSource(hikari);
        } catch (Exception e) {
            plugin.getLogger().severe("データベース接続に失敗しました。設定を確認してください。");
            throw e;
        }

        // 非同期処理用スレッドプール
        this.executor = Executors.newFixedThreadPool(Math.max(2, hikari.getMaximumPoolSize()), runnable -> {
            Thread thread = new Thread(runnable, "SacraEconomy-DB");
            thread.setDaemon(true);
            return thread;
        });
    }
    
    public CompletableFuture<Void> initialize() {
        return run(() -> {
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS ec_accounts (
                            mcid VARCHAR(32) NOT NULL PRIMARY KEY,
                            balance DECIMAL(19,2) NOT NULL DEFAULT 0.00,
                            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS ec_companies (
                            name VARCHAR(64) NOT NULL PRIMARY KEY,
                            president_mcid VARCHAR(32) NOT NULL,
                            balance DECIMAL(19,2) NOT NULL DEFAULT 0.00,
                            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            INDEX idx_ec_companies_president (president_mcid)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS ec_licenses (
                            holder_mcid VARCHAR(32) NOT NULL,
                            category TINYINT NOT NULL,
                            holder_type ENUM('PERSONAL','COMPANY_PRESIDENT') NOT NULL,
                            price_paid DECIMAL(19,2) NOT NULL,
                            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            PRIMARY KEY (holder_mcid, category)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS ec_codes (
                            code VARCHAR(64) NOT NULL PRIMARY KEY,
                            reward DECIMAL(19,2) NOT NULL,
                            enabled BOOLEAN NOT NULL DEFAULT TRUE,
                            used_by VARCHAR(32) NULL,
                            used_at TIMESTAMP NULL,
                            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                        """);
            }
            ensureAccountSync(GOVERNMENT_WALLET);
        });
    }

    public CompletableFuture<BigDecimal> getBalance(String mcid) {
        return supply(() -> {
            ensureAccountSync(mcid);
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT balance FROM ec_accounts WHERE mcid = ?")) {
                statement.setString(1, mcid);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getBigDecimal("balance") : BigDecimal.ZERO.setScale(2);
                }
            }
        });
    }

    public CompletableFuture<CommandResult> transfer(String from, String to, BigDecimal amount) {
        return supply(() -> inTransaction(connection -> {
            ensureAccountSync(connection, from);
            ensureAccountSync(connection, to);
            BigDecimal senderBalance = lockedBalance(connection, from);
            if (senderBalance.compareTo(amount) < 0) {
                return CommandResult.error("所持金が足りません。必要額: %s円".formatted(format(amount)));
            }
            addBalance(connection, from, amount.negate());
            addBalance(connection, to, amount);
            return CommandResult.ok("%s に %s円を送金しました。".formatted(to, format(amount)));
        }));
    }

    public CompletableFuture<CommandResult> addMoney(String mcid, BigDecimal amount) {
        return supply(() -> inTransaction(connection -> {
            ensureAccountSync(connection, mcid);
            addBalance(connection, mcid, amount);
            return CommandResult.ok("%s の口座に %s円を追加しました。".formatted(mcid, format(amount)));
        }));
    }


    public CompletableFuture<CommandResult> withdrawMoney(String mcid, BigDecimal amount) {
        return supply(() -> inTransaction(connection -> {
            ensureAccountSync(connection, mcid);
            BigDecimal balance = lockedBalance(connection, mcid);
            if (balance.compareTo(amount) < 0) {
                return CommandResult.error("所持金が足りません。");
            }
            addBalance(connection, mcid, amount.negate());
            return CommandResult.ok("%s の口座から %s円を引き落としました。".formatted(mcid, format(amount)));
        }));
    }

    public CompletableFuture<CommandResult> deleteAccount(String mcid) {
        return supply(() -> {
            if (GOVERNMENT_WALLET.equalsIgnoreCase(mcid)) {
                return CommandResult.error("国庫口座は削除できません。");
            }
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement("DELETE FROM ec_accounts WHERE mcid = ?")) {
                statement.setString(1, mcid);
                statement.executeUpdate();
                return CommandResult.ok("%s の口座データを削除しました。".formatted(mcid));
            }
        });
    }

    public CompletableFuture<CommandResult> createCompany(String president, String companyName) {
        return supply(() -> inTransaction(connection -> {
            ensureAccountSync(connection, president);
            if (companyExists(connection, companyName)) {
                return CommandResult.error("その会社名は既に使われています。");
            }
            BigDecimal balance = lockedBalance(connection, president);
            if (balance.compareTo(COMPANY_TOTAL_COST) < 0) {
                return CommandResult.error("会社設立には %s円 が必要です。".formatted(format(COMPANY_TOTAL_COST)));
            }
            addBalance(connection, president, COMPANY_TOTAL_COST.negate());
            ensureAccountSync(connection, GOVERNMENT_WALLET);
            addBalance(connection, GOVERNMENT_WALLET, COMPANY_TAX);
            insertCompany(connection, president, companyName, COMPANY_CAPITAL);
            return CommandResult.ok("会社 %s を設立しました。資本金: %s円 / 設立税: %s円".formatted(companyName, format(COMPANY_CAPITAL), format(COMPANY_TAX)));
        }));
    }

    public CompletableFuture<CommandResult> forceCreateCompany(String president, String companyName) {
        return supply(() -> inTransaction(connection -> {
            if (companyExists(connection, companyName)) {
                return CommandResult.error("その会社名は既に使われています。");
            }
            insertCompany(connection, president, companyName, BigDecimal.ZERO.setScale(2));
            return CommandResult.ok("会社 %s を強制設立しました。社長: %s".formatted(companyName, president));
        }));
    }

    public CompletableFuture<CommandResult> deleteCompany(String companyName) {
        return supply(() -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement("DELETE FROM ec_companies WHERE name = ?")) {
                statement.setString(1, companyName);
                int changed = statement.executeUpdate();
                return changed == 0
                        ? CommandResult.error("会社 %s は存在しません。".formatted(companyName))
                        : CommandResult.ok("会社 %s を削除しました。".formatted(companyName));
            }
        });
    }

    public CompletableFuture<CommandResult> buyLicense(String mcid, LicenseCategory category) {
        return supply(() -> inTransaction(connection -> {
            ensureAccountSync(connection, mcid);
            if (hasLicense(connection, mcid, category)) {
                return CommandResult.error("%d類営業許可は既に購入済みです。".formatted(category.id()));
            }
            boolean president = isPresident(connection, mcid);
            BigDecimal price = president ? category.price().multiply(LICENSE_COMPANY_DISCOUNT).setScale(2, RoundingMode.HALF_UP) : category.price();
            BigDecimal balance = lockedBalance(connection, mcid);
            if (balance.compareTo(price) < 0) {
                return CommandResult.error("営業許可の購入には %s円 が必要です。".formatted(format(price)));
            }
            addBalance(connection, mcid, price.negate());
            ensureAccountSync(connection, GOVERNMENT_WALLET);
            addBalance(connection, GOVERNMENT_WALLET, price);
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO ec_licenses (holder_mcid, category, holder_type, price_paid) VALUES (?, ?, ?, ?)")) {
                statement.setString(1, mcid);
                statement.setInt(2, category.id());
                statement.setString(3, president ? "COMPANY_PRESIDENT" : "PERSONAL");
                statement.setBigDecimal(4, price);
                statement.executeUpdate();
            }
            String discount = president ? "（会社社長割引10%OFF適用）" : "";
            return CommandResult.ok("%d類（%s）の営業許可を %s円 で購入しました%s。".formatted(category.id(), category.label(), format(price), discount));
        }));
    }

    public CompletableFuture<Optional<RankEntry>> rankOf(String mcid) {
        return supply(() -> {
            ensureAccountSync(mcid);
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                         SELECT ranked.mcid, ranked.balance, ranked.position FROM (
                             SELECT mcid, balance, DENSE_RANK() OVER (ORDER BY balance DESC) AS position
                             FROM ec_accounts
                         ) ranked WHERE ranked.mcid = ?
                         """)) {
                statement.setString(1, mcid);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new RankEntry(result.getString("mcid"), result.getBigDecimal("balance"), result.getLong("position")));
                }
            }
        });
    }

    public CompletableFuture<CommandResult> redeemCode(String mcid, String code) {
        return supply(() -> inTransaction(connection -> {
            ensureAccountSync(connection, mcid);
            try (PreparedStatement statement = connection.prepareStatement("SELECT reward, enabled, used_by FROM ec_codes WHERE code = ? FOR UPDATE")) {
                statement.setString(1, code);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return CommandResult.error("そのGiveawayコードは存在しません。");
                    }
                    if (!result.getBoolean("enabled") || result.getString("used_by") != null) {
                        return CommandResult.error("そのGiveawayコードは使用できません。");
                    }
                    BigDecimal reward = result.getBigDecimal("reward");
                    addBalance(connection, mcid, reward);
                    try (PreparedStatement update = connection.prepareStatement("UPDATE ec_codes SET enabled = FALSE, used_by = ?, used_at = CURRENT_TIMESTAMP WHERE code = ?")) {
                        update.setString(1, mcid);
                        update.setString(2, code);
                        update.executeUpdate();
                    }
                    return CommandResult.ok("Giveawayコードを使用し、%s円 を受け取りました。".formatted(format(reward)));
                }
            }
        }));
    }

    public CompletableFuture<Boolean> hasAccount(String mcid) {
        return supply(() -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM ec_accounts WHERE mcid = ?")) {
                statement.setString(1, mcid);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next();
                }
            }
        });
    }

    public CompletableFuture<Boolean> setBalance(String mcid, BigDecimal amount) {
        return supply(() -> inTransaction(connection -> {
            ensureAccountSync(connection, mcid);
            try (PreparedStatement statement = connection.prepareStatement("UPDATE ec_accounts SET balance = ? WHERE mcid = ?")) {
                statement.setBigDecimal(1, amount);
                statement.setString(2, mcid);
                return statement.executeUpdate() > 0;
            }
        }));
    }

    public CompletableFuture<Company> presidentCompany(String mcid) {
        return supply(() -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT name, president_mcid, balance FROM ec_companies WHERE president_mcid = ? LIMIT 1")) {
                statement.setString(1, mcid);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? new Company(result.getString("name"), result.getString("president_mcid"), result.getBigDecimal("balance")) : null;
                }
            }
        });
    }

    public static BigDecimal money(String text) {
        return new BigDecimal(text).setScale(2, RoundingMode.HALF_UP);
    }

    public static String format(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }

    private void ensureAccountSync(String mcid) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            ensureAccountSync(connection, mcid);
        }
    }

    private void ensureAccountSync(Connection connection, String mcid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT IGNORE INTO ec_accounts (mcid, balance) VALUES (?, 0.00)")) {
            statement.setString(1, mcid);
            statement.executeUpdate();
        }
    }

    private BigDecimal lockedBalance(Connection connection, String mcid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT balance FROM ec_accounts WHERE mcid = ? FOR UPDATE")) {
            statement.setString(1, mcid);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getBigDecimal("balance") : BigDecimal.ZERO.setScale(2);
            }
        }
    }

    private void addBalance(Connection connection, String mcid, BigDecimal amount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE ec_accounts SET balance = balance + ? WHERE mcid = ?")) {
            statement.setBigDecimal(1, amount);
            statement.setString(2, mcid);
            statement.executeUpdate();
        }
    }

    private boolean companyExists(Connection connection, String companyName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM ec_companies WHERE name = ?")) {
            statement.setString(1, companyName);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private boolean isPresident(Connection connection, String mcid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM ec_companies WHERE president_mcid = ? LIMIT 1")) {
            statement.setString(1, mcid);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private boolean hasLicense(Connection connection, String mcid, LicenseCategory category) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM ec_licenses WHERE holder_mcid = ? AND category = ?")) {
            statement.setString(1, mcid);
            statement.setInt(2, category.id());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private void insertCompany(Connection connection, String president, String companyName, BigDecimal balance) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO ec_companies (name, president_mcid, balance) VALUES (?, ?, ?)")) {
            statement.setString(1, companyName);
            statement.setString(2, president);
            statement.setBigDecimal(3, balance);
            statement.executeUpdate();
        }
    }

    private <T> T inTransaction(SqlCallable<T> callable) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = callable.call(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(oldAutoCommit);
            }
        }
    }

    private CompletableFuture<Void> run(SqlRunnable runnable) {
        return CompletableFuture.runAsync(() -> {
            try {
                runnable.run();
            } catch (SQLException exception) {
                throw new IllegalStateException(exception);
            }
        }, executor);
    }

    private <T> CompletableFuture<T> supply(SqlSupplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.get();
            } catch (SQLException exception) {
                throw new IllegalStateException(exception);
            }
        }, executor);
    }

    @Override
    public void close() {
        if (executor != null) executor.shutdown();
        if (dataSource != null) dataSource.close();
    }
    
    @FunctionalInterface
    private interface SqlCallable<T> {
        T call(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    @FunctionalInterface
    private interface SqlRunnable {
        void run() throws SQLException;
    }
}
