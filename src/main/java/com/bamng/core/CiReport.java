package com.bamng.core;

import java.util.List;

/** F9: plain CI artefact, no external service. */
public final class CiReport {
    private CiReport() {
    }

    public static String markdown(String suite, List<String> results) {
        StringBuilder builder = new StringBuilder("# BAMNG CI\n\n- suite: ").append(suite).append("\n\n");
        for (String result : results) {
            builder.append("- ").append(result).append('\n');
        }
        return builder.toString();
    }
}
