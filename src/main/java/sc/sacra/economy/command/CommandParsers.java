package sc.sacra.economy.command;

import sc.sacra.economy.db.MySqlEconomyStore;

import java.math.BigDecimal;
import java.util.Optional;

final class CommandParsers {
    private CommandParsers() {
    }

    static Optional<BigDecimal> positiveMoney(String text) {
        try {
            BigDecimal amount = MySqlEconomyStore.money(text);
            return amount.signum() > 0 ? Optional.of(amount) : Optional.empty();
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }
}
