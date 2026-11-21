package com.bamng.core;

/** A single wheel modelled as a vertical spring/damper at a chassis node. */
public final class WheelSuspension {
    public final SoftBodyWorld world;
    public final int nodeIndex;
    public final double localX;
    public final double localZ;
    public final double radius;
    private final int[] group;
    private final double[] weights;

    public double restLength = 0.46;
    public double spring = 80000.0;
    public double damper = 8000.0;
    public double maxForce = 180000.0;
    public double driveForce = 5200.0;
    public double brakeForce = 7000.0;
    public boolean front;
    public boolean rear;

    public WheelSuspension(SoftBodyWorld world, int nodeIndex, double localX, double localZ, double radius) {
        this.world = world;
        this.nodeIndex = nodeIndex;
        this.localX = localX;
        this.localZ = localZ;
        this.radius = radius;
        this.front = localX > 0.0;
        this.rear = localX < 0.0;

        SoftNode anchor = world.nodes.get(nodeIndex);
        java.util.List<Integer> found = new java.util.ArrayList<>();
        java.util.List<Double> foundWeights = new java.util.ArrayList<>();
        double radiusSq = 0.62 * 0.62;
        for (SoftNode node : world.nodes) {
            double dx = node.position.x - anchor.position.x;
            double dy = node.position.y - anchor.position.y;
            double dz = node.position.z - anchor.position.z;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 <= radiusSq) {
                found.add(node.index);
                foundWeights.add(1.0 / (0.08 + Math.sqrt(d2)));
            }
        }
        if (found.isEmpty()) {
            found.add(nodeIndex);
            foundWeights.add(1.0);
        }
        double total = 0.0;
        for (double w : foundWeights) {
            total += w;
        }
        this.group = new int[found.size()];
        this.weights = new double[found.size()];
        for (int i = 0; i < found.size(); i++) {
            group[i] = found.get(i);
            weights[i] = foundWeights.get(i) / total;
        }
    }

    public void apply(double throttle, double brake, double steer) {
        // BAMNG's shell is driven by the real Vettura rigid body, so all wheel
        // coefficients are zero.  Skip the group lookup/force writes instead
        // of looping over every attached node for a 0 N contribution.
        if (spring == 0.0 && damper == 0.0 && maxForce == 0.0
                && driveForce == 0.0 && brakeForce == 0.0) {
            return;
        }
        SoftNode anchor = world.nodes.get(nodeIndex);
        // restLength is suspension travel above the wheel contact plane. The
        // chassis attachment sits one wheel radius plus restLength above the
        // road; BAMNG nodes are world-space, so include both offsets.
        double roadY = world.groundY + radius + restLength;
        double compression = roadY - anchor.position.y;

        if (compression > -0.12) {
            double force = spring * compression - damper * anchor.velocity.y;
            force = Math.max(0.0, Math.min(maxForce, force));
            for (int i = 0; i < group.length; i++) {
                world.nodes.get(group[i]).addForce(0.0, force * weights[i], 0.0);
            }
        }

        boolean grounded = anchor.position.y < roadY + 0.18;
        if (!grounded) {
            return;
        }

        double forward = anchor.velocity.x;
        if (throttle > 0.0) {
            for (int i = 0; i < group.length; i++) {
                world.nodes.get(group[i]).addForce(driveForce * throttle * weights[i], 0.0, 0.0);
            }
        }
        if (brake > 0.0 && Math.abs(forward) > 0.02) {
            double f = -Math.signum(forward) * brakeForce * brake;
            for (int i = 0; i < group.length; i++) {
                world.nodes.get(group[i]).addForce(f * weights[i], 0.0, 0.0);
            }
        }
        if (front && Math.abs(steer) > 0.01 && Math.abs(forward) > 0.05) {
            double side = steer * 1800.0 * Math.min(1.0, Math.abs(forward) / 8.0);
            for (int i = 0; i < group.length; i++) {
                world.nodes.get(group[i]).addForce(0.0, 0.0, side * weights[i]);
            }
        }
    }
}
