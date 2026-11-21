package com.bamng;

import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod.EventBusSubscriber(modid = BamngMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class BamngItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, BamngMod.MODID);

    public static final RegistryObject<Item> SOFT_BODY_SPAWNER = ITEMS.register("soft_body_spawner",
            () -> new BamngSpawnerItem(new Item.Properties().stacksTo(1)));

    private BamngItems() {
    }

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }

    @SubscribeEvent
    public static void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(SOFT_BODY_SPAWNER);
        }
    }
}
