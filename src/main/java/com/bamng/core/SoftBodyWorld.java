package com.bamng.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Force-based soft-body world (M1–M4 core).
 *
 * <p>Step order follows the BeamNG blueprint:</p>
 * <ol>
 *   <li>clear force accumulators;</li>
 *   <li>gravity + chassis anchors + optional wheel forces;</li>
 *   <li>axial spring/damper forces (Jacobi, fixed order);</li>
 *   <li>semi-implicit Euler integration;</li>
 *   <li>damage / plastic flow / break propagation;</li>
 *   <li>ground and static-box collision projection + impulses;</li>
 *   <li>velocity damping and missive recovery.</li>
 * </ol>
 *
 * <p>Collisions are position projections plus impulses, never springs.  This
 * keeps the spring stiffness in its stable range while still stopping a node
 * that has already penetrated a wall.</p>
 */
public final class SoftBodyWorld {
    public static final double GRAVITY = -9.81;
    /** BeamNG's public 2.5 cm collision band. */
    public static final double CONTACT_THICKNESS = 0.025;
    /** 10 cm sleep / query radius in the public spec. */
    public static final double QUERY_RADIUS = 0.10;

    public final List<SoftNode> nodes = new ArrayList<>();
    public final List<SoftBeam> beams = new ArrayList<>();
    public final List<SoftTriangle> triangles = new ArrayList<>();
    private java.util.Map<Integer, java.util.List<SoftBeam>> beamIncidence;
    private int beamIncidenceSize = -1;
    public final List<WheelSuspension> wheels = new ArrayList<>();
    public final List<CollisionBox> collisionBoxes = new ArrayList<>();
    /** Broad phase for static Minecraft block boxes.  Narrow phase stays exact. */
    private static final double COLLISION_CELL = 1.0;
    private static final double COLLISION_PADDING = 0.25;
    private final Map<Long, List<CollisionBox>> collisionGrid = new HashMap<>();
    public final List<StaticMesh> staticMeshes = new ArrayList<>();
    public final DamageGraph damage = new DamageGraph();
    public final ContactPool contacts = new ContactPool();

    /** D8: global body namespace.  Group/breakGroup names are local to this
     *  body; no numeric id is ever compared across bodies. */
    private static int NEXT_BODY_ID;
    public final int bodyId = NEXT_BODY_ID++;
    public final int generation = 1;
    private final java.util.Map<String, Integer> localGroups = new java.util.LinkedHashMap<>();
    private SoftNode[] nodeCache;
    private int recommendedCache = -1;
    private int recommendedBeamCount = -1;

    public int localGroup(String name) {
        return localGroups.computeIfAbsent(name, key -> localGroups.size());
    }

    public String namespace(String group) {
        return "body#" + bodyId + ":group:" + group;
    }

    public double groundY = 0.0;
    public double groundFriction = 0.62;
    public GroundMaterial groundMaterial = GroundMaterial.DEFAULT;

    /** Scratch for the per-substep tunnel sweep; the solver is single-threaded. */
    private final double[] sweepStep = new double[3];
    public double gravityScale = 1.0;
    public double globalDamping = 0.020;
    public double contactWarmStart = 0.5;
    /** F10: L2 body isolation, so one poisoned body cannot infect the world. */
    public boolean isolated;
    public int nanEvents;
    public int nanSubsteps;
    /** F7: last tick wall-clock cost, split into integration and narrow phase. */
    public long lastStepNanos;
    public long lastSubstepNanos;
    public double airDensity = 1.225;
    public double windX;
    public double windY;
    public double windZ;

    /** Kept for old callers; the force solver uses substeps, not PBD iterations. */
    public int constraintIterations = 4;
    public double throttle;
    public double brake;
    public double steer;

    /** Diagnostics. */
    public int lastSubsteps = 1;
    private int substepIndex;
    /**
     * Shell self collision is a stabiliser, not ground contact.  Running the
     * full 464-triangle narrow phase on every one of 80 substeps cost ~9M
     * closest-point tests per tick and was the main stutter source.  Static
     * ground/box contacts still run every substep.
     */
    private static final int TRIANGLE_COLLISION_STRIDE = 4;
    public int lastContacts = 0;
    /** Wall/block contacts only; ground-plane contacts are excluded. */
    public int lastBoxContacts = 0;
    /**
     * Wall/block contacts accumulated over every substep of the last tick.
     * lastBoxContacts intentionally reflects only the final substep (HUD and
     * per-substep collision fast path); the client crash fallback needs the
     * whole tick or an early contact that clears by the last substep is lost.
     */
    public int lastStepBoxContacts = 0;
    /**
     * Front-only wall contacts accumulated over the tick.  A side scrape or
     * tail contact must not select the twenty-to-thirty-substep crash path.
     */
    public int lastStepFrontBoxContacts = 0;
    /** Final-substep front-only contacts, for the same fast path as above. */
    public int lastFrontBoxContacts = 0;
    /** Rear/tailgate-only wall contacts; reverse impacts use these. */
    public int lastStepRearBoxContacts = 0;
    public int lastRearBoxContacts = 0;
    /**
     * Tall, near-vertical box faces touched by the front/rear crush groups
     * during the last tick.  A terrain lip has a low top face and is excluded;
     * a wall remains tall above the contact node.  The client uses this to
     * recognise a real wall even when Vettura has already stopped the rigid
     * entity and the tick-to-tick speed drop is small.
     */
    public int lastStepFrontWallContacts = 0;
    public int lastStepRearWallContacts = 0;
    public double lastStepFrontWallImpact = 0.0;
    public double lastStepRearWallImpact = 0.0;
    /** Strongest wall contact normal for the last tick (world axes). */
    public double lastStepFrontWallNormalX = 0.0;
    public double lastStepFrontWallNormalZ = 0.0;
    public double lastStepRearWallNormalX = 0.0;
    public double lastStepRearWallNormalZ = 0.0;
    public double lastStepFrontWallSpeedLoss = 0.0;
    public double lastStepRearWallSpeedLoss = 0.0;
    /**
     * Rigid truck velocity in world axes, supplied by the client before the
     * step.  The rigid feed-forward moves node positions directly, so the
     * soft node velocity alone is near zero on the first wall frame; using it
     * as the only closing-speed signal made a 100 km/h hit look like a parked
     * touch while a 20 km/h squeeze registered.
     */
    public double rigidVelX = 0.0;
    public double rigidVelY = 0.0;
    public double rigidVelZ = 0.0;
    public int lastFrontWallContacts = 0;
    public int lastRearWallContacts = 0;
    public double lastFrontWallImpact = 0.0;
    public double lastRearWallImpact = 0.0;
    public double lastFrontWallNormalX = 0.0;
    public double lastFrontWallNormalZ = 0.0;
    public double lastRearWallNormalX = 0.0;
    public double lastRearWallNormalZ = 0.0;
    public double lastFrontWallSpeedLoss = 0.0;
    public double lastRearWallSpeedLoss = 0.0;
    private long plasticTick = 0;
    private static final double PLASTIC_MIN_NORMAL_SPEED = 1.2;
    /** Whole-vehicle envelope, replaced by VehicleBody from layout size. */
    public double globalPlasticLimit = Double.POSITIVE_INFINITY;

    /**
     * Permanent plastic ceiling for the chassis/cab shell.  The crash ceilings
     * only bound the authored crush panels; body beams run the continuous
     * plasticity path, and before this cap a single impact could flow the cab
     * nodes all the way to the whole-vehicle envelope (3.1 m), which the
     * renderer draws as black spikes.  0.30 m is more than a 20 % cab collapse
     * and still keeps the shell a truck.
     */
    public double chassisPlasticLimit = 0.30;
    /** Local-space rest extents, used for layered force attenuation. */
    private double minRestX = Double.POSITIVE_INFINITY;
    private double maxRestX = -Double.POSITIVE_INFINITY;
    private static final double WALL_DENT_MIN_NORMAL_SPEED = 1.2;
    /** Number of static boxes handed to the exact narrow phase during the last tick. */
    public long lastBoxCandidates = 0;
    public double maxPenetration = 0.0;
    public double lastBeamForce = 0.0;
    /** Cached maximum plastic displacement on the authored crush panels. */
    public double panelPlasticDepth = 0.0;
    /** Front-only plastic depth, so backing into a wall cannot fold the nose. */
    public double panelFrontDepth = 0.0;
    public int degenerateTriangles = 0;
    public int lastTriangleContacts = 0;
    public boolean triangleCollision = true;
    /** Static bodies and tests keep damage live; the live client shell turns
     *  this off while it is merely driving so the 1912-beam damage scan is not
     *  run once per tick for a condition that cannot change until the shell
     *  actually touches something. */
    public boolean damageUpdateEnabled = false;

    /** Damage-window state from the previous tick, for the falling edge. */
    private boolean damageWindowWasOpen = false;
    /**
     * Set by the client before {@link #step}: the truck has finished spawning
     * and a tall wall contact is allowed to write plastic on its first tick,
     * even though the crash hot window has not opened yet.
     */
    public boolean crashGateArmed = false;
    /**
     * Speed the truck was actually carrying when it reached the wall, plus
     * the direction it was moving.  Vettura resolves its rigid hull against
     * the block before the soft step runs, so by the time a node touches the
     * wall the entity has already been stopped and {@link #rigidVel} carries
     * only the residual 2-3 m/s.  Judging the crush from that residual made a
     * 47 km/h hit register as a 3 m/s tap and produced a 5 cm dent.  The
     * client therefore hands the pre-hit speed in here; it is applied only
     * along the wall normal and only when a tall wall is actually touched.
     */
    public double crashApproachSpeed = 0.0;
    public double crashApproachDirX = 0.0;
    public double crashApproachDirZ = 0.0;
    private boolean wallContactThisStep = false;
    /**
     * Beam-level wall settlement is a one-shot per crash event.  The narrow
     * phase re-flags the same nodes every tick while the truck is resting on
     * the wall; without this guard a long contact kept shortening the same
     * beams until the 5 cm floor, even though the panel plastic was already
     * capped.
     */
    private boolean wallDentAppliedThisEvent = false;
    /** Total permanent displacement allowed for foreign-body contacts per tick. */
    private double foreignDamageBudget = 0.25;
    /** Per-crash plastic ceiling for the authored front/rear panels.  The
     *  old solver added up to 6 cm per node per tick for as long as the
     *  crash window stayed open.  A truck resting against the wall after
     *  the first contact therefore accumulated 1.7 m of plastic and the
     *  front shell was translated into the cab.  These ceilings are set
     *  from the real crush target at crash registration. */
    public double frontPlasticLimit = Double.POSITIVE_INFINITY;
    public double rearPlasticLimit = Double.POSITIVE_INFINITY;
    /** C10: triangles with no collidable node within 10 cm for this many
     *  substeps stop participating in the narrow phase. */
    public int sleepFrames = 12;
    public int sleepingTriangles = 0;
    public final List<String> spawnContacts = new ArrayList<>();
    /** E1/E3: closed pressure chambers evaluated once per substep. */
    public final List<PressureVolume> pressureVolumes = new ArrayList<>();
    private long frameCounter = 0;
    private int substepCounter = 0;

    public void addNode(SoftNode node) {
        nodes.add(node);
        this.minRestX = Math.min(this.minRestX, node.rest.x);
        this.maxRestX = Math.max(this.maxRestX, node.rest.x);
        this.nodeCache = null;
    }

