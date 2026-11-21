package com.bamng.client;

import com.bamng.BamngMod;

/** Render-side proof: did the skin actually emit vertices during a collision? */
public final class BamngRenderStats {
    private static final boolean ENABLED = Boolean.getBoolean("bamng.renderstats");
    private static long vertices;
    private static long nanos;
    private static long frames;
    private static long deformedFrames;
    private static double maxDelta;

    private BamngRenderStats() {
    }

    static void vertex() {
        if (ENABLED) {
            vertices++;
        }
    }

    public static boolean enabled() {
        return ENABLED;
    }

    static void frame(boolean deformed, long startNanos, double delta) {
        if (!ENABLED) {
            return;
        }
        frames++;
        nanos += System.nanoTime() - startNanos;
        maxDelta = Math.max(maxDelta, delta);
        if (deformed) {
            deformedFrames++;
        }
        if (frames % 60 == 0) {
            BamngMod.LOGGER.info("BAMNG render frames={} deformed={} verts={} avgMs={} maxDelta={}",
                    frames, deformedFrames, vertices,
                    String.format(java.util.Locale.ROOT, "%.2f", nanos / 1.0e6 / frames),
                    String.format(java.util.Locale.ROOT, "%.4f", maxDelta));
            vertices = 0;
            nanos = 0;
            frames = 0;
            deformedFrames = 0;
            maxDelta = 0.0;
        }
    }
}
