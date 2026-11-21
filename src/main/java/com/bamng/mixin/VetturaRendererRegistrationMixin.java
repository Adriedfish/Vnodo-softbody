package com.bamng.mixin;

import com.antaurora.vettura.client.VetturaClient;
import com.antaurora.vettura.registry.VetturaEntities;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.client.event.EntityRenderersEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Let Vettura register VEHICLE_PART, CARTON and its block-entity renderer, but
 * skip the two truck entity types.  BAMNG's own listener runs at EventPriority
 * LOWEST and installs the skinned delegating renderer for those.
 *
 * <p>Cancelling the whole {@code renderers} method (the previous version) also
 * removed the part/carton/workbench renderers, so detached parts and the
 * workbench became invisible.</p>
 */
@Mixin(VetturaClient.class)
public abstract class VetturaRendererRegistrationMixin {
    @Redirect(
            method = "renderers",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraftforge/client/event/EntityRenderersEvent$RegisterRenderers;"
                            + "registerEntityRenderer(Lnet/minecraft/world/entity/EntityType;"
                            + "Lnet/minecraft/client/renderer/entity/EntityRendererProvider;)V"),
            remap = false)
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void bamng$keepSkinnedRenderer(EntityRenderersEvent.RegisterRenderers event,
                                                  EntityType type,
                                                  EntityRendererProvider provider) {
        if (type == VetturaEntities.PICKUP_T15.get() || type == VetturaEntities.VEHICLE.get()) {
            return;
        }
        event.registerEntityRenderer(type, provider);
    }
}
