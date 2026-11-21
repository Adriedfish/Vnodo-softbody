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
        if (uuid == null || field == null || field.length == 0) {
            FIELDS.remove(uuid);
            return;
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
