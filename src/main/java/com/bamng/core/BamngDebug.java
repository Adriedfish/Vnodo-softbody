package com.bamng.core;

/**
 * F1/F2: five-level debug funnel and a plain-text overlay source.
 *
 * <p>Level 0 is off, 1 errors, 2 contacts, 3 damage, 4 full per-body state.
 * The renderer is deliberately separate: this class only decides what is
 * worth showing, so a headless test can still exercise the funnel.</p>
 */
public final class BamngDebug {
    public static final int OFF = 0;
    public static final int ERRORS = 1;
    public static final int CONTACTS = 2;
    public static final int DAMAGE = 3;
    public static final int FULL = 4;

    public static int level = OFF;

    private BamngDebug() {
    }

    public static boolean enabled(int wanted) {
        return level >= wanted;
    }

    public static String overlay(SoftBodyWorld world) {
        if (level == OFF || world == null) {
            return "";
        }
        StringBuilder text = new StringBuilder(160);
        if (enabled(ERRORS)) {
            text.append("err nan=").append(world.nanEvents)
                    .append(" isolated=").append(world.isolated).append('\n');
        }
        if (enabled(CONTACTS)) {
            text.append(String.format(java.util.Locale.ROOT,
                    "contacts=%d tri=%d sleep=%d pen=%.3f%n",
                    world.lastContacts, world.lastTriangleContacts,
                    world.sleepingTriangles, world.maxPenetration));
        }
        if (enabled(DAMAGE)) {
            text.append("brokenBeams=").append(world.damage.brokenBeamCount())
                    .append(" brokenTris=").append(world.damage.brokenTriangleCount())
                    .append(" groups=").append(world.damage.damagedGroups()).append('\n');
        }
        if (enabled(FULL)) {
            text.append(String.format(java.util.Locale.ROOT,
                    "body=%d nodes=%d beams=%d tris=%d center=%s vmax=%.3f%n",
                    world.bodyId, world.nodes.size(), world.beams.size(), world.triangles.size(),
                    world.center(), world.maxSpeed()));
        }
        return text.toString();
    }
}
