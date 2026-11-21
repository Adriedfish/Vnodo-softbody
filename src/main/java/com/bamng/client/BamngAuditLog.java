package com.bamng.client;

import com.bamng.BamngMod;
import com.bamng.core.SoftBeam;
import com.bamng.core.SoftNode;
import com.bamng.core.VehicleBody;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Full-fidelity audit log for BAMNG.  Writes one JSON object per sampled tick
 * and one line per event.  Impact events also capture the current frame into
 * logs/bamng-captures/, so a crash can be inspected without reproducing it.
 */
public final class BamngAuditLog {
    private static final boolean ENABLED = Boolean.getBoolean("bamng.audit");
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static PrintWriter writer;
    private static File gameDir;
    private static long lastCaptureMillis;
    private static int captureCount;
    private static int lastBrokenBeams = -1;
    private static int lastBrokenTriangles = -1;
    /** Captures are armed only after a physical wall hit; terrain noise stays out. */
    private static boolean wallCaptureArmed;
    private static long wallCaptureUntil;

    private BamngAuditLog() {
    }

    public static synchronized void tick(int tick, Object truck, VehicleBody body,
                                         double speed, double truckX, double truckY,
                                         double truckZ, float yaw, int substeps) {
        if (!ENABLED || body == null || body.world == null) {
            return;
        }
        if ((tick % 5) != 0) {
            return;
        }
        var world = body.world;
        var nodes = world.nodeArray();
        List<SoftBeam> beams = world.beams;
        int brokenBeams = world.damage.brokenBeamCount();
        int brokenTriangles = world.damage.brokenTriangleCount();

        StringBuilder json = new StringBuilder(1024);
        json.append("{\"type\":\"tick\",\"t\":").append(tick);
        json.append(",\"truck\":[").append(f(truckX)).append(',').append(f(truckY))
                .append(',').append(f(truckZ)).append(',').append(f(yaw)).append(']');
        json.append(",\"speed\":").append(f(speed));
        json.append(",\"world\":{\"center\":[")
                .append(f(world.center().x)).append(',').append(f(world.center().y))
                .append(',').append(f(world.center().z)).append(']');
        json.append(",\"minY\":").append(f(world.minY()));
        json.append(",\"maxSpeed\":").append(f(world.maxSpeed()));
        json.append(",\"contacts\":").append(world.lastContacts);
        json.append(",\"triContacts\":").append(world.lastTriangleContacts);
        json.append(",\"sleepTris\":").append(world.sleepingTriangles);
        json.append(",\"pen\":").append(f(world.maxPenetration));
        json.append(",\"stepMs\":").append(f(world.lastStepNanos / 1_000_000.0));
        json.append(",\"substeps\":").append(substeps);
        json.append(",\"isolated\":").append(world.isolated);
        json.append(",\"nodes\":").append(nodes.length);
        json.append(",\"beams\":").append(beams.size());
        json.append(",\"triangles\":").append(world.triangles.size());
        json.append("},\"damage\":{\"brokenBeams\":").append(brokenBeams)
                .append(",\"brokenTriangles\":").append(brokenTriangles)
                .append(",\"groups\":").append(quoteList(world.damage.damagedGroups()))
                .append('}');

        // Top five loaded beams: the earliest place a crash becomes visible in
        // numbers, before the renderer or the rigid Vettura model reacts.
        List<SoftBeam> worst = new ArrayList<>(beams);
        worst.sort(Comparator.comparingDouble((SoftBeam b) -> -Math.abs(b.lastForce)));
        json.append(",\"worstBeams\":[");
        for (int i = 0; i < Math.min(5, worst.size()); i++) {
            SoftBeam b = worst.get(i);
            if (i > 0) json.append(',');
            json.append("{\"a\":").append(b.a).append(",\"b\":").append(b.b)
                    .append(",\"f\":").append(f(b.lastForce))
                    .append(",\"rest\":").append(f(b.restLength))
                    .append(",\"strength\":").append(f(b.beamStrength))
                    .append(",\"broken\":").append(b.broken).append('}');
        }
        json.append(']');

        List<SoftNode> deepest = new ArrayList<>(List.of(nodes));
        deepest.sort(Comparator.comparingDouble(n -> n.position.y));
        json.append(",\"lowestNodes\":[");
        for (int i = 0; i < Math.min(5, deepest.size()); i++) {
            SoftNode n = deepest.get(i);
            if (i > 0) json.append(',');
            json.append("{\"i\":").append(n.index).append(",\"p\":[")
                    .append(f(n.position.x)).append(',').append(f(n.position.y))
                    .append(',').append(f(n.position.z)).append(']')
                    .append(",\"v\":").append(f(n.velocity.length()))
                    .append(",\"part\":").append(quote(n.part)).append('}');
        }
        json.append("]}");
        write(json.toString());

        if (brokenBeams != lastBrokenBeams || brokenTriangles != lastBrokenTriangles) {
            boolean first = lastBrokenBeams < 0;
            lastBrokenBeams = brokenBeams;
            lastBrokenTriangles = brokenTriangles;
            if (!first) {
                event("damage", "\"brokenBeams\":" + brokenBeams
                        + ",\"brokenTriangles\":" + brokenTriangles
                        + ",\"groups\":" + quoteList(world.damage.damagedGroups()));
                if (wallCaptureWindow()) {
                    capture("damage-b" + brokenBeams + "-t" + brokenTriangles);
                }
            }
        }
    }

