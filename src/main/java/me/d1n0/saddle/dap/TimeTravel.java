package me.d1n0.saddle.dap;

import me.d1n0.saddle.debugger.BreakpointManager;
import me.d1n0.saddle.debugger.DebugSession;
import me.d1n0.saddle.debugger.StepRequest;
import me.d1n0.saddle.debugger.TtdTrace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Navigation over the {@link TtdTrace} recording for one DAP session. Owns
 * the history cursor (an absolute step index, -1 = present) and produces the
 * stack/scope views for past steps. Only meaningful while the server thread
 * is suspended; the recording cannot grow behind the cursor's back except
 * through console evaluation, which appends strictly after it.
 */
final class TimeTravel {
    private final VariableTree variables;
    /** Emits DAP events: (event name, body). */
    private final BiConsumer<String, Map<String, Object>> events;
    private volatile long cursor = -1;

    TimeTravel(VariableTree variables, BiConsumer<String, Map<String, Object>> events) {
        this.variables = variables;
        this.events = events;
    }

    boolean active() {
        return cursor != -1;
    }

    void reset() {
        cursor = -1;
    }

    void stepBack() {
        long from = cursor == -1 ? TtdTrace.newestStep() : cursor;
        travelTo(Math.max(TtdTrace.oldestStep(), from - 1), "step");
    }

    void reverseContinue() {
        long from = cursor == -1 ? TtdTrace.newestStep() : cursor;
        long target = TtdTrace.previousMatching(from - 1,
                s -> BreakpointManager.hit(s.functionId(), s.line()));
        if (target != -1) {
            travelTo(target, "breakpoint");
        } else {
            travelTo(TtdTrace.oldestStep(), "step");
        }
    }

    /** Forward step inside the recording; lands on the present when it runs out. */
    void forwardStep(StepRequest.Mode mode) {
        TtdTrace.Step current = TtdTrace.step(cursor);
        StepRequest step = new StepRequest(mode, current != null ? current.depth() : 0);
        long head = TtdTrace.newestStep();
        long target = TtdTrace.nextMatching(cursor, head, s -> step.matches(s.depth()));
        travelTo(target == -1 ? head : target, "step");
    }

    /**
     * Continue inside the recording. Stops at the next recorded breakpoint
     * hit; otherwise returns to the present and reports {@code false} so the
     * caller resumes live execution.
     */
    boolean forwardContinue() {
        long head = TtdTrace.newestStep();
        long target = TtdTrace.nextMatching(cursor, head - 1,
                s -> BreakpointManager.hit(s.functionId(), s.line()));
        if (target == -1) {
            cursor = -1;
            return false;
        }
        travelTo(target, "breakpoint");
        return true;
    }

    Map<String, Object> stackTrace() {
        List<Map<String, Object>> result = new ArrayList<>();
        List<TtdTrace.Step> stack = TtdTrace.stackAt(cursor);
        for (int i = 0; i < stack.size(); i++) {
            TtdTrace.Step step = stack.get(i);
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("id", i + 1);
            frame.put("name", step.functionId().toString());
            frame.put("line", step.line());
            frame.put("column", 1);
            frame.put("source", DapSession.describeSource(step.functionId()));
            if (i == 0) frame.put("presentationHint", "subtle");
            result.add(frame);
        }
        return Map.of("stackFrames", result, "totalFrames", result.size());
    }

    Map<String, Object> scopes(int frameId) {
        List<TtdTrace.Step> stack = TtdTrace.stackAt(cursor);
        if (frameId < 1 || frameId > stack.size()) return Map.of("scopes", List.of());
        TtdTrace.Step step = stack.get(frameId - 1);
        long behind = TtdTrace.newestStep() - cursor;
        // Scope names match the live ones on purpose: VS Code restores row
        // expansion by name, so switching between present and history keeps
        // the tree open. The Executor scope carries the time-travel marker.
        List<Map<String, Object>> scopes = new ArrayList<>();
        scopes.add(scope("Executor", new VariableTree.RecordedExecutorNode(step, behind), false));
        if (!step.macroArgs().isEmpty()) {
            scopes.add(scope("Macro Arguments", new VariableTree.MacroArgsNode(step.macroArgs()), false));
        }
        scopes.add(scope("Command", new VariableTree.RecordedCommandNode(step), false));
        scopes.add(scope("Scoreboard", new VariableTree.ScoreboardAtNode(cursor), true));
        scopes.add(scope("Storage", new VariableTree.StorageAtNode(cursor), true));
        return Map.of("scopes", scopes);
    }

    private Map<String, Object> scope(String name, VariableTree.Node node, boolean expensive) {
        return Map.of(
                "name", name,
                "variablesReference", variables.register(node),
                "expensive", expensive);
    }

    /** Moves the cursor and reports the new location as a stop. */
    private void travelTo(long target, String reason) {
        long head = TtdTrace.newestStep();
        cursor = target >= head ? -1 : target;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);
        body.put("threadId", DapSession.THREAD_ID);
        body.put("allThreadsStopped", true);
        if (cursor != -1) {
            body.put("description", "Time travel: " + (head - cursor) + " step(s) behind");
        }
        events.accept("stopped", body);
    }
}
