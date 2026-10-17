package cn.codex.minecraftbridge.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class FoodReserveChatPolicyTest {
    @Test void acceptsBothOrdersOfDisableRequests() {
        for (String message : new String[]{"常备安全口粮关闭", "关闭常备安全口粮", "请把常备口粮关掉吧", "常备口粮设为关闭", "别再自动补粮了"}) {
            assertEquals(0, FoodReserveChatPolicy.requestedCount(message, "伙伴").orElseThrow(), message);
        }
    }
    @Test void acceptsConfiguredNamesAndBridgePrefixes() {
        for (String message : new String[]{"反重力 常备安全口粮关闭", "Codex: 关闭常备口粮", "伙伴，关闭自动补粮喵"}) {
            assertEquals(0, FoodReserveChatPolicy.requestedCount(message, "伙伴").orElseThrow(), message);
        }
    }
    @Test void reopensWithTheDefaultReserve() {
        for (String message : new String[]{"开启常备安全口粮", "常备口粮打开", "伙伴 恢复自动补粮"}) {
            assertEquals(8, FoodReserveChatPolicy.requestedCount(message, "伙伴").orElseThrow(), message);
        }
    }
    @Test void doesNotChangeSettingsForQuestionsQuotesOrFoodOrders() {
        for (String message : new String[]{"常备安全口粮关闭了吗", "关闭常备口粮？", "她说关闭常备口粮", "去找8份口粮", "关闭常备口粮有什么影响", "/say 关闭常备口粮", "常备口粮已关闭"}) {
            assertTrue(FoodReserveChatPolicy.requestedCount(message, "伙伴").isEmpty(), message);
        }
    }
    @Test void doesNotReverseNegativeRequestsOrTreatMissingInputAsCommands() {
        for (String message : new String[]{"不要关闭常备口粮", "别关闭自动补粮", "不要开启常备口粮", "", "   "}) {
            assertTrue(FoodReserveChatPolicy.requestedCount(message, "伙伴").isEmpty(), message);
        }
        assertTrue(FoodReserveChatPolicy.requestedCount(null, null).isEmpty());
    }
}
