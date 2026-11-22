package com.bamng.client;

import com.antaurora.vettura.vehicle.PickupTruckEntity;
import com.antaurora.vettura.vehicle.VehicleDamage;
import com.antaurora.vettura.vehicle.physics.VehiclePhysics;
import com.bamng.BamngMod;
import com.bamng.BamngNetwork;
import com.bamng.core.SoftNode;
import com.bamng.core.VehicleBody;
import com.bamng.core.VnodoCollisionField;
import com.bamng.core.VnodoConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Client-side bridge between the real Vettura truck and the BAMNG soft body.
 *
 * <p>Since 0.8 the shell is no longer rigidly translated every tick.  It is a
 * force-driven lattice anchored at its core nodes; the front bumper, hood and
 * tailgate are deliberately unanchored so a wall contact produces a real
 * crush instead of being teleported through the wall.  The truck entity is
 * still the authoritative rigid body.</p>
 */
@Mod.EventBusSubscriber(modid = BamngMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BamngVetturaPhysicsClient {
    private static final Map<PickupTruckEntity, SoftRuntime> RUNTIMES = new WeakHashMap<>();

    private BamngVetturaPhysicsClient() {
    }

    static java.util.List<VehicleBody> activeBodies() {
        java.util.List<VehicleBody> result = new java.util.ArrayList<>();
        for (java.util.Map.Entry<PickupTruckEntity, SoftRuntime> entry : RUNTIMES.entrySet()) {
            PickupTruckEntity truck = entry.getKey();
            SoftRuntime runtime = entry.getValue();
            if (truck == null || truck.isRemoved()) {
                continue;
            }
            if (runtime != null && runtime.initialized && runtime.body != null) {
                result.add(runtime.body);
            }
        }
        return result;
    }

    /** Feed the real block shapes around the shell into the soft-body narrow phase. */
    private static void refreshWorldCollision(SoftRuntime runtime) {
        var level = Minecraft.getInstance().level;
        var world = runtime.body.world;
        // Clear the spatial index too.  Clearing only the list left every old
        // AABB in collisionGrid forever, so after a few refreshes the narrow
        // phase still tested tens of thousands of stale ground boxes.
        world.clearCollisionBoxes();
        if (level == null || world.nodes.isEmpty()) {
            return;
        }
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (SoftNode node : world.nodes) {
            minX = Math.min(minX, node.position.x);
            minY = Math.min(minY, node.position.y);
            minZ = Math.min(minZ, node.position.z);
            maxX = Math.max(maxX, node.position.x);
            maxY = Math.max(maxY, node.position.y);
            maxZ = Math.max(maxZ, node.position.z);
        }
        // Include the wall ahead before the rigid entity touches it.  Vettura
        // stops the truck about one metre short, so an AABB-only scan never
        // contained the wall and the soft nodes had nothing to hit.
        double pad = 3.5;
        BlockPos from = BlockPos.containing(minX - pad, minY - 1.0, minZ - pad);
        BlockPos to = BlockPos.containing(maxX + pad, maxY + 1.0, maxZ + pad);
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
            // The heightmap already resolves every block whose top is at or
            // below the road.  Skipping here avoids getCollisionShape() and
            // toAabbs() for the hundreds of dirt/stone blocks under the truck.
            if (pos.getY() + 1 <= world.groundY + 0.20) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            VoxelShape shape = state.getCollisionShape(level, pos);
            if (shape.isEmpty()) {
                continue;
            }
            for (AABB box : shape.toAabbs()) {
                // The heightmap plane already resolves the road.  Adding every
                // ground cube below it made 314 nodes test hundreds of coplanar
                // boxes and produced ~17k contacts per tick (100+ ms steps).
                // Only walls/structures above the road belong in the narrow
                // phase.
                if (box.maxY + pos.getY() <= world.groundY + 0.20) {
                    continue;
                }
                world.addCollisionBox(
                        box.minX + pos.getX(), box.minY + pos.getY(), box.minZ + pos.getZ(),
                        box.maxX + pos.getX(), box.maxY + pos.getY(), box.maxZ + pos.getZ());
            }
        }
    }

    static VehicleBody bodyFor(PickupTruckEntity truck) {
        SoftRuntime runtime = RUNTIMES.get(truck);
        return runtime == null ? null : runtime.body;
    }

    /** Renderer-facing body; null while the total Vnodo switch is off. */
    static VehicleBody skinBodyFor(PickupTruckEntity truck) {
        return isActive(truck) ? bodyFor(truck) : null;
    }

    /** Vettura's collision input for this truck, or null when Vnodo is off. */
    public static VnodoCollisionField collisionField(PickupTruckEntity truck) {
        if (!isActive(truck)) {
            return null;
        }
        SoftRuntime runtime = RUNTIMES.get(truck);
        return runtime == null ? null : runtime.collisionField;
    }

    /** Re-apply all persisted region multipliers to every live shell. */
    public static void applyTuning() {
        for (SoftRuntime runtime : RUNTIMES.values()) {
            if (runtime != null && runtime.body != null) {
                runtime.body.applyTuning();
            }
        }
    }

    /** Flush every attached shell before the client loses its level. */
    public static boolean isActive(PickupTruckEntity truck) {
        return VnodoConfig.enabled()
                && "vnodo".equals(VnodoConfig.source())
                && truck != null
                && RUNTIMES.containsKey(truck);
    }

    /**
     * Stop every client-side shell and clear the server-side collision shadow
     * that was sent for it.  Clearing RUNTIMES alone left the server field
     * alive, so switching to Vettura's own shell still fed the old soft hull
     * into collision and teleported/knocked trucks on the next spawn.
     */
    private static void releaseShell(PickupTruckEntity truck, SoftRuntime runtime) {
        if (truck == null || runtime == null) {
            return;
        }
        runtime.saveState(truck, true);
        if (Minecraft.getInstance().getConnection() != null) {
            BamngNetwork.sendCollisionField(truck.getUUID(), 0, new float[0]);
        }
        BamngRenderState.reset(truck);
    }

    private static void releaseAllShells() {
        for (Map.Entry<PickupTruckEntity, SoftRuntime> entry : RUNTIMES.entrySet()) {
            releaseShell(entry.getKey(), entry.getValue());
        }
        RUNTIMES.clear();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        // Fired before the client drops the level/entities on disconnect.
        // This is the reliable last chance to write a crash shape that was
        // still inside its one-second periodic save window, and to clear the
        // server shadow and render skin before the level reference disappears.
        releaseAllShells();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // Sliders are dirty while dragging.  Flush once per client tick even
        // when no world is loaded, so leaving the world cannot drop the last
        // edit.
        com.bamng.core.VnodoConfig.saveIfDirty();
        if (!VnodoConfig.enabled() || !"vnodo".equals(VnodoConfig.source())) {
            // Keep the last deformed shell on disk before releasing it, so
            // toggling Vnodo back on after a world reload restores the same
            // crash shape instead of starting from a clean truck.  The server
            // shadow must be cleared too; otherwise its stale hull survives
            // after the client switched back to Vettura.
            releaseAllShells();
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            // World exit / title screen: the entity may still be alive for a
            // tick, so this is the last safe point to flush the shell and
            // release its server shadow and render skin.
            releaseAllShells();
            return;
        }
        // Dead trucks used to stay in the WeakHashMap until GC, leaving a
        // frozen shell and a second HUD row after respawn.
        RUNTIMES.entrySet().removeIf(entry -> {
            boolean remove = entry.getKey() == null
                    || entry.getKey().isRemoved()
                    || entry.getKey().level() != minecraft.level;
            if (remove && entry.getValue() != null && entry.getKey() != null) {
                releaseShell(entry.getKey(), entry.getValue());
            }
            return remove;
        });
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (!(entity instanceof PickupTruckEntity truck) || !truck.isAlive()) {
                continue;
            }
            VehiclePhysics physics = truck.devPhysics();
            if (physics == null) {
                continue;
            }
            boolean riding = player.getVehicle() == truck;
            // All Vettura pickups are soft now.  The previous BamngSoft name /
            // tag gate is why an imported truck (and a bed-mounted spare) was
            // never attached.  The layout/spec are read from the entity below.
            if (!riding) {
                // Build and keep the shell for a Vettura pickup even when
                // nobody is sitting in it.  Previously the first runtime was
                // only created on mount, so a freshly spawned truck had no
                // soft surface or collision until the player got in.
                if (player.distanceToSqr(truck) > 24.0 * 24.0) {
                    continue;
                }
            }
            float throttle = riding && player.input.up ? 1.0F : 0.0F;
            float brake = riding && player.input.down ? 1.0F : 0.0F;
            float steer = riding ? (player.input.left ? 1.0F : 0.0F) - (player.input.right ? 1.0F : 0.0F) : 0.0F;
            if (truck.tickCount % 100 == 0) {
                var renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(truck);
                BamngMod.LOGGER.info("BAMNG renderer class={} truck={} type={}",
                        renderer == null ? "null" : renderer.getClass().getName(), truck.getUUID(), truck.getType());
            }
            SoftRuntime runtime = RUNTIMES.computeIfAbsent(truck, SoftRuntime::new);
            if (!runtime.initialized) {
                BamngMod.LOGGER.info("BAMNG attach {} at {}", truck.getUUID(), truck.position());
            }
            runtime.tick(truck, physics, throttle, brake, steer);
        }
        resolveBodyPairs();
    }

    private static void resolveBodyPairs() {
        if (RUNTIMES.size() < 2) {
            return;
        }
        java.util.List<java.util.Map.Entry<PickupTruckEntity, SoftRuntime>> entries =
                new java.util.ArrayList<>(RUNTIMES.entrySet());
        for (int i = 0; i < entries.size(); i++) {
            PickupTruckEntity a = entries.get(i).getKey();
            SoftRuntime ra = entries.get(i).getValue();
            if (a == null || ra == null || ra.body == null) {
                continue;
            }
            for (int j = i + 1; j < entries.size(); j++) {
                PickupTruckEntity b = entries.get(j).getKey();
                SoftRuntime rb = entries.get(j).getValue();
                if (b == null || rb == null || rb.body == null) {
                    continue;
                }
                // Only nearby shells can touch.  Without this guard eight
                // parked trucks cost 28 full 314x464 narrow-phase passes
                // every tick, which was the main source of frame hitching.
                if (a.distanceToSqr(b) > 8.0 * 8.0) {
                    continue;
                }
                // Do not turn spawn correction or the initial anchor pull
                // into vehicle-to-vehicle damage.
                if (ra.settleTicks > 0 || rb.settleTicks > 0) {
                    continue;
                }
                // Resolve both directions. resolveForeign() processes this
                // world's nodes against the other world's surface; a single
                // call leaves the opposite node/triangle direction unchecked
                // and makes head-on contacts depend on pair iteration order.
                ra.body.world.resolveForeign(rb.body.world);
                rb.body.world.resolveForeign(ra.body.world);
            }
        }
    }

    private static final class SoftRuntime {
        private final VehicleBody body;
        private final VnodoCollisionField collisionField;
        private boolean initialized;
        private int settleTicks;
        private int logTicks;
        private int lastBrokenBeams;
        private int lastBrokenTriangles;
        private boolean lastTailgateDetached;
        private float impact;
        private double lastX;
        private double lastY;
        private double lastZ;
        private double lastTruckX;
        private double lastTruckY;
        private double lastTruckZ;
        private float lastTruckYaw;
        /** Last yaw used to rotate accumulated world-space plastic offsets. */
        private double lastPlasticYaw;
        private double spawnYaw;
        private double boxX = Double.MAX_VALUE;
        private double boxY = Double.MAX_VALUE;
        private double boxZ = Double.MAX_VALUE;
        private int boxTick;
        private double lastDirX = 0.0;
        private double lastDirZ = 1.0;
        private double prevSpeed = 0.0;
        /** Four-tick entity speed window; drop is measured across the window. */
        private final double[] speedHistory = new double[4];
        private int speedHistoryIndex;
        /** Ticks since attach; crash proxies stay disarmed through spawn placement. */
        private int postAttachTicks;
        /** Consecutive near-rest ticks with no box contact. */
        private int stableTicks;
        private boolean crashArmed;
        /** Bit per VehicleDamage.Pane that Vnodo has already shattered. */
        private int glassBrokenMask;
        private final double[] paneOffset = new double[4];
        private final double[] panePlastic = new double[4];
        /** How many nodes in each pane's frame are past the bend limit. */
        private final int[] paneBendCount = new int[4];

        /** Real-world frontal crush target in metres, from impact speed. */
        private double crashDent = 0.0;
        /** Permanent front crash proxy, only filled by a forward impact. */
        private double frontCrashDepth = 0.0;
        /** Permanent tailgate crash proxy, only filled by a reverse impact. */
        private double rearCrashDepth = 0.0;
        /** Per-tick decay of the visual crash proxy; physical plastic remains. */
        private static final double CRASH_PROXY_DECAY = 0.965;
        /** Normal of the last accepted wall hit, used to kill the rigid rebound. */
        private double reboundNormalX;
        private double reboundNormalZ;
        private int reboundDampTicks;
        /** Short window of extra substeps after an impact. */
        private int crashHotTicks = 0;
        private int impactCooldown = 0;
        private int frontBreakCooldown = 0;
        private boolean substepsLogged;
        /** True after Vettura reports a non-empty repair list. */
        private boolean repairPending;
        /** True while the repair list still contains the vehicle BODY job. */
        private boolean repairBodyPending;
        /** Last shell state hash flushed to the per-vehicle NBT cache. */
        private long lastSavedStateHash = Long.MIN_VALUE;
        private int stateSaveTicks;
        private int lastSentCollisionVersion = Integer.MIN_VALUE;
        private boolean lastSentCollisionDeformed;
        private int collisionSendTicks;
        /** Scratch output for the allocation-free rigid sweep. */
        private final double[] sweepOut = new double[3];
        private double comBaseX;
        private double comBaseY;
        private double comBaseZ;
        private double bodyBaseX;
        private double bodyBaseY;
        private double bodyBaseZ;

        private SoftRuntime(PickupTruckEntity truck) {
            var type = truck.vehicleType();
            this.body = VehicleBody.create(type.layout, type.spec, 0.82);
            this.collisionField = new VnodoCollisionField(this.body, type.layout);
            // The shell is a crushable skin, not a self-colliding bag.  Wall
            // and ground contacts use CollisionBox; running 464 triangles
            // against 314 nodes every 4 substeps was still pure waste.
            this.body.world.triangleCollision = false;
            this.body.world.damage.addListener(new com.bamng.core.DamageGraph.Listener() {
                @Override
                public void onBeamBroken(com.bamng.core.SoftBeam beam) {
                    body.timeline.record("beamBroken", beam.a + "->" + beam.b + " " + beam.breakGroups);
                    // A 100 km/h hit can break hundreds of beams in one tick;
                    // writing every one at INFO turns the crash frame into
                    // synchronous log I/O.  Keep the aggregate HUD/log line
                    // and expose individual edges only at DEBUG.
                    BamngMod.LOGGER.debug("BAMNG event beamBroken {} {} {}", beam.a, beam.b, beam.breakGroups);
                }

                @Override
                public void onTriangleBroken(com.bamng.core.SoftTriangle triangle) {
                    body.timeline.record("triangleBroken", triangle.id + " " + triangle.a);
                    BamngMod.LOGGER.debug("BAMNG event triangleBroken {} {}", triangle.id, triangle.a);
                }

                @Override
                public void onGroupBroken(String group) {
                    body.timeline.record("groupBroken", group);
                    BamngMod.LOGGER.info("BAMNG event groupBroken {}", group);
                }

                @Override
                public void onPartDetached(String part) {
                    body.timeline.record("partDetached", part);
                    BamngMod.LOGGER.info("BAMNG event partDetached {}", part);
                }
            });
            BamngMod.LOGGER.info(
                    "BAMNG body layout front={} rear={} halfWidth={} top={} mass={} nodes={} beams={} triangles={}",
                    this.body.front, this.body.rear, this.body.halfWidth, this.body.top,
                    type.spec.mass, this.body.world.nodes.size(),
                    this.body.world.beams.size(), this.body.world.triangles.size());
            int foldBeams = 0;
            double foldStep = 0.0;
            double foldHardening = 0.0;
            for (com.bamng.core.SoftBeam beam : this.body.world.beams) {
                if (beam.foldStep > 0.0) {
                    foldBeams++;
                    foldStep = beam.foldStep;
                    foldHardening = beam.foldHardening;
                }
            }
            BamngMod.LOGGER.info("BAMNG fold enabled beams={} step={} hardening={}",
                    foldBeams, foldStep, foldHardening);
            int anchored = 0;
            for (SoftNode node : this.body.world.nodes) {
                if (node.anchored) {
                    anchored++;
                }
            }
            BamngMod.LOGGER.info("BAMNG anchors={} free={} forceDamage=true k=900k thickness={}m",
                    anchored, this.body.world.nodes.size() - anchored,
                    com.bamng.core.SoftBodyWorld.CONTACT_THICKNESS);
            BamngMod.LOGGER.info("BAMNG tires={} tireClosed={} tireVolume={}",
                    this.body.tires.size(),
                    this.body.tires.stream().allMatch(t -> t.chamber.closed),
                    String.format(java.util.Locale.ROOT, "%.4f",
                            this.body.tires.stream().mapToDouble(t -> t.chamber.volume).sum()));
            BamngMod.LOGGER.info("BAMNG pressure volume={} closed={} convex={} spawnContacts={}",
                    String.format(java.util.Locale.ROOT, "%.2f", this.body.pressure.volume),
                    this.body.pressure.closed, this.body.pressure.convex,
                    this.body.world.spawnContacts.size());
        }

        private void tick(PickupTruckEntity truck, VehiclePhysics physics, float throttle, float brake, float steer) {
            if (!this.initialized) {
                this.init(truck, physics);
            }
            this.checkRepair(truck);

            int groundY = truck.level().getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    (int) Math.floor(truck.getX()),
                    (int) Math.floor(truck.getZ()));
            int columnY = truck.getBlockY();
            int effectiveGround = groundY > -60 ? groundY : columnY;
            this.body.world.groundY = effectiveGround;

            double truckX = truck.getX();
            double truckY = truck.getY();
            double truckZ = truck.getZ();
            float truckYaw = truck.getYRot();
            this.lastTruckYaw = truckYaw;

            double shiftX = truckX - this.lastTruckX;
            double shiftY = truckY - this.lastTruckY;
            double shiftZ = truckZ - this.lastTruckZ;
            boolean teleported = !Double.isFinite(shiftX) || !Double.isFinite(shiftY)
                    || !Double.isFinite(shiftZ)
                    || (shiftX * shiftX + shiftY * shiftY + shiftZ * shiftZ) > 16.0;

            this.updateTargets(truckX, truckY, truckZ, truckYaw, !teleported);
            this.collisionField.update(truckX, truckY, truckZ, truckYaw);
            if (teleported) {
                this.resetToTargets();
                this.prevSpeed = 0.0;
            }
            // Collision boxes are rebuilt once per tick by
            // refreshWorldCollision(); collectColliders() was a second full
            // 11x11x5 block scan whose result was immediately cleared.

            double speed = Math.sqrt(shiftX * shiftX + shiftZ * shiftZ) / 0.05;
            // Keep the previous tick's speed for the post-step box-contact
            // fallback below; this.prevSpeed is overwritten before world.step.
            double previousTickSpeed = this.prevSpeed;
            // Spawn placement, seat corrections and the first fall onto the
            // terrain can move the entity several blocks in one tick.  Those
            // look exactly like a 100 km/h impact to the decel proxy and were
            // registering a phantom crash on mount.  Arm the proxies only
            // after the truck has either rested cleanly for a few ticks or
            // survived the full spawn window.
            this.postAttachTicks++;
            if (!this.crashArmed && this.settleTicks <= 0) {
                if (speed < 1.5 && this.body.world.lastStepBoxContacts == 0 && !teleported) {
                    this.stableTicks++;
                } else {
                    this.stableTicks = 0;
                }
                if (this.stableTicks >= 8 || this.postAttachTicks >= 40) {
                    this.crashArmed = true;
                    // Drop any spawn displacement speed so the first real
                    // impact has to build its own speed history.
                    this.prevSpeed = 0.0;
                    previousTickSpeed = 0.0;
                    this.stableTicks = 0;
                    BamngMod.LOGGER.info("BAMNG crash detection armed after {} ticks", this.postAttachTicks);
                }
            }
            // Front-only contact count is no longer a crash signal by itself.
            // The authoritative signal is the tall-wall marker written by
            // SoftBodyWorld during this tick: a low terrain lip cannot write
            // it, and a near-vertical landing normal is excluded inside the
            // marker.  A world-space impulse or a speed threshold is not used.
            if (!teleported && speed > 0.5) {
                double len = Math.sqrt(shiftX * shiftX + shiftZ * shiftZ);
                this.lastDirX = shiftX / len;
                this.lastDirZ = shiftZ / len;
            }
            double windowDrop = this.recordAndWindowDrop(speed);
            if (this.impactCooldown > 0) {
                this.impactCooldown--;
            }
            if (this.crashHotTicks > 0) {
                if (this.crashHotTicks == 1) {
                    BamngMod.LOGGER.info("BAMNG crash settle foldEvents={} panelFront={}",
                            this.body.world.foldEventCount(), this.body.world.panelFrontDepth());
                }
                this.crashHotTicks--;
            }

            // Deliberately no truck.damage().body() proxy here.  That value
            // is changed by Vettura's own server damage and by ordinary
            // non-front hits, so using it as a front-crash signal duplicated
            // the original damage and produced phantom nose folds.  Block
            // contact and the pre-step deceleration proxy are the only
            // authoritative sources.
            // A teleport produces one enormous synthetic shift; do not carry
            // that into the next tick as a false "impact speed".
            this.prevSpeed = teleported ? 0.0 : speed;

            if (this.settleTicks <= 0) {
                this.applyImpacts(physics, truck, speed, windowDrop);
            }

            this.body.world.throttle = 0.0;
            this.body.world.brake = 0.0;
            this.body.world.steer = 0.0;
            // The generic fallback lattice is deliberately damped and has a
            // soft crumple zone.  Compute the stability recommendation once
            // (SoftBodyWorld caches it); normal driving does not need the
            // full 30-substep crash rate.
            // Root fix for the spawn/tailgate explosion: 20 substeps is
            // below the T15 lattice's stability threshold.  A headless replay
            // of init() at 20 substeps reproduced the 8-9 m tailgate throw in
            // the second tick; the same replay at the recommended 40 substeps
            // stayed at 0.11 m and normal rest bounds.  The old code halved
            // the recommendation and picked 12-30 by state, so idle, driving
            // and crash all ran in the unstable band.  Never go below what the
            // solver itself asks for; the extra cost is ~3 ms/tick.
            int recommended = this.body.world.recommendedSubsteps(0.05);
            int substeps = Math.max(1, recommended);
            if (!this.substepsLogged) {
                this.substepsLogged = true;
                BamngMod.LOGGER.info("BAMNG solver substeps={} recommended={} contacts={}",
                        substeps, recommended, this.body.world.lastContacts);
            }
            this.body.timeline.tick();
            double boxShift = Math.abs(truckX - this.boxX)
                    + Math.abs(truckY - this.boxY) + Math.abs(truckZ - this.boxZ);
            // At 100 km/h the truck moves 1.4 blocks/tick, so the old
            // boxShift>0.20 test rebuilt the 11x11x5 block collider every
            // tick.  The scan already carries a 3.5-block pad, enough for two
            // ticks of travel; refresh at most every second tick while moving
            // and every ten ticks while parked.
            boolean moved = boxShift > 0.20 && truck.tickCount - this.boxTick >= 2;
            if (moved || truck.tickCount - this.boxTick >= 10) {
                refreshWorldCollision(this);
                this.boxX = truckX;
                this.boxY = truckY;
                this.boxZ = truckZ;
                this.boxTick = truck.tickCount;
            }
            // DamageGraph only runs during a confirmed crash window.  A box
            // contact by itself is not a crash: a truck parked against a wall,
            // spawn settling and a slow squeeze all keep contact counts above
            // zero and previously lost 1,700+ beams without any impact.
            // The first wall contact is resolved inside world.step, before the
            // client can run registerCrash.  Arm the wall plastic path here so
            // that step can write its own local dent instead of returning the
            // whole impact as an elastic rebound.
            this.body.world.crashGateArmed = this.settleTicks <= 0
                    && this.crashArmed && !teleported;
            this.body.world.damageUpdateEnabled = this.settleTicks <= 0
                    && this.crashHotTicks > 0;
            // Rigid follow moves positions, not soft velocity.  Give the
            // narrow phase the truck's actual world velocity so a 100 km/h
            // first-contact frame has a real closing speed instead of looking
            // like a stationary lean against the wall.
            double rigidScale = teleported ? 0.0 : 1.0 / 0.05;
            this.body.world.rigidVelX = shiftX * rigidScale;
            this.body.world.rigidVelY = 0.0;
            this.body.world.rigidVelZ = shiftZ * rigidScale;
            // Vettura's hull hits the block first and stops the entity, so by
            // the time the soft shell reaches the wall the shift above is
            // already down to a few m/s.  Hand over the pre-hit speed so the
            // wall contact can size its crush from the energy that actually
            // arrived instead of from the leftover crawl.
            double approach = this.crashArmed ? Math.max(previousTickSpeed,
                    this.speedWindowMax()) : 0.0;
            this.body.world.crashApproachSpeed = teleported ? 0.0 : approach;
            this.body.world.crashApproachDirX = this.lastDirX;
            this.body.world.crashApproachDirZ = this.lastDirZ;
            this.body.world.step(0.05, substeps);

            this.clampToTruck();
            // The soft step can add permanent plastic.  Re-sample after the
            // clamp so Vettura's next bodyBoxes()/hull() query sees the same
            // shape the renderer is about to draw.
            this.collisionField.update(truckX, truckY, truckZ, truckYaw);
            this.syncCollisionField(truck, false);
            this.updateGlassDamage(truck);
            // A real block contact is stronger evidence than the deceleration
            // proxy.  Vettura normally stops the rigid truck at the wall, but
            // on some ticks the entity has already stopped before the client
            // sees the previous high speed.  The authoritative signal here is
            // the tall-wall marker written by SoftBodyWorld during this tick;
            // a low terrain lip or a vertical landing normal never writes it.
            boolean frontWallHit = this.body.world.lastStepFrontWallContacts > 1
                    && this.body.world.lastStepFrontWallImpact > 3.0;
            boolean rearWallHit = this.body.world.lastStepRearWallContacts > 1
                    && this.body.world.lastStepRearWallImpact > 3.0;
            double frontWallDot = -(this.body.world.lastStepFrontWallNormalX * this.lastDirX
                    + this.body.world.lastStepFrontWallNormalZ * this.lastDirZ);
            double rearWallDot = -(this.body.world.lastStepRearWallNormalX * this.lastDirX
                    + this.body.world.lastStepRearWallNormalZ * this.lastDirZ);
            frontWallHit = frontWallHit && frontWallDot > 0.25;
            rearWallHit = rearWallHit && rearWallDot > 0.25;
            double impactSpeed = Math.max(previousTickSpeed, this.speedWindowMax());
            boolean wallImpact = this.crashArmed && this.settleTicks <= 0
                    && this.impactCooldown <= 0 && !teleported
                    && impactSpeed > 8.0
                    && (frontWallHit || rearWallHit);
            if (wallImpact && (rearWallHit && this.backwardImpact()
                    || frontWallHit && this.forwardImpact())) {
                boolean rearHit = rearWallHit && this.backwardImpact();
                double wallSpeedLoss = rearHit
                        ? this.body.world.lastStepRearWallSpeedLoss
                        : this.body.world.lastStepFrontWallSpeedLoss;
                double hitX = truckX + (rearHit ? -1.0 : 1.0) * this.lastDirX * 2.35;
                double hitY = truckY + 0.55;
                double hitZ = truckZ + (rearHit ? -1.0 : 1.0) * this.lastDirZ * 2.35;
                this.body.world.damage.setBreakZone(hitX, hitY, hitZ, 1.35,
                        this.breakBudgetFor(impactSpeed), rearHit ? "tailgate" : "front");
                boolean registered = this.registerCrash(impactSpeed, wallSpeedLoss);
                if (!registered) {
                    // Glancing contact: do not push the shell, throw parts,
                    // log an impact, or start a cooldown that would mask the
                    // next real hit.
                    this.body.world.damage.clearBreakZone();
                } else {
                    if (wallSpeedLoss >= 2.5) {
                        // Vettura's rigid hull still applies its own BOUNCE after
                        // the soft-body wall contact.  Kill the outward normal
                        // component for a few ticks so the hit settles into the
                        // crush instead of launching the truck back like a ball.
                        this.reboundNormalX = rearHit
                                ? this.body.world.lastStepRearWallNormalX
                                : this.body.world.lastStepFrontWallNormalX;
                        this.reboundNormalZ = rearHit
                                ? this.body.world.lastStepRearWallNormalZ
                                : this.body.world.lastStepFrontWallNormalZ;
                        this.reboundDampTicks = 8;
                    }
                    if (!rearHit) {
                        this.pushFront(hitX, hitY, hitZ, this.lastDirX, this.lastDirZ, impactSpeed);
                    }
                    BamngMod.LOGGER.info(
                            "BAMNG impact source={} at {} {} {} speed={} wallImpact={} wallDot={}",
                            rearHit ? "wall-rear" : "wall-front", hitX, hitY, hitZ,
                            impactSpeed, rearHit
                                    ? this.body.world.lastStepRearWallImpact
                                    : this.body.world.lastStepFrontWallImpact,
                            rearHit ? rearWallDot : frontWallDot);
                    double auditDrop = Math.max(windowDrop, wallSpeedLoss);
                    BamngAuditLog.impact(rearHit ? "wall-rear" : "wall-front",
                            hitX, hitY, hitZ, speed, impactSpeed, auditDrop);
                    this.impactCooldown = 12;
                }
            }
            if (!wallImpact) {
                // The old proxy was a session-long max: one 60 km/h terrain
                // touch kept targetCrush=0.556 even after the truck slowed to
                // 5 m/s.  Decay the visual target; the permanent plastic depth
                // is still read separately by setFrontDepth/setRearDepth.
                // Only the short-lived elastic proxy decays.  The permanent
                // front/rear crush depth must stay until the Vettura repair
                // path resets it; decaying it here made a real fold slowly
                // shrink back while the plastic field was still there.
                this.crashDent *= CRASH_PROXY_DECAY;
            }
            BamngAuditLog.tick(truck.tickCount, truck, this.body, speed,
                    truckX, truckY, truckZ, truckYaw, substeps);
            this.dampWallRebound(physics);
            // Without this the "speed" above is the distance from the spawn
            // point divided by one tick, so it never drops when the truck
            // stops and the deceleration crash proxy can never fire.
            this.lastTruckX = truckX;
            this.lastTruckY = truckY;
            this.lastTruckZ = truckZ;

            if (this.frontBreakCooldown > 0) {
                this.frontBreakCooldown--;
            }
            int brokenBeams = this.body.world.damage.brokenBeamCount();
            int brokenTriangles = this.body.world.damage.brokenTriangleCount();
            boolean tailgateDetached = !this.body.world.damage.parts.visible("tailgate");
            if (brokenBeams != this.lastBrokenBeams || brokenTriangles != this.lastBrokenTriangles
                    || tailgateDetached != this.lastTailgateDetached) {
                BamngMod.LOGGER.info(
                        "BAMNG damage brokenBeams={} brokenTriangles={} groups={} tailgateDetached={}",
                        brokenBeams, brokenTriangles, this.body.world.damage.damagedGroups(), tailgateDetached);
                this.lastBrokenBeams = brokenBeams;
                this.lastBrokenTriangles = brokenTriangles;
                this.lastTailgateDetached = tailgateDetached;
            }

            this.logTicks++;
            if ((this.logTicks % 200) == 0) {
                BamngMod.LOGGER.info(
                        "BAMNG ground groundY={} truckY={} minY={} center={} contacts={} triContacts={} sleepTris={} warm={} pen={} bodyMaxSpeed={} stepMs={} isolated={} v={}",
                        effectiveGround, truckY, this.body.world.minY(), this.body.world.center(),
                        this.body.world.lastContacts, this.body.world.lastTriangleContacts,
                        this.body.world.sleepingTriangles,
                        String.format("%.2f", this.body.world.contacts.warmStartRatio()),
                        this.body.world.maxPenetration, this.body.world.maxSpeed(),
                        String.format(java.util.Locale.ROOT, "%.2f", this.body.world.lastStepNanos / 1_000_000.0),
                        this.body.world.isolated, physics.v);
            }

            this.updateRenderScale(truck);
            this.saveState(truck, false);
            if (truck.tickCount % 200 == 0) {
                SoftNode top = null;
                double best = 0.0;
                for (SoftNode node : this.body.world.nodes) {
                    double p2 = node.plasticX * node.plasticX + node.plasticY * node.plasticY
                            + node.plasticZ * node.plasticZ;
                    if (p2 > best) { best = p2; top = node; }
                }
                if (top != null) {
                    // Compare the live node against target + plastic.  The old
                    // position-target read included the anchor spring and was
                    // not body-local, so it jumped between 0.19 and 0.97 m at
                    // the same plastic value during a bounce.
                    double tx = top.targetX + top.plasticX;
                    double ty = top.targetY + top.plasticY;
                    double tz = top.targetZ + top.plasticZ;
                    double dx = top.position.x - tx;
                    double dy = top.position.y - ty;
                    double dz = top.position.z - tz;
                    double yaw = Math.toRadians(this.lastTruckYaw);
                    double localX = dx * Math.cos(yaw) + dz * Math.sin(yaw);
                    double localZ = -dx * Math.sin(yaw) + dz * Math.cos(yaw);
                    double plx = top.plasticX * Math.cos(yaw) + top.plasticZ * Math.sin(yaw);
                    double plz = -top.plasticX * Math.sin(yaw) + top.plasticZ * Math.cos(yaw);
                    BamngMod.LOGGER.info(
                            "BAMNG node probe group={} part={} plastic={} bodyLocal=[{},{}] plasticLocal={} dynDelta={}",
                            top.group0, top.part, Math.sqrt(best),
                            String.format(java.util.Locale.ROOT, "%.3f", localX),
                            String.format(java.util.Locale.ROOT, "%.3f", localZ),
                            String.format(java.util.Locale.ROOT, "%.3f",
                                    Math.sqrt(plx * plx + plz * plz)),
                            String.format(java.util.Locale.ROOT,
                                    "%.3f", Math.sqrt(dx * dx + dy * dy + dz * dz)));
                }
            }
            com.bamng.core.Vec3 center = this.body.world.center();
            this.lastX = center.x;
            this.lastY = center.y;
            this.lastZ = center.z;

            if (this.settleTicks > 0) {
                this.settleTicks--;
                if (this.settleTicks == 0) {
                    this.impact = 0.0F;
                    // The settle window may have contained a large spawn
                    // correction.  Do not carry it into the first armed tick.
                    this.prevSpeed = 0.0;
                    this.lastBrokenBeams = this.body.world.damage.brokenBeamCount();
                    this.lastBrokenTriangles = this.body.world.damage.brokenTriangleCount();
                    BamngMod.LOGGER.info(
                            "BAMNG settled center={} minY={} maxSpeed={} beams={} triangles={}",
                            center, this.body.world.minY(), this.body.world.maxSpeed(),
                            this.body.world.beams.size(), this.body.world.triangles.size());
                }
            }
        }

        /**
         * Rebuild every world target from the current truck transform.  The
         * local offsets are captured once at spawn, so there is no accumulated
         * drift from repeatedly rotating the whole lattice.
         */
        private void updateTargets(double truckX, double truckY, double truckZ, float truckYaw,
                                   boolean sweepFollow) {
            // Plastic offsets are accumulated in world axes by the collision
            // solver. Rotate them by the truck's yaw delta before rebuilding
            // the anchor targets so a dent follows the vehicle when it turns.
            boolean rotatePlastic = truckYaw != this.lastPlasticYaw;
            double plasticAngle = rotatePlastic ? Math.toRadians(-(truckYaw - this.lastPlasticYaw)) : 0.0;
            double plasticCos = rotatePlastic ? Math.cos(plasticAngle) : 1.0;
            double plasticSin = rotatePlastic ? Math.sin(plasticAngle) : 0.0;
            double angle = Math.toRadians(-(truckYaw - this.spawnYaw));
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            for (SoftNode node : this.body.world.nodes) {
                double oldTargetX = node.targetX;
                double oldTargetY = node.targetY;
                double oldTargetZ = node.targetZ;
                double oldPlasticX = node.plasticX;
                double oldPlasticY = node.plasticY;
                double oldPlasticZ = node.plasticZ;
                if (rotatePlastic) {
                    double px = node.plasticX;
                    double pz = node.plasticZ;
                    node.plasticX = px * plasticCos + pz * plasticSin;
                    node.plasticZ = -px * plasticSin + pz * plasticCos;
                }

                double lx = node.localX;
                double lz = node.localZ;
                // target is the undeformed vehicle-frame anchor. applyAnchor()
                // adds the plastic offset exactly once.
                node.targetX = truckX + lx * cos + lz * sin;
                node.targetY = truckY + node.localY;
                node.targetZ = truckZ - lx * sin + lz * cos;

                // Rigid feed-forward.  Waiting for the anchor spring to pull
                // every node after the truck moves makes the skin trail the
                // chassis by several centimetres, which reads as a slow,
                // delayed vehicle.  Translate/rotate the current shape with
                // the new target (and the rotated plastic offset) instead of
                // letting the spring catch up; only relative deformation is
                // left to the soft solver.
                double followX = node.targetX - oldTargetX + node.plasticX - oldPlasticX;
                double followY = node.targetY - oldTargetY + node.plasticY - oldPlasticY;
                double followZ = node.targetZ - oldTargetZ + node.plasticZ - oldPlasticZ;
                if (sweepFollow && this.body.world.collisionBoxes.size() > 0
                        && Math.abs(followX) + Math.abs(followY) + Math.abs(followZ) > 0.05) {
                    // One 100 km/h tick moves 1.4 blocks: a point test at the
                    // far end can skip a one-block wall, which is why the
                    // high-speed front used to stay perfectly rigid while a
                    // slow nudge dented.  Stop the rigid follow at the face.
                    this.body.world.sweepRigidFollow(node,
                            followX, followY, followZ, this.sweepOut);
                    followX = this.sweepOut[0];
                    followY = this.sweepOut[1];
                    followZ = this.sweepOut[2];
                }
                node.position.x += followX;
                node.position.y += followY;
                node.position.z += followZ;
                node.previous.x += followX;
                node.previous.y += followY;
                node.previous.z += followZ;
            }
            this.lastPlasticYaw = truckYaw;
        }

        private void resetToTargets() {
            for (SoftNode node : this.body.world.nodes) {
                node.position.set(node.targetX + node.plasticX,
                        node.targetY + node.plasticY,
                        node.targetZ + node.plasticZ);
                node.previous.set(node.position);
                node.velocity.set(0.0, 0.0, 0.0);
            }
        }

        /**
         * Build the local static mesh from Minecraft block collision shapes.
         * Ground-height blocks are skipped because the heightmap plane is
         * smoother and cheaper; walls, barriers and building blocks remain as
         * exact AABB colliders.
         */
        private void collectColliders(PickupTruckEntity truck, int groundY) {
            this.body.world.clearCollisionBoxes();
            var level = truck.level();
            int cx = (int) Math.floor(truck.getX());
            int cy = (int) Math.floor(truck.getY());
            int cz = (int) Math.floor(truck.getZ());
            int radius = 5;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    for (int dy = -1; dy <= 3; dy++) {
                        pos.set(cx + dx, cy + dy, cz + dz);
                        BlockState state = level.getBlockState(pos);
                        if (state.isAir()) {
                            continue;
                        }
                        VoxelShape shape = state.getCollisionShape(level, pos);
                        if (shape.isEmpty()) {
                            continue;
                        }
                        AABB box = shape.bounds().move(pos);
                        // Ground layer: use the heightmap plane, not thousands
                        // of coplanar cubes.  Walls taller than the road stay.
                        if (box.maxY <= groundY + 0.20) {
                            continue;
                        }
                        this.body.world.addCollisionBox(box.minX, box.minY, box.minZ,
                                box.maxX, box.maxY, box.maxZ);
                    }
                }
            }
        }

        /**
         * Real-world front crush estimate.  A 1450 kg pickup at 100 km/h
         * carries ~560 kJ; a 0.65 m front crush absorbs it at roughly 65 g
         * average deceleration, which is the severe-but-survivable range.
         * Scale with v^2; the only hard cap is the 10 m crash envelope.
         */
        /**
         * A steering scrape/box contact at parking speed can produce a very
         * deep solver penetration on this soft lattice.  In that case beam
         * breaking is not a crash signal at all: it just tears the bumper off
         * the truck.  Below 8 m/s keep the lattice connected and let the
         * authored plastic fold do the visible work; above it, allow a
         * bounded local break budget that grows with impact speed.
         */
        private int breakBudgetFor(double speed) {
            if (speed < 8.0) {
                return 0;
            }
            return Math.min(32, 8 + (int) speed);
        }

        private boolean registerCrash(double speed, double speedLoss) {
            double v = Math.min(120.0, Math.max(0.0, speed));
            if (v < 4.0) {
                return false;
            }
            // Do not derive the visual crush from the entity's speed alone:
            // that is exactly the signal that treated every terrain ledge as a
            // wall.  The wall normal's own speed loss is the energy that was
            // actually absorbed by this impact; a glancing hit keeps a small
            // loss and must not fold the whole nose.
            double loss = Math.max(0.0, Math.min(27.8, speedLoss));
            if (loss < 2.5) {
                // A wall contact that did not actually take 2.5 m/s out of
                // the truck is a scrape, not a crush.  Do not extend the
                // crash hot window or throw parts for it.
                return false;
            }
            boolean forward = this.forwardImpact();
            boolean backward = this.backwardImpact();
            // Crush depth grows with absorbed energy (v²), not with v.  The
            // old linear 0.14*loss made a 100 km/h hit request a 3.9 m nose
            // translation, so every node was allowed to reach the whole
            // vehicle envelope.  The result was either a rigid first frame
            // (high speed, no wall plastic yet) or a tail that collapsed to
            // the chassis on a light reverse hit.
            double frontTarget = Math.min(this.body.envelope, 0.050 + 0.00080 * loss * loss);
            double rearTarget = Math.min(this.body.envelope, 0.035 + 0.00055 * loss * loss);
            double target = forward ? frontTarget : backward ? rearTarget : 0.0;
            this.crashDent = Math.max(this.crashDent * CRASH_PROXY_DECAY, target);
            double currentFront = this.frontCrashDepth;
            double currentRear = this.rearCrashDepth;
            if (forward) {
                this.body.world.setCrushPlasticLimits(
                        Math.max(currentFront, target), this.body.world.rearPlasticLimit);
            } else if (backward) {
                this.body.world.setCrushPlasticLimits(
                        this.body.world.frontPlasticLimit, Math.max(currentRear, target));
            } else {
                // Side/oblique contact: do not turn the whole shell into a
                // crush panel just because the normal happened to face a
                // front/rear corner.
                this.body.world.setCrushPlasticLimits(
                        this.body.world.frontPlasticLimit, this.body.world.rearPlasticLimit);
            }
            if (forward) {
                this.frontCrashDepth = Math.max(currentFront, target);
            } else if (backward) {
                this.rearCrashDepth = Math.max(currentRear, target);
            }
            // Vettura owns part loss and the authored wheel/hood/tailgate
            // state.  Vnodo only supplies the crush field; do not call
            // breakGroupHard or devKnockOff from the client shell.
            // Low-speed nudges do not need six seconds of 20-30 substeps.
            // Scale the hot window with impact speed; a 100 km/h hit still
            // gets the full settlement time.
            this.crashHotTicks = Math.min(120, 20 + (int) (v * 3.0));
            BamngMod.LOGGER.info("BAMNG crash model speed={} targetCrush={}", v, this.crashDent);
            return true;
        }

        /** Record one tick of entity speed and return drop across four ticks. */
        private double recordAndWindowDrop(double speed) {
            this.speedHistory[this.speedHistoryIndex] = speed;
            this.speedHistoryIndex = (this.speedHistoryIndex + 1) % this.speedHistory.length;
            return Math.max(0.0, this.speedWindowMax() - speed);
        }

        private double speedWindowMax() {
            double max = 0.0;
            for (double value : this.speedHistory) {
                if (value > max) {
                    max = value;
                }
            }
            return max;
        }

        /** Was the pre-impact motion toward the world front (+Z at yaw 0)? */
        private boolean forwardImpact() {            double yaw = Math.toRadians(this.lastTruckYaw);
            double dot = this.lastDirX * Math.sin(yaw)
                    + this.lastDirZ * Math.cos(yaw);
            return dot > 0.25;
        }

        /** Was the pre-impact motion toward the authored rear (reverse)? */
        private boolean backwardImpact() {
            double yaw = Math.toRadians(this.lastTruckYaw);
            double dot = this.lastDirX * Math.sin(yaw)
                    + this.lastDirZ * Math.cos(yaw);
            return dot < -0.25;
        }

        private void applyImpulse(double hx, double hy, double hz,
                                  double jx, double jy, double jz, double j) {
            double strength = Math.min(900.0, 80.0 + j * 0.08);
            double scale = strength / Math.max(1.0, j);
            for (SoftNode node : this.body.world.nodes) {
                if ("wheel".equals(node.group0) || "tire".equals(node.group0)) {
                    continue;
                }
                double nx = node.position.x - hx;
                double ny = node.position.y - hy;
                double nz = node.position.z - hz;
                double d2 = nx * nx + ny * ny + nz * nz;
                if (d2 > 0.81) {
                    continue;
                }
                double falloff = 1.0 - Math.sqrt(d2) / 0.9;
                double force = scale * falloff * falloff;
                // Layered attenuation: only the outermost panel gets the full
                // impulse.  The cab and bed see a reduced share, so an impact
                // propagates as a fold instead of a rigid shove.
                double layer = Math.min(1.0, Math.max(0.0,
                        (this.body.front - node.rest.x) / Math.max(0.6, this.body.front - this.body.rear)));
                force *= Math.max(0.35, 1.0 - layer * 0.55);
                double dv = Math.min(0.8, force * 0.05 / node.mass);
                node.velocity.x += jx / j * dv;
                node.velocity.y += jy / j * dv;
                node.velocity.z += jz / j * dv;
            }
        }

        /**
         * Small local impulse at a Vettura-reported contact.  The old version
         * damped every node and explicitly broke 18 beams; with real node-wall
         * collision that would double-count the crash.  Now it only transfers
         * momentum into the contact cube and leaves fracture to the solver.
         */
        private void pushFront(double hx, double hy, double hz, double dx, double dz,
                               double speed) {
            if (this.frontBreakCooldown > 0) {
                return;
            }
            // Cover the whole authored front clip (1.1 m), not a 0.8 m ball
            // around the hit point: the old radius reached 14 nodes and the
            // 12 cm behind them stayed perfectly straight.
            double radius = 1.10;
            double maxD2 = radius * radius;
            // Use the arrival speed, not this.prevSpeed.  prevSpeed has
            // already been overwritten with the tick's post-stop crawl (3 m/s
            // after a 13 m/s hit), so the impulse was 2.4 m/s and the solver
            // measured literally zero plastic: the truck felt like rebar.
            double impulse = Math.min(8.0, Math.max(1.2, speed * 0.70));
            for (SoftNode node : this.body.world.nodes) {
                if ("wheel".equals(node.group0) || "tire".equals(node.group0)) {
                    continue;
                }
                double nx = node.position.x - hx;
                double ny = node.position.y - hy;
                double nz = node.position.z - hz;
                double d2 = nx * nx + ny * ny + nz * nz;
                if (d2 > maxD2) {
                    continue;
                }
                double falloff = 1.0 - Math.sqrt(d2) / radius;
                node.velocity.x += dx * impulse * falloff;
                node.velocity.z += dz * impulse * falloff;
            }
            this.frontBreakCooldown = 8;
            BamngMod.LOGGER.info("BAMNG front impulse at {} {} {} dir={} {} speed={}",
                    hx, hy, hz, dx, dz, speed);
        }

        private void dampWallRebound(VehiclePhysics physics) {
            if (this.reboundDampTicks <= 0) {
                return;
            }
            this.reboundDampTicks--;
            double nx = this.reboundNormalX;
            double nz = this.reboundNormalZ;
            double n2 = nx * nx + nz * nz;
            if (n2 < 1.0e-6) {
                return;
            }
            double vn = physics.v.x * nx + physics.v.z * nz;
            if (vn <= 0.0) {
                return;
            }
            physics.v.x -= nx * vn * 0.85;
            physics.v.z -= nz * vn * 0.85;
        }

        /** A pane cracks once its own frame has bent past a centimetre. */
        private static final double GLASS_PANE_LIMIT = 0.010;
        /** ...or once its corner of the shell has taken this much plastic. */
        private static final double GLASS_PANE_PLASTIC = 0.010;
        /**
         * A single node drifting is not a bent window frame.  The pane is
         * carried by a lattice edge, so at least two nodes of that edge have
         * to be past the limit before the glass can crack.
         */
        private static final int GLASS_PANE_MIN_NODES = 2;

        /**
         * Glass is Vettura-owned, so Vnodo never hides panes and never owns
         * their mesh.  It does own the shell, though, and the panes are drawn
         * on that shell: a pane is judged by the cab nodes carrying it.  The
         * judgement only runs while the shell is taking a hit, and needs
         * either permanent plastic or a whole bent edge, because the raw
         * spring lag appears during ordinary driving.  Each pane is judged
         * separately: a nose hit cracks the windscreen, a side swipe cracks
         * that side, a rear hit cracks the back light.  Bit order matches
         * VehicleDamage.Pane: FRONT, REAR, LEFT, RIGHT.
         */
        private void updateGlassDamage(PickupTruckEntity truck) {
            if (this.settleTicks > 0 || this.glassBrokenMask == 0x0F) {
                return;
            }
            // paneOffsets() measures the shell's spring lag against the rigid
            // truck target, and that lag happens on every hard brake, turn and
            // terrain step: a normal drive already produces 10-30 mm at the
            // cab.  The old gate accepted any box contact, and a truck driving
            // past a kerb, fence or step block has those every few ticks, so
            // panes cracked with plastic=0.000 and no impact at all (the
            // 12:47 series in the log: front=0.011...0.033, four panes gone in
            // three seconds of ordinary driving).  A box contact is not a
            // crash.  Only the confirmed crash window or a real tall-wall hit
            // may judge glass.
            boolean impactContext = this.body.world.damageUpdateEnabled
                    || (this.body.world.lastStepFrontWallContacts > 0
                            && this.body.world.lastStepFrontWallImpact > 3.0)
                    || (this.body.world.lastStepRearWallContacts > 0
                            && this.body.world.lastStepRearWallImpact > 3.0);
            if (!impactContext) {
                return;
            }
            if (!this.paneOffsets()) {
                return;
            }
            int mask = 0;
            if (this.paneCracked(0)) {
                mask |= 1;
            }
            if (this.paneCracked(1)) {
                mask |= 2;
            }
            if (this.paneCracked(2)) {
                mask |= 4;
            }
            if (this.paneCracked(3)) {
                mask |= 8;
            }
            mask &= ~this.glassBrokenMask;
            if (mask == 0) {
                return;
            }
            this.glassBrokenMask |= mask;
            BamngNetwork.sendGlassShatter(truck.getId(), mask);
            BamngMod.LOGGER.info(
                    "BAMNG glass shattered panes={} front={}/{}/{} rear={}/{}/{} left={}/{}/{} right={}/{}/{}",
                    Integer.bitCount(mask),
                    String.format(java.util.Locale.ROOT, "%.3f", this.paneOffset[0]),
                    String.format(java.util.Locale.ROOT, "%.3f", this.panePlastic[0]),
                    this.paneBendCount[0],
                    String.format(java.util.Locale.ROOT, "%.3f", this.paneOffset[1]),
                    String.format(java.util.Locale.ROOT, "%.3f", this.panePlastic[1]),
                    this.paneBendCount[1],
                    String.format(java.util.Locale.ROOT, "%.3f", this.paneOffset[2]),
                    String.format(java.util.Locale.ROOT, "%.3f", this.panePlastic[2]),
                    this.paneBendCount[2],
                    String.format(java.util.Locale.ROOT, "%.3f", this.paneOffset[3]),
                    String.format(java.util.Locale.ROOT, "%.3f", this.panePlastic[3]),
                    this.paneBendCount[3]);
        }

        /**
         * Permanent crush always cracks a pane.  Elastic bend only counts
         * when several nodes of that pane's frame are past the limit, so one
         * stray node on the cab roof cannot take out a side window.
         */
        private boolean paneCracked(int pane) {
            return this.panePlastic[pane] > GLASS_PANE_PLASTIC
                    || (this.paneBendCount[pane] >= GLASS_PANE_MIN_NODES
                            && this.paneOffset[pane] > GLASS_PANE_LIMIT);
        }

        /**
         * Per-pane local deformation: how far the cab nodes carrying a pane
         * have moved away from their truck-space target.  The target already
         * contains the rigid transform, so this is shell bend rather than
         * driving motion, but it still includes elastic spring lag: ordinary
         * braking and terrain steps produce 10-30 mm here, which is why the
         * caller must gate it on an actual impact.  Zones: front x-max, rear
         * x-min, sides split by z over the middle of the cab.
         */
        private boolean paneOffsets() {
            double minX = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            for (SoftNode node : this.body.world.nodes) {
                minX = Math.min(minX, node.rest.x);
                maxX = Math.max(maxX, node.rest.x);
            }
            if (!(maxX > minX)) {
                return false;
            }
            double length = Math.max(1.0, maxX - minX);
            double cabStart = maxX - length * 0.62;
            double cMinX = Double.MAX_VALUE;
            double cMaxX = -Double.MAX_VALUE;
            double cMinY = Double.MAX_VALUE;
            double cMaxY = -Double.MAX_VALUE;
            double cMinZ = Double.MAX_VALUE;
            double cMaxZ = -Double.MAX_VALUE;
            for (SoftNode node : this.body.world.nodes) {
                if (!"body".equals(node.group0) || node.rest.x <= cabStart) {
                    continue;
                }
                cMinX = Math.min(cMinX, node.rest.x);
                cMaxX = Math.max(cMaxX, node.rest.x);
                cMinY = Math.min(cMinY, node.rest.y);
                cMaxY = Math.max(cMaxY, node.rest.y);
                cMinZ = Math.min(cMinZ, node.rest.z);
                cMaxZ = Math.max(cMaxZ, node.rest.z);
            }
            if (!(cMaxX > cMinX)) {
                return false;
            }
            double cabLength = Math.max(1.0, cMaxX - cMinX);
            double midY = 0.5 * (cMinY + cMaxY);
            double midZ = 0.5 * (cMinZ + cMaxZ);
            this.paneOffset[0] = 0.0;
            this.paneOffset[1] = 0.0;
            this.paneOffset[2] = 0.0;
            this.paneOffset[3] = 0.0;
            this.panePlastic[0] = 0.0;
            this.panePlastic[1] = 0.0;
            this.panePlastic[2] = 0.0;
            this.panePlastic[3] = 0.0;
            this.paneBendCount[0] = 0;
            this.paneBendCount[1] = 0;
            this.paneBendCount[2] = 0;
            this.paneBendCount[3] = 0;
            for (SoftNode node : this.body.world.nodes) {
                if (!"body".equals(node.group0) || node.rest.x <= cabStart || node.rest.y <= midY) {
                    continue;
                }
                double ox = node.position.x - node.targetX;
                double oy = node.position.y - node.targetY;
                double oz = node.position.z - node.targetZ;
                double d = Math.sqrt(ox * ox + oy * oy + oz * oz);
                double p = Math.sqrt(node.plasticX * node.plasticX
                        + node.plasticY * node.plasticY + node.plasticZ * node.plasticZ);
                boolean bent = d > GLASS_PANE_LIMIT;
                if (node.rest.x > cMaxX - cabLength * 0.22) {
                    this.paneOffset[0] = Math.max(this.paneOffset[0], d);
                    this.panePlastic[0] = Math.max(this.panePlastic[0], p);
                    if (bent) {
                        this.paneBendCount[0]++;
                    }
                }
                if (node.rest.x < cMinX + cabLength * 0.22) {
                    this.paneOffset[1] = Math.max(this.paneOffset[1], d);
                    this.panePlastic[1] = Math.max(this.panePlastic[1], p);
                    if (bent) {
                        this.paneBendCount[1]++;
                    }
                }
                if (node.rest.x > cMinX + cabLength * 0.18
                        && node.rest.x < cMaxX - cabLength * 0.18) {
                    if (node.rest.z < midZ) {
                        this.paneOffset[2] = Math.max(this.paneOffset[2], d);
                        this.panePlastic[2] = Math.max(this.panePlastic[2], p);
                        if (bent) {
                            this.paneBendCount[2]++;
                        }
                    } else {
                        this.paneOffset[3] = Math.max(this.paneOffset[3], d);
                        this.panePlastic[3] = Math.max(this.panePlastic[3], p);
                        if (bent) {
                            this.paneBendCount[3]++;
                        }
                    }
                }
            }
            return true;
        }

        private void syncCollisionField(PickupTruckEntity truck, boolean force) {
            if (!this.initialized || this.collisionField == null || truck == null) {
                return;
            }
            boolean deformed = this.collisionField.hasDeformation();
            int version = this.collisionField.version();
            if (!force && !deformed && !this.lastSentCollisionDeformed
                    && version == this.lastSentCollisionVersion) {
                return;
            }
            this.collisionSendTicks++;
            boolean changed = deformed != this.lastSentCollisionDeformed
                    || version != this.lastSentCollisionVersion;
            // A damaged shell that stopped changing no longer needs a packet
            // every other tick: the server hull is a pure function of the field
            // version, so the old logic burned ~39 KB/s per wrecked truck on
            // identical data.  Send on change, plus a slow keepalive so a lost
            // packet cannot leave the server hull stale forever.
            if (!force && !changed
                    && (!deformed || this.collisionSendTicks % 40 != 0)) {
                return;
            }
            this.lastSentCollisionDeformed = deformed;
            this.lastSentCollisionVersion = version;
            BamngNetwork.sendCollisionField(truck.getUUID(), version,
                    deformed ? this.collisionField.field() : new float[0]);
        }

        /**
         * RepairKitItem repairs Vettura one job at a time and syncs the
         * damage NBT to the client.  Vnodo's own plastic lattice has no NBT
         * writer yet, so when the repair list transitions from non-empty to
         * empty we reset the client shell in the same tick.  This also keeps
         * the Vettura collision hull and the Vnodo crush field in sync.
         */
        private void checkRepair(PickupTruckEntity truck) {
            // toMend() allocates a small list; five ticks is fast enough to
            // make the toolbox feel immediate without doing it every frame.
            if ((truck.tickCount & 3) != 0) {
                return;
            }
            // toMend() builds a fresh list, so read it once per check instead
            // of calling it three times (isEmpty, scan, empty check).
            java.util.List<VehicleDamage.Mend> mends = truck.damage().toMend();
            boolean pending = !mends.isEmpty();
            boolean bodyPending = false;
            for (VehicleDamage.Mend mend : mends) {
                if (mend.job() == VehicleDamage.Job.BODY) {
                    bodyPending = true;
                    break;
                }
            }
            // RepairKitItem repairs one job at a time.  The shell must reset
            // when the BODY job is gone, not after every tyre/engine/glass job
            // has also been repaired: the player sees the body repaired and
            // expects the folds to disappear immediately.
            if (this.repairBodyPending && !bodyPending) {
                this.resetDamage(truck);
                this.glassBrokenMask = 0;
            }
            // Keep the old all-jobs fallback for a body job that was already
            // completed before the first check (for example a one-tick repair
            // command).  It is harmless when bodyPending already handled it.
            if (this.repairPending && !pending && !bodyPending) {
                this.resetDamage(truck);
                this.glassBrokenMask = 0;
            }
            this.repairBodyPending = bodyPending;
            this.repairPending = pending;
        }

        /** Restore the exact plastic fold from the last time this truck was saved. */
        private void loadSavedState(PickupTruckEntity truck) {
            CompoundTag tag = VnodoSoftStateStore.read(truck);
            if (tag == null) {
                return;
            }
            byte[] bytes = VnodoSoftStateStore.softBytes(tag);
            if (bytes == null || bytes.length == 0) {
                return;
            }
            try {
                com.bamng.core.SoftBodySnapshot.loadVehicleState(this.body.world, bytes);
            } catch (java.io.IOException | RuntimeException error) {
                BamngMod.LOGGER.warn("Vnodo shell state rejected for {}: {}",
                        truck.getUUID(), error.toString());
                return;
            }
            // Clamp the plastic the moment it is read: a save written before
            // the chassis cap existed carries metres of offset, and the first
            // rendered frame must not show it.  Restore the saved crash
            // ceilings first; reset() installed the clean 0.72/0.34 defaults,
            // and capping a 0.46 m tailgate fold with the 0.34 default loses
            // 0.12 m before the saved front/rear proxy is even assigned.
            double savedFront = VnodoSoftStateStore.frontDepth(tag);
            double savedRear = VnodoSoftStateStore.rearDepth(tag);
            if (!Double.isFinite(savedFront) || savedFront < 0.0) {
                savedFront = 0.0;
            } else {
                savedFront = Math.min(this.body.world.globalPlasticLimit, savedFront);
            }
            if (!Double.isFinite(savedRear) || savedRear < 0.0) {
                savedRear = 0.0;
            } else {
                savedRear = Math.min(this.body.world.globalPlasticLimit, savedRear);
            }
            this.body.world.restoreSavedCrushLimits(savedFront, savedRear);
            this.frontCrashDepth = savedFront;
            this.rearCrashDepth = savedRear;
            this.crashDent = 0.0;
            // Plastic is stored in world axes.  If the truck was parked at a
            // different yaw when the snapshot was written, rotate the saved
            // offsets back into the current body frame before rebuilding
            // positions; otherwise a turned truck reloads its dent on the
            // wrong side.  Legacy saves without Yaw keep the old behaviour.
            double savedYaw = VnodoSoftStateStore.savedYaw(tag);
            if (Double.isFinite(savedYaw)) {
                double plasticAngle = Math.toRadians(-(truck.getYRot() - savedYaw));
                double plasticCos = Math.cos(plasticAngle);
                double plasticSin = Math.sin(plasticAngle);
                for (SoftNode node : this.body.world.nodes) {
                    double px = node.plasticX;
                    double pz = node.plasticZ;
                    node.plasticX = px * plasticCos + pz * plasticSin;
                    node.plasticZ = -px * plasticSin + pz * plasticCos;
                }
            }
            double maxPlastic = 0.0;
            double maxFront = 0.0;
            for (SoftNode node : this.body.world.nodes) {
                // The saved payload also had old world positions/velocities.
                // Rebuild them from this session's target pose so a truck
                // moved while the save was closed does not teleport its shell.
                node.position.set(node.targetX + node.plasticX,
                        node.targetY + node.plasticY,
                        node.targetZ + node.plasticZ);
                node.previous.set(node.position);
                node.velocity.set(0.0, 0.0, 0.0);
                double d = Math.sqrt(node.plasticX * node.plasticX
                        + node.plasticY * node.plasticY + node.plasticZ * node.plasticZ);
                maxPlastic = Math.max(maxPlastic, d);
                if ("front".equals(node.group0)) {
                    maxFront = Math.max(maxFront, d);
                }
            }
            this.body.world.panelPlasticDepth = maxPlastic;
            this.body.world.panelFrontDepth = maxFront;
            this.body.world.damage.recountDamageState();
            this.collisionField.update(truck.getX(), truck.getY(), truck.getZ(), truck.getYRot());
            this.lastBrokenBeams = this.body.world.damage.brokenBeamCount();
            this.lastBrokenTriangles = this.body.world.damage.brokenTriangleCount();
            this.lastTailgateDetached = !this.body.world.damage.parts.visible("tailgate");
            this.lastSavedStateHash = VnodoSoftStateStore.stateHash(
                    this.body.world, this.frontCrashDepth, this.rearCrashDepth);
            BamngMod.LOGGER.info("Vnodo shell restored truck={} nodes={} front={} rear={}",
                    truck.getUUID(), this.body.world.nodes.size(),
                    String.format(java.util.Locale.ROOT, "%.3f", this.frontCrashDepth),
                    String.format(java.util.Locale.ROOT, "%.3f", this.rearCrashDepth));
        }

        private boolean hasShellState() {
            return this.frontCrashDepth > 1.0e-4
                    || this.rearCrashDepth > 1.0e-4
                    || this.body.world.plasticDepth() > 1.0e-4
                    || this.body.world.damage.brokenBeamCount() > 0
                    || this.body.world.damage.brokenTriangleCount() > 0;
        }

        /**
         * Periodic client-side flush.  A crash can change the shell every
         * tick, but writing the 70 KB snapshot every tick is unnecessary; one
         * write per second keeps an immediate world exit from losing the last
         * visible fold.
         */
        private void saveState(PickupTruckEntity truck, boolean force) {
            if (!this.initialized || this.body == null) {
                return;
            }
            if (!this.hasShellState()) {
                // Do not litter the save directory with one clean file for
                // every parked pickup.  A repaired shell deletes its old file
                // in resetDamage().
                return;
            }
            if (!force) {
                this.stateSaveTicks++;
                if (this.stateSaveTicks < 20) {
                    return;
                }
                this.stateSaveTicks = 0;
            }
            long hash = VnodoSoftStateStore.stateHash(
                    this.body.world, this.frontCrashDepth, this.rearCrashDepth);
            if (!force && hash == this.lastSavedStateHash) {
                return;
            }
            try {
                CompoundTag tag = VnodoSoftStateStore.makeTag(
                        this.body.world, this.frontCrashDepth, this.rearCrashDepth,
                        truck.getYRot());
                VnodoSoftStateStore.write(truck, tag);
                this.lastSavedStateHash = hash;
            } catch (java.io.IOException | RuntimeException error) {
                BamngMod.LOGGER.warn("Vnodo shell save failed for {}: {}",
                        truck.getUUID(), error.toString());
            }
        }

        private void resetDamage(PickupTruckEntity truck) {
            this.body.world.reset();
            this.body.applyTuning();
            this.crashDent = 0.0;
            this.frontCrashDepth = 0.0;
            this.rearCrashDepth = 0.0;
            this.crashHotTicks = 0;
            this.impactCooldown = 0;
            this.frontBreakCooldown = 0;
            this.reboundDampTicks = 0;
            this.lastBrokenBeams = 0;
            this.lastBrokenTriangles = 0;
            this.lastTailgateDetached = false;
            this.stateSaveTicks = 0;
            this.lastSavedStateHash = VnodoSoftStateStore.stateHash(
                    this.body.world, 0.0, 0.0);
            VnodoSoftStateStore.delete(truck);
            this.collisionField.update(truck.getX(), truck.getY(), truck.getZ(), truck.getYRot());
            this.syncCollisionField(truck, true);
            BamngRenderState.reset(truck);
            BamngMod.LOGGER.info("Vnodo shell reset after Vettura repair");
        }

        private void clampToTruck() {
            double maxSpeed = 12.0;
            double maxOffset = this.nodeOffsetLimit();
            for (SoftNode node : this.body.world.nodes) {
                if (!Double.isFinite(node.position.x) || !Double.isFinite(node.position.y)
                        || !Double.isFinite(node.position.z)) {
                    node.position.set(node.targetX, node.targetY, node.targetZ);
                    node.previous.set(node.position);
                    node.velocity.set(0.0, 0.0, 0.0);
                    continue;
                }
                double dx = node.position.x - node.targetX;
                double dy = node.position.y - node.targetY;
                double dz = node.position.z - node.targetZ;
                double distSq = dx * dx + dy * dy + dz * dz;
                // Do not let a steering/terrain contact throw the shell metres
                // away from the truck.  The old 10.5 m envelope let the
                // tailgate probe reach posDelta=9.1 and the bumper/nodes look
                // detached after a small turn.  Only the spawn settle keeps
                // the wide envelope; a real crash gets enough room for the
                // authored 0.7 m crush, but not for whole-body translation.
                if (distSq > maxOffset * maxOffset) {
                    double dist = Math.sqrt(distSq);
                    double scale = maxOffset / dist;
                    node.position.set(node.targetX + dx * scale,
                            node.targetY + dy * scale,
                            node.targetZ + dz * scale);
                    node.previous.set(node.position);
                    node.velocity.scale(0.35);
                }
                double vx = node.velocity.x;
                double vy = node.velocity.y;
                double vz = node.velocity.z;
                double speedSq = vx * vx + vy * vy + vz * vz;
                if (!Double.isFinite(speedSq)) {
                    node.velocity.set(0.0, 0.0, 0.0);
                } else if (speedSq > maxSpeed * maxSpeed) {
                    node.velocity.scale(maxSpeed / Math.sqrt(speedSq));
                }
            }
        }

        private double nodeOffsetLimit() {
            // One whole-vehicle envelope only.  The old settle/crash/normal
            // tiered limits (10.5 / 1.4 / 0.85 m) are what made a deep fold
            // stop at a local line and read like a knife cut.
            return this.body.envelope;
        }

        private void applyImpacts(VehiclePhysics physics, PickupTruckEntity truck,
                                  double speed, double windowDrop) {
            if (physics.hits.isEmpty()) {
                return;
            }
            boolean entityDamageArmed = false;
            com.bamng.core.Vec3 center = this.body.world.center();
            for (VehiclePhysics.Hit hit : physics.hits) {
                // Vettura reports every solid contact without a source type.
                // Vehicle colliders are already handled by resolveBodyPairs;
                // feeding them here would double-apply the impulse (once for
                // each body) and shove a pair apart before the soft-soft pass
                // can write its contact plastic.  Null-source contacts are
                // phantom and must not move the shell.
                Object other = hit.other;
                if (other == null || other == truck
                        || other instanceof com.antaurora.vettura.vehicle.VehicleColliders.Collider) {
                    continue;
                }
                // Block contacts already enter through the wall-crush path
                // above.  An entity hit is the non-wall case: arm the same
                // damage window from its absorbed impulse so a truck-to-mob /
                // truck-to-player hit fractures and folds instead of only
                // pushing nodes around.
                if (!entityDamageArmed && other instanceof net.minecraft.world.entity.Entity
                        && Math.abs(hit.jx) + Math.abs(hit.jy) + Math.abs(hit.jz) > 4.0) {
                    double loss = Math.max(windowDrop,
                            Math.min(27.8, (Math.abs(hit.jx) + Math.abs(hit.jy) + Math.abs(hit.jz)) * 0.02));
                    this.registerCrash(speed, loss);
                    entityDamageArmed = true;
                }
                double hx = hit.x;
                double hy = hit.y;
                double hz = hit.z;
                double jx = hit.jx;
                double jy = hit.jy;
                double jz = hit.jz;
                double j = Math.sqrt(jx * jx + jy * jy + jz * jz);
                if (j < 1.0e-6) {
                    double dx = center.x - hx;
                    double dy = center.y - hy;
                    double dz = center.z - hz;
                    double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (len < 1.0e-4) {
                        continue;
                    }
                    jx = dx / len;
                    jy = dy / len;
                    jz = dz / len;
                    j = 1.0;
                }
                this.applyImpulse(hx, hy, hz, jx, jy, jz, j);
                BamngMod.LOGGER.debug("BAMNG impact at {} {} {} impulse={}", hx, hy, hz, j);
            }
        }

        private void updateRenderScale(PickupTruckEntity truck) {
            if (this.settleTicks > 0) {
                BamngRenderState.reset(truck);
                return;
            }
            BamngRenderState.update(truck, this.body.world.nodes,
                    Math.max(this.body.world.panelPlasticDepth(), this.crashDent),
                    truck.getX(), truck.getY(), truck.getZ(), truck.getYRot());
            BamngRenderState.setFrontDepth(truck,
                    Math.max(this.rearwardFrontPlastic(truck), this.frontCrashDepth));
            BamngRenderState.setRearDepth(truck,
                    Math.max(this.rearwardTailgatePlastic(truck), this.rearCrashDepth));
        }

        /**
         * Project front-node plastic flow onto the authored +Z (rearward)
         * axis.  panelFrontDepth is a 3-D magnitude, so a side scrape used to
         * count as nose crush and either over-fold the front or completely
         * cancel it after smoothing.  Only the rearward component is real
         * crush for the front shell.
         */
        private double rearwardFrontPlastic(PickupTruckEntity truck) {
            double modelYaw = Math.toRadians(180.0 - truck.getYRot());
            double sin = Math.sin(modelYaw);
            double cos = Math.cos(modelYaw);
            double max = 0.0;
            for (SoftNode node : this.body.world.nodes) {
                if (!"front".equals(node.group0)) {
                    continue;
                }
                double rear = node.plasticX * sin + node.plasticZ * cos;
                if (rear > max) {
                    max = rear;
                }
            }
            return max;
        }

        /** Tailgate-node plastic flow toward the authored rear panel. */
        private double rearwardTailgatePlastic(PickupTruckEntity truck) {
            double modelYaw = Math.toRadians(180.0 - truck.getYRot());
            double sin = Math.sin(modelYaw);
            double cos = Math.cos(modelYaw);
            double max = 0.0;
            for (SoftNode node : this.body.world.nodes) {
                if (!"tailgate".equals(node.group0)) {
                    continue;
                }
                double rear = -(node.plasticX * sin + node.plasticZ * cos);
                if (rear > max) {
                    max = rear;
                }
            }
            return max;
        }

        private void init(PickupTruckEntity truck, VehiclePhysics physics) {            // Vettura's life, part-loss and hood/tailgate state are now
            // authoritative.  Do not repair the truck or clear its hinge
            // angles when the Vnodo skin attaches: doing so erased real
            // damage the player had already inflicted.
            this.crashDent = 0.0;
            this.frontCrashDepth = 0.0;
            this.rearCrashDepth = 0.0;
            this.crashHotTicks = 0;
            this.impactCooldown = 0;
            this.reboundNormalX = 0.0;
            this.reboundNormalZ = 0.0;
            this.reboundDampTicks = 0;
            this.prevSpeed = 0.0;
            for (int i = 0; i < this.speedHistory.length; i++) {
                this.speedHistory[i] = 0.0;
            }
            this.speedHistoryIndex = 0;
            this.postAttachTicks = 0;
            this.stableTicks = 0;
            this.crashArmed = false;
            Quaterniond q = new Quaterniond().rotationY(Math.toRadians(180.0 - truck.getYRot()));
            for (SoftNode node : this.body.world.nodes) {
                double bodyX = node.rest.z;
                // Tire nodes are authored with y=radius above the road, not
                // above the chassis base; subtracting baseY would lift every
                // tire by 0.82 m.
                double bodyY = node.part.startsWith("tire") ? node.rest.y : node.rest.y - this.body.baseY;
                double bodyZ = -node.rest.x;
                Vector3d rotated = q.transform(new Vector3d(bodyX, bodyY, bodyZ));
                double wx = truck.getX() + rotated.x;
                double wy = truck.getY() + rotated.y;
                double wz = truck.getZ() + rotated.z;
                node.position.set(wx, wy, wz);
                node.previous.set(node.position);
                node.localX = wx - truck.getX();
                node.localY = wy - truck.getY();
                node.localZ = wz - truck.getZ();
                node.targetX = wx;
                node.targetY = wy;
                node.targetZ = wz;
            }
            this.spawnYaw = truck.getYRot();
            this.lastPlasticYaw = this.spawnYaw;
            // Clear leftover beam damage/plastic from a previous session.  The
            // target pose was written above, so reset() restores a clean shell
            // instead of keeping the 1,700 broken beams seen at attach.
            this.body.world.reset();
            this.body.world.damage.registerTriangles(this.body.world.triangles, this.body.world.nodeArray());
            BamngRenderState.capture(truck, this.body.world.nodes,
                    truck.getX(), truck.getY(), truck.getZ(), truck.getYRot());
            this.initialized = true;
            this.lastX = this.body.world.center().x;
            this.lastY = this.body.world.center().y;
            this.lastZ = this.body.world.center().z;
            this.comBaseX = physics.com.x;
            this.comBaseY = physics.com.y;
            this.comBaseZ = physics.com.z;
            this.bodyBaseX = this.body.world.center().x;
            this.bodyBaseY = this.body.world.center().y;
            this.bodyBaseZ = this.body.world.center().z;
            this.lastTruckX = truck.getX();
            this.lastTruckY = truck.getY();
            this.lastTruckZ = truck.getZ();
            this.lastTruckYaw = truck.getYRot();
            this.loadSavedState(truck);
            this.settleTicks = 20;
            this.glassBrokenMask = 0;
            BamngMod.LOGGER.info("BAMNG attach base com={},{},{} body={},{},{} spawnYaw={}",
                    this.comBaseX, this.comBaseY, this.comBaseZ,
                    this.bodyBaseX, this.bodyBaseY, this.bodyBaseZ, this.spawnYaw);
        }
    }
}
