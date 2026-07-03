package me.d1n0.saddle.debugger;

import net.minecraft.resources.Identifier;

import java.util.BitSet;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verified breakpoints, keyed by function id and 1-based source line.
 * BitSets are built fresh on every update and never mutated after being
 * published, so lock-free reads from the command hot path are safe.
 */
public final class BreakpointManager {
    private static final Map<Identifier, BitSet> BREAKPOINTS = new ConcurrentHashMap<>();
    private static volatile boolean anySet;

    private BreakpointManager() {}

    public static void set(Identifier functionId, Collection<Integer> lines) {
        if (lines.isEmpty()) {
            BREAKPOINTS.remove(functionId);
        } else {
            BitSet bits = new BitSet();
            for (int line : lines) {
                if (line >= 1) bits.set(line);
            }
            BREAKPOINTS.put(functionId, bits);
        }
        anySet = !BREAKPOINTS.isEmpty();
    }

    public static boolean hit(Identifier functionId, int line) {
        if (!anySet) return false;
        BitSet bits = BREAKPOINTS.get(functionId);
        return bits != null && line >= 0 && bits.get(line);
    }

    public static void clearAll() {
        BREAKPOINTS.clear();
        anySet = false;
    }

    /** Drops breakpoints for functions removed by a datapack reload. */
    public static void retainAll(java.util.Set<Identifier> liveFunctions) {
        BREAKPOINTS.keySet().retainAll(liveFunctions);
        anySet = !BREAKPOINTS.isEmpty();
    }
}
