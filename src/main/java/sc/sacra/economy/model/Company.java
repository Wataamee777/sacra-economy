package sc.sacra.economy.model;

import java.math.BigDecimal;

public record Company(String name, String presidentMcid, BigDecimal balance) {
}
