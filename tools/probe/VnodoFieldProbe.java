import com.antaurora.vettura.vehicle.VehicleLayout;
import com.antaurora.vettura.vehicle.physics.VehicleSpec;
import com.bamng.core.SoftBeam;
import com.bamng.core.SoftNode;
import com.bamng.core.VehicleBody;
import com.bamng.core.VnodoCollisionField;
import com.bamng.core.VnodoConfig;

import java.nio.file.Files;
import java.nio.file.Path;

public class VnodoFieldProbe {
    public static void main(String[] args) throws Exception {
        VnodoConfig.setConfigPath(Files.createTempFile("vnodo-probe", ".properties"));
        VnodoConfig.reset();

        VehicleBody body = VehicleBody.create(VehicleLayout.T15, VehicleSpec.T15, 0.82);
        VnodoCollisionField field = new VnodoCollisionField(body, VehicleLayout.T15);
        System.out.printf("body nodes=%d beams=%d%n", body.world.nodes.size(), body.world.beams.size());
        System.out.printf("frame nodes=%d cells=%d field=%d%n",
                field.frameCount(), field.cellCount(), field.field().length);

        // Lay the shell at the origin, yaw 0, with target = untouched rest pose.
        for (SoftNode node : body.world.nodes) {
            boolean tire = node.part != null && node.part.startsWith("tire");
            double restY = node.rest.y - (tire ? 0.0 : body.baseY);
            node.targetX = -node.rest.z;
            node.targetY = restY;
            node.targetZ = node.rest.x;
            node.plasticX = 0.0;
            node.plasticY = 0.0;
            node.plasticZ = 0.0;
        }
        field.update(0.0, 0.0, 0.0, 0.0f);
        System.out.printf("undeformed max=%s version=%d%n",
                max(field.field()), field.version());

        SoftNode front = null;
        for (SoftNode node : body.world.nodes) {
            if ("front_bumper".equals(node.part)) {
                if (front == null || Math.abs(node.rest.z) < Math.abs(front.rest.z)) {
                    front = node;
                }
            }
        }
        if (front == null) {
            throw new IllegalStateException("no front bumper node");
        }
        // At yaw 0 the authored +X nose points at world +Z.  A rearward
        // 25 cm crush is therefore a negative world-Z plastic offset.
        front.plasticZ = -0.25;
        field.update(0.0, 0.0, 0.0, 0.0f);
        double max = max(field.field());
        System.out.printf("front node rest=(%.3f,%.3f,%.3f) plasticZ=%.3f max=%s version=%d deformed=%s%n",
                front.rest.x, front.rest.y, front.rest.z, front.plasticZ,
                max, field.version(), field.hasDeformation());
        if (max < 0.05) {
            throw new IllegalStateException("field did not pick up front crush");
        }

        // Check that the field is not a copy of the per-frame node array.
        int nonZero = 0;
        for (int i = 0; i < field.field().length; i++) {
            if (Math.abs(field.field()[i]) > 1.0e-5) {
                nonZero++;
                int cell = i / 3;
                int fi = cell / (field.frame().nr * field.frame().ny);
                int rem = cell % (field.frame().nr * field.frame().ny);
                int ri = rem / field.frame().ny;
                int yi = rem % field.frame().ny;
                System.out.printf("nonzero cell=%d f=%.3f r=%.3f y=%.3f value=%.4f node=%d%n",
                        cell,
                        field.frame().f0 + fi * field.frame().spacing,
                        field.frame().r0 + ri * field.frame().spacing,
                        field.frame().y0 + yi * field.frame().spacing,
                        field.field()[i], field.frame().cell[cell]);
            }
        }
        System.out.printf("non-zero field components=%d%n", nonZero);

        float[] moved = new float[3];
        for (SoftNode node : body.world.nodes) {
            if ("front_bumper".equals(node.part)) {
                node.plasticZ = -0.20;
            }
        }
        field.update(0.0, 0.0, 0.0, 0.0f);
        int broadNonZero = 0;
        for (float value : field.field()) {
            if (Math.abs(value) > 1.0e-5) {
                broadNonZero++;
            }
        }
        System.out.printf("broad front crush max=%s nonzero=%d version=%d%n",
                max(field.field()), broadNonZero, field.version());
        field.frame().move(field.field(), 2.40, -0.88, 0.04, moved);
        System.out.printf("broad corner move dx=%.4f dy=%.4f dz=%.4f%n",
                moved[0], moved[1], moved[2]);
        if (Math.abs(moved[0]) < 0.01) {
            throw new IllegalStateException("front corner collision did not follow broad crush");
        }

        // Yaw invariance: the Vnodo field must rotate with the truck.  A
        // rearward 20 cm front crush is expressed in world space, then
        // transformed back into Vettura's f/r/y frame.
        for (float yaw : new float[]{0.0F, 45.0F, 90.0F, 180.0F, 270.0F}) {
            layRest(body, 0.0, 0.0, 0.0, yaw);
            double modelYaw = Math.toRadians(180.0 - yaw);
            double rearX = Math.sin(modelYaw) * 0.20;
            double rearZ = Math.cos(modelYaw) * 0.20;
            for (SoftNode node : body.world.nodes) {
                node.plasticX = 0.0;
                node.plasticY = 0.0;
                node.plasticZ = 0.0;
                if ("front_bumper".equals(node.part)) {
                    node.plasticX = rearX;
                    node.plasticZ = rearZ;
                }
            }
            field.update(0.0, 0.0, 0.0, yaw);
            double yawMax = max(field.field());
            field.frame().move(field.field(), 2.40, -0.88, 0.04, moved);
            System.out.printf("yaw=%.1f fieldMax=%.4f cornerDx=%.4f version=%d%n",
                    yaw, yawMax, moved[0], field.version());
            if (yawMax < 0.15 || Math.abs(moved[0]) < 0.10) {
                throw new IllegalStateException("yaw field lost crush at " + yaw);
            }
        }

        for (double mf : new double[]{2.40, 2.25, 2.10, 1.95}) {
            for (double mr : new double[]{-0.88, -0.60, -0.30, 0.0, 0.30, 0.60, 0.88}) {
                field.frame().move(field.field(), mf, mr, 0.04, moved);
                System.out.printf("move f=%.2f r=%.2f -> dx=%.4f dy=%.4f dz=%.4f%n",
                        mf, mr, moved[0], moved[1], moved[2]);
            }
        }
        field.frame().move(field.field(), 2.25, 0.0, 0.04, moved);
        if (Math.abs(moved[0]) + Math.abs(moved[1]) + Math.abs(moved[2]) < 0.01) {
            throw new IllegalStateException("frame.move did not consume the field");
        }

        // Region tuning: baseline, soft, hard, live edit.
        VnodoConfig.applyPreset("default");
        body.applyTuning();
        checkRegion(body, "front_bumper", 0.80);
        VnodoConfig.applyPreset("soft");
        body.applyTuning();
        checkRegion(body, "front_bumper", 0.55);
        checkRegion(body, "hood", 0.35);
        VnodoConfig.applyPreset("hard");
        body.applyTuning();
        checkRegion(body, "front_bumper", 1.10);
        VnodoConfig.setValue("front_bumper", 0.42);
        body.applyTuning();
        checkRegion(body, "front_bumper", 0.42);
        System.out.println("VNODO FIELD PROBE OK");
    }

