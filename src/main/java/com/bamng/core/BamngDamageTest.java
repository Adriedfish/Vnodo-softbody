package com.bamng.core;

/** Headless checks for breakGroup propagation and logical part damage. */
public final class BamngDamageTest {
    public static void main(String[] args) {
        testType0CascadesToType1();
        testType1DoesNotTriggerGroup();
        testMultiGroupPropagation();
        testPartDetach();
        testDeformGroup();
        testT15TailgateCascade();
        System.out.println("BamngDamageTest OK");
    }

    private static void testType0CascadesToType1() {
        SoftBodyWorld world = new SoftBodyWorld();
        SoftNode a = node(world, 0, 0);
        SoftNode b = node(world, 1, 0);
        SoftNode c = node(world, 3, 0);
        SoftNode d = node(world, 4, 0);

        SoftBeam trigger = beam(0, 1, "door_support", 0);
        SoftBeam follower = beam(2, 3, "door_support", 1);
        world.beams.add(trigger);
        world.beams.add(follower);
        world.damage.register(world.beams);

        b.position.x = 2.0; // 100% strain, well over breakStrain
        world.damage.update(world.nodeArray());

        check(trigger.broken, "type0 beam must break");
        check(follower.broken, "type1 follower must be broken by the group");
        check(world.damage.brokenGroups().contains("door_support"), "group must be marked broken");
    }

    private static void testType1DoesNotTriggerGroup() {
        SoftBodyWorld world = new SoftBodyWorld();
        SoftNode a = node(world, 0, 0);
        SoftNode b = node(world, 1, 0);
        node(world, 3, 0);
        node(world, 4, 0);

        SoftBeam leader = beam(0, 1, "support", 1);
        SoftBeam witness = beam(2, 3, "support", 0);
        world.beams.add(leader);
        world.beams.add(witness);
        world.damage.register(world.beams);

        b.position.x = 2.0;
        world.damage.update(world.nodeArray());

        check(leader.broken, "type1 beam may break directly");
        check(!witness.broken, "type1 beam must not trigger its breakGroup");
        check(!world.damage.brokenGroups().contains("support"), "type1 must not mark the group broken");
        check(world.damage.damagedGroups().contains("support"), "type1 must still be recorded as damaged");
    }

    private static void testMultiGroupPropagation() {
        SoftBodyWorld world = new SoftBodyWorld();
        node(world, 0, 0);
        node(world, 1, 0);
        node(world, 3, 0);
        node(world, 4, 0);
        node(world, 6, 0);
        node(world, 7, 0);

        SoftBeam bridge = beam(0, 1, "group_a", 0);
        bridge.breakGroups.add("group_b");
        SoftBeam aFollower = beam(2, 3, "group_a", 1);
        SoftBeam bFollower = beam(4, 5, "group_b", 1);
        world.beams.add(bridge);
        world.beams.add(aFollower);
        world.beams.add(bFollower);
        world.damage.register(world.beams);

        world.nodes.get(1).position.x = 2.0;
        world.damage.update(world.nodeArray());

        check(bridge.broken, "bridge breaks");
        check(aFollower.broken, "group A follower breaks");
        check(bFollower.broken, "group B follower breaks through the multi-group bridge");
        check(world.damage.brokenGroups().contains("group_a"), "group A marked");
        check(world.damage.brokenGroups().contains("group_b"), "group B marked");
    }

    private static void testPartDetach() {
        SoftBodyWorld world = new SoftBodyWorld();
        node(world, 0, 0);
        node(world, 1, 0);
        SoftBeam beam = beam(0, 1, "tailgate", 0);
        world.beams.add(beam);
        world.damage.register(world.beams);
        world.damage.linkPart("tailgate", "tailgate");

        world.nodes.get(1).position.x = 2.0;
        world.damage.update(world.nodeArray());

        check(!world.damage.parts.visible("tailgate"), "linked part must be hidden after group break");
        check(world.damage.parts.visible("body"), "unrelated part stays visible");
    }

    private static void testDeformGroup() {
        SoftBodyWorld world = new SoftBodyWorld();
        node(world, 0, 0);
        node(world, 1, 0);
        SoftBeam beam = beam(0, 1, "tailgate", 0);
        beam.forceDamage = false; // this case exercises the legacy strain path
        beam.breakStrain = 0.40;
        beam.deformGroups.add("tailgate_skin");
        beam.deformationTriggerRatio = 0.12;
        world.beams.add(beam);
        world.damage.register(world.beams);
        world.damage.linkDeformPart("tailgate_skin", "tailgate");

        world.nodes.get(1).position.x = 1.25; // 25% strain: deform, no break
        world.damage.update(world.nodeArray());

        check(!beam.broken, "deform trigger must not break the beam");
        check(world.damage.parts.state("tailgate") == PartState.State.DEFORMED,
                "deformGroup must switch the part to DEFORMED");
        check(world.damage.parts.visible("tailgate"), "deformed part remains visible");
    }

    private static void testT15TailgateCascade() {
        T15Body body = T15Body.create(0.82);
        check(!body.world.damage.membersOf("tailgate").isEmpty(), "T15 must have tailgate group members");

        SoftBeam beam = body.world.damage.membersOf("tailgate").get(0);
        // The real T15 rear group is passive; fire it explicitly the way the
        // future rear-impact bridge will, then verify the detach contract.
        body.world.damage.breakGroup("tailgate");

        check(!body.world.damage.parts.visible("tailgate"),
                "T15 tailgate mesh must be hidden after the group fires");
    }

    private static SoftNode node(SoftBodyWorld world, double x, double y) {
        SoftNode node = new SoftNode(world.nodes.size());
        node.position.set(x, y, 0);
        node.previous.set(node.position);
        node.rest.set(node.position);
        node.setMass(1.0);
        world.addNode(node);
        return node;
    }

    private static SoftBeam beam(int a, int b, String group, int type) {
        SoftBeam beam = new SoftBeam(a, b, 1.0, 0.72, 0.10, 0.32);
        beam.breakGroups.add(group);
        beam.breakGroupType = type;
        return beam;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
