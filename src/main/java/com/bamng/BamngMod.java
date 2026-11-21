package com.bamng;

import com.bamng.core.VnodoConfig;
import com.mojang.logging.LogUtils;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

@Mod(BamngMod.MODID)
public final class BamngMod {
    public static final String MODID = "vnodo";
    public static final Logger LOGGER = LogUtils.getLogger();

    public BamngMod() {
        VnodoConfig.setConfigPath(FMLPaths.CONFIGDIR.get().resolve("vnodo-client.properties"));
        VnodoConfig.load();
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        BamngItems.register(modBus);
        BamngNetwork.register();
        LOGGER.info("Vnodo node-physics layer loaded");
    }
}