    public SoftNode[] nodeArray() {
        // The solver asks for this array several times per tick.  Rebuilding a
        // fresh 314-element array each call was avoidable garbage on the main
        // thread.
        SoftNode[] cache = this.nodeCache;
        if (cache == null || cache.length != nodes.size()) {
            cache = nodes.toArray(new SoftNode[0]);
            this.nodeCache = cache;
        }
        return cache;
    }

    /** Call once per client tick before {@link #step}.  The client rebuilds
     *  the nearby block colliders from Minecraft's collision shapes. */
    public void clearCollisionBoxes() {
        collisionBoxes.clear();
        collisionGrid.clear();
        maxPenetration = 0.0;
    }

    public void addCollisionBox(double minX, double minY, double minZ,
                                double maxX, double maxY, double maxZ) {
        if (maxX - minX < 1.0e-4 || maxY - minY < 1.0e-4 || maxZ - minZ < 1.0e-4) {
            return;
        }
        CollisionBox box = new CollisionBox(minX, minY, minZ, maxX, maxY, maxZ);
        collisionBoxes.add(box);
        indexCollisionBox(box);
    }

    /**
     * Minimum substeps needed for semi-implicit Euler given the stiffest beam.
     * The caller can override this but must not quietly run a step that is
     * outside the stability envelope.
     */
    public int recommendedSubsteps(double dt) {
        // Beam stiffness and node masses do not change after construction;
        // broken beams only reduce the stiffness sum, so the first answer is
        // still a safe upper bound.  This used to be recomputed twice every
        // tick (two full 1912-beam passes) just to log the same number.
        if (this.recommendedCache > 0 && this.recommendedBeamCount == beams.size()) {
            return this.recommendedCache;
        }
        SoftNode[] array = nodeArray();
        double[] stiffnessSum = new double[array.length];
        for (SoftBeam beam : beams) {
            if (!beam.broken) {
                stiffnessSum[beam.a] += beam.beamSpring;
                stiffnessSum[beam.b] += beam.beamSpring;
            }
        }
        double minStable = Double.POSITIVE_INFINITY;
        for (int i = 0; i < array.length; i++) {
            if (stiffnessSum[i] <= 0.0 || array[i].mass <= 0.0 || array[i].inverseMass <= 0.0) {
                continue;
            }
            double omega = Math.sqrt(stiffnessSum[i] * array[i].inverseMass);
            if (omega > 1.0e-9) {
                minStable = Math.min(minStable, 2.0 / omega);
            }
        }
        int result;
        if (!Double.isFinite(minStable) || minStable > dt) {
            result = 1;
        } else {
            result = Math.max(1, (int) Math.ceil(dt / (0.5 * minStable)));
        }
        this.recommendedCache = result;
        this.recommendedBeamCount = beams.size();
        return result;
    }

    public void step(double dt, int substeps) {
        if (substeps < 1) {
            throw new IllegalArgumentException("substeps must be >= 1");
        }
        if (isolated) {
            return;
        }
        long stepStart = System.nanoTime();
        lastSubstepNanos = 0L;
        lastBoxCandidates = 0L;
        // HUD penetration is a per-tick diagnostic.  It used to reset only
        // when the block collider was rebuilt (every 10 ticks while parked),
        // so a stale spawn penetration showed 5 m under a perfectly resting
        // truck.  Reset every step instead.
        maxPenetration = 0.0;
        lastStepBoxContacts = 0;
        lastStepFrontBoxContacts = 0;
        lastStepRearBoxContacts = 0;
        lastStepFrontWallContacts = 0;
        lastStepRearWallContacts = 0;
        lastStepFrontWallImpact = 0.0;
        lastStepRearWallImpact = 0.0;
        lastStepFrontWallNormalX = 0.0;
        lastStepFrontWallNormalZ = 0.0;
        lastStepRearWallNormalX = 0.0;
        lastStepRearWallNormalZ = 0.0;
        lastStepFrontWallSpeedLoss = 0.0;
        lastStepRearWallSpeedLoss = 0.0;
        plasticTick++;
        this.foreignDamageBudget = 0.25;
        // Falling edge of the crash window: freeze the offset the anchored
        // cab/body actually reached.  Otherwise the anchor spring returns the
        // panel to its authored position the moment the window closes, which
        // is what made the in-session result look rubbery while a world reload
        // (rest shape + plastic only) looked correct.
        if (this.damageWindowWasOpen && !this.damageUpdateEnabled) {
            bakeAnchoredOffsets(0.85);
            // A closed window ends the current crush event.  The next wall
            // contact may settle beams again, but it must not inherit the
            // one-shot guard from the previous crash.
            this.wallDentAppliedThisEvent = false;
        }
        boolean hadWallContactLastStep = this.wallContactThisStep;
        if (this.damageUpdateEnabled && !this.damageWindowWasOpen
                && this.panelPlasticDepth < 0.02) {
            // Rising edge with no preceding first-contact dent: this is a new
            // crash event, so allow one beam settlement pass.
            this.wallDentAppliedThisEvent = false;
        }
        if (!this.damageUpdateEnabled && !hadWallContactLastStep) {
            // A low-speed first touch that never opened a window is over.
            this.wallDentAppliedThisEvent = false;
        }
        this.damageWindowWasOpen = this.damageUpdateEnabled;
        lastSubsteps = substeps;
        wallContactThisStep = false;
        // Rest-length folding is permanent damage.  It is enabled only for
        // the crash window; ordinary driving compression must stay elastic.
        for (SoftBeam beam : beams) {
            beam.foldEnabled = this.damageUpdateEnabled;
            beam.plasticEnabled = this.damageUpdateEnabled;
        }
        double h = dt / substeps;
        SoftNode[] array = nodeArray();
        for (int s = 0; s < substeps; s++) {
            long substepStart = System.nanoTime();
            substepCounter++;
            this.substepIndex = s;
            substep(array, h);
            lastSubstepNanos += System.nanoTime() - substepStart;
        }
        // Permanent beam dents are a per-tick settlement, not a per-substep
        // one.  Running applyWallDents inside every substep multiplied the
        // 0.12 m beam shortening by up to thirty and collapsed the whole front
        // clip during ordinary contact.
        applyWallDents(array);
        relaxFoldSeams();
        capCrushPlastic();
        frameCounter++;
        lastStepNanos = System.nanoTime() - stepStart;
        // The client body has triangleCollision disabled and aero disabled.
        // PressureVolume.step already refreshes the tyre triangles it uses on
        // every substep, so updating them again here would be a duplicate
        // ~112-triangle pass per tick.
        if (triangleCollision || airDensity > 0.0) {
            for (SoftTriangle triangle : triangles) {
                if (!triangle.broken) {
                    triangle.updateGeometry(array);
                }
            }
        }
        if (triangleCollision) {
            updateTriangleSleep(array);
        }
    }

    /**
     * Refresh cached triangle normals/areas from the current node positions.
     * The client disables triangle collision for the shell, so step()
     * skips this pass; vehicle-to-vehicle contacts still need the other body's
     * surface to follow its deformed nodes.
     */
    public void refreshTriangleGeometry() {
        SoftNode[] array = nodeArray();
        for (SoftTriangle triangle : triangles) {
            if (!triangle.broken) {
                triangle.updateGeometry(array);
            }
        }
    }

    /**
     * C10: sleep the triangle only when no collidable node is inside the
     * 10 cm query radius.  Nodes always integrate; only the triangle's
     * narrow-phase work is skipped, and any node entering wakes it.
     */
    private void updateTriangleSleep(SoftNode[] array) {
        int sleeping = 0;
        for (SoftTriangle triangle : triangles) {
            if (triangle.broken || !triangle.collision) {
                triangle.sleeping = false;
                continue;
            }
            SoftNode na = array[triangle.a];
            SoftNode nb = array[triangle.b];
            SoftNode nc = array[triangle.c];
            double minX = Math.min(na.position.x, Math.min(nb.position.x, nc.position.x));
            double minY = Math.min(na.position.y, Math.min(nb.position.y, nc.position.y));
            double minZ = Math.min(na.position.z, Math.min(nb.position.z, nc.position.z));
            double maxX = Math.max(na.position.x, Math.max(nb.position.x, nc.position.x));
            double maxY = Math.max(na.position.y, Math.max(nb.position.y, nc.position.y));
            double maxZ = Math.max(na.position.z, Math.max(nb.position.z, nc.position.z));
            boolean near = false;
            for (SoftNode node : array) {
                if (!node.externalCollisionEnabled() && !node.staticCollisionEnabled()) {
                    continue;
                }
                double qx = clamp(node.position.x, minX, maxX);
                double qy = clamp(node.position.y, minY, maxY);
                double qz = clamp(node.position.z, minZ, maxZ);
                double dx = node.position.x - qx;
                double dy = node.position.y - qy;
                double dz = node.position.z - qz;
                if (dx * dx + dy * dy + dz * dz <= QUERY_RADIUS * QUERY_RADIUS) {
                    near = true;
                    break;
                }
            }
            if (near) {
                triangle.activeFrame = (int) (frameCounter & 0x7fffffff);
                triangle.sleeping = false;
            } else if ((int) (frameCounter & 0x7fffffff) - triangle.activeFrame > sleepFrames) {
                triangle.sleeping = true;
            }
            if (triangle.sleeping) {
                sleeping++;
            }
        }
        sleepingTriangles = sleeping;
    }