    private static void layRest(VehicleBody body, double tx, double ty, double tz, float yaw) {
        double modelYaw = Math.toRadians(180.0 - yaw);
        double cos = Math.cos(modelYaw);
        double sin = Math.sin(modelYaw);
        for (SoftNode node : body.world.nodes) {
            boolean tire = node.part != null && node.part.startsWith("tire");
            double bodyY = node.rest.y - (tire ? 0.0 : body.baseY);
            double bodyX = node.rest.z;
            double bodyZ = -node.rest.x;
            node.targetX = tx + bodyX * cos + bodyZ * sin;
            node.targetY = ty + bodyY;
            node.targetZ = tz - bodyX * sin + bodyZ * cos;
            node.plasticX = 0.0;
            node.plasticY = 0.0;
            node.plasticZ = 0.0;
        }
    }

    private static void checkRegion(VehicleBody body, String region, double expected) {
        double minScale = Double.MAX_VALUE;
        double maxScale = -Double.MAX_VALUE;
        for (SoftBeam beam : body.world.beams) {
            if (region.equals(beam.region)) {
                minScale = Math.min(minScale, beam.crashSpringScale);
                maxScale = Math.max(maxScale, beam.crashSpringScale);
                double expectedDeform = beam.baseDeform * expected;
                if (Math.abs(beam.beamDeform - expectedDeform) > 0.02) {
                    throw new IllegalStateException("region " + region + " deform mismatch: "
                            + beam.beamDeform + " vs " + expectedDeform);
                }
            }
        }
        if (minScale == Double.MAX_VALUE) {
            throw new IllegalStateException("no beams for region " + region);
        }
        if (Math.abs(minScale - expected) > 0.01 || Math.abs(maxScale - expected) > 0.01) {
            throw new IllegalStateException("region " + region + " scale=" + minScale + ".." + maxScale
                    + " expected " + expected);
        }
        System.out.printf("region %-12s scale=%.2f ok%n", region, minScale);
    }

    private static double max(float[] values) {
        double max = 0.0;
        for (float value : values) {
            max = Math.max(max, Math.abs(value));
        }
        return max;
    }
}