    /** A detected impact.  Writes the cause plus an immediate frame capture. */
    public static synchronized void impact(String source, double x, double y, double z,
                                           double speed, double prevSpeed, double drop) {
        wallCaptureArmed = true;
        wallCaptureUntil = System.currentTimeMillis() + 60_000L;
        event("impact", "\"source\":" + quote(source) + ",\"at\":[" + f(x) + ',' + f(y)
                + ',' + f(z) + "],\"speed\":" + f(speed) + ",\"prevSpeed\":"
                + f(prevSpeed) + ",\"drop\":" + f(drop));
        capture("impact-" + source);
    }

    private static boolean wallCaptureWindow() {
        return wallCaptureArmed && System.currentTimeMillis() <= wallCaptureUntil;
    }

    public static synchronized void event(String type, String fields) {
        write("{\"type\":" + quote(type) + ",\"wall\":"
                + quote(LocalDateTime.now().format(STAMP)) + ',' + fields + '}');
    }

    /**
     * Save the current frame off the render thread's next natural screenshot
     * path, then copy it into logs/bamng-captures.  Cooldown prevents a crash
     * from filling the disk with hundreds of near-identical frames.
     */
    public static synchronized void capture(String reason) {
        long now = System.currentTimeMillis();
        if (now - lastCaptureMillis < 1500L) {
            return;
        }
        lastCaptureMillis = now;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gameDirectory == null) {
            return;
        }
        gameDir = mc.gameDirectory;
        RenderTarget target = mc.getMainRenderTarget();
        final String safeReason = reason.replaceAll("[^a-zA-Z0-9._-]", "_");
        Screenshot.grab(gameDir, target, component -> {
            try {
                File shots = new File(gameDir, "screenshots");
                File newest = newestFile(shots);
                if (newest == null) {
                    event("capture-miss", "\"reason\":" + quote(safeReason));
                    return;
                }
                File dir = new File(gameDir, "logs/bamng-captures");
                if (!dir.exists() && !dir.mkdirs()) {
                    return;
                }
                captureCount++;
                String name = String.format(Locale.ROOT, "%s-%03d-%s.png",
                        LocalDateTime.now().format(STAMP), captureCount, safeReason);
                Path copy = new File(dir, name).toPath();
                Files.copy(newest.toPath(), copy, StandardCopyOption.REPLACE_EXISTING);
                event("capture", "\"reason\":" + quote(safeReason)
                        + ",\"path\":" + quote(copy.toString())
                        + ",\"bytes\":" + Files.size(copy));
                BamngMod.LOGGER.info("BAMNG capture {} -> {}", safeReason, copy);
            } catch (Exception exception) {
                BamngMod.LOGGER.warn("BAMNG capture failed for {}", safeReason, exception);
            }
        });
    }

    private static File newestFile(File dir) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".png"));
        if (files == null || files.length == 0) {
            return null;
        }
        File newest = files[0];
        for (File file : files) {
            if (file.lastModified() > newest.lastModified()) {
                newest = file;
            }
        }
        return newest;
    }

    private static void write(String line) {
        try {
            if (writer == null) {
                Minecraft mc = Minecraft.getInstance();
                gameDir = mc == null || mc.gameDirectory == null
                        ? new File(".") : mc.gameDirectory;
                File dir = new File(gameDir, "logs");
                if (!dir.exists() && !dir.mkdirs()) {
                    return;
                }
                writer = new PrintWriter(new FileWriter(new File(dir, "bamng-audit.jsonl"), true));
            }
            writer.println(line);
            writer.flush();
        } catch (Exception exception) {
            BamngMod.LOGGER.warn("BAMNG audit write failed", exception);
        }
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static String quoteList(Iterable<String> values) {
        StringBuilder out = new StringBuilder("[");
        boolean first = true;
        for (String value : values) {
            if (!first) out.append(',');
            first = false;
            out.append(quote(value));
        }
        return out.append(']').toString();
    }

    private static String f(double value) {
        if (!Double.isFinite(value)) {
            return "null";
        }
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
