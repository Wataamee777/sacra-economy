package sc.sacra.economy.model;

import java.math.BigDecimal;
import java.util.Optional;

public enum LicenseCategory {
    CLASS_1(1, "ホテル業", new BigDecimal("15000")),
    CLASS_2(2, "貴重類", new BigDecimal("12000")),
    CLASS_3(3, "武器・爆発物類", new BigDecimal("10000")),
    CLASS_4(4, "食品", new BigDecimal("1000")),
    CLASS_5(5, "その他", new BigDecimal("100"));

    private final int id;
    private final String label;
    private final BigDecimal price;

    LicenseCategory(int id, String label, BigDecimal price) {
        this.id = id;
        this.label = label;
        this.price = price;
    }

    public int id() {
        return id;
    }

    public String label() {
        return label;
    }

    public BigDecimal price() {
        return price;
    }

    public static Optional<LicenseCategory> fromId(int id) {
        return switch (id) {
            case 1 -> Optional.of(CLASS_1);
            case 2 -> Optional.of(CLASS_2);
            case 3 -> Optional.of(CLASS_3);
            case 4 -> Optional.of(CLASS_4);
            case 5 -> Optional.of(CLASS_5);
            default -> Optional.empty();
        };
    }
}
