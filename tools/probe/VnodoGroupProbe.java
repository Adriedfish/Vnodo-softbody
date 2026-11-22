import com.antaurora.vettura.vehicle.VehicleLayout;
import com.antaurora.vettura.vehicle.physics.VehicleSpec;
import com.bamng.core.SoftNode;
import com.bamng.core.VehicleBody;

import java.util.LinkedHashMap;
import java.util.Map;

/** Prints which node groups actually occupy the nose, so the crush panel can be checked. */
public final class VnodoGroupProbe {
    private VnodoGroupProbe() {}

    public static void main(String[] args) {
        VehicleBody body = VehicleBody.create(VehicleLayout.T15, VehicleSpec.T15, 0.82);
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        for (SoftNode node : body.world.nodes) {
            minX = Math.min(minX, node.rest.x);
            maxX = Math.max(maxX, node.rest.x);
        }
        double length = Math.max(1.0, maxX - minX);
        System.out.printf("nodes=%d length=%.3f front=%.3f rear=%.3f%n",
                body.world.nodes.size(), length, maxX, minX);

        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, double[]> extents = new LinkedHashMap<>();
        for (SoftNode node : body.world.nodes) {
            counts.computeIfAbsent(node.group0, k -> new int[1])[0]++;
            double[] ext = extents.computeIfAbsent(node.group0,
                    k -> new double[] {Double.MAX_VALUE, -Double.MAX_VALUE});
            ext[0] = Math.min(ext[0], node.rest.x);
            ext[1] = Math.max(ext[1], node.rest.x);
        }
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            double[] ext = extents.get(e.getKey());
            System.out.printf("group=%-9s count=%5d x=[%.3f..%.3f] fromFront=[%.3f..%.3f]%n",
                    e.getKey(), e.getValue()[0], ext[0], ext[1],
                    maxX - ext[1], maxX - ext[0]);
        }

        int nose = 0;
        int noseFrontGroup = 0;
        for (SoftNode node : body.world.nodes) {
            if (node.rest.x > maxX - 0.30) {
                nose++;
                if ("front".equals(node.group0)) {
                    noseFrontGroup++;
                }
            }
        }
        System.out.printf("nose30cm=%d of which group=front:%d%n", nose, noseFrontGroup);

        int frontGroup = counts.containsKey("front") ? counts.get("front")[0] : 0;
        System.out.printf("frontGroupTotal=%d%n", frontGroup);
    }
}
