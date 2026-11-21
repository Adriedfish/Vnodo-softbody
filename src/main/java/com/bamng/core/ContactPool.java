package com.bamng.core;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * C15: contact pool with a stable key, age, last normal and accumulated
 * normal impulse.  The narrow phase writes into this pool instead of
 * allocating a contact every substep.  Warm starting is deliberately
 * opt-in: the pool can hand back the previous impulse, but the caller
 * decides whether the normal is still trustworthy.
 */
public final class ContactPool {
    public static final int CAPACITY = 4096;

    public static final class Contact {
        public final int node;
        public final int tri;
        public int age;
        public int lastSeen;
        public double normalImpulse;
        public double tangentImpulse;
        public double nx;
        public double ny;
        public double nz;
        public double lastNx;
        public double lastNy;
        public double lastNz;
        public double depth;
        public boolean visited;

        Contact(int node, int tri) {
            this.node = node;
            this.tri = tri;
        }
    }

    private final Map<Long, Contact> active = new HashMap<>();
    private int frame;
    public int highWater;
    public int flickerCount;
    public int warmStartCount;

    public void beginStep() {
        frame++;
        for (Contact contact : active.values()) {
            contact.visited = false;
        }
        highWater = 0;
    }

    public Contact touch(int node, int tri) {
        long key = key(node, tri);
        Contact contact = active.get(key);
        if (contact == null) {
            contact = new Contact(node, tri);
            contact.age = 0;
            active.put(key, contact);
        } else {
            contact.age++;
        }
        contact.visited = true;
        contact.lastSeen = frame;
        if (contact.age == 1) {
            flickerCount++;
        }
        highWater = Math.max(highWater, active.size());
        return contact;
    }

    public void endStep() {
        for (Iterator<Map.Entry<Long, Contact>> it = active.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Contact> entry = it.next();
            Contact contact = entry.getValue();
            if (!contact.visited || frame - contact.lastSeen > 3) {
                it.remove();
            }
        }
    }

    public int size() {
        return active.size();
    }

    public double warmStartRatio() {
        if (active.isEmpty()) {
            return 0.0;
        }
        int warm = 0;
        for (Contact contact : active.values()) {
            if (contact.age >= 2) {
                warm++;
            }
        }
        warmStartCount = warm;
        return warm / (double) active.size();
    }

    public static long key(int node, int tri) {
        return ((long) node << 32) | (tri & 0xffffffffL);
    }
}
