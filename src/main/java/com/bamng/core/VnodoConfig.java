package com.bamng.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persistent Vnodo client tuning.
 *
 * <p>The values are crash compliance multipliers for the authoring regions,
 * not a global "damage on/off" switch.  They are applied to every soft beam
 * at build time and can be re-applied live from the Vettura settings screen.
 * The file is intentionally plain properties so a pack author can ship a
 * default profile without a custom screen.</p>
 */
public final class VnodoConfig {
    public static final String[] REGIONS = {
            "front_bumper", "hood", "body", "bed_floor", "rear_body", "tailgate", "wheel"
    };
    public static final double[] DEFAULTS = {
            0.80, 0.50, 0.70, 0.90, 0.80, 0.70, 1.00
    };
    private static final double[] SOFT = {
            0.55, 0.35, 0.50, 0.65, 0.55, 0.50, 1.00
    };
    private static final double[] HARD = {
            1.10, 0.80, 1.00, 1.20, 1.10, 1.00, 1.00
    };
    private static final Logger LOG = Logger.getLogger("vnodo");
    private static final VnodoConfig INSTANCE = new VnodoConfig();
    /**
     * Compile-time escape hatch for a deliberately locked demo build.
     * 0.7.1-beta leaves the profile editable; the old 0.7.0 hard lock is no
     * longer the release default.
     */
    public static final boolean PHYSICS_LOCKED = false;

    /** One-time migration marker for the old 0.7.0 hard-locked profile. */
    private static final int PROFILE_VERSION = 2;

    private final double[] scales = DEFAULTS.clone();
    private Path path = Path.of("config", "vnodo-client.properties");
    private boolean enabled = true;
    private boolean debugHud = true;
    private boolean dirty;
    private String source = "vnodo";
    private String preset = "default";

    private VnodoConfig() {
    }

    public static VnodoConfig get() {
        return INSTANCE;
    }

    public static void setConfigPath(Path configPath) {
        if (configPath != null) {
            INSTANCE.path = configPath;
        }
    }

    public static void load() {
        INSTANCE.loadFromDisk();
    }

    public static void save() {
        INSTANCE.dirty = false;
        INSTANCE.saveToDisk();
    }

    /** Flush pending slider edits at most once per client tick. */
    public static void saveIfDirty() {
        if (INSTANCE.dirty) {
            INSTANCE.dirty = false;
            INSTANCE.saveToDisk();
        }
    }

    public static boolean enabled() {
        return INSTANCE.enabled;
    }

    public static void setEnabled(boolean value) {
        INSTANCE.enabled = value;
        INSTANCE.saveToDisk();
    }

    public static boolean debugHud() {
        return INSTANCE.debugHud;
    }

    public static void setDebugHud(boolean value) {
        INSTANCE.debugHud = value;
        INSTANCE.saveToDisk();
    }

    public static String source() {
        return INSTANCE.source;
    }

    public static void setSource(String value) {
        INSTANCE.source = "vettura".equalsIgnoreCase(value) ? "vettura" : "vnodo";
        INSTANCE.saveToDisk();
    }

    public static String preset() {
        return INSTANCE.preset;
    }

    public static void applyPreset(String value) {
        if (PHYSICS_LOCKED) {
            // Do not let a stale properties file or a soft/default button
            // silently re-enable the old paper shell.
            System.arraycopy(HARD, 0, INSTANCE.scales, 0, HARD.length);
            INSTANCE.preset = "hard";
            INSTANCE.saveToDisk();
            return;
        }
        switch (value == null ? "" : value) {
            case "soft" -> {
                System.arraycopy(SOFT, 0, INSTANCE.scales, 0, SOFT.length);
                INSTANCE.preset = "soft";
            }
            case "hard" -> {
                System.arraycopy(HARD, 0, INSTANCE.scales, 0, HARD.length);
                INSTANCE.preset = "hard";
            }
            default -> {
                System.arraycopy(DEFAULTS, 0, INSTANCE.scales, 0, DEFAULTS.length);
                INSTANCE.preset = "default";
            }
        }
        INSTANCE.saveToDisk();
    }

    public static double scale(String region) {
        int index = indexOf(region);
        if (index < 0) {
            return 1.0;
        }
        // Keep the old -D system-property escape hatch for headless probes.
        String override = System.getProperty("vnodo.region." + region);
        if (override == null) {
            override = System.getProperty("bamng.region." + region);
        }
        if (override != null && !override.isBlank()) {
            try {
                return clamp(Double.parseDouble(override.trim()));
            } catch (NumberFormatException ignored) {
                // fall through to the persisted/locked value
            }
        }
        return PHYSICS_LOCKED ? HARD[index] : clamp(INSTANCE.scales[index]);
    }

