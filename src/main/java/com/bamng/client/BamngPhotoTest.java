package com.bamng.client;

import com.bamng.BamngMod;
import com.antaurora.vettura.vehicle.PickupTruckEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Side camera over the truck already stopped at the wall. -Dbamng.photo4=true */
@Mod.EventBusSubscriber(modid = BamngMod.MODID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BamngPhotoTest {
    private static final Path LOG = Path.of("/tmp/bamng-photo.log");
    private static int tick;

    private BamngPhotoTest() {
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        boolean photo7 = Boolean.getBoolean("bamng.photo7");
        if (photo7) {
            runPhoto7();
            return;
        }
        boolean photo8 = Boolean.getBoolean("bamng.photo8");
        if (photo8) {
            runPhoto8();
            return;
        }
        if (!Boolean.getBoolean("bamng.photo6")) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.player.connection == null) {
            return;
        }
        tick++;
        try {
            if (tick == 1) {
                command(mc, "gamerule sendCommandFeedback false");
                command(mc, "gamemode spectator @s");
                command(mc, "tp @s -50 160 -20 0 30");
                log("photo4 side camera");
            } else if (tick == 15) {
                command(mc, "kill @e[type=!player,distance=..300]");
                command(mc, "time set noon");
                log("arena rebuilt");
            } else if (tick == 26) {
                command(mc, "summon vettura:pickup_t15 -50 151 -15 {Tags:[\"BamngSoft\",\"BamngRun\",\"BamngPhotoTarget\"],CustomName:'\"BAMNGRun\"',Fuel:100,Coolant:20}");
                log("truck summoned");
            } else if (tick == 32) {
                command(mc, "tp @e[type=vettura:pickup_t15,tag=BamngPhotoTarget,limit=1] -50 151 -15 0 0");
                command(mc, "tp @s -50 151 -13 0 0");
                command(mc, "gamemode creative @s");
                log("truck placed");
            } else if (tick == 38) {
                command(mc, "ride @s mount @e[type=vettura:pickup_t15,tag=BamngPhotoTarget,limit=1]");
                log("mounted");
            } else if (tick == 44) {
                command(mc, "vnodo driver");
                log("driver engaged");
            } else if (tick >= 48 && tick < 180) {
                mc.player.input.up = true;
                if (tick % 10 == 0) {
                    net.minecraft.world.entity.Entity truck = findTruck(mc);
                    log(String.format("drive tick=%d vehicle=%s z=%.2f",
                            tick, mc.player.getVehicle() == null ? "none" : "yes",
                            truck == null ? 0.0 : truck.getZ()));
                }
            } else if (tick == 180) {
                mc.player.input.up = false;
                command(mc, "ride @s dismount");
                log("braked");
            } else if (tick == 190) {
                command(mc, "gamemode spectator @s");
                command(mc, "tp @s -49.5 152.5 -17.0 180 8");
                log("front camera");
            } else if (tick == 210) {
                int count = 0;
                for (net.minecraft.world.entity.Entity entity : mc.level.getEntitiesOfClass(
                        net.minecraft.world.entity.Entity.class, mc.player.getBoundingBox().inflate(120.0))) {
                    if (entity.getType().toString().contains("pickup_t15")) {
                        count++;
                        log(String.format("truck %s at %.2f,%.2f,%.2f", entity.getCustomName(),
                                entity.getX(), entity.getY(), entity.getZ()));
                    }
                }
                log("nearby trucks=" + count + " PHOTO_READY");
            }
        } catch (Throwable error) {
            log("ERROR " + error);
        }
    }

    private static void runPhoto7() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.player.connection == null) {
            return;
        }
        tick++;
        if (tick == 1) {
            command(mc, "gamerule sendCommandFeedback false");
            command(mc, "gamemode spectator @s");
            command(mc, "time set noon");
            command(mc, "fill 95 150 -25 105 150 -14 stone");
            command(mc, "fill 95 151 -25 105 157 -24 stone");
            command(mc, "tp @s 104.5 152.2 -22.5 90 5");
            log("photo7 clean arena start");
        } else if (tick == 15) {
            command(mc, "kill @e[type=!player]");
        } else if (tick == 26) {
            command(mc, "summon vettura:pickup_t15 100 151 -15 {Rotation:[180f,0f],Tags:[\"BamngSoft\",\"BamngRun\",\"BamngPhotoTarget\"],CustomName:'\"BAMNGRun\"',Fuel:100,Coolant:20}");
            log("photo7 truck summoned");
        } else if (tick == 60) {
            moveTrucks(mc, 1000.0, 200.0, 1000.0, 180.0f, 0.0);
            log("photo7 field moved away");
        } else if (tick == 70) {
            log("photo7 target kept away");
        } else if (tick == 75) {
            command(mc, "summon vettura:pickup_t15 100 151 -20.0 {Rotation:[180f,0f],Tags:[\"BamngSoft\",\"BamngRun\",\"BamngPhotoTarget\"],CustomName:'\"BAMNGRun\"',Fuel:100,Coolant:20}");
            log("photo7 single target");
        } else if (tick == 85) {
            moveTrucks(mc, 100.0, 151.0, -22.0, 180.0f, -0.8);
            command(mc, "tp @s 104.5 152.2 -20.0 90 5");
            log("photo7 approach 1");
        } else if (tick == 87) {
            moveTrucks(mc, 100.0, 151.0, -22.3, 180.0f, -0.8);
            log("photo7 wall contact");
        } else if (tick == 130) {
            crushForPhoto();
            log("photo7 crush applied");
        } else if (tick == 95) {
            log("photo7 deeper");
        } else if (tick == 120) {
            command(mc, "fill 95 151 -25 105 157 -24 air");
            command(mc, "tp @s 104.5 152.2 -20.0 90 5");
            log("photo7 front view");
        } else if (tick == 105) {
            log("photo7 ready");
        }
    }

    private static void crushForPhoto() {
        for (var body : BamngVetturaPhysicsClient.activeBodies()) {
            for (var node : body.world.nodes) {
                // Simulate a 100 km/h frontal hit: fold the front zone
                // backward along world +Z by 0.65 m.  The old code used
                // node.localX, which is the spawn-world X offset and becomes
                // the lateral axis when the photo truck is spawned at 180
                // degrees; node.rest.x is the authored longitudinal front.
                double lx = node.rest.x;
                if (lx > 1.0 && "front".equals(node.group0)) {
                    double f = Math.min(1.0, (lx - 1.0) / 1.40);
                    node.plasticZ += 0.65 * f * f;
                    node.plasticY -= 0.15 * f;
                }
                node.position.set(node.targetX + node.plasticX,
                        node.targetY + node.plasticY,
                        node.targetZ + node.plasticZ);
            }
        }
    }

    private static void moveTrucks(Minecraft mc, double x, double y, double z,
                                   float yaw, double vz) {
        if (mc.level == null) {
            return;
        }
        for (PickupTruckEntity truck : mc.level.getEntitiesOfClass(
                PickupTruckEntity.class, new AABB(x - 80, y - 40, z - 80, x + 80, y + 40, z + 80))) {
            truck.setPos(x, y, z);
            truck.setYRot(yaw);
            truck.setXRot(0.0f);
            truck.setDeltaMovement(0.0, 0.0, vz);
        }
    }

    private static void runPhoto8() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.player.connection == null) {
            return;
        }
        tick++;
        if (tick == 1) {
            command(mc, "gamerule sendCommandFeedback false");
            command(mc, "gamemode spectator @s");
            command(mc, "time set noon");
            command(mc, "fill 95 150 -25 105 150 -14 stone");
            command(mc, "fill 95 151 -25 105 157 -24 stone");
            command(mc, "tp @s 104.5 152.2 -20.0 90 5");
            log("photo8 100kmh arena start");
        } else if (tick == 15) {
            command(mc, "kill @e[type=!player,distance=..300]");
        } else if (tick == 26) {
            command(mc, "summon vettura:pickup_t15 100 151 -18.0 {Rotation:[180f,0f],Tags:[\"BamngSoft\",\"BamngRun\",\"BamngPhotoTarget\"],CustomName:'\"BAMNGRun\"',Fuel:100,Coolant:20}");
            log("photo8 truck summoned");
        } else if (tick == 34) {
            command(mc, "tp @s 104.5 152.2 -20.0 90 5");
            log("photo8 side camera");
        } else if (tick >= 40 && tick <= 43) {
            double z = -18.0 - 1.39 * (tick - 39);
            moveTrucks(mc, 100.0, 151.0, z, 180.0f, 0.0);
            log("photo8 approach tick=" + tick + " z=" + z);
        } else if (tick == 44) {
            moveTrucks(mc, 100.0, 151.0, -23.2, 180.0f, 0.0);
            log("photo8 wall stop");
        } else if (tick == 70) {
            for (var body : BamngVetturaPhysicsClient.activeBodies()) {
                log(String.format("photo8 result dent=%.3f", body.world.plasticDepth()));
            }
            log("photo8 ready");
        }
    }

    private static net.minecraft.world.entity.Entity findTruck(Minecraft mc) {
        for (net.minecraft.world.entity.Entity entity : mc.level.getEntitiesOfClass(
                net.minecraft.world.entity.Entity.class, mc.player.getBoundingBox().inflate(120.0))) {
            if (entity.getCustomName() != null && "BAMNGRun".equals(entity.getCustomName().getString())) {
                return entity;
            }
        }
        return null;
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

