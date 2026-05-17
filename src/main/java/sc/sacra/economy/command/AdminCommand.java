package sc.sacra.economy.command;

import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import sc.sacra.economy.CommandFeedback;
import sc.sacra.economy.db.MySqlEconomyStore;

import java.math.BigDecimal;

public final class AdminCommand implements CommandExecutor {
    private final MySqlEconomyStore store;
    private final CommandFeedback feedback;

    public AdminCommand(MySqlEconomyStore store, CommandFeedback feedback) {
        this.store = store;
        this.feedback = feedback;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!sender.isOp()) {
            feedback.send(sender, "§cこのコマンドはOP専用です。");
            return true;
        }
        if (args.length < 2) {
            usage(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "money" -> money(sender, args);
            case "company" -> company(sender, args);
            case "code" -> code(sender, args);
            case "shop" -> shop(sender, args); // 新設: ショップ管理用サブコマンド
            default -> usage(sender);
        }
        return true;
    }

    private void money(CommandSender sender, String[] args) {
        switch (args[1].toLowerCase()) {
            case "add" -> {
                if (args.length < 4) {
                    usage(sender);
                    return;
                }
                CommandParsers.positiveMoney(args[3]).ifPresentOrElse(
                        amount -> feedback.handle(sender, store.adminAddMoney(sender.getName(), args[2], amount, reason(args, 4))),
                        () -> feedback.send(sender, "金額は0より大きい数字で指定してください。"));
            }
            case "remove" -> {
                if (args.length < 4) {
                    usage(sender);
                    return;
                }
                CommandParsers.positiveMoney(args[3]).ifPresentOrElse(
                        amount -> feedback.handle(sender, store.adminRemoveMoney(sender.getName(), args[2], amount, reason(args, 4))),
                        () -> feedback.send(sender, "金額は0より大きい数字で指定してください。"));
            }
            default -> usage(sender);
        }
    }

    private void company(CommandSender sender, String[] args) {
        switch (args[1].toLowerCase()) {
            case "add" -> {
                if (args.length == 4) {
                    feedback.handle(sender, store.forceCreateCompany(args[2], args[3]));
                } else {
                    usage(sender);
                }
            }
            case "delete" -> {
                if (args.length == 3) {
                    feedback.handle(sender, store.deleteCompany(args[2]));
                } else {
                    usage(sender);
                }
            }
            default -> usage(sender);
        }
    }

    private void code(CommandSender sender, String[] args) {
        if (!args[1].equalsIgnoreCase("set") || args.length < 3) {
            usage(sender);
            return;
        }
        CommandParsers.positiveMoney(args[2]).ifPresentOrElse(
                amount -> feedback.handle(sender, store.createGiveawayCode(sender.getName(), amount, reason(args, 3))),
                () -> feedback.send(sender, "金額は0より大きい数字で指定してください。"));
    }

    /**
     * 新設: ショップ運営用サブコマンド
     * /admin shop item <enable|disable> [アイテムID]
     * /admin shop price <+10%|-20%|reset> [アイテムID]
     */
    private void shop(CommandSender sender, String[] args) {
        if (args.length < 4) {
            usage(sender);
            return;
        }

        String subType = args[1].toLowerCase();   // "item" または "price"
        String action = args[2].toLowerCase();    // "enable/disable" または "+10%/reset"
        String itemId = args[3].toUpperCase();    // "DIRT" や "IRON_INGOT"、QAの武器名

        // 存在チェックの警告（バニラにない場合。QualityArmorsを考慮して完全に弾きはしない）
        if (Material.getMaterial(itemId) == null && !itemId.startsWith("QA_")) {
            feedback.send(sender, "§e[警告] 指定されたID '" + itemId + "' はバニラマテリアルに見つかりません。QAアイテムとして設定します。");
        }

        // 1. アイテムの売却有効化・無効化処理
        if (subType.equals("item")) {
            if (action.equals("enable")) {
                // 有効化＝運営倍率を等倍（1.00）に戻す
                store.setPriceModifier(itemId, BigDecimal.ONE).whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        feedback.send(sender, "§cエラー: アイテムの有効化に失敗しました。");
                        return;
                    }
                    feedback.send(sender, "§a" + itemId + " のショップ売却を【有効化】しました。");
                });
            } else if (action.equals("disable")) {
                // 無効化＝運営倍率を「0.00」にして売却不可能なアイテムに変える
                store.setPriceModifier(itemId, BigDecimal.ZERO).whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        feedback.send(sender, "§cエラー: アイテムの無効化に失敗しました。");
                        return;
                    }
                    feedback.send(sender, "§c" + itemId + " のショップ売却を【無効化】しました。");
                });
            } else {
                usage(sender);
            }
            return;
        }

        // 2. 価値のパーセンテージ・相場補正処理
        if (subType.equals("price")) {
            if (action.equals("reset")) {
                store.setPriceModifier(itemId, BigDecimal.ONE).whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        feedback.send(sender, "§cエラー: 価格補正のリセットに失敗しました。");
                        return;
                    }
                    feedback.send(sender, "§a" + itemId + " の価格補正をリセット（±0%）しました。");
                });
                return;
            }

            try {
                if (!action.endsWith("%")) {
                    feedback.send(sender, "§cエラー: 補正値の末尾には '%' を付けてください (例: +10%)。");
                    return;
                }
                String numStr = action.substring(0, action.length() - 1);
                double percent = Double.parseDouble(numStr);
                double modifierValue = 1.0 + (percent / 100.0);

                if (modifierValue <= 0) {
                    feedback.send(sender, "§cエラー: 価格が0以下になる補正は設定できません。売却を止めたい場合は item disable を使用してください。");
                    return;
                }

                store.setPriceModifier(itemId, BigDecimal.valueOf(modifierValue)).whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        feedback.send(sender, "§cエラー: 価格補正の保存に失敗しました。");
                        return;
                    }
                    feedback.send(sender, "§a" + itemId + " の価格価値を " + action + " に設定しました。");
                });
            } catch (NumberFormatException e) {
                feedback.send(sender, "§cエラー: 数値の解析に失敗しました。形式を確認してください。");
            }
            return;
        }

        usage(sender);
    }

    private String reason(String[] args, int startIndex) {
        return args.length <= startIndex ? "理由なし" : String.join(" ", java.util.Arrays.copyOfRange(args, startIndex, args.length));
    }

    private void usage(CommandSender sender) {
        feedback.send(sender, "§e/admin money add [MCID] [金額] [名目] §7/ §e/admin money remove [MCID] [金額] [名目] §7/ §e/admin code set [金額]\n" +
                "§e/admin company add [社長MCID] [会社名] §7/ §e/admin company delete [会社名]\n" +
                "§b/admin shop item <enable|disable> [アイテムID] §7/ §b/admin shop price <+10%|-20%|reset> [アイテムID]");
    }
}
