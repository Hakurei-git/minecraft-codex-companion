package cn.codex.minecraftbridge.forge.client;

import cn.codex.minecraftbridge.forge.CodexNpcMenu;
import cn.codex.minecraftbridge.forge.CodexNpcEntity;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public final class CodexNpcScreen extends AbstractContainerScreen<CodexNpcMenu> {
    public CodexNpcScreen(CodexNpcMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 328;
        imageHeight = 168;
        inventoryLabelY = 74;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xF015191B);
        graphics.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + 16, 0xFF26312D);
        graphics.fill(leftPos + 165, topPos + 17, leftPos + 211, topPos + 81, 0xFF202726);
        graphics.fill(leftPos + 218, topPos + 17, leftPos + imageWidth - 1, topPos + imageHeight - 1, 0xFF202726);
        for (var slot : menu.slots) {
            int x = leftPos + slot.x - 1;
            int y = topPos + slot.y - 1;
            graphics.fill(x, y, x + 18, y + 18, 0xFF39433F);
            graphics.fill(x + 1, y + 1, x + 17, y + 17, 0xFF111514);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, 0xDCEBE3, false);
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0xAFC2B8, false);
        graphics.drawString(font, Component.literal("主手  副手"), 168, 7, 0x8EACA0, false);
        renderNpcStatus(graphics);
    }

    private void renderNpcStatus(GuiGraphics graphics) {
        CodexNpcEntity npc = menu.npc();
        int x = 225;
        int width = 94;
        graphics.drawString(font, Component.literal("NPC 状态"), x, 7, 0xDCEBE3, false);

        graphics.drawString(font, Component.literal("生命"), x, 22, 0x8EACA0, false);
        drawBar(graphics, x, 31, width, npc.getHealth() / Math.max(1.0F, npc.getMaxHealth()), 0xFFB74D58);
        graphics.drawString(font, Component.literal(formatNumber(npc.getHealth()) + "/" + formatNumber(npc.getMaxHealth())), x + 3, 30, 0xFFF4F7F5, false);

        graphics.drawString(font, Component.literal("饱食 / 饱和"), x, 44, 0x8EACA0, false);
        drawBar(graphics, x, 53, width, npc.foodLevel() / 20.0F, 0xFFD49A3A);
        graphics.drawString(font, Component.literal(npc.foodLevel() + "/20 · " + formatNumber(npc.saturationLevel())), x + 3, 52, 0xFFF4F7F5, false);

        graphics.drawString(font, Component.literal("模式"), x, 67, 0x8EACA0, false);
        graphics.drawString(font, Component.literal(npc.materialModeForDisplay() + " · " + stanceLabel(npc)), x, 77, 0xFFDCEBE3, false);

        String task = npc.activeTaskKindForDisplay();
        graphics.drawString(font, Component.literal("当前任务"), x, 91, 0x8EACA0, false);
        graphics.drawString(font, Component.literal(task.isBlank() ? "无" : taskLabel(task) + " " + npc.activeTaskProgressPercent() + "%"), x, 101, 0xFFDCEBE3, false);

        graphics.drawString(font, Component.literal("实时状态"), x, 115, 0x8EACA0, false);
        drawTrimmed(graphics, npc.status(), x, 125, width, 0xFFDCEBE3);
        if (npc.pausedTaskCountForDisplay() > 0) {
            String paused = "暂停 " + npc.pausedTaskCountForDisplay() + " · " + npc.pauseReasonForDisplay();
            drawTrimmed(graphics, paused, x, 143, width, 0xFFE6C56D);
        }
    }

    private void drawBar(GuiGraphics graphics, int x, int y, int width, float ratio, int color) {
        graphics.fill(x, y, x + width, y + 10, 0xFF111514);
        int filled = Math.round(Math.max(0.0F, Math.min(1.0F, ratio)) * width);
        if (filled > 0) graphics.fill(x, y, x + filled, y + 10, color);
    }

    private void drawTrimmed(GuiGraphics graphics, String value, int x, int y, int width, int color) {
        String first = font.plainSubstrByWidth(value, width);
        graphics.drawString(font, Component.literal(first), x, y, color, false);
        if (first.length() >= value.length()) return;
        String rest = font.plainSubstrByWidth(value.substring(first.length()), width);
        graphics.drawString(font, Component.literal(rest), x, y + 9, color, false);
    }

    private String stanceLabel(CodexNpcEntity npc) {
        return switch (npc.stance()) {
            case FOLLOW -> "跟随";
            case STAY -> "等待";
            case GUARD -> "护卫";
            case WORK -> "工作";
        };
    }

    private String taskLabel(String kind) {
        return switch (kind) {
            case "combat" -> "战斗";
            case "gather" -> "采集";
            case "deliver" -> "交付";
            case "eat" -> "进食";
            case "provision-food" -> "寻食";
            case "ranch" -> "畜牧";
            case "fish" -> "钓鱼";
            case "craft" -> "制作";
            case "smelt" -> "烧炼";
            case "farm" -> "农务";
            case "build" -> "建造";
            case "dragon" -> "骑龙";
            default -> kind;
        };
    }

    private String formatNumber(float value) {
        return value == Math.round(value) ? Integer.toString(Math.round(value)) : String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
