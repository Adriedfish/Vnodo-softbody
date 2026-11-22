import com.antaurora.vettura.vehicle.VehicleLayout;
import com.antaurora.vettura.vehicle.physics.VehicleSpec;
import com.bamng.core.SoftNode;
import com.bamng.core.VehicleBody;

/**
 * Headless check for the two cab fixes: the cab band must run on the soft
 * 30 kN/m anchor instead of the 95 kN/m chassis one, and the falling edge of
 * the crash window must bake the current cab offset into plastic so the panel
 * does not spring back out.
 */
public final class VnodoCabProbe {
    private VnodoCabProbe() {}

    public static void main(String[] args) {
        VehicleBody body = VehicleBody.create(VehicleLayout.T15, VehicleSpec.T15, 0.82);
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        for (SoftNode node : body.world.nodes) {
            node.targetX = node.position.x;
            node.targetY = node.position.y;
            node.targetZ = node.position.z;
            minX = Math.min(minX, node.rest.x);
            maxX = Math.max(maxX, node.rest.x);
        }
        double length = Math.max(1.0, maxX - minX);
        double cabStart = maxX - length * 0.62;

        int cab = 0;
        int stiff = 0;
        int bed = 0;
        SoftNode cabNode = null;
        for (SoftNode node : body.world.nodes) {
            if (!node.anchored) {
                continue;
            }
            if ("body".equals(node.group0) && node.rest.x > cabStart) {
                cab++;
                if (node.anchorStiffness <= 30_000.0 + 1.0e-6) {
                    stiff++;
                }
                if (cabNode == null || node.rest.y > cabNode.rest.y) {
                    cabNode = node;
                }
            } else if ("bed".equals(node.group0)) {
                bed++;
                if (node.anchorStiffness < 95_000.0 - 1.0e-6) {
                    throw new AssertionError("bed anchor must stay stiff");
                }
            }
        }
        if (cab == 0 || cabNode == null) {
            throw new AssertionError("no cab band nodes found");
        }
        if (stiff != cab) {
            throw new AssertionError("cab band not softened: " + stiff + "/" + cab);
        }

        // A 12 cm push on the observation-window frame, then close the crash
        // window.  bakeAnchoredOffsets must keep ~85 % of it as permanent
        // plastic; without it the offset would be zero and the node would snap
        // back to its authored position.
        double dx = -0.10;
        double dy = -0.04;
        cabNode.position.x += dx;
        cabNode.position.y += dy;
        body.world.bakeAnchoredOffsets(0.85);
        double px = cabNode.plasticX;
        double py = cabNode.plasticY;
        double kept = Math.sqrt(px * px + py * py);
        double applied = Math.sqrt(dx * dx + dy * dy);
        if (kept < applied * 0.70 || kept > applied * 0.95) {
            throw new AssertionError("bake kept " + kept + " of " + applied);
        }
        double depth = body.world.plasticDepth();
        if (!(depth > 0.05)) {
            throw new AssertionError("plastic depth too small: " + depth);
        }
        System.out.printf("CAB PROBE OK cab=%d bed=%d stiffness=%.0f bake=%.3f depth=%.3f%n",
                cab, bed, cabNode.anchorStiffness, kept, depth);
    }
}
