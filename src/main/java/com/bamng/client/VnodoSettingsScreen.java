package com.bamng.client;

import com.bamng.core.VnodoConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.Locale;

/**
 * In-game Vnodo tuning page, opened from Vettura's settings screen.
 *
 * <p>Every region slider writes the same multiplier used by
 * {@code VehicleBody.applyTuning()}; the change is applied to live shells
 * immediately and persisted to {@code config/vnodo-client.properties}.  The
 * screen deliberately does not replace Vettura's damage page: life, part
 * loss, glass, tyres and repair remain Vettura-owned.</p>
 *
 * <p>The layout is a fixed three-column grid: label, slider, value.  A compact
 * header is used when the logical screen height is smaller than the full
 * panel; the old fixed 326 px panel was taller than a 2x GUI on a 511 px
 * window, which pushed the title above the screen and made the rows look
 * uneven.</p>
 */
public final class VnodoSettingsScreen extends Screen {
    private static final int PANEL_W = 420;
    private static final int FULL_H = 326;
    private static final int MIN_H = 236;
    private static final int ACCENT = 0xFF48D6C4;
    private static final int TEXT = 0xFFE8F3F1;
    private static final int MUTED = 0xFF9AB0AC;
    private static final int PANEL = 0xF0182422;
    private static final int TRACK = 0xFF2B3B38;
    private static final int KNOB = 0xFF73E6D4;
    private static final int SLIDER_X = 148;
    private static final int SLIDER_W = 150;
    private static final int VALUE_X = 312;

    private final Screen parent;
    private int panelX;
    private int panelY;
    private int panelH;
    private int titleY;
    private int subtitleY;
    private int presetY;
    private int switchY;
    private int hudY;
    private int sliderY;
    private int rowH;
    private int bottomY;
    private int dragging = -1;

