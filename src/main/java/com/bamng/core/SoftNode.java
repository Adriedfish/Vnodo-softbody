package com.bamng.core;

/**
 * A point mass in the soft-body lattice.
 *
 * <p>Positions are world-space metres (Minecraft Y-up).  The local fields are
 * the node's rest offset in the truck frame, so the client can rebuild the
 * kinematic anchor target after the truck moves or yaws without translating
 * every node by hand.</p>
 */
public final class SoftNode {
    public final int index;
    public final Vec3 position = new Vec3();
    public final Vec3 previous = new Vec3();
    public final Vec3 rest = new Vec3();
    public final Vec3 velocity = new Vec3();

    /** Rest offset in the truck frame (x=forward, y=up, z=lateral). */
    public double localX;
    public double localY;
    public double localZ;

    /** Permanent crush offset accumulated from wall contacts. */
    public double plasticX;
    public double plasticY;
    public double plasticZ;
    /** Whole-vehicle plastic envelope assigned by VehicleBody. */
    public double plasticLimit = Double.MAX_VALUE;

    /**
     * Per-tick plastic budget.  The narrow phase can run twenty to thirty
     * substeps; without this a sustained wall touch kept adding five
     * centimetres on every substep and the nose buried itself in seconds.
     */
    public long plasticTick = Long.MIN_VALUE;
    public double plasticTickAmount;

    /** Set by the wall narrow phase when this node needs a permanent dent pass. */
    public boolean wallDented;
    public double wallImpactSpeed;

    /** Last tick a foreign-body impact was converted into permanent damage. */
    public long foreignImpactTick = Long.MIN_VALUE;

    /** Current anchor target in world space. */
    public double targetX;
    public double targetY;
    public double targetZ;

    public double mass = 1.0;
    public double inverseMass = 1.0;
    public double radius = 0.04;
    public double friction = 0.62;
    public double restitution = 0.02;
    public boolean sleeping;
    public boolean pinned;
    public boolean fixed;

    /** First collision group, matching BeamNG's "only the first group" rule. */
    public String group0 = "body";
    public boolean collision = true;
    public boolean selfCollision = false;
    public boolean staticCollision = true;

    /** Chassis anchor.  Only the core body nodes are anchored; the crumple
     *  zone stays connected through beams and is free to deform. */
    public boolean anchored;
    public double anchorStiffness = 120_000.0;
    public double anchorDamping = 700.0;

    /** Logical vehicle part this node belongs to (cab, bed, tailgate...). */
    public String part = "body";

    // Accumulated force for the current substep.
    public double forceX;
    public double forceY;
    public double forceZ;

    public SoftNode(int index) {
        this.index = index;
    }

    public void zeroForce() {
        forceX = 0.0;
        forceY = 0.0;
        forceZ = 0.0;
    }

    public void addForce(double x, double y, double z) {
        forceX += x;
        forceY += y;
        forceZ += z;
    }

    public void setMass(double mass) {
        this.mass = Math.max(1.0e-6, mass);
        this.inverseMass = (pinned || fixed) ? 0.0 : 1.0 / this.mass;
    }

    public void pin() {
        pinned = true;
        inverseMass = 0.0;
        velocity.set(0.0, 0.0, 0.0);
    }

    public void setFixed(boolean value) {
        fixed = value;
        inverseMass = (pinned || fixed) ? 0.0 : 1.0 / mass;
        if (fixed) {
            velocity.set(0.0, 0.0, 0.0);
        }
    }

    /** Can this node take part in dynamic / self collision? */
    public boolean dynamicCollision() {
        return collision && !fixed;
    }

    /** Static collision is independent of {@link #collision}; fixed nodes can
     *  still collide with static geometry, matching the BeamNG switch matrix. */
    public boolean staticCollisionEnabled() {
        return staticCollision;
    }

    /** Dynamic collision switch (self/external), independent of static. */
    public boolean externalCollisionEnabled() {
        return collision && !fixed;
    }

    public double distanceSquaredTo(double x, double y, double z) {
        double dx = position.x - x;
        double dy = position.y - y;
        double dz = position.z - z;
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    public String toString() {
        return "node[" + index + "] " + position;
    }
}
