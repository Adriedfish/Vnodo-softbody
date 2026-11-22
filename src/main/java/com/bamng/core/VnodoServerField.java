package com.bamng.core;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side mirror of the client Vnodo crush field.
 *
 * <p>Vettura's collision boxes and hull are queried on the server, where the
 * client {@code SoftRuntime} does not exist.  Without this mirror the client
 * renders a deformed shell while the server keeps using Vettura's old crush
 * field, so the visible collision box appears detached from the body.</p>
 *
 * <p>Values arrive as quantized shorts, which keeps a 2856-cell field around
 * 5.7 KB per packet.  The map is keyed by entity UUID because the client and
 * server entity instances are different objects.</p>
 */
public final class VnodoServerField {
    private static final Map<UUID, Entry> FIELDS = new ConcurrentHashMap<>();

    private VnodoServerField() {
    }

    public static void put(UUID uuid, int version, float[] field) {
        put(uuid, version, field, 0);
    }

    /**
     * Store a field only when its shape matches the receiving truck's crush
     * frame.  The expected length is supplied by the network handler from the
     * server-side VehicleDamage frame; zero means "unknown, keep legacy
     * behaviour" for direct probe/tests.
     */
    public static void put(UUID uuid, int version, float[] field, int expectedLength) {
        if (uuid == null) {
            return;
        }
        if (field == null || field.length == 0) {
            FIELDS.remove(uuid);
            return;
        }
        // Vnodo's field namespace starts at 1,000,003 so it can never alias
        // Vettura's own low crushVersion values.  A non-empty field outside
        // that namespace is not one of ours.
        if (version < 1_000_000) {
            return;
        }
        if (expectedLength > 0 && field.length != expectedLength) {
            return;
        }
        for (float value : field) {
            if (!Float.isFinite(value)) {
                return;
            }
        }
        FIELDS.put(uuid, new Entry(version, field.clone()));
    }

    public static void clear(UUID uuid) {
        if (uuid != null) {
            FIELDS.remove(uuid);
        }
    }

    public static boolean has(UUID uuid) {
        return uuid != null && FIELDS.containsKey(uuid);
    }

    public static float[] field(UUID uuid) {
        Entry entry = uuid == null ? null : FIELDS.get(uuid);
        return entry == null ? null : entry.field;
    }

    public static int version(UUID uuid) {
        Entry entry = uuid == null ? null : FIELDS.get(uuid);
        return entry == null ? 0 : entry.version;
    }

    private record Entry(int version, float[] field) {
    }
}
