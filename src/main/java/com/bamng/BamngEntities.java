package com.bamng;

import com.bamng.entity.SoftBodyVehicleEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class BamngEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, BamngMod.MODID);

    public static final RegistryObject<EntityType<SoftBodyVehicleEntity>> SOFT_BODY_VEHICLE =
            ENTITY_TYPES.register("soft_body_vehicle", () -> EntityType.Builder
                    .<SoftBodyVehicleEntity>of(SoftBodyVehicleEntity::new, MobCategory.MISC)
                    .sized(1.9F, 1.9F)
                    .clientTrackingRange(10)
                    .updateInterval(1)
                    .build(BamngMod.MODID + ":soft_body_vehicle"));

    private BamngEntities() {
    }

    public static void register(IEventBus bus) {
        ENTITY_TYPES.register(bus);
    }
}
