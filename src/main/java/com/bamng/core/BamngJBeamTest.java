package com.bamng.core;

public final class BamngJBeamTest {
    public static void main(String[] args) {
        String good = """
            {"nodes":[
              {"id":"a","x":0,"y":0,"z":0,"mass":2,"group":"body"},
              {"id":"b","x":1,"y":0,"z":0,"mass":2,"group":"body"},
              {"id":"c","x":0,"y":0,"z":1,"mass":2,"group":"body"}],
             "beams":[
              {"a":"a","b":"b","spring":900000,"damp":300,"strength":90000},
              {"a":"b","b":"c","spring":900000,"damp":300,"strength":90000},
              {"a":"c","b":"a","spring":900000,"damp":300,"strength":90000}],
             "triangles":[{"a":"a","b":"b","c":"c"}]}
            """;
        JBeamLoader.Result result = JBeamLoader.load(good);
        if (!result.usable() || result.triangles.size() != 1) {
            throw new IllegalStateException("good sample rejected");
        }
        JBeamLoader.Result bad = JBeamLoader.load(
                "{\"nodes\":[{\"id\":\"a\",\"x\":0,\"y\":0,\"z\":0},{\"id\":\"b\",\"x\":1,\"y\":0,\"z\":0}]," +
                "\"beams\":[{\"a\":\"a\",\"b\":\"missing\"}],\"triangles\":[{\"a\":\"a\",\"b\":\"missing\",\"c\":\"b\"}]}");
        if (bad.triangles.size() != 0 || bad.warnings.isEmpty()) {
            throw new IllegalStateException("bad sample was not tolerated");
        }
        System.out.println("JBEAM OK nodes=" + result.nodes.size() + " beams=" + result.beams.size()
                + " triangles=" + result.triangles.size() + " warnings=" + bad.warnings.size());
        System.out.println("BAD SAMPLE WARNING: " + bad.warnings.get(0));
    }
}
