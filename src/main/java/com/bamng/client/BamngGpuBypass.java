package com.bamng.client;

/** While a BAMNG-owned truck renders, force Vextris through its CPU vertex path. */
public final class BamngGpuBypass {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private BamngGpuBypass() {
    }

    public static void push() {
        DEPTH.set(DEPTH.get() + 1);
    }

    public static void pop() {
        int depth = DEPTH.get() - 1;
        if (depth <= 0) {
            DEPTH.remove();
        } else {
            DEPTH.set(depth);
        }
    }

    public static boolean active() {
        return DEPTH.get() > 0;
    }
}
