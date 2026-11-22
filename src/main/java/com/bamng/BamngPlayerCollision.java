package com.bamng;

import com.antaurora.vettura.vehicle.PickupTruckEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side "the truck can hit a person" response.
 *
 * <p>The soft body lives on the client, so it cannot damage a player in a
 * multiplayer world.  This bridge uses the authoritative Vettura entity
 * transform and its per-tick displacement: the same oriented box that the
 * shell occupies is tested against living entities, and a moving truck knocks
 * them out of the body with damage proportional to impact speed.</p>
 *
 * <p>This deliberately runs on the server only.  A client-side push would look
 * correct for the driver but would be rejected by the server on the next
 * movement packet, which is exactly the "car passes through a person" symptom.</p>
 */
@Mod.EventBusSubscriber(modid = BamngMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BamngPlayerCollision {
    /** Body half width from the T15 layout. */
    private static final double HALF_WIDTH = 0.88;
    private static final double FRONT = 2.40;
    private static final double REAR = -2.37;
    private static final double BODY_TOP = 1.845;
    /** A person is not necessarily at the chassis origin; include their feet. */
    private static final double MIN_IMPACT_SPEED = 0.75;
    private static final int DAMAGE_COOLDOWN_TICKS = 10;

    private static final Map<UUID, Vec3> LAST_TRUCK_POS = new HashMap<>();
    private static final Map<String, Integer> LAST_HIT_TICK = new HashMap<>();
    private static final Map<String, Vec3> LAST_PERSON_POS = new HashMap<>();
    private static final Map<String, Integer> PERSON_NEAR_SINCE = new HashMap<>();
    private static final Map<UUID, Double> LAST_TRUCK_SPEED = new HashMap<>();

    static {
        BamngMod.LOGGER.info("BAMNG player collision bridge loaded");
    }

    private BamngPlayerCollision() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        // Run before the entity tick.  Vettura's own collider pushes living
        // entities out of the hull during its tick; at END the person is
        // already displaced sideways and the impact is missed.
        if (event.phase != TickEvent.Phase.START || event.getServer() == null) {
            return;
        }
        int tick = event.getServer().getTickCount();
        for (ServerLevel level : event.getServer().getAllLevels()) {
            for (var entity : level.getAllEntities()) {
                if (entity instanceof PickupTruckEntity truck) {
                    collideTruck(level, truck, tick);
                }
            }
        }
        if ((tick & 31) == 0) {
            LAST_HIT_TICK.entrySet().removeIf(entry -> tick - entry.getValue() > 200);
        }
    }

    private static void collideTruck(ServerLevel level, PickupTruckEntity truck, int tick) {
        if (!truck.isAlive() || truck.isRemoved()) {
            LAST_TRUCK_POS.remove(truck.getUUID());
            LAST_TRUCK_SPEED.remove(truck.getUUID());
            return;
        }

        Vec3 now = truck.position();
        Vec3 previous = LAST_TRUCK_POS.put(truck.getUUID(), now);
        if (previous == null) {
            return;
        }
        double vx = (now.x - previous.x) / 0.05;
        double vz = (now.z - previous.z) / 0.05;
        double speed = Math.sqrt(vx * vx + vz * vz);
        // Vettura can teleport a vehicle after a reload.  Do not turn that into
        // a battering ram; the real per-tick motion is bounded well below this.
        if (!Double.isFinite(speed) || speed > 35.0) {
            return;
        }

        double impactSpeed = Math.max(speed, LAST_TRUCK_SPEED.getOrDefault(truck.getUUID(), 0.0));
        LAST_TRUCK_SPEED.put(truck.getUUID(), speed);
        double yaw = Math.toRadians(truck.getYRot());
        // VehiclePhysics.q is rotationY(180 - entityYaw) and its local forward
        // is (0,0,-1), so the world front at yaw=0 is +Z.  The old -sin/-cos
        // here put FRONT at the tailgate and made a person standing ahead of
        // the truck fall outside the collision box.
        double forwardX = Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        double rightX = Math.cos(yaw);
        double rightZ = -Math.sin(yaw);
        double truckY = truck.getY();

        // Broad phase around the oriented body, then the exact local-space
        // test.  The margin covers a player's width and a small collision skin.
        double radius = Math.max(FRONT, -REAR) + 1.2;
        var broad = truck.getBoundingBox().inflate(radius, 1.0, radius);
        var nearby = level.getEntitiesOfClass(LivingEntity.class, broad);
        for (LivingEntity entity : nearby) {
            if (!entity.isAlive() || entity.isSpectator()) {
                continue;
            }
            if (entity.getVehicle() instanceof PickupTruckEntity) {
                continue;
            }

            Vec3 pos = entity.position();
            double dx = pos.x - now.x;
            double dz = pos.z - now.z;
            double along = dx * forwardX + dz * forwardZ;
            double lateral = dx * rightX + dz * rightZ;
            double halfPerson = entity.getBbWidth() * 0.5;
            if (along < REAR - halfPerson || along > FRONT + halfPerson
                    || Math.abs(lateral) > HALF_WIDTH + halfPerson) {
                continue;
            }
            double entityMinY = entity.getBoundingBox().minY;
            double entityMaxY = entity.getBoundingBox().maxY;
            if (entityMaxY < truckY || entityMinY > truckY + BODY_TOP) {
                continue;
            }

            // Vettura's own collider runs during the entity tick and can shove
            // a person sideways before this bridge sees them.  Keep a wider
            // catch zone and remember the pre-shove position; a rapid shove
            // away from the hull is the impact signal.
            String personKey = truck.getUUID() + ":" + entity.getUUID();
            Vec3 lastPerson = LAST_PERSON_POS.put(personKey, pos);
            boolean wide = along >= REAR - 1.0 && along <= FRONT + 1.5
                    && Math.abs(lateral) <= HALF_WIDTH + 1.8;
            if (wide) {
                PERSON_NEAR_SINCE.putIfAbsent(personKey, tick);
            } else {
                int since = PERSON_NEAR_SINCE.getOrDefault(personKey, tick);
                if (tick - since > 12) {
                    PERSON_NEAR_SINCE.remove(personKey);
                }
            }
            double shoved = 0.0;
            if (lastPerson != null) {
                double sx = pos.x - lastPerson.x;
                double sz = pos.z - lastPerson.z;
                // Ignore ordinary walking and only count displacement away
                // from the truck centre line.
                double awayX = dx * sx + dz * sz;
                if (awayX > 0.0) {
                    shoved = Math.sqrt(sx * sx + sz * sz);
                }
            }
            int nearSince = PERSON_NEAR_SINCE.getOrDefault(personKey, tick);
            // The person can already be several metres to the side by the
            // time we run; what matters is that they were near the hull a
            // moment ago and have just been displaced.
            boolean recent = PERSON_NEAR_SINCE.containsKey(personKey)
                    && (tick - nearSince) <= 6;
            boolean bumped = recent && shoved > 0.25 && impactSpeed > MIN_IMPACT_SPEED;

            // Direction from the truck toward the person, used for push out
            // and knockback.  If the person is exactly on the centre line use
            // the truck's own travel direction.
            double pushX = dx;
            double pushZ = dz;
            double pushLen = Math.sqrt(pushX * pushX + pushZ * pushZ);
            if (pushLen < 1.0e-4) {
                pushX = vx;
                pushZ = vz;
                pushLen = Math.max(1.0e-4, Math.sqrt(pushX * pushX + pushZ * pushZ));
            }
            pushX /= pushLen;
            pushZ /= pushLen;

            double approach = vx * pushX + vz * pushZ;
            String key = truck.getUUID() + ":" + entity.getUUID();
            int lastHit = LAST_HIT_TICK.getOrDefault(key, Integer.MIN_VALUE / 2);
            boolean mayHit = tick - lastHit >= DAMAGE_COOLDOWN_TICKS;

            // Always separate the person from the body.  This is the part that
            // makes a parked truck solid even when it is not moving.
            double pushStrength = Math.min(0.75, 0.18 + Math.max(0.0, approach) * 0.08);
            entity.push(pushX * pushStrength, 0.12, pushZ * pushStrength);
            if (speed > 0.3) {
                BamngMod.LOGGER.info("BAMNG player contact entity={} speed={} approach={} along={} lateral={}",
                        entity.getName().getString(), impactSpeed, approach, along, lateral);
            }

            if (!mayHit || impactSpeed < MIN_IMPACT_SPEED || (!bumped && approach < 0.35)) {
                continue;
            }

            float damage = (float) Math.min(14.0, 2.0 + Math.max(approach, 0.0) * 1.15);
            boolean hurt = entity.hurt(level.damageSources().generic(), damage);
            double knock = Math.min(1.8, 0.35 + Math.max(approach, 0.0) * 0.14);
            entity.push(pushX * knock, Math.min(0.8, 0.28 + approach * 0.04), pushZ * knock);
            entity.hurtMarked = true;
            LAST_HIT_TICK.put(key, tick);
            BamngMod.LOGGER.info(
                    "BAMNG player impact entity={} speed={} approach={} damage={} hurt={}",
                    entity.getName().getString(), impactSpeed, approach, damage, hurt);
        }
    }
}
