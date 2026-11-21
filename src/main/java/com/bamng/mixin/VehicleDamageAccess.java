package com.bamng.mixin;

import com.antaurora.vettura.vehicle.PickupTruckEntity;
import com.antaurora.vettura.vehicle.VehicleDamage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to VehicleDamage's owning entity for render-side checks. */
@Mixin(value = VehicleDamage.class, remap = false)
public interface VehicleDamageAccess {
    @Accessor(value = "truck", remap = false)
    PickupTruckEntity bamng$truck();
}
