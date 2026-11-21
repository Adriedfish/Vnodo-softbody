package com.bamng.mixin;

import com.antaurora.vettura.client.VetturaSettingsScreen;
import com.bamng.client.VnodoSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds a small vanilla-style Vnodo entry to Vettura's settings header.
 *
 * <p>Vettura's screen has no public init extension point and its layout is
 * drawn by a custom render pass, so a normal Button widget cannot be added
 * without rewriting the page.  Draw one extra 20x20 vanilla widget button
 * after the page and intercept its click before Vettura's own handler sees
 * it.  The icon is a small three-node graph, drawn with pixels so no extra
 * texture asset is needed.</p>
 */
@Mixin(value = VetturaSettingsScreen.class, remap = false)
public class VnodoSettingsMixin {
    private static final int ENTRY = 20;
    private static final ResourceLocation WIDGETS =
            new ResourceLocation("textures/gui/widgets.png");

    @Shadow(remap = false)
    private float panelX;
    @Shadow(remap = false)
    private float panelY;

    @Inject(remap = false, method = "m_88315_", at = @At("TAIL"))
    private void vnodo$drawEntry(GuiGraphics graphics, int mouseX, int mouseY,
                                 float partialTick, CallbackInfo ci) {
        float x = this.vnodoX();
        float y = this.vnodoY();
        boolean hover = mouseX >= x && mouseX < x + ENTRY
                && mouseY >= y && mouseY < y + ENTRY;
        // 46 = normal, 66 = hover in vanilla widgets.png.  Draw the button
        // as three vertical slices (4/12/4) instead of shrinking the whole
        // 200x20 atlas region; that keeps the original 1 px border crisp.
        int v = hover ? 66 : 46;
        graphics.blit(WIDGETS, (int) x, (int) y, 4, ENTRY,
                0.0F, (float) v, 4, 20, 256, 256);
        graphics.blit(WIDGETS, (int) x + 4, (int) y, 12, ENTRY,
                4.0F, (float) v, 192, 20, 256, 256);
        graphics.blit(WIDGETS, (int) x + 16, (int) y, 4, ENTRY,
                196.0F, (float) v, 4, 20, 256, 256);
        this.vnodo$drawNodeIcon(graphics, (int) x, (int) y, hover ? 0xFF2F8F82 : 0xFF3D5B55);
    }

    @Inject(remap = false, method = "m_6375_", at = @At("HEAD"), cancellable = true)
    private void vnodo$clickEntry(double mouseX, double mouseY, int button,
                                  CallbackInfoReturnable<Boolean> cir) {
        if (button != 0) {
            return;
        }
        float x = this.vnodoX();
        float y = this.vnodoY();
        if (mouseX >= x && mouseX < x + ENTRY && mouseY >= y && mouseY < y + ENTRY) {
            Minecraft.getInstance().setScreen(new VnodoSettingsScreen((Screen) (Object) this));
            cir.setReturnValue(true);
        }
    }

    private void vnodo$drawNodeIcon(GuiGraphics graphics, int x, int y, int color) {
        // Three nodes, two short links.
        graphics.fill(x + 5, y + 5, x + 7, y + 7, color);
        graphics.fill(x + 13, y + 5, x + 15, y + 7, color);
        graphics.fill(x + 9, y + 12, x + 11, y + 14, color);
        graphics.fill(x + 7, y + 6, x + 9, y + 7, color);
        graphics.fill(x + 11, y + 6, x + 13, y + 7, color);
        graphics.fill(x + 7, y + 7, x + 8, y + 12, color);
        graphics.fill(x + 12, y + 7, x + 13, y + 12, color);
    }

    private float vnodoX() {
        return this.panelX + 420.0F - 10.0F - ENTRY;
    }

    private float vnodoY() {
        return this.panelY + 6.0F;
    }
}
