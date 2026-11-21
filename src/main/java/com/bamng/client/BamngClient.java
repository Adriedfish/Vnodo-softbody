package com.bamng.client;

import com.antaurora.vettura.registry.VetturaEntities;
import com.bamng.BamngMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = BamngMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class BamngClient {
    private BamngClient() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // The custom renderer only delegates every draw to Vettura's own
        // PickupTruckRenderer; it exists solely to wrap its buffer in the
        // soft-body skin.  Vettura's duplicate registration is suppressed in
        // VetturaRendererRegistrationMixin so this one stays in place.
        event.registerEntityRenderer(VetturaEntities.PICKUP_T15.get(), BamngPickupRenderer::new);
        event.registerEntityRenderer(VetturaEntities.VEHICLE.get(), BamngPickupRenderer::new);
        BamngMod.LOGGER.info("BAMNG skinned renderer registered");
    }
}