    private void substep(SoftNode[] array, double h) {
        contacts.beginStep();
        int previousBoxContacts = lastBoxContacts;
        lastContacts = 0;
        lastBoxContacts = 0;
        lastFrontBoxContacts = 0;
        lastRearBoxContacts = 0;
        lastFrontWallContacts = 0;
        lastRearWallContacts = 0;
        lastFrontWallImpact = 0.0;
        lastRearWallImpact = 0.0;
        lastFrontWallNormalX = 0.0;
        lastFrontWallNormalZ = 0.0;
        lastRearWallNormalX = 0.0;
        lastRearWallNormalZ = 0.0;
        lastFrontWallSpeedLoss = 0.0;
        lastRearWallSpeedLoss = 0.0;
        for (SoftNode node : array) {
            node.zeroForce();
            node.previous.set(node.position);
            if (!node.pinned && !node.fixed) {
                node.addForce(0.0, GRAVITY * node.mass * gravityScale, 0.0);
            }
            applyAnchor(node);
        }

        for (WheelSuspension wheel : wheels) {
            wheel.apply(throttle, brake, steer);
        }

        applyAero(array);
        for (PressureVolume volume : pressureVolumes) {
            // The chassis volume is created with gauge 0 (a diagnostic closed
            // shell).  Running its 464-triangle volume/force pass every
            // substep only to add 0 N was measurable main-thread cost.
            if (volume.gaugePressure > 0.0) {
                volume.step(array);
            }
        }

        // Jacobi force pass, fixed array order for determinism.
        for (SoftBeam beam : beams) {
            if (!beam.broken) {
                lastBeamForce = beam.solveForce(array);
            }
        }

        for (SoftNode node : array) {
            if (node.pinned || node.fixed) {
                node.velocity.set(0.0, 0.0, 0.0);
                node.previous.set(node.position);
                continue;
            }
            node.velocity.x += node.forceX * node.inverseMass * h;
            node.velocity.y += node.forceY * node.inverseMass * h;
            node.velocity.z += node.forceZ * node.inverseMass * h;
            if (node.anchored) {
                // Implicit anchor damping: the explicit form goes unstable
                // when c*h/m > 2, which is exactly what happened to the old
                // 700 N s/m anchor on a 5.8 kg node.
                double factor = 1.0 / (1.0 + node.anchorDamping * node.inverseMass * h);
                node.velocity.x *= factor;
                node.velocity.y *= factor;
                node.velocity.z *= factor;
            }
            double stepX = node.velocity.x * h;
            double stepY = node.velocity.y * h;
            double stepZ = node.velocity.z * h;
            // Explicit integration can leap clean over a one-block wall: at
            // 28 m/s with six substeps a node travels 0.23 m per step, wider
            // than the 0.125 m blocks the client feeds in.  Sweep only the
            // fast nodes and stop them on the wall face instead, otherwise the
            // nose ends up inside the wall base with the engine under the road.
            if (!this.collisionBoxes.isEmpty() && node.staticCollisionEnabled()) {
                double travel = Math.sqrt(stepX * stepX + stepY * stepY + stepZ * stepZ);
                if (travel > 0.05) {
                    sweepRigidFollow(node, stepX, stepY, stepZ, this.sweepStep);
                    stepX = this.sweepStep[0];
                    stepY = this.sweepStep[1];
                    stepZ = this.sweepStep[2];
                }
            }
            node.position.x += stepX;
            node.position.y += stepY;
            node.position.z += stepZ;
        }

        // Damage keeps the original every-other-substep cadence so an impact
        // still breaks the same beams at the same time.  DamageGraph's cached
        // force/strain rejection makes the untouched-beam scan cheap; the
        // final substep is always included so a late collision cannot be
        // missed.
        if (this.damageUpdateEnabled
                && ((this.substepIndex & 1) == 0 || this.substepIndex == this.lastSubsteps - 1)) {
            damage.update(array);
        }

        boolean collideTriangles = triangleCollision
                && (this.substepIndex % TRIANGLE_COLLISION_STRIDE == 0
                || this.substepIndex == this.lastSubsteps - 1);
        if (triangleCollision) {
            for (SoftTriangle triangle : triangles) {
                if (!triangle.broken) {
                    triangle.updateGeometry(array);
                }
            }
            if (collideTriangles) {
                lastTriangleContacts = 0;
            }
        }

        // Static collision resolution.  Two passes handle a node wedged into
        // a corner without introducing a new solver iteration.  The second
        // pass is only useful after the previous substep actually touched a
        // box; before first contact (or on open road) one pass is enough.
        int collisionPasses = previousBoxContacts > 0 && !collisionBoxes.isEmpty() ? 2 : 1;
        for (int pass = 0; pass < collisionPasses; pass++) {
            for (SoftNode node : array) {
                if (node.pinned) {
                    continue;
                }
                if (node.staticCollisionEnabled()) {
                    resolveGround(node);
                    // collisionGrid.clear() leaves the grid map empty on open
                    // road.  Calling resolveBoxes() for 314 nodes every pass
                    // then only hashes each node into a missing bucket.
                    if (!collisionBoxes.isEmpty()) {
                        resolveBoxes(node);
                    }
                    if (!staticMeshes.isEmpty()) {
                        resolveStaticMeshes(node);
                    }
                }
                if (collideTriangles && node.externalCollisionEnabled()) {
                    resolveTriangles(node, array);
                }
            }
        }

        // Wall hits need a permanent beam-level dent, not just a node target
        // offset.  Without this the live node is pushed out, the beams pull it
        // back to the old rest length, and the panel looks untouched.
        lastStepBoxContacts += lastBoxContacts;
        lastStepFrontBoxContacts += lastFrontBoxContacts;
        lastStepRearBoxContacts += lastRearBoxContacts;
        lastStepFrontWallContacts += lastFrontWallContacts;
        lastStepRearWallContacts += lastRearWallContacts;
        if (lastFrontWallImpact > lastStepFrontWallImpact) {
            lastStepFrontWallImpact = lastFrontWallImpact;
            lastStepFrontWallNormalX = lastFrontWallNormalX;
            lastStepFrontWallNormalZ = lastFrontWallNormalZ;
        }
        lastStepFrontWallSpeedLoss = Math.max(lastStepFrontWallSpeedLoss, lastFrontWallSpeedLoss);
        if (lastRearWallImpact > lastStepRearWallImpact) {
            lastStepRearWallImpact = lastRearWallImpact;
            lastStepRearWallNormalX = lastRearWallNormalX;
            lastStepRearWallNormalZ = lastRearWallNormalZ;
        }
        lastStepRearWallSpeedLoss = Math.max(lastStepRearWallSpeedLoss, lastRearWallSpeedLoss);
        // Fold events write node plastic directly; keep them under the same
        // crash target as wall contact plasticity.  The node count is small,
        // so this pass is cheap compared with the beam solve.
        capCrushPlastic();
        // Seam relaxation is a per-tick visual pass, not a per-substep one.
        // Running 40 diffusion passes per tick flattened the whole front into
        // one rigid block, which is the opposite of the desired banana fold.

        contacts.endStep();
        if (nanSubsteps > 0) {
            nanSubsteps = 0;
            if (nanEvents >= 4) {
                isolated = true;
            }
        }
        for (SoftNode node : array) {
            if (node.pinned || node.fixed) {
                continue;
            }
            double damping = Math.max(0.0, 1.0 - globalDamping * h);
            node.velocity.scale(damping);
            if (!Double.isFinite(node.position.x) || !Double.isFinite(node.position.y)
                    || !Double.isFinite(node.position.z)) {
                recoverNode(node);
                nanEvents++;
                nanSubsteps++;
            }
        }
    }

    /**
     * E2: per-triangle drag/lift.  Only NORMAL triangles with non-zero
     * coefficients contribute, so this is inert until a definition opts in.
     */
    private void applyAero(SoftNode[] array) {
        // This client-side crush shell is not the authoritative vehicle
        // physics.  Aero was enabled for every shell triangle (464 of them,
        // each doing a sqrt and an asin per substep) even though the shell is
        // visually hidden under the authored truck.  VehicleBody disables it;
        // keep the early return here so an imported body cannot pay that cost.
        if (airDensity <= 0.0) {
            return;
        }
        for (SoftTriangle triangle : triangles) {
            if (triangle.broken || !triangle.aero || triangle.degenerate()) {
                continue;
            }
            if (triangle.dragCoef == 0.0 && triangle.liftCoef == 0.0) {
                continue;
            }
            SoftNode a = array[triangle.a];
            SoftNode b = array[triangle.b];
            SoftNode c = array[triangle.c];
            double vx = (a.velocity.x + b.velocity.x + c.velocity.x) / 3.0 - windX;
            double vy = (a.velocity.y + b.velocity.y + c.velocity.y) / 3.0 - windY;
            double vz = (a.velocity.z + b.velocity.z + c.velocity.z) / 3.0 - windZ;
            double speed2 = vx * vx + vy * vy + vz * vz;
            if (speed2 < 1.0e-9) {
                continue;
            }
            double speed = Math.sqrt(speed2);
            double q = 0.5 * airDensity * speed2;
            double area = triangle.area;
            double cd = triangle.dragCoef / 100.0;
            double cl = triangle.liftCoef / 100.0;
            double dot = (vx * triangle.nx + vy * triangle.ny + vz * triangle.nz) / speed;
            double aoa = Math.asin(Math.min(1.0, Math.abs(dot)));
            if (aoa > triangle.stallAngle) {
                cl = 0.0;
            }
            double fx = -vx / speed * q * area * cd;
            double fy = -vy / speed * q * area * cd;
            double fz = -vz / speed * q * area * cd;
            double nx = triangle.nx * q * area * cl / 3.0;
            double ny = triangle.ny * q * area * cl / 3.0;
            double nz = triangle.nz * q * area * cl / 3.0;
            a.addForce(fx / 3.0 + nx, fy / 3.0 + ny, fz / 3.0 + nz);
            b.addForce(fx / 3.0 + nx, fy / 3.0 + ny, fz / 3.0 + nz);
            c.addForce(fx / 3.0 + nx, fy / 3.0 + ny, fz / 3.0 + nz);
        }
    }

    private void applyAnchor(SoftNode node) {
        if (!node.anchored || node.pinned || node.fixed) {
            return;
        }
        // Plastic flow must move the rest target, otherwise the anchor
        // spring pulls the panel straight back out of the wall and the dent
        // disappears the moment contact ends.
        double tx = node.targetX + node.plasticX;
        double ty = node.targetY + node.plasticY;
        double tz = node.targetZ + node.plasticZ;
        double dx = tx - node.position.x;
        double dy = ty - node.position.y;
        double dz = tz - node.position.z;
        double d2 = dx * dx + dy * dy + dz * dz;
        if (d2 > 0.64) {
            // Only chassis anchors take this recovery path.  A core node
            // dragged more than 0.8 m from its target is not a plausible
            // elastic state; the old 2 m threshold let a collapsed beam pull
            // cab nodes nearly two metres out and the renderer drew the mesh
            // as black spikes.  Crumple-zone nodes are intentionally
            // unanchored and retain the separate 10 m collision/plastic
            // envelope.
            node.position.set(tx, ty, tz);
            node.previous.set(node.position);
            node.velocity.set(0.0, 0.0, 0.0);
            return;
        }
        double fx = node.anchorStiffness * dx;
        double fy = node.anchorStiffness * dy;
        double fz = node.anchorStiffness * dz;
        double cap = 8_000.0;
        double capSq = cap * cap;
        double forceSq = fx * fx + fy * fy + fz * fz;
        if (forceSq > capSq) {
            double len = Math.sqrt(forceSq);
            fx *= cap / len;
            fy *= cap / len;
            fz *= cap / len;
        }
        node.addForce(fx, fy, fz);
    }

    private void recoverNode(SoftNode node) {
        double x = Double.isFinite(node.targetX + node.plasticX)
                ? node.targetX + node.plasticX : 0.0;
        double y = Double.isFinite(node.targetY + node.plasticY)
                ? node.targetY + node.plasticY : groundY + node.radius;
        double z = Double.isFinite(node.targetZ + node.plasticZ)
                ? node.targetZ + node.plasticZ : 0.0;
        node.position.set(x, y, z);
        node.previous.set(node.position);
        node.velocity.set(0.0, 0.0, 0.0);
    }

    private void resolveGround(SoftNode node) {
        double floor = groundY + node.radius;
        if (node.position.y < floor) {
            double penetration = floor - node.position.y;
            node.position.y = floor;
            if (node.velocity.y < 0.0) {
                node.velocity.y = 0.0;
            }
            if (penetration > maxPenetration) {
                maxPenetration = penetration;
            }
            applyTangentialFriction(node, Math.min(node.friction, groundFriction));
            lastContacts++;
        }
    }

