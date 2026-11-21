package com.bamng.client;

import com.antaurora.vettura.vehicle.PickupTruckEntity;
import com.bamng.BamngMod;
import com.bamng.core.SoftBeam;
import com.bamng.core.VehicleBody;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Headless driving harness for the VM test client.  Enabled only with
 * -Dbamng.autotest=true.  It sends commands through the connection instead of
 * the keyboard, so the vehicle can be positioned and driven even when the
 * window manager never gives GLFW keyboard focus.
 */
@Mod.EventBusSubscriber(modid = BamngMod.MODID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BamngAutoTest {
    private static final Path LOG = Path.of("/tmp/bamng-autotest.log");
    private static int tick;
    private static boolean armed;

    private BamngAutoTest() {
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Boolean.getBoolean("bamng.autotest")) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.player.connection == null) {
            return;
        }
        tick++;
        try {
            if (tick == 1) {
                command(mc, "kill @e[type=vettura:pickup_t15]");
                log("cleared old trucks");
            } else if (tick == 3) {
                command(mc, "summon vettura:pickup_t15 -50 150 -60 {Tags:[\"BamngSoft\",\"BamngRun\"],CustomName:'\"BAMNGRun\"',Fuel:100,Coolant:20}");
                log("summoned soft truck");
            } else if (tick == 6) {
                command(mc, "tp @e[type=vettura:pickup_t15,name=BAMNGRun,limit=1,sort=nearest] -50 150 -60 180 0");
                log("truck faced north");
            } else if (tick == 8) {
                command(mc, "gamemode spectator @s");
                command(mc, "tp @s -50 154 -52 180 15");
                log("spectator chase camera");
            } else if (tick == 14) {
                command(mc, "fill -58 150 -86 -42 157 -83 stone");
                command(mc, "time set noon");
                log("wall built");
            } else if (tick >= 20 && tick < 60) {
                net.minecraft.world.entity.Entity vehicle = findTruck(mc);
                if (vehicle != null) {
                    double z = Math.max(-82.0, vehicle.getZ() - 0.80);
                    command(mc, String.format("tp @e[type=vettura:pickup_t15,name=BAMNGRun,limit=1,sort=nearest] %.3f %.3f %.3f",
                            vehicle.getX(), vehicle.getY(), z));
                    command(mc, String.format("tp @s %.3f %.3f %.3f 180 15",
                            vehicle.getX(), vehicle.getY() + 3.2, z + 8.0));
                    if (tick % 5 == 0) {
                        VehicleBody body = BamngVetturaPhysicsClient.bodyFor((PickupTruckEntity) vehicle);
                        double speed = vehicle.getDeltaMovement().length() * 20.0;
                        int broken = body == null ? -1 : body.world.damage.brokenBeamCount();
                        log(String.format("tick=%d pos=%.2f,%.2f,%.2f speed=%.2f broken=%d",
                                tick, vehicle.getX(), vehicle.getY(), vehicle.getZ(), speed, broken));
                    }
                } else {
                    log("tick=" + tick + " no truck entity");
                }
            }
        } catch (Throwable error) {
            log("ERROR " + error);
        }
    }

    private static Entity findTruck(Minecraft mc) {
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : mc.level.getEntitiesOfClass(PickupTruckEntity.class,
                mc.player.getBoundingBox().inflate(200.0))) {
            if (entity.getCustomName() == null || !"BAMNGRun".equals(entity.getCustomName().getString())) {
                continue;
            }
            double distance = entity.distanceToSqr(mc.player);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entity;
            }
        }
        return best;
    }

    private static void command(Minecraft mc, String command) {
        mc.player.connection.sendCommand(command);
    }

    private static void log(String message) {
        try {
            Files.writeString(LOG, message + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }
}

