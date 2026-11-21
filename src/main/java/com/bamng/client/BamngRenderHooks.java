package com.bamng.client;

/** Per-render-call context shared by the Vettura mesh redirect and BAMNG buffer. */
public final class BamngRenderHooks {
    private static final ThreadLocal<String> GEOMETRY = new ThreadLocal<>();
    /** Current Vextris bone during a CPU draw. */
    private static final ThreadLocal<String> BONE = new ThreadLocal<>();
    // Vextris meshes are authored in bone-pivot-local blocks.  The bone pivot
    // is the translation drawParts()/cached() apply before rendering a bone;
    // the skin buffer needs it to turn the inverse-posed, bone-local vertex
    // back into full model space (otherwise every hood/tailgate vertex binds
    // to the wrong node and the fold lands on the tailgate).
    //
    // Rendering can be re-entered by another vehicle or a preview render.
    // Keeping this static let one truck's hood pivot leak into another
    // truck's tailgate; the values are now thread-local like GEOMETRY.
    private static final ThreadLocal<float[]> PIVOT = ThreadLocal.withInitial(() -> new float[3]);
    private static final ThreadLocal<Integer> PIVOT_GENERATION = ThreadLocal.withInitial(() -> 0);
    /** True only while the main pickup_t15 body needs the CPU tailgate path. */
    private static final ThreadLocal<Boolean> MAIN_SKIN = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private BamngRenderHooks() {
    }

    public static void beginRender() {
        GEOMETRY.remove();
        BONE.remove();
        PIVOT.get()[0] = 0.0F;
        PIVOT.get()[1] = 0.0F;
        PIVOT.get()[2] = 0.0F;
    }

    public static void bone(String name) {
        if (name == null) {
            BONE.remove();
        } else {
            BONE.set(name);
        }
    }

    public static String bone() {
        return BONE.get();
    }

    /**
     * Doors, glass, the interior and the authored wheels are deliberately
     * left rigid.  The old pivot-distance guard could not tell a bed side
     * (pivot near the outer skin) from a door, so it silently left the rear
     * quarter/fender and wheel arch unskinned.  Bone names are the reliable
     * discriminator.
     */
    public static boolean rigidBone() {
        String bone = BONE.get();
        if (bone == null) {
            return false;
        }
        String name = bone.toLowerCase(java.util.Locale.ROOT);
        if (name.contains("spare") || name.contains("steer")) {
            // The spare tire/hoist must follow the bed lattice; only the four
            // road wheels keep the rigid Vettura transform.  The steering
            // wheel is interior trim, not a road wheel.
            return false;
        }
        return name.contains("wheel") || name.contains("tire") || name.contains("rim");
    }

    public static void bonePivot(float x, float y, float z) {
        float[] pivot = PIVOT.get();
        pivot[0] = x;
        pivot[1] = y;
        pivot[2] = z;
        PIVOT_GENERATION.set(PIVOT_GENERATION.get() + 1);
    }

    public static int bonePivotGeneration() {
        return PIVOT_GENERATION.get();
    }

    public static float bonePivotX() {
        return PIVOT.get()[0];
    }

    public static float bonePivotY() {
        return PIVOT.get()[1];
    }

    public static float bonePivotZ() {
        return PIVOT.get()[2];
    }

    public static void geometry(String path) {
        if (path == null || path.isBlank()) {
            GEOMETRY.remove();
            return;
        }
        GEOMETRY.set(path);
    }

    public static String geometry() {
        return GEOMETRY.get();
    }

    private static final ThreadLocal<Boolean> FRONT_SHELL_REAR_ONLY =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    public static void frontShellRearOnly(boolean active) {
        FRONT_SHELL_REAR_ONLY.set(active);
    }

    public static boolean shouldSkinBone(String bone) {
        if (bone != null && bone.toLowerCase(java.util.Locale.ROOT).contains("spare")) {
            // The spare tyre/hoist is physically part of the main pickup mesh
            // on the T15 (there is no separate Vettura spare entity).  Force
            // it onto the skin path whenever a Vnodo shell is active; the
            // bed lattice will bind it to the nearest rear node.
            return true;
        }
        if (!shouldSkin()) {
            return false;
        }
        String path = GEOMETRY.get();
        if (Boolean.TRUE.equals(FRONT_SHELL_REAR_ONLY.get()) && path != null
                && path.toLowerCase(java.util.Locale.ROOT).contains("pickup_t15_front")) {
            // In a rear-only crash the front shell contributes only the
            // tailgate to the rear fold.  Keeping the other front bones on
            // the GPU cache avoids processing ~25.7k untouched vertices on
            // every frame while the bed/chassis still deform.
            return "tailgate".equalsIgnoreCase(bone);
        }
        return true;
    }

    /**
     * Declares whether the main pickup_t15 body is currently on the CPU skin
     * path.  Front-only crashes leave the main shell on the GPU cache; reverse
     * crashes need it for the tailgate fold.
     */
    public static void mainSkin(boolean active) {
        MAIN_SKIN.set(active);
    }

    /** True while the BAMNG-owned truck is on the CPU skin path. */
    public static boolean mainSkinActive() {
        return Boolean.TRUE.equals(MAIN_SKIN.get());
    }

    public static boolean shouldSkin() {
        String path = GEOMETRY.get();
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("lod2") || lower.contains("key")) {
            return false;
        }
        if (lower.contains("spare") || lower.contains("front") || lower.contains("bumper")
                || lower.contains("hood") || lower.contains("chassis")
                || lower.contains("engine") || lower.contains("door") || lower.contains("glass")
                || lower.contains("interior") || lower.contains("seat") || lower.contains("cab")
                || lower.contains("belt") || lower.contains("strap") || lower.contains("harness")
                || lower.contains("wheel") || lower.contains("tire") || lower.contains("rim")) {
            return true;
        }
        if (lower.contains("pickup_t15")) {
            return MAIN_SKIN.get();
        }
        return false;
    }

    public static void endRender() {
        GEOMETRY.remove();
        MAIN_SKIN.remove();
        FRONT_SHELL_REAR_ONLY.remove();
        PIVOT.remove();
        PIVOT_GENERATION.remove();
    }
}