    private void resolveBoxes(SoftNode node) {
        double r = node.radius + CONTACT_THICKNESS;
        List<CollisionBox> candidates = collisionGrid.get(collisionKey(node.position.x, node.position.y, node.position.z));
        if (candidates == null) {
            return;
        }
        lastBoxCandidates += candidates.size();
        for (CollisionBox box : candidates) {
            // Defense in depth: the client already excludes boxes at or below
            // the heightmap plane, but a stale/alternative collider must not
            // turn the road into 314 coplanar contacts again.
            if (box.maxY <= groundY + 0.20) {
                continue;
            }
            if (node.position.x < box.minX - r || node.position.x > box.maxX + r
                    || node.position.y < box.minY - r || node.position.y > box.maxY + r
                    || node.position.z < box.minZ - r || node.position.z > box.maxZ + r) {
                continue;
            }

            double qx = clamp(node.position.x, box.minX, box.maxX);
            double qy = clamp(node.position.y, box.minY, box.maxY);
            double qz = clamp(node.position.z, box.minZ, box.maxZ);
            double dx = node.position.x - qx;
            double dy = node.position.y - qy;
            double dz = node.position.z - qz;
            double d2 = dx * dx + dy * dy + dz * dz;

            double nx;
            double ny;
            double nz;
            double depth;
            if (d2 > 1.0e-12) {
                double dist = Math.sqrt(d2);
                if (dist >= r) {
                    continue;
                }
                nx = dx / dist;
                ny = dy / dist;
                nz = dz / dist;
                depth = r - dist;
            } else {
                // Inside the box: use the nearest face and push out through it.
                double toMinX = node.position.x - box.minX;
                double toMaxX = box.maxX - node.position.x;
                double toMinY = node.position.y - box.minY;
                double toMaxY = box.maxY - node.position.y;
                double toMinZ = node.position.z - box.minZ;
                double toMaxZ = box.maxZ - node.position.z;
                nx = -1.0;
                ny = 0.0;
                nz = 0.0;
                depth = toMinX;
                if (toMaxX < depth) { depth = toMaxX; nx = 1.0; ny = 0.0; nz = 0.0; }
                if (toMinY < depth) { depth = toMinY; nx = 0.0; ny = -1.0; nz = 0.0; }
                if (toMaxY < depth) { depth = toMaxY; nx = 0.0; ny = 1.0; nz = 0.0; }
                if (toMinZ < depth) { depth = toMinZ; nx = 0.0; ny = 0.0; nz = -1.0; }
                if (toMaxZ < depth) { depth = toMaxZ; nx = 0.0; ny = 0.0; nz = 1.0; }
                depth += r;
            }

            // Velocity along the contact normal before the position is
            // corrected.  This used to be a literal 0, so every wall contact
            // looked like a zero-speed touch and never dented anything.
            double vnBefore = node.velocity.x * nx + node.velocity.y * ny + node.velocity.z * nz;
            // Rigid follow moves the lattice with the truck but deliberately
            // leaves the soft velocity relative to the chassis.  For a wall
            // contact the missing component is the truck's own approach speed;
            // without it the first (and sometimes only) wall frame at high
            // speed had vnBefore≈0 and never armed crash plastic.
            double rigidClosing = -(this.rigidVelX * nx + this.rigidVelY * ny
                    + this.rigidVelZ * nz);
            // Vettura resolves its rigid hull against the block before this
            // step runs, so by the time the shell touches the wall the entity
            // has already stopped and rigidVel only carries the residual
            // 2-3 m/s.  Use the speed the truck actually arrived with, signed
            // along this contact normal, so a 47 km/h hit is not recorded as
            // a 3 m/s tap.
            double approachClosing = -(this.crashApproachDirX * nx
                    + this.crashApproachDirZ * nz) * this.crashApproachSpeed;
            double impactSpeed = Math.max(0.0, Math.max(-vnBefore,
                    Math.max(rigidClosing, approachClosing)));
            // A tall face with a mostly horizontal normal is a wall.  A
            // one-block wall top is groundY+1.0; the old 1.20 threshold
            // rejected it and made a one-block test wall look like it never
            // dented.  A terrain step's top face is still excluded by ny.
            boolean wallFace = Math.abs(ny) < 0.30
                    && box.maxY > this.groundY + 0.75;
            // First-tick wall damage.  The client only opens the crash hot
            // window after it sees the wall marker, one tick too late for the
            // first contact.  This flag lets the same step that resolves the
            // wall also write the initial local plastic -- but only for a
            // genuinely fast approach.  crashGateArmed is a long-lived
            // "detector enabled" flag, not a crash window; using it alone let
            // a 1.3 m/s wall touch write permanent plastic.
            boolean firstWallTouch = this.crashGateArmed
                    && this.crashApproachSpeed > 8.0;
            boolean wallCrashContact = wallFace
                    && (this.damageUpdateEnabled || firstWallTouch)
                    && impactSpeed > 1.2;
            if (wallCrashContact) {
                wallContactThisStep = true;
            }

            node.position.x += nx * depth;
            node.position.y += ny * depth;
            node.position.z += nz * depth;
            if (depth > maxPenetration) {
                maxPenetration = depth;
            }

            double vn = node.velocity.x * nx + node.velocity.y * ny + node.velocity.z * nz;
            if (vn < 0.0) {
                node.velocity.x -= nx * vn;
                node.velocity.y -= ny * vn;
                node.velocity.z -= nz * vn;
            }
            applyTangentialFriction(node, Math.min(node.friction, groundFriction));

            // A tall face with a mostly horizontal normal is a wall, not the
            // low top face of a terrain step.  Record its closing speed so the
            // client can accept a real wall hit even when Vettura has already
            // stopped the rigid entity and the per-tick speed drop is small.
            if (wallFace && impactSpeed > PLASTIC_MIN_NORMAL_SPEED) {
                double speedLoss = Math.max(0.0, impactSpeed - Math.max(0.0, vn));
                if (isFrontCrushGroup(node)) {
                    lastFrontWallContacts++;
                    if (impactSpeed > lastFrontWallImpact) {
                        lastFrontWallImpact = impactSpeed;
                        lastFrontWallNormalX = nx;
                        lastFrontWallNormalZ = nz;
                    }
                    lastFrontWallSpeedLoss = Math.max(lastFrontWallSpeedLoss, speedLoss);
                } else if (isRearCrushGroup(node)) {
                    lastRearWallContacts++;
                    if (impactSpeed > lastRearWallImpact) {
                        lastRearWallImpact = impactSpeed;
                        lastRearWallNormalX = nx;
                        lastRearWallNormalZ = nz;
                    }
                    lastRearWallSpeedLoss = Math.max(lastRearWallSpeedLoss, speedLoss);
                }
            }

            // Permanent plastic flow is front-crush only and is budgeted per
            // tick.  The old code added up to 0.05 m on every substep for any
            // node, including the tailgate; normal driving along a wall could
            // bury the nose and destroy the rear without a real impact.
            // Gate it on the confirmed crash window as well: spawn settling,
            // seat corrections and a parked truck leaning on a wall all
            // produce transient node speeds above 1.2 m/s.  Without this gate
            // the hood collected ~0.66 m of plastic before the player ever
            // drove, which is exactly the "hood already offset on mount" bug.
            // A wall contact is often resolved to only a few millimetres of
            // penetration in the first substep.  Requiring 12 mm there meant
            // the first real wall hit never wrote any plastic and the whole
            // impact returned as a rigid rebound.  Any positive wall overlap
            // is enough; the normal closing speed gates the amount.
            double depthForDamage = (wallCrashContact || (this.damageUpdateEnabled && wallFace))
                    ? 1.0e-6 : 0.012;
            if (depth > depthForDamage && impactSpeed > PLASTIC_MIN_NORMAL_SPEED
                    && (this.damageUpdateEnabled || wallCrashContact)) {
                if (isCrushPanel(node)) {
                    if (node.plasticTick != plasticTick) {
                        node.plasticTick = plasticTick;
                        node.plasticTickAmount = 0.0;
                    }
                    // Hard ceiling per crash event.  The visual target is
                    // also the physical plastic ceiling: 31 km/h rear =
                    // 0.217 m, 100 km/h front = 0.70 m.  Without this the
                    // solver kept adding the full per-tick budget while the
                    // truck was wedged against the wall and buried the
                    // entire front clip inside the cab.
                    double limit = isFrontCrushGroup(node)
                            ? this.frontPlasticLimit : this.rearPlasticLimit;
                    double d = Math.sqrt(node.plasticX * node.plasticX
                            + node.plasticY * node.plasticY
                            + node.plasticZ * node.plasticZ);
                    if (d > limit) {
                        double scale = limit / d;
                        node.plasticX *= scale;
                        node.plasticY *= scale;
                        node.plasticZ *= scale;
                        d = limit;
                    }
                    double remaining = limit - d;
                    if (remaining > 1.0e-9) {
                        // A single crash tick used to add the same amount in
                        // every substep, so a rear tap could consume the whole
                        // ceiling before the next frame.  Spread the permanent
                        // flow over several ticks: the panel still folds
                        // visibly on impact, but it cannot turn to paper in
                        // one substep.
                        double tickCap = Math.min(0.24, Math.max(0.02, limit * 0.32));
                        double tickLeft = tickCap - node.plasticTickAmount;
                        if (tickLeft > 1.0e-9) {
                            double amount = Math.min(remaining,
                                    Math.min(tickLeft, depth * 0.50 + impactSpeed * 0.020));
                            node.plasticX += nx * amount;
                            node.plasticY += ny * amount;
                            node.plasticZ += nz * amount;
                            node.plasticTickAmount += amount;
                            d += amount;
                        }
                    }
                    if (d > this.panelPlasticDepth) {
                        this.panelPlasticDepth = d;
                    }
                    if (isFrontCrushGroup(node) && d > this.panelFrontDepth) {
                        this.panelFrontDepth = d;
                    }
                }
                // Beam-level settlement is still allowed on any non-wheel
                // body part, but only once per tick and only with a genuine
                // normal impact speed.  A light scrape no longer shortens
                // every incident beam by 12 cm.
                if (wallFace && !"wheel".equals(node.group0) && !"tire".equals(node.group0)) {
                    node.wallDented = true;
                    node.wallImpactSpeed = Math.max(node.wallImpactSpeed, impactSpeed);
                }
            }
            lastContacts++;
            lastBoxContacts++;
            if (isFrontCrushGroup(node)) {
                lastFrontBoxContacts++;
            } else if (isRearCrushGroup(node)) {
                lastRearBoxContacts++;
            }
        }
    }

    /**
     * Sweep a rigid follow displacement against the block AABBs and stop it
     * at the first wall face it would cross.
     *
     * <p>The client moves every lattice node by the truck's per-tick shift.
     * At 100 km/h that is 1.4 blocks, which is wider than a one-block wall:
     * the old point test at the end of the move could skip a wall entirely,
     * so the high-speed nose never made contact and never crushed.  This
     * returns the allowed displacement; the node is expected to be moved by
     * the returned delta, not the original one.</p>
     */
    public double[] sweepRigidFollow(SoftNode node, double dx, double dy, double dz) {
        double[] out = new double[3];
        this.sweepRigidFollow(node, dx, dy, dz, out);
        return out;
    }

