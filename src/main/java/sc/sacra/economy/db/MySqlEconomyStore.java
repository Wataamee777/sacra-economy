package sc.sacra.economy.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import sc.sacra.economy.model.CommandResult;
import sc.sacra.economy.model.Company;
import sc.sacra.economy.model.LicenseCategory;
import sc.sacra.economy.model.OperationLogEntry;
import sc.sacra.economy.model.QuestReward;
import sc.sacra.economy.model.RankEntry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MySqlEconomyStore implements AutoCloseable {
    public static final String GOVERNMENT_WALLET = "Government_Wallet";
    public static final BigDecimal COMPANY_CAPITAL = money("100000");
    public static final BigDecimal COMPANY_TAX = money("1000");
    public static final BigDecimal COMPANY_TOTAL_COST = COMPANY_CAPITAL.add(COMPANY_TAX);
    public static final BigDecimal LICENSE_COMPANY_DISCOUNT = new BigDecimal("0.90");
    private static final String DEFAULT_REASON = "理由なし";
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final HikariDataSource dataSource;
    private final ExecutorService executor;
    private final SecureRandom random = new SecureRandom();

    public MySqlEconomyStore(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        String host = config.getString("mysql.host", "localhost");
        int port = config.getInt("mysql.port", 3306);
        String database = config.getString("mysql.database", "minecraft");
        boolean useSsl = config.getBoolean("mysql.useSSL", false);

        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl("jdbc:mysql://%s:%d/%s?useSSL=%s&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC"
                .formatted(host, port, database, useSsl));
        hikari.setDriverClassName("com.mysql.cj.jdbc.Driver");
        hikari.setUsername(config.getString("mysql.username", "root"));
        hikari.setPassword(config.getString("mysql.password", ""));
        hikari.setMaximumPoolSize(config.getInt("mysql.pool-size", 10));
        hikari.setPoolName("SacraEconomyPool");
        this.dataSource = new HikariDataSource(hikari);
        this.executor = Executors.newFixedThreadPool(Math.max(2, config.getInt("mysql.pool-size", 10)), runnable -> {
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
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS ec_daily_bonuses (
                            mcid VARCHAR(32) NOT NULL PRIMARY KEY,
                            last_claim_date DATE NOT NULL,
                            updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS ec_operation_logs (
                            id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                            actor_mcid VARCHAR(32) NOT NULL,
                            target_mcid VARCHAR(128) NULL,
                            action VARCHAR(64) NOT NULL,
                            amount DECIMAL(19,2) NULL,
                            reason VARCHAR(255) NOT NULL DEFAULT '理由なし',
                            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            INDEX idx_ec_operation_logs_actor (actor_mcid),
                            INDEX idx_ec_operation_logs_target (target_mcid),
                            INDEX idx_ec_operation_logs_action (action)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                        """);
                statement.executeUpdate("ALTER TABLE ec_operation_logs MODIFY target_mcid VARCHAR(128) NULL");
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS ec_quest_claims (
                            mcid VARCHAR(32) NOT NULL,
                            advancement_key VARCHAR(128) NOT NULL,
                            level VARCHAR(16) NOT NULL,
                            reward DECIMAL(19,2) NOT NULL,
                            claimed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            PRIMARY KEY (mcid, advancement_key),
                            INDEX idx_ec_quest_claims_mcid (mcid)
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
            insertOperationLog(connection, from, to, "PLAYER_PAY", amount, "プレイヤー送金");
            return CommandResult.ok("%s に %s円を送金しました。".formatted(to, format(amount)));
        }));
    }

    public CompletableFuture<CommandResult> addMoney(String mcid, BigDecimal amount) {
        return supply(() -> inTransaction(connection -> {
            ensureAccountSync(connection, mcid);
            addBalance(connection, mcid, amount);
            insertOperationLog(connection, "SYSTEM", mcid, "VAULT_DEPOSIT", amount, "Vault入金");
            return CommandResult.ok("%s の口座に %s円を追加しました。".formatted(mcid, format(amount)));
        }));
    }


    public CompletableFuture<CommandResult> addSystemReward(String mcid, BigDecimal amount, String action, String reason) {
        return supply(() -> inTransaction(connection -> {
            ensureAccountSync(connection, mcid);
            addBalance(connection, mcid, amount);
            insertOperationLog(connection, "SYSTEM", mcid, action, amount, reason);
            return CommandResult.ok("%s円を受け取りました。".formatted(format(amount)));
        }));
    }

    public CompletableFuture<CommandResult> adminAddMoney(String actor, String mcid, BigDecimal amount, String reason) {
        return supply(() -> inTransaction(connection -> {
            ensureAccountSync(connection, mcid);
            addBalance(connection, mcid, amount);
            insertOperationLog(connection, actor, mcid, "ADMIN_MONEY_ADD", amount, normalizeReason(reason));
            return CommandResult.ok("%s の口座に %s円を追加しました。名目: %s".formatted(mcid, format(amount), normalizeReason(reason)));
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
            insertOperationLog(connection, "SYSTEM", mcid, "VAULT_WITHDRAW", amount, "Vault出金");
            return CommandResult.ok("%s の口座から %s円を引き落としました。".formatted(mcid, format(amount)));
        }));
    }

    public CompletableFuture<CommandResult> adminRemoveMoney(String actor, String mcid, BigDecimal amount, String reason) {
        return supply(() -> inTransaction(connection -> {
            ensureAccountSync(connection, mcid);
            BigDecimal balance = lockedBalance(connection, mcid);
            if (balance.compareTo(amount) < 0) {
                return CommandResult.error("%s の残高が足りません。現在残高: %s円".formatted(mcid, format(balance)));
            }
            addBalance(connection, mcid, amount.negate());
            insertOperationLog(connection, actor, mcid, "ADMIN_MONEY_REMOVE", amount, normalizeReason(reason));
            return CommandResult.ok("%s の口座から %s円を削除しました。名目: %s".formatted(mcid, format(amount), normalizeReason(reason)));
        }));
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
            insertOperationLog(connection, president, companyName, "COMPANY_CREATE", COMPANY_TOTAL_COST, "会社設立");
            return CommandResult.ok("会社 %s を設立しました。資本金: %s円 / 設立税: %s円".formatted(companyName, format(COMPANY_CAPITAL), format(COMPANY_TAX)));
        }));
    }

    public CompletableFuture<CommandResult> forceCreateCompany(String president, String companyName) {
        return supply(() -> inTransaction(connection -> {
            if (companyExists(connection, companyName)) {
                return CommandResult.error("その会社名は既に使われています。");
            }
            insertCompany(connection, president, companyName, BigDecimal.ZERO.setScale(2));
            insertOperationLog(connection, "ADMIN", companyName, "ADMIN_COMPANY_ADD", BigDecimal.ZERO.setScale(2), "管理者による会社設立");
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
            insertOperationLog(connection, mcid, GOVERNMENT_WALLET, "LICENSE_BUY", price, "%d類営業許可".formatted(category.id()));
            String discount = president ? "（会社社長割引10%OFF適用）" : "";
            return CommandResult.ok("%d類（%s）の営業許可を %s円 で購入しました%s。".formatted(category.id(), category.label(), format(price), discount));
        }));
    }

    public CompletableFuture<List<RankEntry>> topRankings(int limit) {
        return supply(() -> {
            List<RankEntry> rankings = new ArrayList<>();
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                         SELECT mcid, balance, position FROM (
                             SELECT mcid, balance, DENSE_RANK() OVER (ORDER BY balance DESC) AS position
                             FROM ec_accounts
                             WHERE mcid <> ?
                         ) ranked
                         WHERE position <= ?
                         ORDER BY position ASC, balance DESC, mcid ASC
                         """)) {
                statement.setString(1, GOVERNMENT_WALLET);
                statement.setInt(2, limit);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        rankings.add(new RankEntry(result.getString("mcid"), result.getBigDecimal("balance"), result.getLong("position")));
                    }
                }
            }
            return rankings;
        });
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

    public CompletableFuture<Boolean> claimDailyLoginBonus(String mcid, BigDecimal amount, LocalDate today) {
        return supply(() -> inTransaction(connection -> {
            ensureAccountSync(connection, mcid);
            try (PreparedStatement select = connection.prepareStatement("SELECT last_claim_date FROM ec_daily_bonuses WHERE mcid = ? FOR UPDATE")) {
                select.setString(1, mcid);
                try (ResultSet result = select.executeQuery()) {
                    if (result.next() && today.equals(result.getDate("last_claim_date").toLocalDate())) {
                        return false;
                    }
                }
            }
            addBalance(connection, mcid, amount);
            insertOperationLog(connection, "SYSTEM", mcid, "DAILY_LOGIN_BONUS", amount, "デイリーログインボーナス");
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO ec_daily_bonuses (mcid, last_claim_date) VALUES (?, ?)
                    ON DUPLICATE KEY UPDATE last_claim_date = VALUES(last_claim_date)
                    """)) {
                upsert.setString(1, mcid);
                upsert.setDate(2, java.sql.Date.valueOf(today));
                upsert.executeUpdate();
            }
            return true;
        }));
    }

    public CompletableFuture<CommandResult> claimQuestReward(String mcid, String advancementKey, String level, BigDecimal reward) {
        return supply(() -> inTransaction(connection -> {
            ensureAccountSync(connection, mcid);
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT IGNORE INTO ec_quest_claims (mcid, advancement_key, level, reward)
                    VALUES (?, ?, ?, ?)
                    """)) {
                insert.setString(1, mcid);
                insert.setString(2, advancementKey);
                insert.setString(3, level);
                insert.setBigDecimal(4, reward);
                if (insert.executeUpdate() == 0) {
                    return CommandResult.error("この実績報酬は既に受け取り済みです: " + advancementKey);
                }
            }
            addBalance(connection, mcid, reward);
            insertOperationLog(connection, "SYSTEM", mcid, "QUEST_CLAIM", reward, "実績報酬: %s (%s)".formatted(advancementKey, level));
            return CommandResult.ok("実績報酬 %s円 を受け取りました。実績: %s / レベル: %s".formatted(format(reward), advancementKey, level.toLowerCase(java.util.Locale.ROOT)));
        }));
    }

    public CompletableFuture<CommandResult> claimQuestRewards(String mcid, List<QuestReward> rewards) {
        return supply(() -> inTransaction(connection -> {
            ensureAccountSync(connection, mcid);
            BigDecimal total = BigDecimal.ZERO.setScale(2);
            int claimed = 0;
            for (QuestReward reward : rewards) {
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT IGNORE INTO ec_quest_claims (mcid, advancement_key, level, reward)
                        VALUES (?, ?, ?, ?)
                        """)) {
                    insert.setString(1, mcid);
                    insert.setString(2, reward.advancementKey());
                    insert.setString(3, reward.level());
                    insert.setBigDecimal(4, reward.reward());
                    if (insert.executeUpdate() == 0) {
                        continue;
                    }
                }
                claimed++;
                total = total.add(reward.reward());
                insertOperationLog(connection, "SYSTEM", mcid, "QUEST_CLAIM", reward.reward(),
                        "実績報酬: %s (%s)".formatted(reward.advancementKey(), reward.level()));
            }

            if (claimed == 0) {
                return CommandResult.error("受け取り可能な未受取の実績報酬はありません。");
            }
            addBalance(connection, mcid, total);
            return CommandResult.ok("解除済み実績報酬を %d件受け取りました。合計: %s円".formatted(claimed, format(total)));
        }));
    }

    public CompletableFuture<CommandResult> createGiveawayCode(String actor, BigDecimal amount, String reason) {
        return supply(() -> inTransaction(connection -> {
            String code = uniqueCode(connection);
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO ec_codes (code, reward, enabled) VALUES (?, ?, TRUE)")) {
                statement.setString(1, code);
                statement.setBigDecimal(2, amount);
                statement.executeUpdate();
            }
            insertOperationLog(connection, actor, code, "ADMIN_CODE_SET", amount, normalizeReason(reason));
            return CommandResult.ok("Giveawayコード %s を発行しました。報酬: %s円 / 名目: %s".formatted(code, format(amount), normalizeReason(reason)));
        }));
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
                    insertOperationLog(connection, mcid, code, "GIVEAWAY_REDEEM", reward, "Giveawayコード使用");
                    return CommandResult.ok("Giveawayコードを使用し、%s円 を受け取りました。".formatted(format(reward)));
                }
            }
        }));
    }

    public CompletableFuture<List<OperationLogEntry>> historyOf(String mcid, int limit) {
        return supply(() -> {
            List<OperationLogEntry> history = new ArrayList<>();
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                         SELECT id, actor_mcid, target_mcid, action, amount, reason, created_at
                         FROM ec_operation_logs
                         WHERE actor_mcid = ? OR target_mcid = ?
                         ORDER BY created_at DESC, id DESC
                         LIMIT ?
                         """)) {
                statement.setString(1, mcid);
                statement.setString(2, mcid);
                statement.setInt(3, limit);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        history.add(new OperationLogEntry(
                                result.getLong("id"),
                                result.getString("actor_mcid"),
                                result.getString("target_mcid"),
                                result.getString("action"),
                                result.getBigDecimal("amount"),
                                result.getString("reason"),
                                result.getTimestamp("created_at").toLocalDateTime()));
                    }
                }
            }
            return history;
        });
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

    public static BigDecimal money(int value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
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

    private String uniqueCode(Connection connection) throws SQLException {
        for (int attempt = 0; attempt < 10; attempt++) {
            String code = randomCode();
            try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM ec_codes WHERE code = ?")) {
                statement.setString(1, code);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return code;
                    }
                }
            }
        }
        throw new SQLException("Giveawayコードの生成に失敗しました。");
    }

    private String randomCode() {
        StringBuilder builder = new StringBuilder("SACRA-");
        for (int index = 0; index < 12; index++) {
            if (index > 0 && index % 4 == 0) {
                builder.append('-');
            }
            builder.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]);
        }
        return builder.toString();
    }

    private void insertOperationLog(Connection connection, String actor, String target, String action, BigDecimal amount, String reason) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ec_operation_logs (actor_mcid, target_mcid, action, amount, reason)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, normalizeActor(actor));
            statement.setString(2, target);
            statement.setString(3, action);
            statement.setBigDecimal(4, amount);
            statement.setString(5, normalizeReason(reason));
            statement.executeUpdate();
        }
    }

    private String normalizeActor(String actor) {
        return actor == null || actor.isBlank() ? "SYSTEM" : actor;
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return DEFAULT_REASON;
        }
        return reason.length() <= 255 ? reason : reason.substring(0, 255);
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
