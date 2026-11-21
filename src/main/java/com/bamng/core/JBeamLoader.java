package com.bamng.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * G3/G5: tolerant JBeam-style import.
 *
 * <p>BeamNG nodes are referenced by string id, while the runtime lattice uses
 * integer indices.  This loader resolves ids, fills safe defaults for absent
 * optional fields, and reports every skipped entry instead of rejecting the
 * whole file.  It intentionally accepts JSONC through {@link Jsonc#strip}.</p>
 */
public final class JBeamLoader {
    public static final class Node {
        public final String id;
        public final double x, y, z, mass;
        public final String group;
        public final boolean fixed;

        Node(String id, double x, double y, double z, double mass, String group, boolean fixed) {
            this.id = id; this.x = x; this.y = y; this.z = z;
            this.mass = mass; this.group = group; this.fixed = fixed;
        }
    }

    public static final class Beam {
        public final String a, b;
        public final double spring, damp, strength;

        Beam(String a, String b, double spring, double damp, double strength) {
            this.a = a; this.b = b; this.spring = spring; this.damp = damp; this.strength = strength;
        }
    }

    public static final class Triangle {
        public final String a, b, c;

        Triangle(String a, String b, String c) {
            this.a = a; this.b = b; this.c = c;
        }
    }

    public static final class Result {
        public final List<Node> nodes = new ArrayList<>();
        public final List<Beam> beams = new ArrayList<>();
        public final List<Triangle> triangles = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public final Map<String, Integer> indexById = new HashMap<>();

        public boolean usable() {
            return nodes.size() >= 3 && beams.size() >= 2;
        }
    }

    private JBeamLoader() {
    }

    public static Result load(String jsonc) {
        Result result = new Result();
        JsonElement root;
        try {
            root = JsonParser.parseString(Jsonc.strip(jsonc));
        } catch (RuntimeException error) {
            result.warnings.add("G5-01 invalid JSONC: " + error.getMessage());
            return result;
        }
        if (!root.isJsonObject()) {
            result.warnings.add("G5-02 root must be an object");
            return result;
        }
        JsonObject object = root.getAsJsonObject();
        JsonArray nodeArray = array(object, "nodes");
        for (JsonElement element : nodeArray) {
            if (!element.isJsonObject()) {
                result.warnings.add("G5-03 node entry is not an object");
                continue;
            }
            JsonObject node = element.getAsJsonObject();
            String id = string(node, "id");
            if (id.isEmpty()) {
                result.warnings.add("G5-04 node without id skipped");
                continue;
            }
            if (result.indexById.containsKey(id)) {
                result.warnings.add("G5-05 duplicate node id skipped: " + id);
                continue;
            }
            double x = number(node, "x", Double.NaN);
            double y = number(node, "y", Double.NaN);
            double z = number(node, "z", Double.NaN);
            if (!finite(x) || !finite(y) || !finite(z)) {
                result.warnings.add("G5-06 node " + id + " has non-finite position, skipped");
                continue;
            }
            double mass = number(node, "mass", 1.0);
            if (!(mass > 0.0) || !finite(mass)) {
                result.warnings.add("G5-07 node " + id + " mass<=0, using 1.0");
                mass = 1.0;
            }
            String group = string(node, "group");
            if (group.isEmpty()) {
                group = "body";
            }
            boolean fixed = bool(node, "fixed", false);
            result.indexById.put(id, result.nodes.size());
            result.nodes.add(new Node(id, x, y, z, mass, group, fixed));
        }
        JsonArray beamArray = array(object, "beams");
        for (JsonElement element : beamArray) {
            if (!element.isJsonObject()) {
                result.warnings.add("G5-08 beam entry is not an object");
                continue;
            }
            JsonObject beam = element.getAsJsonObject();
            String a = string(beam, "a");
            String b = string(beam, "b");
            if (!result.indexById.containsKey(a) || !result.indexById.containsKey(b) || a.equals(b)) {
                result.warnings.add("G5-09 beam references unknown/equal node skipped: " + a + "->" + b);
                continue;
            }
            double spring = number(beam, "spring", 900_000.0);
            double damp = number(beam, "damp", 300.0);
            double strength = number(beam, "strength", 90_000.0);
            if (!(spring > 0.0) || !finite(spring)) {
                result.warnings.add("G5-10 beam " + a + "->" + b + " bad spring, using 900k");
                spring = 900_000.0;
            }
            result.beams.add(new Beam(a, b, spring, Math.max(0.0, damp), Math.max(1.0, strength)));
        }
        JsonArray triangleArray = array(object, "triangles");
        for (JsonElement element : triangleArray) {
            if (!element.isJsonObject()) {
                result.warnings.add("G5-11 triangle entry is not an object");
                continue;
            }
            JsonObject triangle = element.getAsJsonObject();
            String a = string(triangle, "a");
            String b = string(triangle, "b");
            String c = string(triangle, "c");
            if (!result.indexById.containsKey(a) || !result.indexById.containsKey(b)
                    || !result.indexById.containsKey(c) || a.equals(b) || b.equals(c) || a.equals(c)) {
                result.warnings.add("G5-12 triangle references unknown/equal node skipped");
                continue;
            }
            result.triangles.add(new Triangle(a, b, c));
        }
        if (result.nodes.isEmpty()) {
            result.warnings.add("G5-13 no usable nodes");
        }
        return result;
    }

    private static JsonArray array(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    private static String string(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    private static double number(JsonObject object, String name, double fallback) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        try {
            return element.getAsDouble();
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject object, String name, boolean fallback) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        try {
            return element.getAsBoolean();
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    private static boolean finite(double value) {
        return Double.isFinite(value);
    }
}
