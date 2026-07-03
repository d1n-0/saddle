package me.d1n0.saddle.dap;

import me.d1n0.saddle.debugger.BreakpointManager;
import me.d1n0.saddle.debugger.FunctionIndex;

import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** setBreakpoints / breakpointLocations request handling. */
final class BreakpointRequests {

    private BreakpointRequests() {}

    @SuppressWarnings("unchecked")
    static Map<String, Object> setBreakpoints(Map<String, Object> args) {
        String path = Args.getString(Args.getMap(args, "source"), "path");
        Identifier id = FunctionIndex.resolveClientPath(path);
        boolean known = id != null && FunctionIndex.isKnown(id);

        List<Map<String, Object>> requested = args.get("breakpoints") instanceof List<?> l
                ? (List<Map<String, Object>>) l : List.of();
        List<Integer> verifiedLines = new ArrayList<>();
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> bp : requested) {
            int line = Args.getInt(bp, "line", 0);
            Map<String, Object> result = new LinkedHashMap<>();
            if (!known) {
                result.put("line", line);
                result.put("verified", false);
                result.put("message", id == null
                        ? "Path is not inside a datapack function directory"
                        : "Function " + id + " is not loaded");
            } else {
                // Requests on comments/blank lines shift down to the next
                // line that maps to a runtime entry (command or macro line).
                int effective = FunctionIndex.isBreakableLine(id, line)
                        ? line : FunctionIndex.nextBreakableLine(id, line);
                if (effective > 0) {
                    result.put("line", effective);
                    result.put("verified", true);
                    verifiedLines.add(effective);
                } else {
                    result.put("line", line);
                    result.put("verified", false);
                    result.put("message", "No executable line at or below this position");
                }
            }
            results.add(result);
        }
        if (known) {
            BreakpointManager.set(id, verifiedLines);
            FunctionIndex.learnClientPath(id, path);
        }
        return Map.of("breakpoints", results);
    }

    static Map<String, Object> breakpointLocations(Map<String, Object> args) {
        Identifier id = FunctionIndex.resolveClientPath(
                Args.getString(Args.getMap(args, "source"), "path"));
        if (id == null) return Map.of("breakpoints", List.of());
        int line = Args.getInt(args, "line", 1);
        int endLine = Args.getInt(args, "endLine", line);
        List<Map<String, Object>> locations = FunctionIndex.breakableLinesIn(id, line, endLine).stream()
                .map(l -> Map.<String, Object>of("line", l))
                .toList();
        return Map.of("breakpoints", locations);
    }
}
