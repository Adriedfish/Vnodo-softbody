package com.bamng.mixin;

import com.antaurora.vextris.mesh.MeshModel;
import com.antaurora.vettura.vehicle.PickupTruckEntity;
import com.antaurora.vettura.vehicle.VehicleDamage;
import com.bamng.client.BamngVetturaPhysicsClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * BAMNG replaces Vettura's dent mesh entirely.  Returning the undeformed model
 * at the dent entry point removes the original crumple visual without touching
 * non-BAMNG Vettura vehicles.
 */
@Mixin(targets = "com.antaurora.vettura.client.vehicle.VehicleDents", remap = false)
public class VehicleDentsMixin {
    @Inject(remap = false, method = "of", at = @At("HEAD"), cancellable = true)
    private static void bamng$skipOriginalDents(MeshModel model, VehicleDamage damage,
                                                 CallbackInfoReturnable<MeshModel> cir) {
        PickupTruckEntity truck = ((VehicleDamageAccess) (Object) damage).bamng$truck();
        if (BamngVetturaPhysicsClient.isActive(truck)) {
            cir.setReturnValue(model);
        }
    }

    /**
     * PickupTruckRenderer.warm() calls warming() before the first frame and
     * caches its result.  Cancelling only of() left a stale Vettura dent mesh
     * in place on already-warmed trucks, so the original crumple could still
     * show through.  Return the plain model for every BAMNG-managed truck.
     */
    @Inject(remap = false, method = "warming", at = @At("HEAD"), cancellable = true)
    private static void bamng$skipWarmDents(MeshModel model, VehicleDamage damage,
                                             CallbackInfoReturnable<MeshModel> cir) {
        PickupTruckEntity truck = ((VehicleDamageAccess) (Object) damage).bamng$truck();
        if (BamngVetturaPhysicsClient.isActive(truck)) {
            cir.setReturnValue(model);
        }
    }
}
