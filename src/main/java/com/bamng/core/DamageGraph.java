package com.bamng.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BeamNG-style damage event graph.
 *
 * <p>breakGroupType semantics:</p>
 * <ul>
 *   <li>0: the beam triggers its groups when it breaks, and can be broken by
 *       another member of those groups.</li>
 *   <li>1: the beam follows the group and can be broken by it, but never
 *       triggers the group itself.</li>
 * </ul>
 *
 * <p>A beam may belong to several groups. Breaking any group member breaks
 * it; if that member is type 0, it in turn fires every group it belongs to.
 * This mirrors BeamNG's multi-group propagation instead of a single
 * {@code broken} flag.</p>
 */
public final class DamageGraph {
    private final List<SoftBeam> beams = new ArrayList<>();
    private final List<SoftTriangle> triangles = new ArrayList<>();
    private final Map<SoftBeam, List<SoftTriangle>> trianglesByBeam = new LinkedHashMap<>();
    private final Map<String, List<SoftTriangle>> trianglesByGroup = new LinkedHashMap<>();
    private final Map<String, List<SoftBeam>> breakGroups = new LinkedHashMap<>();
    private final Map<String, List<SoftBeam>> deformGroups = new LinkedHashMap<>();
    private final Map<String, String> partByBreakGroup = new LinkedHashMap<>();
    private final Map<String, String> partByDeformGroup = new LinkedHashMap<>();
    private final Set<String> brokenGroups = new LinkedHashSet<>();
    private final Set<String> damagedGroups = new LinkedHashSet<>();
    private final Set<String> triggeredDeformGroups = new LinkedHashSet<>();
    private final ArrayDeque<SoftBeam> pending = new ArrayDeque<>();
    private SoftNode[] nodes;
    /** O(1) counters; scanning 1912 beams/triangles every HUD frame was waste. */
    private int brokenBeamCount;
    private int brokenTriangleCount;

    /**
     * Optional local damage window.  A crash may only break beams whose
     * midpoint is inside this sphere; without it a deep box penetration
     * generated enough solver force to snap nearly every one of the 2009
     * lattice beams in a single tick (the 1847-beam steering/contact failure).
     */
    private boolean breakZoneActive;
    private double breakZoneX;
    private double breakZoneY;
    private double breakZoneZ;
    private double breakZoneRadius;
    private int breakBudget;
    /** Optional authored part filter ("front" / "tailgate"). */
    private String breakZonePart;

    /** E5: damage events consumed by audio/particles/renderers. */
    public interface Listener {
        default void onBeamBroken(SoftBeam beam) {
        }

        default void onTriangleBroken(SoftTriangle triangle) {
        }

        default void onGroupBroken(String group) {
        }

        default void onPartDetached(String part) {
        }
    }

    private final List<Listener> listeners = new ArrayList<>();

