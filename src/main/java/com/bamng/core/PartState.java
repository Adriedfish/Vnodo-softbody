package com.bamng.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Logical damage state for mesh parts. Mesh submeshes such as body_paint and
 * door_l_paint are material groups, not vehicle parts; this class is the
 * bridge the renderer uses to hide or deform a logical part.
 */
public final class PartState {
    public enum State {
        INTACT,
        DEFORMED,
        DETACHED
    }

    private final Map<String, State> states = new LinkedHashMap<>();

    public State state(String part) {
        return states.getOrDefault(part, State.INTACT);
    }

    public boolean visible(String part) {
        return state(part) != State.DETACHED;
    }

    public void deform(String part) {
        if (part == null || part.isBlank()) {
            return;
        }
        State current = states.get(part);
        if (current == null || current == State.INTACT) {
            states.put(part, State.DEFORMED);
        }
    }

    public void detach(String part) {
        if (part == null || part.isBlank()) {
            return;
        }
        states.put(part, State.DETACHED);
    }

    public Set<String> detachedParts() {
        return Collections.unmodifiableSet(states.entrySet().stream()
                .filter(entry -> entry.getValue() == State.DETACHED)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet()));
    }

    public void clear() {
        states.clear();
    }
}
