package com.bamng.core;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;

/** Mid-impact snapshot for the visual crash figure. */
public final class BamngCrashFigureTest {
    public static void main(String[] args) throws IOException {
        T15Body body = T15Body.create(0.82);
        SoftBodyWorld world = body.world;
        world.gravityScale = 0.0;
        world.groundY = -100.0;
        world.addCollisionBox(3.0, 0.0, -3.0, 3.6, 3.2, 3.0);
        for (SoftNode node : world.nodes) {
            node.anchored = false;
            node.velocity.set(28.0, 0.0, 0.0);
        }
        Path output = Path.of(System.getProperty("java.io.tmpdir"), "bamng-crash-figure");
        write(output.resolve("t15_fig_before.csv"), world);
        for (int i = 0; i < 9; i++) {
            world.step(0.05, 60);
        }
        write(output.resolve("t15_fig_after.csv"), world);
        System.out.println("FIGURE OK broken=" + world.damage.brokenBeamCount() + " output=" + output);
    }

    private static void write(Path path, SoftBodyWorld world) throws IOException {
        java.nio.file.Files.createDirectories(path.getParent());
        try (BufferedWriter out = new BufferedWriter(new FileWriter(path.toFile()))) {
            out.write("kind,a,b,x,y,z\n");
            for (SoftNode node : world.nodes) {
                out.write(String.format("N,%d,%d,%.4f,%.4f,%.4f%n",
                        node.index, node.index, node.position.x, node.position.y, node.position.z));
            }
            for (SoftBeam beam : world.beams) {
                if (!beam.broken) {
                    SoftNode a = world.nodes.get(beam.a);
                    SoftNode b = world.nodes.get(beam.b);
                    out.write(String.format("B,%d,%d,%.4f,%.4f,%.4f%n",
                            beam.a, beam.b, a.position.x, a.position.y, a.position.z));
                    out.write(String.format("B,%d,%d,%.4f,%.4f,%.4f%n",
                            beam.a, beam.b, b.position.x, b.position.y, b.position.z));
                }
            }
        }
    }
}
