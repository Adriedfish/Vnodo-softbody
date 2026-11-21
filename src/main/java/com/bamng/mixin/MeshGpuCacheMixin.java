package com.bamng.mixin;

import com.antaurora.vextris.client.MeshGpuCache;
import com.antaurora.vextris.mesh.MeshLayer;
import com.antaurora.vextris.mesh.MeshModel;
import com.bamng.client.BamngGpuBypass;
import com.bamng.client.BamngRenderHooks;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keep BAMNG's per-vertex deformation on one path for the whole vehicle. */
@Mixin(MeshGpuCache.class)
public class MeshGpuCacheMixin {
    @Inject(method = "drawBone", at = @At("HEAD"), cancellable = true, remap = false)
    private static void bamng$drawBone(MeshModel model, String bone, MeshLayer layer,
                                       MeshGpuCache.PartSelector selector,
                                       ResourceLocation texture, PoseStack pose,
                                       int packedLight, int overlay,
                                       float a, float b, float c, float d,
                                       CallbackInfoReturnable<Boolean> cir) {
        // The geometry path is only recorded when Vextris rebuilds through
        // VextrisMeshes.byGeometry.  On a GPU-cache hit that mixin never runs,
        // so shouldSkinBone() has no path and returns false while the cached
        // mesh is drawn.  For the BAMNG-owned truck mainSkinActive() is the
        // authoritative signal: force the CPU path so the skin buffer sees
        // every vertex even when the geometry path has not been re-recorded.
        // Record the bone before the CPU fallback.  The skin buffer needs it
        // to skip only doors/glass/wheels, not every part whose pivot happens
        // to sit near the outer skin such as a bed side or rear quarter.
        BamngRenderHooks.bone(bone);
        if (BamngGpuBypass.active()
                && (BamngRenderHooks.shouldSkinBone(bone)
                    || BamngRenderHooks.mainSkinActive())) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "drawModel", at = @At("HEAD"), cancellable = true, remap = false)
    private static void bamng$drawModel(CallbackInfoReturnable<Boolean> cir) {
        if (BamngGpuBypass.active()
                && (BamngRenderHooks.shouldSkin()
                    || BamngRenderHooks.mainSkinActive())) {
            cir.setReturnValue(false);
        }
    }
}
