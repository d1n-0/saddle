package me.d1n0.saddle.debugger;

import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Map;

/**
 * Immutable view of the datapack call stack, captured on the server thread at
 * the moment execution is suspended. DAP threads read it without touching any
 * live game state.
 */
public record StackSnapshot(int stoppedDepth, List<Frame> frames) {

    /**
     * {@code frames} is ordered innermost first. {@code entityUuid} is the
     * executor entity's UUID (null when the executor is not an entity).
     * {@code macroArgs} holds the macro argument values when the frame is a
     * {@code $...} line. {@code source} is the live command source — opaque to
     * DAP threads, only dereferenced on the server thread (selector/coordinate
     * resolution while suspended).
     */
    public record Frame(Identifier functionId, int line, List<Variable> variables,
            String entityUuid, Map<String, String> macroArgs, Object source) {}

    public record Variable(String name, String value) {}
}
