package com.bamng.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** E4: small named ground table; values are starting points, not claims of
 *  BeamNG's original coefficients. */
public final class GroundMaterial {
    public static final GroundMaterial DEFAULT = new GroundMaterial("default", 0.62, 0.04, 0.0);
    private static final Map<String, GroundMaterial> TABLE = new LinkedHashMap<>();

    static {
        TABLE.put("default", DEFAULT);
        TABLE.put("asphalt", new GroundMaterial("asphalt", 0.85, 0.015, 0.0));
        TABLE.put("concrete", new GroundMaterial("concrete", 0.80, 0.020, 0.0));
        TABLE.put("dirt", new GroundMaterial("dirt", 0.65, 0.080, 0.0));
        TABLE.put("grass", new GroundMaterial("grass", 0.55, 0.100, 0.0));
        TABLE.put("sand", new GroundMaterial("sand", 0.40, 0.180, 0.0));
        TABLE.put("ice", new GroundMaterial("ice", 0.08, 0.005, 0.0));
    }

    public final String name;
    public final double friction;
    public final double rollingResistance;
    public final double bumpHeight;

    public GroundMaterial(String name, double friction, double rollingResistance, double bumpHeight) {
        this.name = name;
        this.friction = friction;
        this.rollingResistance = rollingResistance;
        this.bumpHeight = bumpHeight;
    }

    public static GroundMaterial byName(String name) {
        return TABLE.getOrDefault(name == null ? "default" : name.toLowerCase(), DEFAULT);
    }
}
