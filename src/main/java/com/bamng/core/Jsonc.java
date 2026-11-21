package com.bamng.core;

/**
 * G1: minimal JSONC reader for JBeam-style definitions.
 *
 * <p>Strips line and block comments outside strings, removes trailing commas,
 * then parses with the platform's Gson.  It never executes content and never
 * repairs structural errors; those go to the linter.</p>
 */
public final class Jsonc {
    private Jsonc() {
    }

    public static String strip(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean inString = false;
        boolean escaped = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                    out.append(c);
                }
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (inString) {
                out.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
                continue;
            }
            if (c == '/' && next == '/') {
                lineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                blockComment = true;
                i++;
                continue;
            }
            out.append(c);
        }
        return removeTrailingCommas(out.toString());
    }

    private static String removeTrailingCommas(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (inString) {
                out.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
                continue;
            }
            if (c == ',') {
                int j = i + 1;
                while (j < source.length() && Character.isWhitespace(source.charAt(j))) {
                    j++;
                }
                if (j < source.length() && (source.charAt(j) == '}' || source.charAt(j) == ']')) {
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }
}
