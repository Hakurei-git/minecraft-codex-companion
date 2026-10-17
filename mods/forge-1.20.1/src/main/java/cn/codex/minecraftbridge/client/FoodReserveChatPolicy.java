package cn.codex.minecraftbridge.client;

import java.util.OptionalInt;
import java.util.regex.Pattern;

/** Exact local setting requests must work without an AI connection. */
final class FoodReserveChatPolicy {
    private static final String SUBJECT = "(?:常备(?:安全)?口粮|(?:自动)?口粮储备|自动补粮|自动备粮)";
    private static final String POLITE = "(?:(?:请|麻烦|帮我|你|先|现在|把|将)\\s*)*";
    private static final String END = "\\s*(?:吧|呀|啊|喵|了)?\\s*[！!。.]?\\s*";
    private static final Pattern DISABLE = Pattern.compile(POLITE + "(?:(?:关闭|关掉|停用|禁用|停止|取消)\\s*" + SUBJECT
        + "|" + SUBJECT + "\\s*(?:(?:设置|设|改)为)?\\s*(?:关闭|关掉|停用|禁用|停止|取消)"
        + "|(?:不要|别)(?:再)?(?:自动)?(?:补粮|备粮))" + END);
    private static final Pattern ENABLE = Pattern.compile(POLITE + "(?:(?:开启|打开|启用|恢复)\\s*" + SUBJECT
        + "|" + SUBJECT + "\\s*(?:(?:设置|设|改)为)?\\s*(?:开启|打开|启用|恢复))" + END);

    private FoodReserveChatPolicy() { }

    static OptionalInt requestedCount(String message, String companionName) {
        if (message == null || message.isBlank()) return OptionalInt.empty();
        String names = "反重力|antigravity|codex|claude";
        if (companionName != null && !companionName.isBlank()) names += "|" + Pattern.quote(companionName.trim());
        String text = message.trim().replaceFirst("(?iu)^(?:" + names + ")\\s*[:：,，]?\\s*", "");
        if (DISABLE.matcher(text).matches()) return OptionalInt.of(0);
        if (ENABLE.matcher(text).matches()) return OptionalInt.of(8);
        return OptionalInt.empty();
    }
}
