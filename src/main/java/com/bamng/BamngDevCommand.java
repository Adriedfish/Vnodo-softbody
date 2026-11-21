package com.bamng;

import com.antaurora.vettura.vehicle.PickupTruckEntity;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Comparator;
import java.util.List;

/** Test-only command: force the nearest rider into seat 0 and start the engine. */
@Mod.EventBusSubscriber(modid = BamngMod.MODID)
public final class BamngDevCommand {
    private BamngDevCommand() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("vnodo")
                .then(Commands.literal("driver").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    List<PickupTruckEntity> trucks = player.level().getEntitiesOfClass(
                            PickupTruckEntity.class, player.getBoundingBox().inflate(12.0),
                            truck -> truck.isAlive() && BamngSpawnerItem.isSoft(truck));
                    if (trucks.isEmpty()) {
                        context.getSource().sendFailure(Component.literal("No Vnodo soft truck within 12 blocks"));
                        return 0;
                    }
                    PickupTruckEntity truck = trucks.stream()
                            .min(Comparator.comparingDouble(t -> t.distanceToSqr(player)))
                            .orElseThrow();
                    if (player.getVehicle() != truck) {
                        player.startRiding(truck, true);
                    }
                    truck.devSeat(0);
                    truck.devRun();
                    truck.devDrive(1, 90.0);
                    BamngMod.LOGGER.info("Vnodo dev driver seat forced for {}", truck.getUUID());
                    context.getSource().sendSuccess(() -> Component.literal("Vnodo: seat 0 + engine + gear 1"), true);
                    return 1;
                })));
    }
}
