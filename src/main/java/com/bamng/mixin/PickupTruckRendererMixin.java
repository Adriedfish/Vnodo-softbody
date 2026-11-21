package com.bamng.mixin;

import com.antaurora.vettura.client.vehicle.PickupTruckRenderer;
import com.antaurora.vextris.client.VextrisMeshes;
import com.antaurora.vextris.mesh.MeshModel;
import com.antaurora.vettura.vehicle.PickupTruckEntity;
import com.bamng.client.BamngRenderHooks;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Supplies the current Vextris geometry identity to BAMNG's vertex buffer. */
@Mixin(PickupTruckRenderer.class)
public abstract class PickupTruckRendererMixin {
    @Inject(
            method = "render(Lcom/antaurora/vettura/vehicle/PickupTruckEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), remap = false)
    private void bamng$begin(PickupTruckEntity truck, float yaw, float partial,
                             PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo ci) {
        BamngRenderHooks.beginRender();
    }

    @Inject(
            method = "render(Lcom/antaurora/vettura/vehicle/PickupTruckEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("RETURN"), remap = false)
    private void bamng$end(PickupTruckEntity truck, float yaw, float partial,
                           PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo ci) {
        BamngRenderHooks.endRender();
    }

    @Redirect(
            method = "bent",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/antaurora/vextris/client/VextrisMeshes;byGeometry(Lnet/minecraft/resources/ResourceLocation;)Lcom/antaurora/vextris/mesh/MeshModel;"),
            remap = false)
    private static MeshModel bamng$trackGeometry(ResourceLocation geometry) {
        BamngRenderHooks.geometry(geometry == null ? null : geometry.getPath());
        return VextrisMeshes.byGeometry(geometry);
    }

    /** drawParts()/cached() translate to each bone pivot before emitting parts. */
    @Redirect(
            method = "drawParts",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/PoseStack;m_252880_(FFF)V",
                    remap = false),
            remap = false)
    private static void bamng$pivotDrawParts(PoseStack pose, float x, float y, float z) {
        BamngRenderHooks.bonePivot(x, y, z);
        pose.translate(x, y, z);
    }

    @Redirect(
            method = "cached",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/PoseStack;m_252880_(FFF)V",
                    remap = false),
            remap = false)
    private static void bamng$pivotCached(PoseStack pose, float x, float y, float z) {
        BamngRenderHooks.bonePivot(x, y, z);
        pose.translate(x, y, z);
    }
}
