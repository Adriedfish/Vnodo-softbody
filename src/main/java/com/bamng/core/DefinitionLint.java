package com.bamng.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * G2: twenty deterministic lint rules for an imported JBeam-style definition.
 *
 * <p>Input is either an object with {@code nodes}/{@code beams}/{@code
 * triangles}, or a root array of parts.  The linter is pure: it never mutates
 * the definition and never invents missing fields.</p>
 */
public final class DefinitionLint {
    public static final int RULE_COUNT = 20;

    public static final class Issue {
        public final String code;
        public final String message;

        Issue(String code, String message) {
            this.code = code;
            this.message = message;
        }

        @Override
        public String toString() {
            return code + ": " + message;
        }
    }

    public static List<Issue> lint(String jsonc) {
        List<Issue> issues = new ArrayList<>();
        JsonElement root;
        try {
            root = JsonParser.parseString(Jsonc.strip(jsonc));
        } catch (RuntimeException error) {
            issues.add(new Issue("G2-01", "not valid JSONC: " + error.getMessage()));
            return issues;
        }
        if (!root.isJsonObject()) {
            issues.add(new Issue("G2-01", "root must be an object"));
            return issues;
        }
        JsonObject object = root.getAsJsonObject();
        JsonArray nodes = array(object, "nodes");
        JsonArray beams = array(object, "beams");
        JsonArray triangles = array(object, "triangles");
        if (nodes.size() == 0) {
            issues.add(new Issue("G2-02", "nodes is empty"));
        }
        if (beams.size() == 0) {
            issues.add(new Issue("G2-03", "beams is empty"));
        }
        if (!object.has("mass") || number(object, "mass", 0.0) <= 0.0) {
            issues.add(new Issue("G2-04", "root mass must be positive"));
        }

        Map<String, JsonObject> nodeById = new HashMap<>();
        Map<String, double[]> nodePos = new HashMap<>();
        for (JsonElement element : nodes) {
            if (!element.isJsonObject()) {
                issues.add(new Issue("G2-05", "node entry is not an object"));
                continue;
            }
            JsonObject node = element.getAsJsonObject();
            String id = string(node, "id");
            if (id.isEmpty()) {
                issues.add(new Issue("G2-05", "node id is empty"));
                continue;
            }
            if (nodeById.put(id, node) != null) {
                issues.add(new Issue("G2-06", "duplicate node id " + id));
            }
            double[] p = {number(node, "x", Double.NaN), number(node, "y", Double.NaN),
                    number(node, "z", Double.NaN)};
            nodePos.put(id, p);
            if (!finite(p[0]) || !finite(p[1]) || !finite(p[2])) {
                issues.add(new Issue("G2-07", "node " + id + " has non-finite position"));
            }
            if (number(node, "mass", 0.0) <= 0.0) {
                issues.add(new Issue("G2-08", "node " + id + " mass must be positive"));
            }
        }

        Set<String> beamPairs = new HashSet<>();
        Map<String, JsonObject> beamById = new HashMap<>();
        for (JsonElement element : beams) {
            if (!element.isJsonObject()) {
                issues.add(new Issue("G2-09", "beam entry is not an object"));
                continue;
            }
            JsonObject beam = element.getAsJsonObject();
            String id = string(beam, "id");
            if (id.isEmpty()) {
                issues.add(new Issue("G2-09", "beam id is empty"));
            } else if (beamById.put(id, beam) != null) {
                issues.add(new Issue("G2-10", "duplicate beam id " + id));
            }
            String a = string(beam, "a");
            String b = string(beam, "b");
            if (!nodeById.containsKey(a) || !nodeById.containsKey(b)) {
                issues.add(new Issue("G2-11", "beam " + id + " endpoint missing"));
            } else {
                String pair = a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
                if (!beamPairs.add(pair)) {
                    issues.add(new Issue("G2-12", "duplicate beam pair " + pair));
                }
            }
            if (number(beam, "spring", 0.0) <= 0.0) {
                issues.add(new Issue("G2-13", "beam " + id + " spring must be positive"));
            }
            if (number(beam, "damp", 0.0) < 0.0) {
                issues.add(new Issue("G2-14", "beam " + id + " damp must be >= 0"));
            }
            if (number(beam, "strength", 0.0) <= 0.0) {
                issues.add(new Issue("G2-15", "beam " + id + " strength must be positive"));
            }
            double type = number(beam, "breakGroupType", 0.0);
            if (type != 0.0 && type != 1.0) {
                issues.add(new Issue("G2-16", "beam " + id + " breakGroupType must be 0 or 1"));
            }
        }

        for (JsonElement element : triangles) {
            if (!element.isJsonObject()) {
                issues.add(new Issue("G2-17", "triangle entry is not an object"));
                continue;
            }
            JsonObject triangle = element.getAsJsonObject();
            String a = string(triangle, "a");
            String b = string(triangle, "b");
            String c = string(triangle, "c");
            if (!nodeById.containsKey(a) || !nodeById.containsKey(b) || !nodeById.containsKey(c)) {
                issues.add(new Issue("G2-18", "triangle references a missing node"));
            }
        }

        int orphan = 0;
        Set<String> used = new HashSet<>();
        for (JsonObject beam : beamById.values()) {
            used.add(string(beam, "a"));
            used.add(string(beam, "b"));
        }
        for (String id : nodeById.keySet()) {
            if (!used.contains(id)) {
                orphan++;
            }
        }
        if (orphan > 0) {
            issues.add(new Issue("G2-19", "orphan nodes: " + orphan));
        }

        for (Map.Entry<String, double[]> entry : nodePos.entrySet()) {
            double[] p = entry.getValue();
            if (finite(p[0]) && finite(p[1]) && finite(p[2])) {
                double length = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
                if (length > 20.0) {
                    issues.add(new Issue("G2-20", "node " + entry.getKey()
                            + " is " + String.format(java.util.Locale.ROOT, "%.2f", length)
                            + " m from origin (unit mismatch?)"));
                    break;
                }
            }
        }
        return issues;
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

    private static boolean finite(double value) {
        return Double.isFinite(value);
    }
}
