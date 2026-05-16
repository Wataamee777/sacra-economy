package sc.sacra.economy.model;

import java.math.BigDecimal;

public record QuestReward(String advancementKey, String level, BigDecimal reward) {
}