    public static double value(String region) {
        int index = indexOf(region);
        if (index < 0) {
            return 1.0;
        }
        return PHYSICS_LOCKED ? HARD[index] : clamp(INSTANCE.scales[index]);
    }

    public static void setValue(String region, double value) {
        if (PHYSICS_LOCKED) {
            // The settings screen still renders the row, but dragging it must
            // not create a second source of truth for crash stiffness.
            return;
        }
        int index = indexOf(region);
        if (index < 0) {
            return;
        }
        double next = clamp(value);
        if (Math.abs(next - INSTANCE.scales[index]) < 1.0e-4) {
            return;
        }
        INSTANCE.scales[index] = next;
        INSTANCE.preset = "custom";
        INSTANCE.dirty = true;
    }

    public static void reset() {
        // The 0.7.1 default is one step softer than the old hard lock.
        // "hard" remains selectable for players who want the tested stiff shell.
        System.arraycopy(DEFAULTS, 0, INSTANCE.scales, 0, DEFAULTS.length);
        INSTANCE.enabled = true;
        INSTANCE.debugHud = true;
        INSTANCE.source = "vnodo";
        INSTANCE.preset = "default";
        INSTANCE.dirty = false;
        INSTANCE.saveToDisk();
    }

    public static double clamp(double value) {
        if (!Double.isFinite(value)) {
            return 1.0;
        }
        return Math.max(0.10, Math.min(1.50, value));
    }

    private static int indexOf(String region) {
        if (region == null) {
            return -1;
        }
        for (int i = 0; i < REGIONS.length; i++) {
            if (REGIONS[i].equals(region)) {
                return i;
            }
        }
        return -1;
    }

    private void loadFromDisk() {
        if (this.path == null || !Files.isRegularFile(this.path)) {
            return;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(this.path)) {
            properties.load(in);
        } catch (IOException exception) {
            LOG.log(Level.WARNING, "Vnodo config read failed: " + this.path, exception);
            return;
        }
        int profileVersion = parseProfileVersion(properties.getProperty("profile_version"));
        this.enabled = Boolean.parseBoolean(properties.getProperty("enabled", "true"));
        this.debugHud = Boolean.parseBoolean(properties.getProperty("debug_hud", "true"));
        this.source = "vettura".equalsIgnoreCase(properties.getProperty("source", "vnodo"))
                ? "vettura" : "vnodo";
        this.preset = properties.getProperty("preset", "default");
        for (int i = 0; i < REGIONS.length; i++) {
            String key = "region." + REGIONS[i];
            String raw = properties.getProperty(key);
            if (raw != null) {
                try {
                    this.scales[i] = clamp(Double.parseDouble(raw.trim()));
                } catch (NumberFormatException ignored) {
                    this.scales[i] = DEFAULTS[i];
                }
            }
        }
        if (PHYSICS_LOCKED) {
            System.arraycopy(HARD, 0, this.scales, 0, HARD.length);
            this.preset = "hard";
        }
        if (profileVersion < PROFILE_VERSION) {
            // 0.7.0 wrote a hard-locked config without a profile marker.
            // Migrate that exact profile once so existing players get the
            // new default instead of staying stuck on hard. A hand-edited
            // custom profile from before the lock is preserved.
            if (isHardLockedProfile()) {
                System.arraycopy(DEFAULTS, 0, this.scales, 0, DEFAULTS.length);
                this.preset = "default";
            }
            this.dirty = false;
            this.saveToDisk();
        }
    }

    private boolean isHardLockedProfile() {
        if (!"hard".equals(this.preset)) {
            return false;
        }
        for (int i = 0; i < REGIONS.length; i++) {
            if (Math.abs(this.scales[i] - HARD[i]) > 1.0e-4) {
                return false;
            }
        }
        return true;
    }

    private static int parseProfileVersion(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void saveToDisk() {
        if (this.path == null) {
            return;
        }
        Properties properties = new Properties();
        properties.setProperty("profile_version", Integer.toString(PROFILE_VERSION));
        properties.setProperty("enabled", Boolean.toString(this.enabled));
        properties.setProperty("debug_hud", Boolean.toString(this.debugHud));
        properties.setProperty("source", this.source);
        properties.setProperty("preset", this.preset);
        for (int i = 0; i < REGIONS.length; i++) {
            properties.setProperty("region." + REGIONS[i], Double.toString(clamp(this.scales[i])));
        }
        try {
            Path parent = this.path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream out = Files.newOutputStream(this.path)) {
                properties.store(out, "Vnodo client tuning");
            }
        } catch (IOException exception) {
            LOG.log(Level.WARNING, "Vnodo config write failed: " + this.path, exception);
        }
    }
}
