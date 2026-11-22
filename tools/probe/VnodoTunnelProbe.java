import com.bamng.core.SoftBodyWorld;
import com.bamng.core.SoftNode;

/**
 * A node moving faster than the wall is thick must stop on the wall face.
 * Without the per-substep sweep it steps clean over a one-block wall and the
 * nose ends up inside the wall base with the engine under the road.
 */
public final class VnodoTunnelProbe {
    private VnodoTunnelProbe() {}

    public static void main(String[] args) {
        for (double speed : new double[] { 28.0, 60.0 }) {
            SoftBodyWorld world = new SoftBodyWorld();
            world.gravityScale = 0.0;
            world.damageUpdateEnabled = false;
            world.addCollisionBox(1.00, 0.0, -1.5, 1.10, 4.0, 1.5);
            SoftNode node = new SoftNode(0);
            node.mass = 1.0;
            node.inverseMass = 1.0;
            node.position.set(0.80, 1.00, 0.00);
            node.previous.set(node.position);
            node.velocity.set(speed, 0.0, 0.0);
            world.addNode(node);
            world.step(0.05, 6);
            double x = node.position.x;
            double limit = 1.10 + node.radius + 0.02;
            if (x > limit) {
                throw new AssertionError("tunnelled at " + speed + " m/s: x=" + x);
            }
            if (x < 0.80) {
                throw new AssertionError("over-corrected: x=" + x);
            }
            if (world.lastStepBoxContacts <= 0) {
                throw new AssertionError("wall never registered a contact at "
                        + speed + " m/s: x=" + x);
            }
            System.out.printf("TUNNEL OK speed=%.0f x=%.3f radius=%.3f contacts=%d%n",
                    speed, x, node.radius, world.lastStepBoxContacts);
        }
    }
}