    public VnodoSettingsScreen(Screen parent) {
        super(Component.translatable("vnodo.settings.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.panelX = Math.max(4, (this.width - PANEL_W) / 2);
        this.panelH = Math.min(FULL_H, Math.max(MIN_H, this.height - 12));
        this.panelY = Math.max(4, (this.height - this.panelH) / 2);
        boolean compact = this.panelH < 336;
        this.titleY = this.panelY + (compact ? 6 : 10);
        this.subtitleY = this.titleY + 13;
        this.presetY = this.panelY + (compact ? 28 : 42);
        this.switchY = this.presetY + (compact ? 22 : 26);
        this.hudY = this.switchY + (compact ? 22 : 26);
        this.sliderY = this.hudY + (compact ? 22 : 28);
        this.rowH = compact
                ? Math.max(14, Math.min(20,
                        (this.panelH - (this.sliderY - this.panelY) - 34) / 7))
                : 24;
        this.bottomY = this.panelY + this.panelH - 26;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        graphics.fill(this.panelX, this.panelY, this.panelX + PANEL_W, this.panelY + this.panelH, PANEL);
        graphics.fill(this.panelX, this.panelY, this.panelX + PANEL_W, this.panelY + 2, ACCENT);
        graphics.drawString(this.font, this.title, this.panelX + 16, this.titleY, TEXT, false);
        graphics.drawString(this.font, Component.translatable("vnodo.settings.subtitle"),
                this.panelX + 16, this.subtitleY, MUTED, false);

        graphics.drawString(this.font, Component.translatable("vnodo.settings.preset"),
                this.panelX + 16, this.presetY + 5, MUTED, false);
        int px = this.panelX + 92;
        if (VnodoConfig.PHYSICS_LOCKED) {
            this.drawButton(graphics, px, this.presetY, 48, 18,
                    Component.translatable("vnodo.settings.preset.hard"), true, mouseX, mouseY);
            graphics.drawString(this.font, Component.translatable("vnodo.settings.locked"),
                    px + 56, this.presetY + 5, ACCENT, false);
        } else {
            this.drawButton(graphics, px, this.presetY, 62, 18,
                    Component.translatable("vnodo.settings.preset.default"),
                    "default".equals(VnodoConfig.preset()), mouseX, mouseY);
            this.drawButton(graphics, px + 68, this.presetY, 48, 18,
                    Component.translatable("vnodo.settings.preset.soft"),
                    "soft".equals(VnodoConfig.preset()), mouseX, mouseY);
            this.drawButton(graphics, px + 122, this.presetY, 48, 18,
                    Component.translatable("vnodo.settings.preset.hard"),
                    "hard".equals(VnodoConfig.preset()), mouseX, mouseY);
            graphics.drawString(this.font, Component.translatable("vnodo.settings.preset.custom"),
                    px + 180, this.presetY + 5,
                    "custom".equals(VnodoConfig.preset()) ? ACCENT : MUTED, false);
        }

        this.drawButton(graphics, this.panelX + 16, this.switchY, 150, 18,
                Component.translatable(VnodoConfig.enabled()
                        ? "vnodo.settings.enabled.on" : "vnodo.settings.enabled.off"),
                VnodoConfig.enabled(), mouseX, mouseY);
        graphics.drawString(this.font, Component.translatable("vnodo.settings.source"),
                this.panelX + 176, this.switchY + 5, MUTED, false);
        this.drawButton(graphics, this.panelX + 246, this.switchY, 56, 18,
                Component.literal("Vnodo"), "vnodo".equals(VnodoConfig.source()), mouseX, mouseY);
        this.drawButton(graphics, this.panelX + 306, this.switchY, 60, 18,
                Component.literal("Vettura"), "vettura".equals(VnodoConfig.source()), mouseX, mouseY);

        this.drawButton(graphics, this.panelX + 16, this.hudY, 150, 18,
                Component.translatable(VnodoConfig.debugHud()
                        ? "vnodo.settings.debug_hud.on" : "vnodo.settings.debug_hud.off"),
                VnodoConfig.debugHud(), mouseX, mouseY);
        graphics.drawString(this.font, Component.translatable("vnodo.settings.debug_hud.hint"),
                this.panelX + 176, this.hudY + 5, MUTED, false);

        for (int i = 0; i < VnodoConfig.REGIONS.length; i++) {
            String region = VnodoConfig.REGIONS[i];
            int ry = this.sliderY + i * this.rowH;
            int labelY = ry + Math.max(2, (this.rowH - 8) / 2);
            graphics.drawString(this.font, Component.translatable("vnodo.part." + region),
                    this.panelX + 16, labelY, TEXT, false);
            double value = VnodoConfig.value(region);
            graphics.drawString(this.font, Component.literal(String.format(Locale.ROOT, "%.2f", value)),
                    this.panelX + VALUE_X, labelY, MUTED, false);
            int sx = this.panelX + SLIDER_X;
            int sw = SLIDER_W;
            int sy = ry + this.rowH / 2 - 2;
            graphics.fill(sx, sy, sx + sw, sy + 4, TRACK);
            int fill = sx + (int) Math.round((value - 0.10) / 1.40 * sw);
            graphics.fill(sx, sy, fill, sy + 4, 0x6648D6C4);
            int knob = fill;
            graphics.fill(knob - 3, sy - 3, knob + 3, sy + 7, KNOB);
        }

        this.drawButton(graphics, this.panelX + 16, this.bottomY, 78, 18,
                Component.translatable("vnodo.settings.reset"), false, mouseX, mouseY);
        this.drawButton(graphics, this.panelX + PANEL_W - 94, this.bottomY, 78, 18,
                Component.translatable("vnodo.settings.done"), true, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        int px = this.panelX + 92;
        if (VnodoConfig.PHYSICS_LOCKED) {
            if (this.inside(mouseX, mouseY, px, this.presetY, 48, 18)) {
                this.applyPreset("hard");
                return true;
            }
        } else {
            if (this.inside(mouseX, mouseY, px, this.presetY, 62, 18)) {
                this.applyPreset("default");
                return true;
            }
            if (this.inside(mouseX, mouseY, px + 68, this.presetY, 48, 18)) {
                this.applyPreset("soft");
                return true;
            }
            if (this.inside(mouseX, mouseY, px + 122, this.presetY, 48, 18)) {
                this.applyPreset("hard");
                return true;
            }
        }
        if (this.inside(mouseX, mouseY, this.panelX + 16, this.switchY, 150, 18)) {
            VnodoConfig.setEnabled(!VnodoConfig.enabled());
            BamngVetturaPhysicsClient.applyTuning();
            return true;
        }
        if (this.inside(mouseX, mouseY, this.panelX + 246, this.switchY, 56, 18)) {
            VnodoConfig.setSource("vnodo");
            return true;
        }
        if (this.inside(mouseX, mouseY, this.panelX + 306, this.switchY, 60, 18)) {
            VnodoConfig.setSource("vettura");
            return true;
        }
        if (this.inside(mouseX, mouseY, this.panelX + 16, this.hudY, 150, 18)) {
            VnodoConfig.setDebugHud(!VnodoConfig.debugHud());
            return true;
        }
        int sx = this.panelX + SLIDER_X;
        int sw = SLIDER_W;
        for (int i = 0; i < VnodoConfig.REGIONS.length; i++) {
            int ry = this.sliderY + i * this.rowH;
            if (mouseX >= sx - 4 && mouseX <= sx + sw + 4
                    && mouseY >= ry + 2 && mouseY <= ry + this.rowH - 2) {
                this.dragging = i;
                this.slide(i, mouseX);
                return true;
            }
        }
        if (this.inside(mouseX, mouseY, this.panelX + 16, this.bottomY, 78, 18)) {
            VnodoConfig.reset();
            BamngVetturaPhysicsClient.applyTuning();
            return true;
        }
        if (this.inside(mouseX, mouseY, this.panelX + PANEL_W - 94, this.bottomY, 78, 18)) {
            this.onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.dragging >= 0) {
            this.slide(this.dragging, mouseX);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.dragging >= 0) {
            this.dragging = -1;
            VnodoConfig.save();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void onClose() {
        VnodoConfig.save();
        Minecraft.getInstance().setScreen(this.parent);
    }

    @Override
    public void removed() {
        // Covers ESC, the Done button and leaving the world while the screen
        // is open.  Sliders only mark the config dirty, so this is the final
        // guaranteed flush point.
        VnodoConfig.save();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    private void applyPreset(String preset) {
        VnodoConfig.applyPreset(preset);
        BamngVetturaPhysicsClient.applyTuning();
    }

    private void slide(int index, double mouseX) {
        int sx = this.panelX + SLIDER_X;
        double ratio = Mth.clamp((mouseX - sx) / (double) SLIDER_W, 0.0, 1.0);
        VnodoConfig.setValue(VnodoConfig.REGIONS[index], 0.10 + ratio * 1.40);
        BamngVetturaPhysicsClient.applyTuning();
    }

    private void drawButton(GuiGraphics graphics, int x, int y, int w, int h,
                            Component label, boolean active, int mouseX, int mouseY) {
        boolean hover = this.inside(mouseX, mouseY, x, y, w, h);
        int fill = active ? 0xFF2F8F82 : (hover ? 0xFF2A4A45 : 0xFF20332F);
        int border = active ? ACCENT : 0xFF3D5B55;
        graphics.fill(x, y, x + w, y + h, fill);
        graphics.fill(x, y, x + w, y + 1, border);
        graphics.fill(x, y + h - 1, x + w, y + h, border);
        graphics.fill(x, y, x + 1, y + h, border);
        graphics.fill(x + w - 1, y, x + w, y + h, border);
        graphics.drawCenteredString(this.font, label, x + w / 2, y + (h - 8) / 2, TEXT);
    }

    private boolean inside(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }
}