    /**
     * Allocation-free variant.  The client moves every node every tick, so the
     * array-returning form above created thousands of three-double arrays per
     * second purely as garbage.
     */
    public void sweepRigidFollow(SoftNode node, double dx, double dy, double dz, double[] out) {
        if (this.collisionBoxes.isEmpty()
                || Math.abs(dx) + Math.abs(dy) + Math.abs(dz) < 1.0e-6) {
            out[0] = dx;
            out[1] = dy;
            out[2] = dz;
            return;
        }
        double r = node.radius + CONTACT_THICKNESS;
        double bestT = 1.0;
        int bestAxis = -1;
        for (CollisionBox box : this.collisionBoxes) {
            if (box.maxY <= this.groundY + 0.20) {
                continue;
            }
            double minX = box.minX - r;
            double maxX = box.maxX + r;
            double minY = box.minY - r;
            double maxY = box.maxY + r;
            double minZ = box.minZ - r;
            double maxZ = box.maxZ + r;
            double t0 = 0.0;
            double t1 = 1.0;
            int axis = -1;

            double tNear;
            double tFar;
            if (Math.abs(dx) < 1.0e-9) {
                if (node.position.x < minX || node.position.x > maxX) {
                    continue;
                }
            } else {
                tNear = (minX - node.position.x) / dx;
                tFar = (maxX - node.position.x) / dx;
                if (tNear > tFar) { double tmp = tNear; tNear = tFar; tFar = tmp; }
                if (tNear > t0) { t0 = tNear; axis = 0; }
                if (tFar < t1) { t1 = tFar; }
                if (t0 > t1) { continue; }
            }
            if (Math.abs(dy) < 1.0e-9) {
                if (node.position.y < minY || node.position.y > maxY) {
                    continue;
                }
            } else {
                tNear = (minY - node.position.y) / dy;
                tFar = (maxY - node.position.y) / dy;
                if (tNear > tFar) { double tmp = tNear; tNear = tFar; tFar = tmp; }
                if (tNear > t0) { t0 = tNear; axis = 1; }
                if (tFar < t1) { t1 = tFar; }
                if (t0 > t1) { continue; }
            }
            if (Math.abs(dz) < 1.0e-9) {
                if (node.position.z < minZ || node.position.z > maxZ) {
                    continue;
                }
            } else {
                tNear = (minZ - node.position.z) / dz;
                tFar = (maxZ - node.position.z) / dz;
                if (tNear > tFar) { double tmp = tNear; tNear = tFar; tFar = tmp; }
                if (tNear > t0) { t0 = tNear; axis = 2; }
                if (tFar < t1) { t1 = tFar; }
                if (t0 > t1) { continue; }
            }
            // t0==0 means the node already starts touching/inside the box;
            // the normal substep resolver owns that penetration.
            if (axis >= 0 && t0 > 1.0e-6 && t0 < bestT) {
                bestT = t0;
                bestAxis = axis;
            }
        }
        if (bestAxis < 0) {
            out[0] = dx;
            out[1] = dy;
            out[2] = dz;
            return;
        }
        // Stop inside the expanded face by a few millimetres.  resolveBoxes()
        // only counts a contact when the node centre is closer than radius +
        // thickness, and a one-micron overlap was eaten by the very first
        // solver correction: the node sat exactly on the boundary, dist >= r
        // failed, and the wall never registered.  Five millimetres is far
        // below anything visible but survives the solver's own displacement.
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double pad = len > 1.0e-9 ? 0.005 / len : 0.0;
        double t = Math.min(1.0, Math.max(0.0, bestT + pad));
        out[0] = dx * t;
        out[1] = dy * t;
        out[2] = dz * t;
    }

