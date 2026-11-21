package com.bamng.client;

import com.antaurora.vettura.vehicle.PickupTruckEntity;
import com.bamng.core.SoftNode;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Per-truck per-vertex skin field.
 *
 * <p>Every BAMNG node has a rest offset relative to the truck.  Each client
 * tick we store the node's current world delta.  The renderer converts each
 * emitted vertex from camera space to world space, adds the weighted local
 * node delta, then converts it back.  Nothing here assumes a mesh name, a
 * vehicle type, or a Vextris bone layout.</p>
 */
public final class BamngRenderState {
    private static final Map<PickupTruckEntity, Skin> SKINS = new WeakHashMap<>();
    private static long appliedVertices;
    private static double appliedMaxSq;
    private static double lastFrontDepth = -1.0;
    private static double lastRearDepth = -1.0;

    private BamngRenderState() {
    }

    /** Called once after the lattice has settled; records the undeformed pose. */
    public static void capture(PickupTruckEntity truck, List<SoftNode> nodes,
                               double truckX, double truckY, double truckZ, float truckYaw) {
        if (nodes.size() > 1024) {
            throw new IllegalArgumentException("BAMNG skin supports at most 1024 nodes, got " + nodes.size());
        }
        Skin skin = new Skin(nodes.size());
        skin.originX = truckX;
        skin.originY = truckY;
        skin.originZ = truckZ;
        // Vettura renders the mesh through rotationY(180 - entityYaw), and
        // the soft-node init uses the same rotation.  Store the node rest
        // offsets in that authored model frame: front z is negative, cab z is
        // near zero.  Keeping the old entity-yaw frame made every front vertex
        // bind to the tailgate nodes.
        skin.yaw = 180.0F - truckYaw;
        double modelYaw = Math.toRadians(skin.yaw);
        double modelCos = Math.cos(modelYaw);
        double modelSin = Math.sin(modelYaw);
        int rigidCount = 0;
        for (int i = 0; i < nodes.size(); i++) {
            SoftNode node = nodes.get(i);
            // Inverse of the model pose above.  The grid, the authored mesh
            // and these rest offsets are all in the same model frame.
            double dx = node.position.x - truckX;
            double dz = node.position.z - truckZ;
            skin.restX[i] = dx * modelCos - dz * modelSin;
            skin.restY[i] = node.position.y - truckY;
            skin.restZ[i] = dx * modelSin + dz * modelCos;
            skin.rigid[i] = node.group0 != null && node.group0.equals("wheel")
                    || node.part != null && node.part.startsWith("tire");
            skin.front[i] = "front".equals(node.group0);
            if (skin.rigid[i]) {
                rigidCount++;
            }
        }
        skin.rigidCountValue = rigidCount;
        skin.rebuildGrid();
        SKINS.put(truck, skin);
        double minRx = 1e9, maxRx = -1e9, minRy = 1e9, maxRy = -1e9, minRz = 1e9, maxRz = -1e9;
        for (int i = 0; i < nodes.size(); i++) {
            minRx = Math.min(minRx, skin.restX[i]); maxRx = Math.max(maxRx, skin.restX[i]);
            minRy = Math.min(minRy, skin.restY[i]); maxRy = Math.max(maxRy, skin.restY[i]);
            minRz = Math.min(minRz, skin.restZ[i]); maxRz = Math.max(maxRz, skin.restZ[i]);
        }
        skin.restMinZ = minRz;
        skin.restMaxZ = maxRz;
        com.bamng.BamngMod.LOGGER.info("BAMNG skin rest bounds x={}..{} y={}..{} z={}..{}",
                String.format(java.util.Locale.ROOT, "%.2f", minRx), String.format(java.util.Locale.ROOT, "%.2f", maxRx),
                String.format(java.util.Locale.ROOT, "%.2f", minRy), String.format(java.util.Locale.ROOT, "%.2f", maxRy),
                String.format(java.util.Locale.ROOT, "%.2f", minRz), String.format(java.util.Locale.ROOT, "%.2f", maxRz));
    }

