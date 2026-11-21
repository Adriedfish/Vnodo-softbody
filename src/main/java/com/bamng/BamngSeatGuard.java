package com.bamng;

import com.antaurora.vettura.vehicle.PickupTruckEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Test/soft-body trucks always put the local rider in seat 0.
 *
 * <p>The generic {@code /ride} command picks the first free seat, which is the
 * passenger seat on this layout; starting the engine there is impossible.
 * Only trucks spawned by the BAMNG item are affected, normal Vettura vehicles
 * keep their own seat behaviour.</p>
 */
@Mod.EventBusSubscriber(modid = BamngMod.MODID)
public final class BamngSeatGuard {
    /** Trucks whose engine/gear were already forced, so driving can shift later. */
    private static final Set<UUID> STARTED = new HashSet<>();

    private BamngSeatGuard() {
    }

    /**
     * Belt-and-braces seat correction.  EntityMountEvent is not fired by every
     * mount path (notably the vanilla /ride command used in test worlds), so a
     * soft truck could leave the rider in the passenger seat with the engine
     * off.  This runs every server player tick and only changes a rider who is
     * genuinely in the wrong seat.
     */
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide) {
            return;
        }
        Player player = event.player;
        if (!(player.getVehicle() instanceof PickupTruckEntity truck)) {
            return;
        }
        if (!BamngSpawnerItem.isSoft(truck)) {
            return;
        }
        if (truck.seatOf(player) != 0) {
            truck.devSeat(0);
            BamngMod.LOGGER.info("BAMNG seat corrected to driver for {}", player.getName().getString());
        }
        if (STARTED.add(truck.getUUID())) {
            truck.devRun();
            truck.devDrive(1, 90.0);
            BamngMod.LOGGER.info("BAMNG test truck: driver seat + running, gear=1");
        }
    }

    @SubscribeEvent
    public static void onMount(EntityMountEvent event) {
        if (!event.isMounting() || !(event.getEntityBeingMounted() instanceof PickupTruckEntity truck)) {
            return;
        }
        if (!(event.getEntityMounting() instanceof Player)) {
            return;
        }
        // The display name is often overwritten by Vettura, so use the
        // spawner tag on the server (and the synced name as fallback).
        if (!BamngSpawnerItem.isSoft(truck)) {
            return;
        }
        if (event.getLevel().getServer() == null) {
            return;
        }
        // Let vanilla finish attaching the passenger, then move this rider to
        // the driver seat before the first input tick.
        event.getLevel().getServer().execute(() -> {
            truck.devSeat(0);
            // The key item currently conflicts with a "Bed" keybind on G, so
            // a BAMNG test truck starts in first gear directly.  Normal
            // Vettura trucks are untouched.
            truck.devDrive(1, 90.0);
            BamngMod.LOGGER.info("BAMNG test truck: driver seat + running, gear=1");
        });
    }
}