    /** Insert each static box in every cell it can touch, with node-radius padding. */
    private void indexCollisionBox(CollisionBox box) {
        int minX = collisionCell(box.minX - COLLISION_PADDING);
        int minY = collisionCell(box.minY - COLLISION_PADDING);
        int minZ = collisionCell(box.minZ - COLLISION_PADDING);
        int maxX = collisionCell(box.maxX + COLLISION_PADDING);
        int maxY = collisionCell(box.maxY + COLLISION_PADDING);
        int maxZ = collisionCell(box.maxZ + COLLISION_PADDING);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    collisionGrid.computeIfAbsent(collisionKey(x, y, z), ignored -> new ArrayList<>()).add(box);
                }
            }
        }
    }

    private static int collisionCell(double value) {
        return (int) Math.floor(value / COLLISION_CELL);
    }

    private static long collisionKey(double x, double y, double z) {
        return collisionKey(collisionCell(x), collisionCell(y), collisionCell(z));
    }

    private static long collisionKey(int x, int y, int z) {
        return ((long) (x & 0x1fffff) << 42)
                | ((long) (y & 0x1fffff) << 21)
                | (z & 0x1fffff);
    }

    /**
     * Convert a wall contact into permanent lattice crush.  Only beams that
     * are already compressed at the contact are shortened, and only by a
     * fraction of that compression.  This is the difference between a rubber
     * ball that springs back and a steel panel that stays dented.
     */
    private static boolean isFrontCrushGroup(SoftNode node) {
        return "front".equals(node.group0);
    }

    private static boolean isRearCrushGroup(SoftNode node) {
        return "tailgate".equals(node.group0);
    }

    private static boolean isCrushPanel(SoftNode node) {
        return isFrontCrushGroup(node) || isRearCrushGroup(node);
    }

    /**
     * Convert a fast foreign-soft-body overlap into a permanent local dent.
     * The wall path uses rest-length settlement; foreign bodies do not have
     * a wall normal/marker, so this writes a small node plastic offset and
     * shortens the incident beams inside the same per-beam deform limit.
     */
    private void applyForeignImpact(SoftNode node, double nx, double ny, double nz,
                                    double closingSpeed) {
        if (node == null || node.foreignImpactTick == this.plasticTick
                || this.foreignDamageBudget <= 1.0e-9) {
            return;
        }
        if ("wheel".equals(node.group0) || "tire".equals(node.group0)
                || (node.part != null && node.part.startsWith("tire"))) {
            return;
        }
        double amount = Math.min(0.08, Math.max(0.0, (closingSpeed - 2.5) * 0.012));
        amount = Math.min(amount, this.foreignDamageBudget);
        if (amount <= 1.0e-6) {
            return;
        }
        // The normal points from the foreign surface toward this node; moving
        // against it is an inward dent on this shell.
        node.plasticX -= nx * amount;
        node.plasticY -= ny * amount;
        node.plasticZ -= nz * amount;
        node.foreignImpactTick = this.plasticTick;
        this.foreignDamageBudget -= amount;
        ensureBeamIncidence();
        java.util.List<SoftBeam> incident = beamIncidence.get(node.index);
        if (incident == null) {
            return;
        }
        for (SoftBeam beam : incident) {
            if (beam.broken) {
                continue;
            }
            double deformLimit = Math.max(0.0, Math.min(1.0, beam.deformLimit));
            double floor = Math.max(0.05, beam.originalRestLength * (1.0 - deformLimit));
            double available = beam.restLength - floor;
            if (available <= 1.0e-9) {
                continue;
            }
            double shorten = Math.min(amount * 0.35, available);
            beam.restLength -= shorten;
            beam.plasticStrain += shorten;
            beam.deformed = true;
            beam.yieldCount++;
        }
    }

    private void applyWallDents(SoftNode[] array) {
        ensureBeamIncidence();
        // Wall denting is part of damage settlement, not ordinary suspension
        // contact.  While the confirmed-crash window is closed, discard stale
        // per-node flags so a parked truck cannot keep shortening beams.
        if (!this.damageUpdateEnabled && !this.wallContactThisStep) {
            clearWallDentFlags(array);
            return;
        }
        if (this.wallDentAppliedThisEvent) {
            // The panel plastic is already capped; a second beam pass on a
            // sustained contact only drives rest lengths to the floor.
            clearWallDentFlags(array);
            return;
        }
        boolean applied = false;
        for (SoftNode node : array) {
            if (!node.wallDented) {
                continue;
            }
            node.wallDented = false;
            double speed = node.wallImpactSpeed;
            node.wallImpactSpeed = 0.0;
            // A slow squeeze still has to dent: the client-side wall proxy
            // only carries a fraction of the entity's approach speed.
            if (speed < WALL_DENT_MIN_NORMAL_SPEED) {
                continue;
            }
            java.util.List<SoftBeam> incident = beamIncidence.get(node.index);
            if (incident == null) {
                continue;
            }
            // Layered force attenuation: the outermost panel receives the
            // full settlement, the next longitudinal layer receives less,
            // and the cab/frame only sees the residual.  This is a smooth
            // per-layer weight, not a per-panel hard ceiling.
            double span = Math.max(0.6, this.maxRestX - this.minRestX);
            double layer;
            if (isFrontCrushGroup(node)) {
                layer = Math.max(0.0, (this.maxRestX - node.rest.x) / span);
            } else {
                layer = Math.max(0.0, (node.rest.x - this.minRestX) / span);
            }
            double layerWeight = Math.max(0.35, 1.0 - layer * 2.2);
            double sag = 0.0;
            for (SoftBeam beam : incident) {
                if (beam.broken || beam.wallDentTick == this.plasticTick) {
                    continue;
                }
                double length = beam.currentLength(array);
                double compression = beam.restLength - length;
                if (compression <= 0.004) {
                    continue;
                }
                // applyPlasticFlow caps a beam at original*(1-deformLimit),
                // but this wall path used to ignore the field and shorten to
                // an absolute 5 cm.  Keep the authored floor and the absolute
                // 5 cm lower bound.
                double deformLimit = Math.max(0.0, Math.min(1.0, beam.deformLimit));
                double floor = Math.max(0.05, beam.originalRestLength * (1.0 - deformLimit));
                double available = beam.restLength - floor;
                if (available <= 1.0e-9) {
                    continue;
                }
                double crush = (0.008 + compression * 0.55) * layerWeight;
                crush = Math.min(crush, available);
                if (crush <= 1.0e-9) {
                    continue;
                }
                beam.restLength = Math.max(floor, beam.restLength - crush);
                beam.wallDentTick = this.plasticTick;
                beam.deformed = true;
                beam.plasticStrain += crush;
                sag += crush;
                applied = true;
            }
            // The wall-dent path must curve too.  Previously it only shortened
            // rest lengths, so the render read as a flat rearward shove.  Put
            // the permanent sag on the node itself; shared neighbours see the
            // same sign and the front becomes a continuous banana.
            if (sag > 0.0 && "front".equals(node.group0)) {
                node.plasticY -= Math.min(0.12, sag * 0.45);
            } else if (sag > 0.0 && ("tailgate".equals(node.group0)
                    || "rear".equals(node.group0))) {
                node.plasticY += Math.min(0.08, sag * 0.35);
            }
        }
        if (applied) {
            this.wallDentAppliedThisEvent = true;
        }
    }

    private static void clearWallDentFlags(SoftNode[] array) {
        for (SoftNode node : array) {
            node.wallDented = false;
            node.wallImpactSpeed = 0.0;
        }
    }

    private void ensureBeamIncidence() {
        if (beamIncidence != null && beamIncidenceSize == beams.size()) {
            return;
        }
        beamIncidence = new java.util.HashMap<>();
        for (SoftBeam beam : beams) {
            beamIncidence.computeIfAbsent(beam.a, ignored -> new java.util.ArrayList<>()).add(beam);
            beamIncidence.computeIfAbsent(beam.b, ignored -> new java.util.ArrayList<>()).add(beam);
        }
        beamIncidenceSize = beams.size();
    }

    /**
     * C4/C5 cross-body narrow phase.  Two soft bodies resolve one direction
     * at a time with mass-weighted reaction; group names are never compared
     * across body namespaces, so every foreign triangle is dynamic.
     */
    /** Total number of discrete buckle events across all beams. */
    public int foldEventCount() {
        int total = 0;
        for (SoftBeam beam : beams) {
            if (beam.foldStep > 0.0) {
                total += beam.foldEvents;
            }
        }
        return total;
    }

    /** Largest permanent plastic displacement currently stored on any node. */
    public double plasticDepth() {
        double max = 0.0;
        for (SoftNode node : nodes) {
            double d = Math.sqrt(node.plasticX * node.plasticX
                    + node.plasticY * node.plasticY
                    + node.plasticZ * node.plasticZ);
            max = Math.max(max, d);
        }
        return max;
    }

    /**
     * Plastic depth on the authored crush panels only.  The bed floor and
     * chassis anchors can accumulate large plastic offsets while the truck is
     * simply resting on the ground; feeding that value to the renderer made
     * an undamaged truck look permanently folded.  Only the front bumper /
     * hood and the tailgate are allowed to drive the visible fold.
     */
    public double panelPlasticDepth() {
        return this.panelPlasticDepth;
    }

    /** Plastic depth on the front crash panel only. */
    public double panelFrontDepth() {
        return this.panelFrontDepth;
    }

    /** Restore front/rear ceilings from a saved crash depth before capping. */
    public void restoreSavedCrushLimits(double frontDepth, double rearDepth) {
        double ceiling = this.globalPlasticLimit > 0.0
                ? this.globalPlasticLimit : Double.MAX_VALUE;
        double front = Double.isFinite(frontDepth) && frontDepth > 0.0
                ? Math.min(ceiling, frontDepth) : 0.0;
        double rear = Double.isFinite(rearDepth) && rearDepth > 0.0
                ? Math.min(ceiling, rearDepth) : 0.0;
        setCrushPlasticLimits(
                Math.max(this.frontPlasticLimit, front),
                Math.max(this.rearPlasticLimit, rear));
    }

    /** Set the permanent plastic ceiling for this crash event.  This is the
     *  same depth the authored skin uses, so the panel cannot be translated
     *  deeper than the visible crush. */
    public void setCrushPlasticLimits(double frontLimit, double rearLimit) {
        // Front and rear are separate physical structures.  Collapsing both
        // ceilings to the whole-vehicle envelope was the code path that let a
        // low-speed tail contact translate the entire tailgate by metres even
        // with the hardest profile selected.
        double ceiling = this.globalPlasticLimit > 0.0
                ? this.globalPlasticLimit : Double.MAX_VALUE;
        this.frontPlasticLimit = Math.max(0.0, Math.min(ceiling, frontLimit));
        this.rearPlasticLimit = Math.max(0.0, Math.min(ceiling, rearLimit));
        capCrushPlastic();
    }

    /** Clamp already accumulated plastic to the current crash ceilings. */
    public void capCrushPlastic() {
        for (SoftNode node : this.nodes) {
            double limit = isFrontCrushGroup(node)
                    ? this.frontPlasticLimit
                    : isRearCrushGroup(node) ? this.rearPlasticLimit
                    : Math.min(this.globalPlasticLimit, this.chassisPlasticLimit);
            double d = Math.sqrt(node.plasticX * node.plasticX
                    + node.plasticY * node.plasticY
                    + node.plasticZ * node.plasticZ);
            if (limit > 0.0 && d > limit && d > 1.0e-9) {
                double scale = limit / d;
                node.plasticX *= scale;
                node.plasticY *= scale;
                node.plasticZ *= scale;
            }
        }
    }

    /**
     * Freeze the current offset of anchored nodes into their permanent plastic
     * field.  Called on the falling edge of the crash window so the crush stays
     * where the impact left it instead of springing back out.
     */
    public void bakeAnchoredOffsets(double factor) {
        double f = Math.max(0.0, Math.min(1.0, factor));
        if (f <= 0.0) {
            return;
        }
        for (SoftNode node : this.nodes) {
            if (!node.anchored) {
                continue;
            }
            double ex = node.position.x - (node.targetX + node.plasticX);
            double ey = node.position.y - (node.targetY + node.plasticY);
            double ez = node.position.z - (node.targetZ + node.plasticZ);
            double d = Math.sqrt(ex * ex + ey * ey + ez * ez);
            if (d < 1.0e-4 || d > 0.30) {
                continue;
            }
            double perNode = Math.min(f, 0.10 / d);
            double px = node.plasticX + ex * perNode;
            double py = node.plasticY + ey * perNode;
            double pz = node.plasticZ + ez * perNode;
            double limit = node.plasticLimit;
            if (limit > 0.0 && limit < Double.MAX_VALUE) {
                double len = Math.sqrt(px * px + py * py + pz * pz);
                if (len > limit && len > 1.0e-9) {
                    double s = limit / len;
                    px *= s;
                    py *= s;
                    pz *= s;
                }
            }
            node.plasticX = px;
            node.plasticY = py;
            node.plasticZ = pz;
        }
        capCrushPlastic();
    }

    /**
     * One cheap smoothing pass over the authored front crush nodes.  A fold
     * event writes both endpoints directly; without relaxation, neighbouring
     * beams can leave one node behind and the seam reads as a knife cut.
     * Only the outer front layer is relaxed, so the chassis and collision
     * boxes keep their exact contact position.
     */
    private void relaxFoldSeams() {
        // Seam smoothing is part of the crash window.  Running it forever
        // after panelFrontDepth crossed 0.02 kept diffusing plastic, changing
        // the saved state hash and making the truck slowly soften while parked.
        if (!this.damageUpdateEnabled) {
            return;
        }
        if (this.panelFrontDepth < 0.02 || this.beamIncidence == null) {
            return;
        }
        for (SoftNode node : this.nodes) {
            if (!"front".equals(node.group0)) {
                continue;
            }
            java.util.List<SoftBeam> incident = this.beamIncidence.get(node.index);
            if (incident == null || incident.size() < 2) {
                continue;
            }
            double ax = 0.0, ay = 0.0, az = 0.0;
            int count = 0;
            for (SoftBeam beam : incident) {
                int other = beam.a == node.index ? beam.b : beam.a;
                if (other < 0 || other >= this.nodes.size()) {
                    continue;
                }
                SoftNode n = this.nodes.get(other);
                ax += n.plasticX;
                ay += n.plasticY;
                az += n.plasticZ;
                count++;
            }
            if (count == 0) {
                continue;
            }
            ax /= count;
            ay /= count;
            az /= count;
            double blend = 0.10;
            node.plasticX += (ax - node.plasticX) * blend;
            node.plasticY += (ay - node.plasticY) * blend;
            node.plasticZ += (az - node.plasticZ) * blend;
        }
    }

    public int resolveForeign(SoftBodyWorld other) {
        if (other == null || other == this) {
            return 0;
        }
        // The client shell runs with triangleCollision=false.  Its step() then
        // does not refresh triangle normals, so a deformed truck still presents
        // the spawn surface to another Vnodo body.  Refresh the foreign surface
        // before the narrow phase; this is a read-only geometric update.
        if (!other.triangleCollision && other.airDensity <= 0.0) {
            other.refreshTriangleGeometry();
        }
        SoftNode[] mine = nodeArray();
        SoftNode[] theirs = other.nodeArray();
        int hits = 0;
        Vec3 closest = new Vec3();
        for (SoftNode node : mine) {
            if (!node.externalCollisionEnabled()) {
                continue;
            }
            for (SoftTriangle triangle : other.triangles) {
                if (triangle.broken || !triangle.collision || triangle.degenerate()) {
                    continue;
                }
                double signed = TriangleCollision.signedDistance(
                        node.position.x, node.position.y, node.position.z,
                        theirs[triangle.a].position.x, theirs[triangle.a].position.y, theirs[triangle.a].position.z,
                        theirs[triangle.b].position.x, theirs[triangle.b].position.y, theirs[triangle.b].position.z,
                        theirs[triangle.c].position.x, theirs[triangle.c].position.y, theirs[triangle.c].position.z,
                        triangle.nx, triangle.ny, triangle.nz, closest);
                double band = node.radius + CONTACT_THICKNESS;
                if (signed <= 0.0 || signed >= band) {
                    continue;
                }
                double depth = band - signed;
                double wNode = node.inverseMass;
                double wTri = theirs[triangle.a].inverseMass
                        + theirs[triangle.b].inverseMass + theirs[triangle.c].inverseMass;
                double total = wNode + wTri;
                if (total < 1.0e-9) {
                    continue;
                }
                double nx = triangle.nx;
                double ny = triangle.ny;
                double nz = triangle.nz;
                node.position.x += nx * depth * (wNode / total);
                node.position.y += ny * depth * (wNode / total);
                node.position.z += nz * depth * (wNode / total);
                SoftNode a = theirs[triangle.a];
                SoftNode b = theirs[triangle.b];
                SoftNode c = theirs[triangle.c];
                double pa = depth * (a.inverseMass / total);
                double pb = depth * (b.inverseMass / total);
                double pc = depth * (c.inverseMass / total);
                a.position.x -= nx * pa; a.position.y -= ny * pa; a.position.z -= nz * pa;
                b.position.x -= nx * pb; b.position.y -= ny * pb; b.position.z -= nz * pb;
                c.position.x -= nx * pc; c.position.y -= ny * pc; c.position.z -= nz * pc;
                double triVx = (theirs[triangle.a].velocity.x
                        + theirs[triangle.b].velocity.x + theirs[triangle.c].velocity.x) / 3.0;
                double triVy = (theirs[triangle.a].velocity.y
                        + theirs[triangle.b].velocity.y + theirs[triangle.c].velocity.y) / 3.0;
                double triVz = (theirs[triangle.a].velocity.z
                        + theirs[triangle.b].velocity.z + theirs[triangle.c].velocity.z) / 3.0;
                double vn = (node.velocity.x - triVx) * nx
                        + (node.velocity.y - triVy) * ny
                        + (node.velocity.z - triVz) * nz;
                if (vn < 0.0) {
                    node.velocity.x -= nx * vn * (wNode / total);
                    node.velocity.y -= ny * vn * (wNode / total);
                    node.velocity.z -= nz * vn * (wNode / total);
                    a.velocity.x += nx * vn * (a.inverseMass / total);
                    a.velocity.y += ny * vn * (a.inverseMass / total);
                    a.velocity.z += nz * vn * (a.inverseMass / total);
                    b.velocity.x += nx * vn * (b.inverseMass / total);
                    b.velocity.y += ny * vn * (b.inverseMass / total);
                    b.velocity.z += nz * vn * (b.inverseMass / total);
                    c.velocity.x += nx * vn * (c.inverseMass / total);
                    c.velocity.y += ny * vn * (c.inverseMass / total);
                    c.velocity.z += nz * vn * (c.inverseMass / total);
                }
                // Vehicle-to-vehicle and foreign-soft-body contacts had no
                // damage channel: the solver pushed the shells apart and
                // zeroed normal velocity, but nothing permanent was written.
                // Only a real closing speed creates a dent, and the per-tick
                // budget prevents a full overlapping shell from folding every
                // node at once.
                if (-vn > 2.5) {
                    applyForeignImpact(node, nx, ny, nz, -vn);
                }
                hits++;
            }
        }
        return hits;
    }

    private void resolveStaticMeshes(SoftNode node) {
        for (StaticMesh mesh : staticMeshes) {
            if (mesh.resolve(node, CONTACT_THICKNESS)) {
                lastContacts++;
                applyTangentialFriction(node, Math.min(node.friction, groundFriction));
            }
        }
    }

    private static void applyTangentialFriction(SoftNode node, double mu) {
        double factor = Math.max(0.0, 1.0 - Math.min(1.0, mu));
        node.velocity.x *= factor;
        node.velocity.z *= factor;
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }

    /**
     * C1-C9: point-triangle contact with a 2.5 cm band, front-side anticlip,
     * CCD sweep, mass-weighted reaction and Coulomb friction.  Triangles in
     * the node's first group are exempt (BeamNG's first-group rule), and the
     * node's own adjacent triangles are skipped so the shell cannot fight
     * itself.
     */
    private void resolveTriangles(SoftNode node, SoftNode[] array) {
        Vec3 closest = new Vec3();
        Vec3 previousClosest = new Vec3();
        for (SoftTriangle triangle : triangles) {
            if (triangle.broken || !triangle.collision || triangle.degenerate()
                    || triangle.sleeping) {
                continue;
            }
            if (triangle.a == node.index || triangle.b == node.index || triangle.c == node.index) {
                continue;
            }
            if (triangle.group(array).equals(node.group0)) {
                continue;
            }
            // C4/C5 matrix: triangles in this single body are self-collision.
            // External bodies would use dynamicCollision(), but the shell has
            // no foreign triangles yet, so selfCollision is the deciding flag.
            if (!node.selfCollision) {
                continue;
            }

            SoftNode na = array[triangle.a];
            SoftNode nb = array[triangle.b];
            SoftNode nc = array[triangle.c];
            double minX = Math.min(na.position.x, Math.min(nb.position.x, nc.position.x));
            double minY = Math.min(na.position.y, Math.min(nb.position.y, nc.position.y));
            double minZ = Math.min(na.position.z, Math.min(nb.position.z, nc.position.z));
            double maxX = Math.max(na.position.x, Math.max(nb.position.x, nc.position.x));
            double maxY = Math.max(na.position.y, Math.max(nb.position.y, nc.position.y));
            double maxZ = Math.max(na.position.z, Math.max(nb.position.z, nc.position.z));
            double band = node.radius + CONTACT_THICKNESS;
            double segMinX = Math.min(node.position.x, node.previous.x);
            double segMinY = Math.min(node.position.y, node.previous.y);
            double segMinZ = Math.min(node.position.z, node.previous.z);
            double segMaxX = Math.max(node.position.x, node.previous.x);
            double segMaxY = Math.max(node.position.y, node.previous.y);
            double segMaxZ = Math.max(node.position.z, node.previous.z);
            if (segMaxX < minX - band || segMinX > maxX + band
                    || segMaxY < minY - band || segMinY > maxY + band
                    || segMaxZ < minZ - band || segMinZ > maxZ + band) {
                continue;
            }

            // C9: use the cached previous normal when it still agrees with
            // the current one, so a fast deforming triangle does not flip the
            // CCD crossing sign between two substeps.
            double ccdNx = triangle.nx;
            double ccdNy = triangle.ny;
            double ccdNz = triangle.nz;
            double lastLength = Math.sqrt(triangle.lastNx * triangle.lastNx
                    + triangle.lastNy * triangle.lastNy + triangle.lastNz * triangle.lastNz);
            if (lastLength > 1.0e-9) {
                double dot = (triangle.lastNx * triangle.nx
                        + triangle.lastNy * triangle.ny + triangle.lastNz * triangle.nz) / lastLength;
                if (dot > 0.2) {
                    ccdNx = triangle.lastNx / lastLength;
                    ccdNy = triangle.lastNy / lastLength;
                    ccdNz = triangle.lastNz / lastLength;
                }
            }
            double signed = TriangleCollision.signedDistance(
                    node.position.x, node.position.y, node.position.z,
                    na.position.x, na.position.y, na.position.z,
                    nb.position.x, nb.position.y, nb.position.z,
                    nc.position.x, nc.position.y, nc.position.z,
                    ccdNx, ccdNy, ccdNz, closest);

            // C13 bias selects the front/back band.  Default keeps the
            // public 2.5 cm front / 1.5 cm back split; oneWay modes disable
            // one side entirely.
            double frontT;
            double backT;
            switch (triangle.bias) {
                case 1 -> { frontT = 0.020; backT = 0.020; }
                case 2 -> { frontT = 0.035; backT = 0.008; }
                case 3 -> { frontT = 0.0; backT = 0.020; }
                case 4 -> { frontT = 0.025; backT = 0.0; }
                default -> { frontT = CONTACT_THICKNESS; backT = 0.015; }
            }
            double frontBand = node.radius + frontT;
            double backBand = node.radius + backT;
            if (frontT > 0.0 && signed > 0.0 && signed < frontBand) {
                pushTriangle(node, triangle, na, nb, nc, signed, frontBand);
            } else if (backT > 0.0 && signed < 0.0 && signed > -backBand) {
                // Back side: keep the node inside rather than popping it out
                // through the front.  This is the anticlip half of C3.
                pushTriangle(node, triangle, na, nb, nc, signed, -backBand);
            }

            // Swept test: only when the previous position was in front and
            // the current one crossed the plane.
            double previousSigned = TriangleCollision.signedDistance(
                    node.previous.x, node.previous.y, node.previous.z,
                    na.position.x, na.position.y, na.position.z,
                    nb.position.x, nb.position.y, nb.position.z,
                    nc.position.x, nc.position.y, nc.position.z,
                    ccdNx, ccdNy, ccdNz, previousClosest);
            if (previousSigned > 0.0 && signed <= 0.0) {
                Vec3 hit = new Vec3();
                double t = TriangleCollision.sweepPointTriangle(
                        node.previous.x, node.previous.y, node.previous.z,
                        node.position.x, node.position.y, node.position.z,
                        na.position.x, na.position.y, na.position.z,
                        nb.position.x, nb.position.y, nb.position.z,
                        nc.position.x, nc.position.y, nc.position.z, hit);
                if (Double.isFinite(t)) {
                    node.position.set(hit.x + triangle.nx * band,
                            hit.y + triangle.ny * band,
                            hit.z + triangle.nz * band);
                    double vn = node.velocity.x * triangle.nx
                            + node.velocity.y * triangle.ny
                            + node.velocity.z * triangle.nz;
                    if (vn < 0.0) {
                        node.velocity.x -= triangle.nx * vn;
                        node.velocity.y -= triangle.ny * vn;
                        node.velocity.z -= triangle.nz * vn;
                    }
                    lastTriangleContacts++;
                }
            }
        }
    }

    private void pushTriangle(SoftNode node, SoftTriangle triangle, SoftNode na,
                              SoftNode nb, SoftNode nc, double signed, double target) {
        double depth = target - signed;
        ContactPool.Contact contact = contacts.touch(node.index, triangle.id);
        if (contact.age >= 2 && contact.normalImpulse > 0.0
                && contact.lastNx * triangle.nx + contact.lastNy * triangle.ny
                + contact.lastNz * triangle.nz > 0.95) {
            double warm = contact.normalImpulse * contactWarmStart;
            double wNode = node.inverseMass;
            double wTri = na.inverseMass + nb.inverseMass + nc.inverseMass;
            if (wNode + wTri > 1.0e-9) {
                node.velocity.x += triangle.nx * warm * wNode;
                node.velocity.y += triangle.ny * warm * wNode;
                node.velocity.z += triangle.nz * warm * wNode;
                double shareA = na.inverseMass / Math.max(1.0e-9, wTri);
                double shareB = nb.inverseMass / Math.max(1.0e-9, wTri);
                double shareC = nc.inverseMass / Math.max(1.0e-9, wTri);
                na.velocity.x -= triangle.nx * warm * shareA;
                na.velocity.y -= triangle.ny * warm * shareA;
                na.velocity.z -= triangle.nz * warm * shareA;
                nb.velocity.x -= triangle.nx * warm * shareB;
                nb.velocity.y -= triangle.ny * warm * shareB;
                nb.velocity.z -= triangle.nz * warm * shareB;
                nc.velocity.x -= triangle.nx * warm * shareC;
                nc.velocity.y -= triangle.ny * warm * shareC;
                nc.velocity.z -= triangle.nz * warm * shareC;
            }
        }
        contact.depth = depth;
        contact.lastNx = contact.nx;
        contact.lastNy = contact.ny;
        contact.lastNz = contact.nz;
        contact.nx = triangle.nx;
        contact.ny = triangle.ny;
        contact.nz = triangle.nz;
        if (depth > maxPenetration) {
            maxPenetration = depth;
        }
        node.position.x += triangle.nx * depth;
        node.position.y += triangle.ny * depth;
        node.position.z += triangle.nz * depth;

        double vn = node.velocity.x * triangle.nx + node.velocity.y * triangle.ny
                + node.velocity.z * triangle.nz;
        if (vn < 0.0) {
            double wNode = node.inverseMass;
            double wTri = na.inverseMass + nb.inverseMass + nc.inverseMass;
            if (wNode + wTri > 1.0e-9) {
                double impulse = -vn / (wNode + wTri);
                contact.normalImpulse += impulse;
                node.velocity.x += triangle.nx * impulse * wNode;
                node.velocity.y += triangle.ny * impulse * wNode;
                node.velocity.z += triangle.nz * impulse * wNode;
                double shareA = na.inverseMass / Math.max(1.0e-9, wTri);
                double shareB = nb.inverseMass / Math.max(1.0e-9, wTri);
                double shareC = nc.inverseMass / Math.max(1.0e-9, wTri);
                na.velocity.x -= triangle.nx * impulse * shareA;
                na.velocity.y -= triangle.ny * impulse * shareA;
                na.velocity.z -= triangle.nz * impulse * shareA;
                nb.velocity.x -= triangle.nx * impulse * shareB;
                nb.velocity.y -= triangle.ny * impulse * shareB;
                nb.velocity.z -= triangle.nz * impulse * shareB;
                nc.velocity.x -= triangle.nx * impulse * shareC;
                nc.velocity.y -= triangle.ny * impulse * shareC;
                nc.velocity.z -= triangle.nz * impulse * shareC;
            }
        }

        double vnAfter = node.velocity.x * triangle.nx + node.velocity.y * triangle.ny
                + node.velocity.z * triangle.nz;
        if (vnAfter > 0.0) {
            // Warm start + projection must not launch the node off the face.
            node.velocity.x -= triangle.nx * vnAfter;
            node.velocity.y -= triangle.ny * vnAfter;
            node.velocity.z -= triangle.nz * vnAfter;
        }
        applyCoulombFriction(node, triangle, vn);
        lastTriangleContacts++;
    }

    private void applyCoulombFriction(SoftNode node, SoftTriangle triangle, double vn) {
        double vx = node.velocity.x - triangle.nx * vn;
        double vy = node.velocity.y - triangle.ny * vn;
        double vz = node.velocity.z - triangle.nz * vn;
        double vt = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (vt < 1.0e-9) {
            return;
        }
        double maxDelta = Math.min(1.0, Math.max(0.0, node.friction)) * Math.abs(vn);
        double scale = Math.min(1.0, maxDelta / vt);
        node.velocity.x -= vx * scale;
        node.velocity.y -= vy * scale;
        node.velocity.z -= vz * scale;
    }

    /**
     * C14: load-time proximity report.  A node closer than 3 cm (soft parts
     * 3.5 cm) to a triangle is a spawn contact; this reports it and never
     * silently moves the model.  Call after all geometry is registered.
     */
    public int checkSpawnContacts(double softThreshold) {
        spawnContacts.clear();
        SoftNode[] array = nodeArray();
        // C14 filter: a node is legitimately coincident with its own 1-ring
        // shell, so skip triangles touching a beam neighbour.  Only foreign
        // parts that are not welded into the lattice are reported.
        java.util.Map<Integer, java.util.Set<Integer>> ring = new java.util.HashMap<>();
        for (SoftBeam beam : beams) {
            ring.computeIfAbsent(beam.a, key -> new java.util.HashSet<>()).add(beam.b);
            ring.computeIfAbsent(beam.b, key -> new java.util.HashSet<>()).add(beam.a);
        }
        for (SoftNode node : array) {
            java.util.Set<Integer> neighbours = ring.getOrDefault(node.index, java.util.Set.of());
            for (SoftTriangle triangle : triangles) {
                if (triangle.broken || triangle.degenerate()) {
                    continue;
                }
                if (triangle.a == node.index || triangle.b == node.index || triangle.c == node.index) {
                    continue;
                }
                if (neighbours.contains(triangle.a) || neighbours.contains(triangle.b)
                        || neighbours.contains(triangle.c)) {
                    continue;
                }
                Vec3 closest = new Vec3();
                double signed = TriangleCollision.signedDistance(
                        node.position.x, node.position.y, node.position.z,
                        array[triangle.a].position.x, array[triangle.a].position.y, array[triangle.a].position.z,
                        array[triangle.b].position.x, array[triangle.b].position.y, array[triangle.b].position.z,
                        array[triangle.c].position.x, array[triangle.c].position.y, array[triangle.c].position.z,
                        triangle.nx, triangle.ny, triangle.nz, closest);
                if (Math.abs(signed) < softThreshold) {
                    spawnContacts.add("node " + node.index + " tri " + triangle.id
                            + " s=" + String.format(java.util.Locale.ROOT, "%.4f", signed));
                }
            }
        }
        return spawnContacts.size();
    }

    /** D7: reset topology and all node kinematics to their target pose. */
    public void reset() {
        damage.reset();
        for (SoftNode node : nodes) {
            node.plasticX = 0.0;
            node.plasticY = 0.0;
            node.plasticZ = 0.0;
            node.wallDented = false;
            node.wallImpactSpeed = 0.0;
            node.foreignImpactTick = Long.MIN_VALUE;
            node.plasticTick = Long.MIN_VALUE;
            node.plasticTickAmount = 0.0;
            node.position.set(node.targetX, node.targetY, node.targetZ);
            node.previous.set(node.position);
            node.velocity.set(0.0, 0.0, 0.0);
            node.sleeping = false;
        }
        for (SoftTriangle triangle : triangles) {
            triangle.updateGeometry(nodeArray());
        }
        maxPenetration = 0.0;
        lastContacts = 0;
        lastBoxContacts = 0;
        lastStepBoxContacts = 0;
        lastFrontBoxContacts = 0;
        lastStepFrontBoxContacts = 0;
        lastRearBoxContacts = 0;
        lastStepRearBoxContacts = 0;
        lastFrontWallContacts = 0;
        lastRearWallContacts = 0;
        lastStepFrontWallContacts = 0;
        lastStepRearWallContacts = 0;
        lastFrontWallImpact = 0.0;
        lastRearWallImpact = 0.0;
        lastStepFrontWallImpact = 0.0;
        lastStepRearWallImpact = 0.0;
        lastFrontWallNormalX = 0.0;
        lastFrontWallNormalZ = 0.0;
        lastRearWallNormalX = 0.0;
        lastRearWallNormalZ = 0.0;
        lastStepFrontWallNormalX = 0.0;
        lastStepFrontWallNormalZ = 0.0;
        lastStepRearWallNormalX = 0.0;
        lastStepRearWallNormalZ = 0.0;
        lastFrontWallSpeedLoss = 0.0;
        lastRearWallSpeedLoss = 0.0;
        lastStepFrontWallSpeedLoss = 0.0;
        lastStepRearWallSpeedLoss = 0.0;
        lastTriangleContacts = 0;
        this.panelPlasticDepth = 0.0;
        this.panelFrontDepth = 0.0;
        this.frontPlasticLimit = Math.min(this.globalPlasticLimit, 0.72);
        this.rearPlasticLimit = Math.min(this.globalPlasticLimit, 0.34);
        this.rigidVelX = 0.0;
        this.rigidVelY = 0.0;
        this.rigidVelZ = 0.0;
        // A fresh/reset world starts with damage settlement closed until the
        // client arms a confirmed crash window.
        this.damageUpdateEnabled = false;
        this.damageWindowWasOpen = false;
        this.crashGateArmed = false;
        this.wallContactThisStep = false;
        this.wallDentAppliedThisEvent = false;
    }

    /** Damage-only hash used by the save layer; ignores live motion. */
    public long damageStateHash() {
        long hash = 0x9E3779B97F4A7C15L;
        for (SoftNode node : nodes) {
            hash = mix(hash, Double.doubleToLongBits(node.plasticX));
            hash = mix(hash, Double.doubleToLongBits(node.plasticY));
            hash = mix(hash, Double.doubleToLongBits(node.plasticZ));
        }
        for (SoftBeam beam : beams) {
            hash = mix(hash, beam.broken ? 1L : 0L);
            hash = mix(hash, Double.doubleToLongBits(beam.restLength));
            hash = mix(hash, Double.doubleToLongBits(beam.plasticStrain));
            hash = mix(hash, Double.doubleToLongBits(beam.beamStrength));
            hash = mix(hash, Double.doubleToLongBits(beam.maxPosStress));
            hash = mix(hash, Double.doubleToLongBits(beam.maxNegStress));
            hash = mix(hash, Double.doubleToLongBits(beam.foldAccum));
            hash = mix(hash, beam.foldEvents);
            hash = mix(hash, beam.deformed ? 1L : 0L);
            hash = mix(hash, beam.yieldCount);
        }
        for (SoftTriangle triangle : triangles) {
            hash = mix(hash, triangle.broken ? 1L : 0L);
        }
        return hash;
    }

    /** F6: deterministic state hash over positions, velocities and topology. */
    public long stateHash() {
        long hash = 0x9E3779B97F4A7C15L;
        for (SoftNode node : nodes) {
            hash = mix(hash, Double.doubleToLongBits(node.position.x));
            hash = mix(hash, Double.doubleToLongBits(node.position.y));
            hash = mix(hash, Double.doubleToLongBits(node.position.z));
            hash = mix(hash, Double.doubleToLongBits(node.velocity.x));
            hash = mix(hash, Double.doubleToLongBits(node.velocity.y));
            hash = mix(hash, Double.doubleToLongBits(node.velocity.z));
            hash = mix(hash, Double.doubleToLongBits(node.plasticX));
            hash = mix(hash, Double.doubleToLongBits(node.plasticY));
            hash = mix(hash, Double.doubleToLongBits(node.plasticZ));
        }
        for (SoftBeam beam : beams) {
            hash = mix(hash, beam.broken ? 1L : 0L);
            hash = mix(hash, Double.doubleToLongBits(beam.restLength));
            hash = mix(hash, Double.doubleToLongBits(beam.plasticStrain));
            hash = mix(hash, Double.doubleToLongBits(beam.beamStrength));
            hash = mix(hash, Double.doubleToLongBits(beam.maxPosStress));
            hash = mix(hash, Double.doubleToLongBits(beam.maxNegStress));
            hash = mix(hash, Double.doubleToLongBits(beam.foldAccum));
            hash = mix(hash, beam.foldEvents);
        }
        for (SoftTriangle triangle : triangles) {
            hash = mix(hash, triangle.broken ? 1L : 0L);
        }
        return hash;
    }

    private static long mix(long hash, long value) {
        hash ^= value + 0x9E3779B97F4A7C15L + (hash << 6) + (hash >>> 2);
        return hash;
    }

    /** F4: return null when the core invariants hold, else the first failure. */
    public String validate() {
        for (SoftNode node : nodes) {
            if (!Double.isFinite(node.position.x) || !Double.isFinite(node.position.y)
                    || !Double.isFinite(node.position.z) || !Double.isFinite(node.velocity.x)
                    || !Double.isFinite(node.velocity.y) || !Double.isFinite(node.velocity.z)) {
                return "node " + node.index + " has non-finite state";
            }
            if (node.mass <= 0.0 || !Double.isFinite(node.mass)) {
                return "node " + node.index + " has invalid mass";
            }
        }
        for (SoftBeam beam : beams) {
            if (beam.a < 0 || beam.a >= nodes.size() || beam.b < 0 || beam.b >= nodes.size()) {
                return "beam index out of range";
            }
            if (beam.restLength < 0.0 || !Double.isFinite(beam.restLength)) {
                return "beam rest length invalid";
            }
        }
        for (SoftTriangle triangle : triangles) {
            if (triangle.a < 0 || triangle.a >= nodes.size()
                    || triangle.b < 0 || triangle.b >= nodes.size()
                    || triangle.c < 0 || triangle.c >= nodes.size()) {
                return "triangle index out of range";
            }
        }
        return null;
    }

    public double kineticEnergy() {
        double sum = 0.0;
        for (SoftNode node : nodes) {
            sum += 0.5 * node.mass * node.velocity.lengthSquared();
        }
        return sum;
    }

    public double maxSpeed() {
        double max = 0.0;
        for (SoftNode node : nodes) {
            max = Math.max(max, node.velocity.length());
        }
        return max;
    }

    public Vec3 center() {
        Vec3 out = new Vec3();
        double total = 0.0;
        for (SoftNode node : nodes) {
            out.add(node.position.x * node.mass, node.position.y * node.mass, node.position.z * node.mass);
            total += node.mass;
        }
        if (total > 0.0) {
            out.scale(1.0 / total);
        }
        return out;
    }

    public double minY() {
        double min = Double.POSITIVE_INFINITY;
        for (SoftNode node : nodes) {
            min = Math.min(min, node.position.y);
        }
        return min;
    }

    public double maxDisplacementFromTargets() {
        double max = 0.0;
        for (SoftNode node : nodes) {
            double dx = node.position.x - node.targetX;
            double dy = node.position.y - node.targetY;
            double dz = node.position.z - node.targetZ;
            max = Math.max(max, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        return max;
    }

    public boolean hasNaN() {
        for (SoftNode node : nodes) {
            if (!Double.isFinite(node.position.x)
                    || !Double.isFinite(node.position.y)
                    || !Double.isFinite(node.position.z)
                    || !Double.isFinite(node.velocity.x)
                    || !Double.isFinite(node.velocity.y)
                    || !Double.isFinite(node.velocity.z)) {
                return true;
            }
        }
        return false;
    }

    /** Small immutable-by-convention box collider for world blocks. */
    public static final class CollisionBox {
        public final double minX;
        public final double minY;
        public final double minZ;
        public final double maxX;
        public final double maxY;
        public final double maxZ;

        public CollisionBox(double minX, double minY, double minZ,
                            double maxX, double maxY, double maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }
    }
}