    public void addListener(Listener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public final PartState parts = new PartState();

    public void register(List<SoftBeam> source) {
        beams.clear();
        triangles.clear();
        trianglesByBeam.clear();
        trianglesByGroup.clear();
        breakGroups.clear();
        deformGroups.clear();
        brokenGroups.clear();
        damagedGroups.clear();
        triggeredDeformGroups.clear();
        pending.clear();
        parts.clear();
        this.brokenBeamCount = 0;
        this.brokenTriangleCount = 0;

        for (SoftBeam beam : source) {
            if (beams.contains(beam)) {
                continue;
            }
            beams.add(beam);
            for (String group : beam.breakGroups) {
                addMember(breakGroups, group, beam);
            }
            for (String group : beam.deformGroups) {
                addMember(deformGroups, group, beam);
            }
        }
    }

    private static void addMember(Map<String, List<SoftBeam>> groups, String group, SoftBeam beam) {
        if (group == null || group.isBlank()) {
            return;
        }
        List<SoftBeam> members = groups.computeIfAbsent(group, ignored -> new ArrayList<>());
        if (!members.contains(beam)) {
            members.add(beam);
        }
    }

    /** Restrict this crash to a local crumple sphere (model/world coordinates). */
    public void setBreakZone(double x, double y, double z, double radius, int maxBreaks) {
        setBreakZone(x, y, z, radius, maxBreaks, null);
    }

    public void setBreakZone(double x, double y, double z, double radius, int maxBreaks,
                             String part) {
        this.breakZoneActive = true;
        this.breakZoneX = x;
        this.breakZoneY = y;
        this.breakZoneZ = z;
        this.breakZoneRadius = Math.max(0.25, radius);
        this.breakBudget = Math.max(0, maxBreaks);
        this.breakZonePart = part;
    }

    public void clearBreakZone() {
        this.breakZoneActive = false;
        this.breakBudget = 0;
        this.breakZonePart = null;
    }

    private boolean inBreakZone(double x, double y, double z) {
        if (!this.breakZoneActive) {
            return true;
        }
        double dx = x - this.breakZoneX;
        double dy = y - this.breakZoneY;
        double dz = z - this.breakZoneZ;
        return dx * dx + dy * dy + dz * dz <= this.breakZoneRadius * this.breakZoneRadius;
    }

    private boolean inBreakZone(SoftBeam beam) {
        if (!this.breakZoneActive || this.nodes == null || beam == null) {
            return true;
        }
        SoftNode a = this.nodes[beam.a];
        SoftNode b = this.nodes[beam.b];
        if (this.breakZonePart != null
                && !this.breakZonePart.equals(a.group0)
                && !this.breakZonePart.equals(b.group0)) {
            return false;
        }
        return inBreakZone((a.position.x + b.position.x) * 0.5,
                (a.position.y + b.position.y) * 0.5,
                (a.position.z + b.position.z) * 0.5);
    }

    /**
     * Register shell triangles.  Edge beams are resolved by endpoint ids so a
     * broken beam can request the adjacent triangle without scanning the mesh.
     */
    public void registerTriangles(List<SoftTriangle> source, SoftNode[] nodeArray) {
        triangles.clear();
        trianglesByBeam.clear();
        trianglesByGroup.clear();
        this.brokenTriangleCount = 0;
        for (SoftTriangle triangle : source) {
            triangles.add(triangle);
            String group = triangle.group(nodeArray);
            List<SoftTriangle> byGroup = trianglesByGroup.computeIfAbsent(group, ignored -> new ArrayList<>());
            if (!byGroup.contains(triangle)) {
                byGroup.add(triangle);
            }
            triangle.edgeBeams.clear();
            addEdgeBeam(triangle, triangle.a, triangle.b, nodeArray);
            addEdgeBeam(triangle, triangle.b, triangle.c, nodeArray);
            addEdgeBeam(triangle, triangle.c, triangle.a, nodeArray);
        }
    }

    private void addEdgeBeam(SoftTriangle triangle, int x, int y, SoftNode[] nodeArray) {
        for (SoftBeam beam : beams) {
            if ((beam.a == x && beam.b == y) || (beam.a == y && beam.b == x)) {
                if (!triangle.edgeBeams.contains(beam)) {
                    triangle.edgeBeams.add(beam);
                }
                List<SoftTriangle> adjacent = trianglesByBeam.computeIfAbsent(beam, ignored -> new ArrayList<>());
                if (!adjacent.contains(triangle)) {
                    adjacent.add(triangle);
                }
                return;
            }
        }
    }

    /** A breakGroup that detaches a logical mesh part. */
    public void linkPart(String breakGroup, String part) {
        if (breakGroup != null && part != null) {
            partByBreakGroup.put(breakGroup, part);
        }
    }

    /** A deformGroup that switches a logical part to its damaged state. */
    public void linkDeformPart(String deformGroup, String part) {
        if (deformGroup != null && part != null) {
            partByDeformGroup.put(deformGroup, part);
        }
    }

    /**
     * Evaluate every intact beam once. Newly broken beams are queued so that
     * their type-0 break groups can cascade without recursion.
     */
    public void update(SoftNode[] nodes) {
        this.nodes = nodes;
        for (SoftBeam beam : beams) {
            if (beam.broken) {
                continue;
            }
            if (!inBreakZone(beam)) {
                continue;
            }
            if (this.breakZoneActive && this.breakBudget <= 0) {
                continue;
            }

            // The solver already knows the axial force and strain for this
            // tick.  A beam far below its strength threshold cannot break,
            // and a beam with no pending deform group cannot trigger a
            // deformation event.  Recomputing its endpoint distance and
            // running evaluateDamage for all 1912 beams every tick was a
            // duplicated sqrt/force pass; this keeps the same damage
            // semantics while touching only the beams that can change state.
            if (beam.lastForceValid && beam.forceDamage
                    && Math.abs(beam.lastForce) < beam.beamStrength
                    && (beam.deformGroups.isEmpty() || beam.deformTriggered
                    || Math.abs(beam.lastStrain) < beam.deformationTriggerRatio)) {
                continue;
            }

            boolean broke = beam.evaluateDamage(nodes);

            if (!broke && !beam.deformTriggered && !beam.deformGroups.isEmpty()
                    && Math.abs(beam.lastStrain) >= beam.deformationTriggerRatio) {
                beam.deformTriggered = true;
                for (String group : beam.deformGroups) {
                    triggerDeformGroup(group);
                }
            }

            if (broke) {
                brokenBeamCount++;
                if (this.breakZoneActive && this.breakBudget > 0) {
                    this.breakBudget--;
                }
                pending.add(beam);
                for (Listener listener : listeners) {
                    listener.onBeamBroken(beam);
                }
            }
        }

        while (!pending.isEmpty()) {
            onBroken(pending.poll());
        }
    }

    private void onBroken(SoftBeam beam) {
        // Record every group the broken beam belongs to.  Passive (type 1)
        // groups must not cascade, but they still need to stay visible as a
        // damaged part of the vehicle; otherwise a local front crumple logs
        // groups=[] and loses the front identifier.
        damagedGroups.addAll(beam.breakGroups);

        if (!beam.disableTriangleBreaking) {
            List<SoftTriangle> adjacent = trianglesByBeam.get(beam);
            if (adjacent != null) {
                for (SoftTriangle triangle : adjacent) {
                    if (!triangle.disableTriangleBreaking) {
                        breakTriangle(triangle);
                    }
                }
            }
        }

        if (beam.breakGroupType == 0) {
            for (String group : beam.breakGroups) {
                triggerBreakGroup(group);
            }
        }
        for (String group : beam.deformGroups) {
            triggerDeformGroup(group);
        }
    }

    private void breakTriangle(SoftTriangle triangle) {
        if (triangle.broken) {
            return;
        }
        triangle.broken = true;
        triangle.collision = false;
        triangle.aero = false;
        brokenTriangleCount++;
        for (Listener listener : listeners) {
            listener.onTriangleBroken(triangle);
        }
    }

    private void triggerBreakGroup(String group) {
        triggerBreakGroup(group, false);
    }

    /**
     * Explicit crash trigger used for a wheel corner.  The tether sits at the
     * front wheel, outside the normal panel break zone, so the zone-limited
     * group trigger only fired the visual detach and left the physics tether
     * welded in place; the surviving tether then acted as a hard stop through
     * the whole front crush.  This variant breaks every member even when the
     * current collision zone does not cover it.
     */
    public void breakGroupHard(String group) {
        triggerBreakGroup(group, true);
    }

    private void triggerBreakGroup(String group, boolean ignoreZone) {
        if (group == null || group.isBlank()) {
            return;
        }
        damagedGroups.add(group);
        if (!brokenGroups.add(group)) {
            return;
        }

        List<SoftBeam> members = breakGroups.get(group);
        if (members != null) {
            for (SoftBeam member : members) {
                if (!member.broken && (ignoreZone || inBreakZone(member))) {
                    if (!ignoreZone && this.breakZoneActive && this.breakBudget <= 0) {
                        break;
                    }
                    if (member.breakFromGroup()) {
                        brokenBeamCount++;
                        if (!ignoreZone && this.breakZoneActive) {
                            this.breakBudget--;
                        }
                    }
                    pending.add(member);
                }
            }
        }

        for (Listener listener : listeners) {
            listener.onGroupBroken(group);
        }
        String part = partByBreakGroup.get(group);
        if (part != null) {
            parts.detach(part);
            for (Listener listener : listeners) {
                listener.onPartDetached(part);
            }
        }

        List<SoftTriangle> groupTriangles = trianglesByGroup.get(group);
        if (groupTriangles != null) {
            for (SoftTriangle triangle : groupTriangles) {
                if (!triangle.disableTriangleBreaking) {
                    breakTriangle(triangle);
                }
            }
        }
    }

    private void triggerDeformGroup(String group) {
        if (group == null || group.isBlank() || !triggeredDeformGroups.add(group)) {
            return;
        }
        String part = partByDeformGroup.get(group);
        if (part != null) {
            parts.deform(part);
        }
    }

    /**
     * D6: connected components over intact beams (union-find, deterministic
     * order).  The largest component is the chassis; every other component is
     * a detached cluster.
     */
    public int[] connectedComponents(SoftNode[] nodes) {
        int[] parent = new int[nodes.length];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        for (SoftBeam beam : beams) {
            if (!beam.broken) {
                union(parent, beam.a, beam.b);
            }
        }
        int[] root = new int[nodes.length];
        for (int i = 0; i < nodes.length; i++) {
            root[i] = find(parent, i);
        }
        return root;
    }

    /** Node indices outside the largest intact component, i.e. fallen parts. */
    public List<Integer> detachedNodes(SoftNode[] nodes) {
        int[] root = connectedComponents(nodes);
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (int value : root) {
            counts.merge(value, 1, Integer::sum);
        }
        int largest = -1;
        int largestCount = -1;
        for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > largestCount) {
                largest = entry.getKey();
                largestCount = entry.getValue();
            }
        }
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < root.length; i++) {
            if (root[i] != largest) {
                out.add(i);
            }
        }
        return out;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) {
            parent[Math.max(ra, rb)] = Math.min(ra, rb);
        }
    }

    /** D7: restore topology and damage state.  Kinematics are the caller's job. */
    public void reset() {
        for (SoftBeam beam : beams) {
            beam.resetDamageState();
        }
        for (SoftTriangle triangle : triangles) {
            triangle.broken = false;
            triangle.collision = true;
            triangle.aero = true;
        }
        brokenGroups.clear();
        damagedGroups.clear();
        triggeredDeformGroups.clear();
        pending.clear();
        parts.clear();
        this.brokenBeamCount = 0;
        this.brokenTriangleCount = 0;
        this.breakZoneActive = false;
        this.breakBudget = 0;
        this.breakZonePart = null;
    }

    /** Explicitly fire a breakGroup (used by the client impact bridge). */
    public void breakGroup(String group) {
        triggerBreakGroup(group);
    }

    /** Break at most {@code max} intact beams whose midpoint is inside the
     *  given sphere.  This gives a controlled crumple zone instead of letting
     *  gravity/inertia snap hundreds of beams by strain alone. */
    public int breakNear(double x, double y, double z, double radius, int max) {
        if (nodes == null) {
            return 0;
        }
        double r2 = radius * radius;
        int count = 0;
        for (SoftBeam beam : beams) {
            if (beam.broken || count >= max) {
                continue;
            }
            double mx = (nodes[beam.a].position.x + nodes[beam.b].position.x) * 0.5;
            double my = (nodes[beam.a].position.y + nodes[beam.b].position.y) * 0.5;
            double mz = (nodes[beam.a].position.z + nodes[beam.b].position.z) * 0.5;
            double dx = mx - x, dy = my - y, dz = mz - z;
            if (dx * dx + dy * dy + dz * dz <= r2) {
                if (beam.breakFromGroup()) {
                    brokenBeamCount++;
                }
                pending.add(beam);
                count++;
            }
        }
        return count;
    }

    public int brokenBeamCount() {
        return this.brokenBeamCount;
    }

    /**
     * Rebuild the O(1) counters and coarse part flags after a saved shell is
     * loaded directly into beam/triangle flags.  The normal break path
     * already updates these increments; this is only for persistence restore.
     */
    public void recountDamageState() {
        this.brokenBeamCount = 0;
        this.brokenTriangleCount = 0;
        this.brokenGroups.clear();
        this.damagedGroups.clear();
        this.parts.clear();
        for (SoftBeam beam : this.beams) {
            if (beam.broken) {
                this.brokenBeamCount++;
                this.damagedGroups.addAll(beam.breakGroups);
            }
            if (beam.deformed) {
                this.damagedGroups.addAll(beam.deformGroups);
            }
        }
        for (SoftTriangle triangle : this.triangles) {
            if (triangle.broken) {
                this.brokenTriangleCount++;
                if (this.nodes != null) {
                    String group = triangle.group(this.nodes);
                    if (group != null) {
                        this.damagedGroups.add(group);
                    }
                }
            }
        }
        for (Map.Entry<String, List<SoftBeam>> entry : this.breakGroups.entrySet()) {
            boolean allBroken = !entry.getValue().isEmpty();
            for (SoftBeam beam : entry.getValue()) {
                if (!beam.broken) {
                    allBroken = false;
                    break;
                }
            }
            if (!allBroken) {
                continue;
            }
            this.brokenGroups.add(entry.getKey());
            String part = this.partByBreakGroup.get(entry.getKey());
            if (part != null) {
                this.parts.detach(part);
            }
        }
        for (Map.Entry<String, List<SoftBeam>> entry : this.deformGroups.entrySet()) {
            for (SoftBeam beam : entry.getValue()) {
                if (beam.deformed) {
                    String part = this.partByDeformGroup.get(entry.getKey());
                    if (part != null) {
                        this.parts.deform(part);
                    }
                    break;
                }
            }
        }
    }

    public int brokenTriangleCount() {
        return this.brokenTriangleCount;
    }

    public List<SoftTriangle> triangles() {
        return Collections.unmodifiableList(triangles);
    }

    public Set<String> brokenGroups() {
        return Collections.unmodifiableSet(brokenGroups);
    }

    /** Groups touched by a broken beam, including passive groups that must not cascade. */
    public Set<String> damagedGroups() {
        return Collections.unmodifiableSet(damagedGroups);
    }

    public Set<String> triggeredDeformGroups() {
        return Collections.unmodifiableSet(triggeredDeformGroups);
    }

    public List<SoftBeam> beams() {
        return Collections.unmodifiableList(beams);
    }

    public List<SoftBeam> membersOf(String group) {
        return Collections.unmodifiableList(breakGroups.getOrDefault(group, List.of()));
    }
}