    /** Called every client tick; stores current node displacement. */
    public static void update(PickupTruckEntity truck, List<SoftNode> nodes, double worldDent,
                              double truckX, double truckY, double truckZ, float truckYaw) {
        Skin skin = SKINS.get(truck);
        if (skin == null || skin.size != nodes.size()) {
            capture(truck, nodes, truckX, truckY, truckZ, truckYaw);
            skin = SKINS.get(truck);
            if (skin == null) {
                return;
            }
        }
        skin.originX = truckX;
        skin.originY = truckY;
        skin.originZ = truckZ;
        skin.yaw = 180.0F - truckYaw;
        // HUD near-distance stats describe this frame's skin queries, not the
        // lifetime average since the skin was built.
        skin.resetQueryStats();
        // frontDepth is injected after update(), so an early return here can
        // otherwise leave dx/dy/dz and hasFrontField from the previous frame
        // alive.  A later setFrontDepth() would then skin the new nose through
        // stale node travel.  Clear the per-frame field before the fast exit.
        java.util.Arrays.fill(skin.dx, 0.0);
        java.util.Arrays.fill(skin.dy, 0.0);
        java.util.Arrays.fill(skin.dz, 0.0);
        skin.frontMaxSq = 0.0;
        skin.smoothedMaxSq = 0.0;
        skin.frontSmoothMaxSq = 0.0;
        skin.frontSmoothZ = 0.0;
        skin.maxDelta = 0.0;
        // The body field is permanent plastic outside the authored nose and
        // tailgate groups.  Sample it before the fast exit so a side/cab hit
        // can start the CPU skin even when frontDepth/rearDepth are still zero.
        double bodyPlastic = 0.0;
        for (int i = 0; i < nodes.size(); i++) {
            SoftNode node = nodes.get(i);
            if (skin.rigid[i] || "front".equals(node.group0) || "tailgate".equals(node.group0)) {
                continue;
            }
            double p2 = node.plasticX * node.plasticX
                    + node.plasticY * node.plasticY
                    + node.plasticZ * node.plasticZ;
            if (p2 > bodyPlastic) {
                bodyPlastic = p2;
            }
        }
        skin.bodyDepth = bodyPlastic > 0.0004
                ? Math.min(1.0, Math.sqrt(bodyPlastic)) : 0.0;
        boolean wasFrontActive = skin.frontDepth > 0.004 || skin.rearDepth > 0.004
                || skin.bodyDepth > 0.004;
        // An undamaged (or tailgate-only) truck needs no per-tick 314-node
        // diffuse pass.  The CPU skin only exists for the authored front
        // shell, so gate the update on the front signal, not on every panel:
        // a permanent tailgate crease used to keep this pass alive forever.
        if (!wasFrontActive) {
            return;
        }
        double yaw = Math.toRadians(180.0 - truckYaw);
        double yawCos = Math.cos(yaw);
        double yawSin = Math.sin(yaw);
        skin.yawCos = yawCos;
        skin.yawSin = yawSin;
        double maxSq = 0.0;
        skin.frontMaxSq = 0.0;
        for (int i = 0; i < nodes.size(); i++) {
            SoftNode node = nodes.get(i);
            double rx = skin.restX[i];
            double rz = skin.restZ[i];
            double restX = truckX + rx * yawCos + rz * yawSin;
            double restY = truckY + skin.restY[i];
            double restZ = truckZ - rx * yawSin + rz * yawCos;
            // Use the permanent plastic rest shape for rendering.  The live
            // node position can lag the anchor spring by several centimetres
            // right after impact, which made a 0.25 m dent render as 1 cm.
            // target + plastic is the shape the panel will keep.
            double dentX = node.targetX + node.plasticX;
            double dentY = node.targetY + node.plasticY;
            double dentZ = node.targetZ + node.plasticZ;
            double dx = dentX - restX;
            double dy = dentY - restY;
            double dz = dentZ - restZ;
            if (skin.rigid[i]) {
                // Wheels are drawn through their own unskinned geometry; their
                // lattice deltas include the spinning rigid transform and used
                // to leak a 1.4 m outlier into the body skin.
                dx = dy = dz = 0.0;
            }
            skin.dx[i] = dx;
            skin.dy[i] = dy;
            skin.dz[i] = dz;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > maxSq) {
                maxSq = d2;
            }
            if (skin.front[i] && d2 > skin.frontMaxSq) {
                skin.frontMaxSq = d2;
            }
        }
        // Diffuse the displacement over neighbouring lattice nodes.  Plastic
        // flow is concentrated on a few nodes; raw nearest-node binding then
        // tears adjacent triangles apart (the body disappears into culled
        // fragments).  Two smoothing passes keep the mesh closed while a
        // 0.25 m dent still spans several nodes.
        double plasticDent = 0.0;
        for (int i = 0; i < nodes.size(); i++) {
            SoftNode node = nodes.get(i);
            // Only the authored crush panels drive the visible fold.  Ground
            // contact can push the bed floor / chassis anchors several metres
            // into plastic while the truck is undamaged; using every node
            // here made a parked truck render as a crushed wreck.
            if (!"front".equals(node.group0) && !"tailgate".equals(node.group0)) {
                continue;
            }
            double p2 = node.plasticX * node.plasticX
                    + node.plasticY * node.plasticY
                    + node.plasticZ * node.plasticZ;
            if (p2 > plasticDent) {
                plasticDent = p2;
                skin.dentLocalX = node.localX;
                skin.dentLocalY = node.localY;
                skin.dentLocalZ = node.localZ;
            }
        }
        skin.smoothDisplacements();
        skin.maxDelta = Math.sqrt(maxSq);
        // No fallback to the all-node raw field: that field is contaminated
        // by bed/chassis ground contacts and is not a crash signal.
        skin.dentDepth = plasticDent > 0.0004
                ? Math.min(1.0, Math.sqrt(plasticDent)) : 0.0;
        // Vettura's own deformation state is the authoritative damage signal.
        // The imported mesh can have a stale/empty node plastic field after a
        // spawn, so use the vehicle damage value as a render fallback too.
        if (worldDent > 0.02) {
            skin.dentDepth = Math.max(skin.dentDepth, Math.min(10.0, worldDent));
            skin.dentLocalZ = skin.restMinZ;
        }
        // frontDepth is supplied separately by the physics runtime (front
        // panel plastic + crash proxy).  Do not copy the all-panel dentDepth
        // here: a tailgate-only dent would otherwise fold the nose too.
        skin.frontDepth = 0.0;
        skin.rearDepth = 0.0;
        if (skin.dentDepth > 0.004) {
            skin.maxDelta = Math.max(skin.maxDelta, skin.dentDepth);
        }
        if (skin.bodyDepth > 0.004) {
            skin.maxDelta = Math.max(skin.maxDelta, skin.bodyDepth);
        }
    }

    public static long appliedVertices() {
        return appliedVertices;
    }

    public static double appliedMax() {
        return Math.sqrt(appliedMaxSq);
    }

    public static double lastFrontDepth() {
        return lastFrontDepth;
    }

    public static double lastRearDepth() {
        return lastRearDepth;
    }

    public static void resetAppliedStats() {
        appliedVertices = 0L;
        appliedMaxSq = 0.0;
    }

    /** Record the total authored-mesh displacement for the HUD/proof counter. */
    static void noteVisualDelta(double dx, double dy, double dz) {
        double d2 = dx * dx + dy * dy + dz * dz;
        if (d2 > 1.0e-10) {
            appliedVertices++;
            if (d2 > appliedMaxSq) {
                appliedMaxSq = d2;
            }
        }
    }

    public static Skin skinFor(PickupTruckEntity truck) {
        return SKINS.get(truck);
    }

    /** Front-only crush depth; leaves the rear fold out of the nose geometry. */
    public static void setFrontDepth(PickupTruckEntity truck, double frontDepth) {
        Skin skin = SKINS.get(truck);
        if (skin != null && Double.isFinite(frontDepth)) {
            // Do not latch.  The old Math.max kept the first stray contact
            // forever and then a real crash could not change the visual.
            // A real crash keeps frontCrashDepth at its impact value, so the
            // fold remains permanent; a transient false positive can now
            // decay back to zero.
            skin.frontDepth = Math.max(0.0, frontDepth);
            lastFrontDepth = skin.frontDepth;
        }
    }

    /** Tailgate-only crush depth; reverse impacts use the rear fold. */
    public static void setRearDepth(PickupTruckEntity truck, double rearDepth) {
        Skin skin = SKINS.get(truck);
        if (skin != null && Double.isFinite(rearDepth)) {
            skin.rearDepth = Math.max(0.0, rearDepth);
            lastRearDepth = skin.rearDepth;
        }
    }

    public static void reset(PickupTruckEntity truck) {
        SKINS.remove(truck);
        lastFrontDepth = -1.0;
        lastRearDepth = -1.0;
    }

    public static final class Skin {
        private final int size;
        private final double[] restX;
        private final double[] restY;
        private final double[] restZ;
        private final boolean[] rigid;
        private final boolean[] front;
        private final double[] dx;
        private final double[] dy;
        private final double[] dz;
        private double[] smoothX;
        private double[] smoothY;
        private double[] smoothZ;
        /** Per-node neighbour lists, built once at capture.  The per-tick
         *  smooth used to do 27 HashMap<Long,...> lookups and box every key
         *  for all 314 nodes; this turns it into a flat int walk. */
        private int[][] smoothNeighbors;
        private final Map<Long, List<Integer>> grid = new HashMap<>();
        /** Rest-position keyed weight cache.  Stable across frames, so a
         *  vertex is searched once instead of once per Vextris buffer. */
        private final LongLongCache weightCache = new LongLongCache();
        private final int[] bestIndexScratch = {-1, -1, -1};
        private final double[] bestDistScratch = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        private double originX;
        private double originY;
        private double originZ;
        private float yaw;
        private float packYaw = Float.NaN;
        private double packCos = 1.0;
        private double packSin = 0.0;
        private double maxDelta;
        private int rigidCountValue;
        private static final double CELL = 0.8;

        private Skin(int size) {
            this.size = size;
            this.restX = new double[size];
            this.restY = new double[size];
            this.restZ = new double[size];
            this.rigid = new boolean[size];
            this.front = new boolean[size];
            this.dx = new double[size];
            this.dy = new double[size];
            this.dz = new double[size];
        }

        private void rebuildGrid() {
            this.grid.clear();
            for (int i = 0; i < this.size; i++) {
                // Grid lives in the truck's rest frame.  Rebuilding it every
                // tick used to allocate one ArrayList per occupied cell and
                // evaluate two trig functions per node; neither is needed
                // when the lookup position is converted to local space.
                long key = key(this.restX[i], this.restY[i], this.restZ[i]);
                this.grid.computeIfAbsent(key, ignored -> new ArrayList<>()).add(i);
            }
            rebuildSmoothNeighbors();
        }

        private void rebuildSmoothNeighbors() {
            int n = this.size;
            int[][] all = new int[n][];
            for (int i = 0; i < n; i++) {
                int cx = cell(this.restX[i]);
                int cy = cell(this.restY[i]);
                int cz = cell(this.restZ[i]);
                List<Integer> found = new ArrayList<>();
                for (int ix = cx - 1; ix <= cx + 1; ix++) {
                    for (int iy = cy - 1; iy <= cy + 1; iy++) {
                        for (int iz = cz - 1; iz <= cz + 1; iz++) {
                            List<Integer> bucket = this.grid.get(key(ix, iy, iz));
                            if (bucket == null) {
                                continue;
                            }
                            for (int j : bucket) {
                                if (!this.rigid[j]) {
                                    found.add(j);
                                }
                            }
                        }
                    }
                }
                int count = found.size();
                int[] neighbors = new int[count];
                for (int k = 0; k < count; k++) {
                    neighbors[k] = found.get(k);
                }
                all[i] = neighbors;
            }
            this.smoothNeighbors = all;
        }

        double smoothedMaxSq;
        /** Largest raw displacement on a front-group node. */
        double frontMaxSq;
        /** Largest front displacement after the diffuse pass actually used by
         *  the vertex skin.  Subtracting the raw peak from the authored nose
         *  fold under-crushed the mesh whenever smoothing diluted the peak. */
        double frontSmoothMaxSq;
        /** Largest rearward (-Z) front travel after smoothing.  This is the
         *  amount the vertex skin already moved the nose, so the authored fold
         *  only has to supply the remainder. */
        double frontSmoothZ;
        /** Truck yaw used for the current update/smoothing pass. */
        double yawSin = 0.0;
        double yawCos = 1.0;
        double dentDepth;
        /** Front-only crush depth used by the authored nose fold. */
        double frontDepth;
        /** Tailgate-only crush depth used by the rear fold. */
        double rearDepth;
        /**
         * Permanent plastic outside the nose/tailgate groups.  This is what
         * lets the cab, doors, bed sides and frame rails visibly yield when
         * the whole body is softened instead of only the front clip.
         */
        double bodyDepth;
        double dentLocalX;
        double dentLocalY;
        double dentLocalZ;
        double dentDirX;
        double dentDirY;
        double dentDirZ;
        double restMinZ = -2.5;
        double restMaxZ = 2.5;
        long skinQueries;
        double nearSum;
        double nearMax;

        public double nearAvg() { return this.skinQueries == 0 ? -1.0 : this.nearSum / this.skinQueries; }
        public double nearMax() { return this.nearMax; }
        void resetQueryStats() {
            this.skinQueries = 0L;
            this.nearSum = 0.0;
            this.nearMax = 0.0;
        }
        /** Actual front displacement after smoothing, along the authored -Z
         *  crush axis.  Do not use the full 3D displacement length here: side
         *  sway of a front node would otherwise be mistaken for nose travel and
         *  cancel the fold. */
        public double frontSkinDepth() {
            return Math.max(0.0, this.frontSmoothZ);
        }
        public float yaw() { return this.yaw; }
        public double originX() { return this.originX; }
        public double originY() { return this.originY; }
        public double originZ() { return this.originZ; }

        public int nodeCount() {
            return this.size;
        }

        public int rigidCount() {
            return this.rigidCountValue;
        }

        public boolean active() {
            // Only the authored front shell is CPU-skinned.  Do not let a
            // permanent tailgate crease, bed/chassis ground plastic, or normal
            // front-node jiggle keep the expensive path and Vextris' GPU cache
            // eviction alive.  frontDepth is the real crush signal (lattice
            // plastic + crash proxy) and starts the fold on the first frame.
            return this.frontDepth > 0.004 || this.rearDepth > 0.004
                    || this.bodyDepth > 0.004;
        }

        /** True when the lattice, rather than the damage fallback, has a usable displacement field. */
        public boolean hasFieldDeformation() {
            return this.smoothedMaxSq > 0.000025;
        }

        public boolean hasFrontField() {
            return this.frontMaxSq > 0.000025;
        }

        public double delta() {
            return this.maxDelta;
        }

        public double dentDepth() {
            return this.dentDepth;
        }

        public double frontDepth() {
            return this.frontDepth;
        }

        public double rearDepth() {
            return this.rearDepth;
        }

        public double bodyDepth() {
            return this.bodyDepth;
        }

        private void smoothDisplacements() {
            int n = this.size;
            double[] sx = this.smoothX;
            double[] sy = this.smoothY;
            double[] sz = this.smoothZ;
            if (sx == null || sx.length != n) {
                sx = new double[n];
                sy = new double[n];
                sz = new double[n];
                this.smoothX = sx;
                this.smoothY = sy;
                this.smoothZ = sz;
            }
            for (int i = 0; i < n; i++) {
                int[][] neighbors = this.smoothNeighbors;
                int[] row = neighbors == null ? null : neighbors[i];
                double tx = 0.0, ty = 0.0, tz = 0.0;
                int count = 0;
                if (row != null) {
                    for (int j : row) {
                        tx += this.dx[j]; ty += this.dy[j]; tz += this.dz[j];
                        count++;
                    }
                }
                if (count > 0) {
                    sx[i] = tx / count; sy[i] = ty / count; sz[i] = tz / count;
                } else {
                    sx[i] = this.dx[i]; sy[i] = this.dy[i]; sz[i] = this.dz[i];
                }
            }
            System.arraycopy(sx, 0, this.dx, 0, n);
            System.arraycopy(sy, 0, this.dy, 0, n);
            System.arraycopy(sz, 0, this.dz, 0, n);
            double maxSq2 = 0.0;
            double frontMaxSq2 = 0.0;
            double frontZ = 0.0;
            for (int i = 0; i < n; i++) {
                double d2 = this.dx[i] * this.dx[i] + this.dy[i] * this.dy[i] + this.dz[i] * this.dz[i];
                if (d2 > maxSq2) maxSq2 = d2;
                if (this.front[i]) {
                    if (d2 > frontMaxSq2) frontMaxSq2 = d2;
                    // Project the smoothed travel onto the authored +Z axis.
                    // The front shell is authored at negative z, so rearward
                    // crush is positive model z.  Lateral sway must not cancel
                    // the authored nose fold; only rearward travel counts.
                    double localDz = this.dx[i] * this.yawSin + this.dz[i] * this.yawCos;
                    if (localDz > 0.0) {
                        frontZ = Math.max(frontZ, localDz);
                    }
                }
            }
            this.smoothedMaxSq = maxSq2;
            this.frontSmoothMaxSq = frontMaxSq2;
            this.frontSmoothZ = frontZ;
        }

        /** Adds the cached three-node lattice displacement to a world-space vertex. */
        public void apply(Vector3f world, long vertexKey) {
            if (!active()) {
                return;
            }
            long packed = this.weightCache.get(vertexKey);
            if (packed == 0L) {
                packed = this.packWeights(world);
                this.weightCache.put(vertexKey, packed);
            }
            if (packed < 0L) {
                return;
            }
            // The T15 lattice contains 542 nodes.  Nine-bit indices silently
            // wrapped nodes 512..541 onto unrelated nodes, so use ten bits
            // per index.  Three 10-bit indices + three 8-bit weights fit in
            // the low 54 bits of a signed long.
            int i0 = (int) (packed & 0x3FFL);
            int i1 = (int) ((packed >>> 10) & 0x3FFL);
            int i2 = (int) ((packed >>> 20) & 0x3FFL);
            double w0 = ((packed >>> 30) & 0xFFL) / 255.0;
            double w1 = ((packed >>> 38) & 0xFFL) / 255.0;
            double w2 = ((packed >>> 46) & 0xFFL) / 255.0;
            world.x += (float) (this.dx[i0] * w0 + this.dx[i1] * w1 + this.dx[i2] * w2);
            world.y += (float) (this.dy[i0] * w0 + this.dy[i1] * w1 + this.dy[i2] * w2);
            world.z += (float) (this.dz[i0] * w0 + this.dz[i1] * w1 + this.dz[i2] * w2);
        }

        /** Pack three nearest nodes and their normalized weights into one long. */
        private long packWeights(Vector3f world) {
            // Look up the vertex in the truck's rest frame.  The grid and the
            // node rest offsets are both local, so a moving/yawing truck never
            // invalidates it and this path needs no per-node trig.
            double yawCos;
            double yawSin;
            if (this.yaw != this.packYaw) {
                double yaw = Math.toRadians(this.yaw);
                this.packYaw = this.yaw;
                this.packCos = Math.cos(yaw);
                this.packSin = Math.sin(yaw);
            }
            yawCos = this.packCos;
            yawSin = this.packSin;
            double dx = world.x - this.originX;
            double dz = world.z - this.originZ;
            double lx = dx * yawCos - dz * yawSin;
            double ly = world.y - this.originY;
            double lz = dx * yawSin + dz * yawCos;
            int cx = cell(lx);
            int cy = cell(ly);
            int cz = cell(lz);
            int[] bestIndex = this.bestIndexScratch;
            double[] bestDist = this.bestDistScratch;
            bestIndex[0] = -1;
            bestIndex[1] = -1;
            bestIndex[2] = -1;
            bestDist[0] = Double.MAX_VALUE;
            bestDist[1] = Double.MAX_VALUE;
            bestDist[2] = Double.MAX_VALUE;
            int radius = 0;
            while (true) {
                for (int ix = cx - radius; ix <= cx + radius; ix++) {
                    for (int iy = cy - radius; iy <= cy + radius; iy++) {
                        for (int iz = cz - radius; iz <= cz + radius; iz++) {
                            List<Integer> bucket = this.grid.get(key(ix, iy, iz));
                            if (bucket == null) {
                                continue;
                            }
                            for (int index : bucket) {
                                if (this.rigid[index]) {
                                    // Wheels/tires carry their own rigid
                                    // transform.  Letting a body vertex bind
                                    // to one leaks the wheel spin into the
                                    // front shell.
                                    continue;
                                }
                                // Bind vertices to the rest shell.  Binding to
                                // already-displaced nodes makes weights jump as
                                // the panel folds, which is both expensive and
                                // visually produces texture swimming.
                                double vx = lx - this.restX[index];
                                double vy = ly - this.restY[index];
                                double vz = lz - this.restZ[index];
                                double d2 = vx * vx + vy * vy + vz * vz;
                                if (d2 > 2.8 * 2.8) {
                                    continue;
                                }
                                if (d2 < bestDist[0]) {
                                    bestDist[2] = bestDist[1]; bestIndex[2] = bestIndex[1];
                                    bestDist[1] = bestDist[0]; bestIndex[1] = bestIndex[0];
                                    bestDist[0] = d2; bestIndex[0] = index;
                                } else if (d2 < bestDist[1]) {
                                    bestDist[2] = bestDist[1]; bestIndex[2] = bestIndex[1];
                                    bestDist[1] = d2; bestIndex[1] = index;
                                } else if (d2 < bestDist[2]) {
                                    bestDist[2] = d2; bestIndex[2] = index;
                                }
                            }
                        }
                    }
                }
                if (bestIndex[0] >= 0 || radius == 3) {
                    break;
                }
                radius++;
            }
            this.skinQueries++;
            if (bestIndex[0] >= 0) {
                double nearest = Math.sqrt(bestDist[0]);
                this.nearSum += nearest;
                this.nearMax = Math.max(this.nearMax, nearest);
            }
            if (bestIndex[0] < 0) {
                // Auto-cover the whole authored shell: engine internals, spare
                // wheel, interior trim and other pieces can sit farther than
                // the normal 2.8 m neighbourhood from a lattice node.  Instead
                // of leaving them rigid/unskinned, bind them to the nearest
                // non-wheel lattice node anywhere inside the truck.  The truck
                // mesh is the only source here, so this is not a scene-wide
                // nearest-point query.
                for (int index = 0; index < this.size; index++) {
                    if (this.rigid[index]) {
                        continue;
                    }
                    double vx = lx - this.restX[index];
                    double vy = ly - this.restY[index];
                    double vz = lz - this.restZ[index];
                    double d2 = vx * vx + vy * vy + vz * vz;
                    if (d2 < bestDist[0]) {
                        bestDist[2] = bestDist[1]; bestIndex[2] = bestIndex[1];
                        bestDist[1] = bestDist[0]; bestIndex[1] = bestIndex[0];
                        bestDist[0] = d2; bestIndex[0] = index;
                    } else if (d2 < bestDist[1]) {
                        bestDist[2] = bestDist[1]; bestIndex[2] = bestIndex[1];
                        bestDist[1] = d2; bestIndex[1] = index;
                    } else if (d2 < bestDist[2]) {
                        bestDist[2] = d2; bestIndex[2] = index;
                    }
                }
            }
            if (bestIndex[0] < 0) {
                return -1L;
            }
            double w0 = 1.0 / (0.035 + bestDist[0]);
            double w1 = bestIndex[1] < 0 ? 0.0 : 1.0 / (0.035 + bestDist[1]);
            double w2 = bestIndex[2] < 0 ? 0.0 : 1.0 / (0.035 + bestDist[2]);
            double total = w0 + w1 + w2;
            if (total <= 1.0e-9) {
                return -1L;
            }
            int q0 = (int) Math.round(w0 / total * 255.0);
            int q1 = (int) Math.round(w1 / total * 255.0);
            // Rounding q0 and q1 independently can make their sum exceed
            // 255, which would give q2 a negative target and over-weight the
            // vertex.  Renormalize the first two before deriving q2.
            if (q0 + q1 > 255) {
                double scale = 255.0 / (q0 + q1);
                q0 = (int) Math.floor(q0 * scale);
                q1 = (int) Math.floor(q1 * scale);
            }
            int q2 = Math.max(0, 255 - q0 - q1);
            long packed = (bestIndex[0] & 0x3FFL)
                    | ((long) (bestIndex[1] < 0 ? 0 : bestIndex[1] & 0x3FF) << 10)
                    | ((long) (bestIndex[2] < 0 ? 0 : bestIndex[2] & 0x3FF) << 20)
                    | ((long) q0 << 30)
                    | ((long) q1 << 38)
                    | ((long) q2 << 46);
            return packed == 0L ? -1L : packed;
        }

        private static long key(double x, double y, double z) {
            return key(cell(x), cell(y), cell(z));
        }

        /** Stable render-time identity for a vertex, quantized to 4 mm. */
        static long vertexKey(double x, double y, double z) {
            long ix = (long) Math.floor(x * 256.0);
            long iy = (long) Math.floor(y * 256.0);
            long iz = (long) Math.floor(z * 256.0);
            return ((ix & 0x1FFFFFL) << 42)
                    | ((iy & 0x1FFFFFL) << 21)
                    | (iz & 0x1FFFFFL);
        }

        /** Open-addressed long -> long map, so the per-vertex path never boxes. */
        private static final class LongLongCache {
            private long[] keys = new long[1 << 14];
            private long[] values = new long[1 << 14];
            private boolean[] used = new boolean[1 << 14];
            private int size;
            private int mask = this.keys.length - 1;

            long get(long key) {
                int i = hash(key) & this.mask;
                while (this.used[i]) {
                    if (this.keys[i] == key) {
                        return this.values[i];
                    }
                    i = (i + 1) & this.mask;
                }
                return 0L;
            }

            void put(long key, long value) {
                if (this.size * 2 >= this.keys.length) {
                    grow();
                }
                int i = hash(key) & this.mask;
                while (this.used[i]) {
                    if (this.keys[i] == key) {
                        this.values[i] = value;
                        return;
                    }
                    i = (i + 1) & this.mask;
                }
                this.used[i] = true;
                this.keys[i] = key;
                this.values[i] = value;
                this.size++;
            }

            private void grow() {
                long[] oldKeys = this.keys;
                long[] oldValues = this.values;
                boolean[] oldUsed = this.used;
                int capacity = oldKeys.length << 1;
                this.keys = new long[capacity];
                this.values = new long[capacity];
                this.used = new boolean[capacity];
                this.mask = capacity - 1;
                this.size = 0;
                for (int i = 0; i < oldKeys.length; i++) {
                    if (oldUsed[i]) {
                        put(oldKeys[i], oldValues[i]);
                    }
                }
            }

            private static int hash(long value) {
                value ^= value >>> 33;
                value *= 0xff51afd7ed558ccdL;
                value ^= value >>> 33;
                value *= 0xc4ceb9fe1a85ec53L;
                value ^= value >>> 33;
                return (int) value;
            }
        }

        private static long key(int x, int y, int z) {
            return ((long) (x & 0x1fffff) << 42)
                    | ((long) (y & 0x1fffff) << 21)
                    | (z & 0x1fffff);
        }

        private static int cell(double value) {
            return (int) Math.floor(value / CELL);
        }
    }
}
