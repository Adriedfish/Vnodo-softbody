package com.bamng.mixin;

import com.antaurora.vettura.vehicle.PickupTruckEntity;
import com.antaurora.vettura.vehicle.VehicleDamage;
import com.bamng.client.BamngVetturaPhysicsClient;
import com.bamng.core.VnodoCollisionField;
import com.bamng.core.VnodoConfig;
import com.bamng.core.VnodoServerField;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vnodo takes the crush-field input, not the damage model.
 *
 * <p>Vettura still owns {@code hurt}, {@code lost}, {@code bent},
 * {@code hull}, {@code applyTo} and the authored parts.  Only the solver
 * input is redirected: while a Vnodo-managed truck is active and the source
 * is Vnodo, {@code crushTick()} is skipped and {@code crushField()} returns
 * the field generated from the Vnodo lattice.  That makes Vettura rebuild
 * its own collision boxes and hull from Vnodo's permanent displacement.</p>
 */
@Mixin(value = VehicleDamage.class, remap = false)
public class VnodoDamageInputMixin {
    @Shadow(remap = false)
    @Final
    private PickupTruckEntity truck;

    private boolean vnodo$ownsField() {
        if (!VnodoConfig.enabled() || !"vnodo".equalsIgnoreCase(VnodoConfig.source())) {
            return false;
        }
        if (BamngVetturaPhysicsClient.isActive(this.truck)) {
            return true;
        }
        // The server entity is a different object from the client entity.
        // It consumes the quantized field mirror sent by the client so its
        // bodyBoxes()/bent()/hull() see the same shell as the renderer.
        return this.truck.level() != null
                && !this.truck.level().isClientSide
                && VnodoServerField.has(this.truck.getUUID());
    }

    @Inject(remap = false, method = "crushTick", at = @At("HEAD"), cancellable = true)
    private void vnodo$skipVetturaSolver(CallbackInfo ci) {
        if (this.vnodo$ownsField()) {
            ci.cancel();
        }
    }

    @Inject(remap = false, method = "crushField", at = @At("HEAD"), cancellable = true)
    private void vnodo$replaceField(CallbackInfoReturnable<float[]> cir) {
        if (!this.vnodo$ownsField()) {
            return;
        }
        VnodoCollisionField field = BamngVetturaPhysicsClient.collisionField(this.truck);
        if (field != null) {
            // Return the zero field too: after a repair the client must not
            // fall back to Vettura's stale crush array while Vnodo is active.
            cir.setReturnValue(field.field());
            return;
        }
        float[] shadow = VnodoServerField.field(this.truck.getUUID());
        if (shadow != null
                && shadow.length == ((VehicleDamage) (Object) this).frame().cell.length * 3) {
            cir.setReturnValue(shadow);
        }
    }

    @Inject(remap = false, method = "crushVersion", at = @At("HEAD"), cancellable = true)
    private void vnodo$replaceVersion(CallbackInfoReturnable<Integer> cir) {
        if (!this.vnodo$ownsField()) {
            return;
        }
        VnodoCollisionField field = BamngVetturaPhysicsClient.collisionField(this.truck);
        if (field != null) {
            cir.setReturnValue(field.version());
            return;
        }
        if (VnodoServerField.has(this.truck.getUUID())) {
            cir.setReturnValue(VnodoServerField.version(this.truck.getUUID()));
        }
    }
}
