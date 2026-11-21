package com.bamng.core;

import java.util.ArrayList;
import java.util.List;

/** F11: deterministic event order for repro and scripted checks. */
public final class EventTimeline {
    public static final class Event {
        public final long tick;
        public final String type;
        public final String detail;

        Event(long tick, String type, String detail) {
            this.tick = tick; this.type = type; this.detail = detail;
        }

        @Override
        public String toString() {
            return tick + " " + type + " " + detail;
        }
    }

    private final List<Event> events = new ArrayList<>();
    private long tick;

    public void tick() {
        this.tick++;
    }

    public void record(String type, String detail) {
        this.events.add(new Event(this.tick, type, detail == null ? "" : detail));
    }

    public List<Event> events() {
        return this.events;
    }

    public String json() {
        StringBuilder builder = new StringBuilder("{\"events\":[");
        for (int i = 0; i < this.events.size(); i++) {
            Event event = this.events.get(i);
            if (i > 0) {
                builder.append(',');
            }
            builder.append("{\"tick\":").append(event.tick)
                    .append(",\"type\":\"").append(escape(event.type))
                    .append("\",\"detail\":\"").append(escape(event.detail)).append("\"}");
        }
        return builder.append("]}").toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
