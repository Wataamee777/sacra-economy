package sc.sacra.economy.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OperationLogEntry(
        long id,
        String actorMcid,
        String targetMcid,
        String action,
        BigDecimal amount,
        String reason,
        LocalDateTime createdAt
) {
}
