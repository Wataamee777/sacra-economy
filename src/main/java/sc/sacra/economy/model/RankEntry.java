package sc.sacra.economy.model;

import java.math.BigDecimal;

public record RankEntry(String mcid, BigDecimal balance, long rank) {
}
