package me.d1n0.saddle.dap;

import me.d1n0.saddle.debugger.BreakpointManager;
import me.d1n0.saddle.debugger.CommandRunner;
import me.d1n0.saddle.debugger.DebugSession;
import me.d1n0.saddle.debugger.FunctionIndex;
import me.d1n0.saddle.debugger.StackSnapshot;
import me.d1n0.saddle.debugger.StepRequest;
import me.d1n0.saddle.debugger.TtdTrace;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One DAP client connection: protocol framing, request dispatch and the live
 * (non-time-travel) stack/scope views. Requests are handled sequentially on
 * the session thread; "stopped"/"terminated"/"output" events are pushed from
 * the server thread via the {@link DebugSession.Listener} callbacks, all
 * serialized by {@code writeLock}.
 *
 * Related units: {@link TimeTravel} (history navigation), {@link VariableTree}
 * (variable nodes), {@link BreakpointRequests}, {@link CustomRequests}.
 */
final class DapSession implements DebugSession.Listener, Closeable {
    private static final Logger LOGGER = LoggerFactory.getLogger("saddle");
    static final int THREAD_ID = 1;
    private static final long EVALUATE_TIMEOUT_MS = 3000;
    private static final long INTROSPECT_TIMEOUT_MS = 5000;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Object writeLock = new Object();
    private final AtomicInteger seq = new AtomicInteger(1);
    private final VariableTree variables = new VariableTree();
    private final TimeTravel timeTravel = new TimeTravel(variables, this::sendEvent);
    /** User-pinned inspection expressions shown as the "Watched" scope. */
    private final List<String> pins = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    DapSession(Socket socket) throws IOException {
        this.socket = socket;
        socket.setTcpNoDelay(true);
        this.in = new BufferedInputStream(socket.getInputStream());
        this.out = new BufferedOutputStream(socket.getOutputStream());
    }

    void run() {
        // Prime the score shadow and attach in one server-thread task:
        // recording starts (armed) in the same tick the shadow is seeded, so
        // no score write can slip in between and record a wrong before-value.
        DebugSession.callOnServerThread(() -> {
            TtdTrace.primeScoreboard();
            if (!closed) {
                DebugSession.attach(this);
                if (closed) DebugSession.detach(this);
            }
            return null;
        });
        try {
            while (!closed) {
                Map<String, Object> message = DapProtocol.readMessage(in);
                if (message == null) break;
                if ("request".equals(message.get("type"))) {
                    handleRequest(message);
                }
            }
        } catch (IOException e) {
            if (!closed) LOGGER.info("DAP client disconnected: {}", e.toString());
        } finally {
            close();
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        DebugSession.detach(this);
        BreakpointManager.clearAll();
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------
    // DebugSession.Listener (called from the server thread)
    // ------------------------------------------------------------------

    @Override
    public void onStopped(String reason, StackSnapshot snapshot) {
        // Old frames/selectors/entities die with the previous suspension;
        // durable references (scoreboard, storage, NBT paths) survive so
        // expanded rows stay open.
        variables.pruneTransient();
        timeTravel.reset();
        sendEvent("stopped", Map.of(
                "reason", reason,
                "threadId", THREAD_ID,
                "allThreadsStopped", true));
    }

    @Override
    public void onTerminated() {
        sendEvent("terminated", Map.of());
    }

    @Override
    public void onOutput(String text) {
        sendEvent("output", Map.of("category", "stdout", "output", text + "\n"));
    }

    // ------------------------------------------------------------------
    // Request dispatch
    // ------------------------------------------------------------------

    private void handleRequest(Map<String, Object> message) {
        int requestSeq = Args.getInt(message, "seq", 0);
        String command = String.valueOf(message.get("command"));
        Map<String, Object> args = Args.getMap(message, "arguments");
        try {
            if (CustomRequests.handles(command)) {
                respond(requestSeq, command, CustomRequests.execute(command, args));
                if ("minecraft/setData".equals(command) || "minecraft/setScore".equals(command)) {
                    invalidateVariables();
                }
                return;
            }
            switch (command) {
                case "initialize" -> {
                    respond(requestSeq, command, capabilities());
                    sendEvent("initialized", Map.of());
                    // Lets the extension warn when its version drifts from the mod's.
                    sendEvent("saddle/version", Map.of("version", me.d1n0.saddle.Saddle.version()));
                }
                case "attach", "launch" -> {
                    respond(requestSeq, command, Map.of());
                    announceAttach();
                }
                case "configurationDone" -> respond(requestSeq, command, Map.of());
                case "setBreakpoints" -> respond(requestSeq, command, BreakpointRequests.setBreakpoints(args));
                case "breakpointLocations" -> respond(requestSeq, command, BreakpointRequests.breakpointLocations(args));
                case "setExceptionBreakpoints" -> respond(requestSeq, command, Map.of("breakpoints", List.of()));
                case "threads" -> respond(requestSeq, command, Map.of(
                        "threads", List.of(Map.of("id", THREAD_ID, "name", "Server thread"))));
                case "stackTrace" -> respond(requestSeq, command,
                        timeTravel.active() ? timeTravel.stackTrace() : handleStackTrace(args));
                case "scopes" -> respond(requestSeq, command, handleScopes(args));
                case "variables" -> respond(requestSeq, command, handleVariables(args));
                case "setVariable" -> respond(requestSeq, command, handleSetVariable(args));
                case "source" -> respond(requestSeq, command, handleSource(args));
                case "continue" -> {
                    respond(requestSeq, command, Map.of("allThreadsContinued", true));
                    if (!timeTravel.active() || !timeTravel.forwardContinue()) {
                        DebugSession.resume(null);
                    }
                }
                case "next" -> resumeStep(requestSeq, command, StepRequest.Mode.STEP_OVER);
                case "stepIn" -> resumeStep(requestSeq, command, StepRequest.Mode.STEP_IN);
                case "stepOut" -> resumeStep(requestSeq, command, StepRequest.Mode.STEP_OUT);
                case "stepBack" -> {
                    requireSuspended();
                    respond(requestSeq, command, Map.of());
                    timeTravel.stepBack();
                }
                case "reverseContinue" -> {
                    requireSuspended();
                    respond(requestSeq, command, Map.of());
                    timeTravel.reverseContinue();
                }
                case "pause" -> {
                    DebugSession.requestPause();
                    respond(requestSeq, command, Map.of());
                }
                case "evaluate" -> {
                    String expression = Args.requireString(args, "expression");
                    String context = Args.getString(args, "context");
                    if ("hover".equals(context) || "watch".equals(context)) {
                        respond(requestSeq, command,
                                handleInspectEvaluate(expression.trim(), Args.getInt(args, "frameId", 0)));
                    } else {
                        handleCommandEvaluate(requestSeq, command, expression);
                    }
                }
                case "completions" -> respond(requestSeq, command, handleCompletions(args));
                case "disconnect" -> {
                    respond(requestSeq, command, Map.of());
                    close();
                }
                case "saddle/pin" -> {
                    String expression = Args.requireString(args, "expression").trim();
                    if (!pins.contains(expression)) pins.add(expression);
                    respond(requestSeq, command, Map.of("pins", List.copyOf(pins)));
                    invalidateVariables();
                }
                case "saddle/unpin" -> {
                    String expression = Args.getString(args, "expression");
                    pins.remove(expression == null ? "" : expression.trim());
                    respond(requestSeq, command, Map.of("pins", List.copyOf(pins)));
                    invalidateVariables();
                }
                case "saddle/pins" -> respond(requestSeq, command, Map.of("pins", List.copyOf(pins)));
                // Stateless live inspection for the extension's watch panel:
                // works while the game is running, no variablesReferences.
                case "saddle/live" -> {
                    String expression = Args.requireString(args, "expression");
                    List<String> path = argPath(args);
                    StackSnapshot.Frame frame = innermostFrame();
                    respond(requestSeq, command, onServerThread(
                            () -> VariableTree.resolveLive(expression, path, frame)));
                }
                case "saddle/liveSet" -> {
                    String expression = Args.requireString(args, "expression");
                    List<String> path = argPath(args);
                    String name = Args.requireString(args, "name");
                    String value = Args.requireString(args, "value");
                    StackSnapshot.Frame frame = innermostFrame();
                    String newValue = onServerThread(
                            () -> VariableTree.resolveLiveSet(expression, path, name, value, frame));
                    respond(requestSeq, command, Map.of("value", newValue));
                    invalidateVariables();
                }
                default -> respondError(requestSeq, command, "Unsupported request: " + command);
            }
        } catch (Exception e) {
            LOGGER.warn("DAP request '{}' failed", command, e);
            try {
                respondError(requestSeq, command, e.getMessage() != null ? e.getMessage() : e.toString());
            } catch (IOException ignored) {
            }
        }
    }

    private static Map<String, Object> capabilities() {
        return Map.of(
                "supportsConfigurationDoneRequest", true,
                "supportsBreakpointLocationsRequest", true,
                "supportsEvaluateForHovers", true,
                "supportsSetVariable", true,
                "supportsStepBack", true,
                "supportsCompletionsRequest", true,
                "completionTriggerCharacters", List.of(" ", "/", ":", "@", "="),
                "supportsTerminateRequest", false,
                "exceptionBreakpointFilters", List.of());
    }

    private void resumeStep(int requestSeq, String command, StepRequest.Mode mode) throws IOException {
        requireSuspended();
        if (timeTravel.active()) {
            respond(requestSeq, command, Map.of());
            timeTravel.forwardStep(mode);
            return;
        }
        StackSnapshot snapshot = DebugSession.currentSnapshot();
        int baseDepth = snapshot != null ? snapshot.stoppedDepth() : 0;
        respond(requestSeq, command, Map.of());
        DebugSession.resume(new StepRequest(mode, baseDepth));
    }

    private static void requireSuspended() {
        if (!DebugSession.isSuspended()) {
            throw new IllegalStateException("Execution is not stopped");
        }
    }

    private static List<String> argPath(Map<String, Object> args) {
        return args.get("path") instanceof List<?> l
                ? l.stream().map(String::valueOf).toList() : List.of();
    }

    private static StackSnapshot.Frame innermostFrame() {
        StackSnapshot snapshot = DebugSession.currentSnapshot();
        return snapshot != null && !snapshot.frames().isEmpty()
                ? snapshot.frames().getFirst() : null;
    }

    // ------------------------------------------------------------------
    // Stack, scopes, variables, source (live views)
    // ------------------------------------------------------------------

    private Map<String, Object> handleStackTrace(Map<String, Object> args) {
        StackSnapshot snapshot = DebugSession.currentSnapshot();
        if (snapshot == null) {
            return Map.of("stackFrames", List.of(), "totalFrames", 0);
        }
        List<StackSnapshot.Frame> frames = snapshot.frames();
        int start = Args.getInt(args, "startFrame", 0);
        int levels = Args.getInt(args, "levels", 0);
        int end = levels > 0 ? Math.min(frames.size(), start + levels) : frames.size();

        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = start; i < end; i++) {
            StackSnapshot.Frame frame = frames.get(i);
            Map<String, Object> dapFrame = new LinkedHashMap<>();
            dapFrame.put("id", i + 1);
            dapFrame.put("name", frame.functionId().toString());
            dapFrame.put("line", frame.line());
            dapFrame.put("column", 1);
            dapFrame.put("source", describeSource(frame.functionId()));
            result.add(dapFrame);
        }
        return Map.of("stackFrames", result, "totalFrames", frames.size());
    }

    /** DAP Source for a function: the known client file, else a sourceReference. */
    static Map<String, Object> describeSource(Identifier functionId) {
        Map<String, Object> source = new LinkedHashMap<>();
        String path = FunctionIndex.clientPath(functionId);
        if (path != null) {
            String normalized = path.replace('\\', '/');
            source.put("name", normalized.substring(normalized.lastIndexOf('/') + 1));
            source.put("path", path);
        } else {
            source.put("name", functionId.toString());
            source.put("sourceReference", FunctionIndex.sourceReference(functionId));
        }
        return source;
    }

    private Map<String, Object> handleScopes(Map<String, Object> args) {
        int frameId = Args.getInt(args, "frameId", 0);
        if (timeTravel.active()) {
            return timeTravel.scopes(frameId);
        }
        StackSnapshot snapshot = DebugSession.currentSnapshot();
        if (snapshot == null || frameId < 1 || frameId > snapshot.frames().size()) {
            return Map.of("scopes", List.of());
        }
        StackSnapshot.Frame frame = snapshot.frames().get(frameId - 1);
        List<Map<String, Object>> scopes = new ArrayList<>();
        scopes.add(scope("Executor", new VariableTree.ExecutorNode(frame), false));
        if (!frame.macroArgs().isEmpty()) {
            scopes.add(scope("Macro Arguments", new VariableTree.MacroArgsNode(frame.macroArgs()), false));
        }
        scopes.add(scope("Command", new VariableTree.CommandNode(frame), false));
        if (!pins.isEmpty()) {
            scopes.add(scope("Watched", new VariableTree.WatchedNode(List.copyOf(pins), frame), false));
        }
        scopes.add(scope("Scoreboard", new VariableTree.ScoreboardNode(), true));
        scopes.add(scope("Storage", new VariableTree.StorageRootNode(), true));
        return Map.of("scopes", scopes);
    }

    private Map<String, Object> scope(String name, VariableTree.Node node, boolean expensive) {
        return Map.of(
                "name", name,
                "variablesReference", variables.register(node),
                "expensive", expensive);
    }

    private Map<String, Object> handleVariables(Map<String, Object> args) throws Exception {
        VariableTree.Node node = variables.get(Args.getInt(args, "variablesReference", 0));
        if (node == null) {
            return Map.of("variables", List.of());
        }
        List<Map<String, Object>> children = onServerThread(() -> node.children(variables));
        return Map.of("variables", children);
    }

    private Map<String, Object> handleSetVariable(Map<String, Object> args) throws Exception {
        VariableTree.Node node = variables.get(Args.getInt(args, "variablesReference", 0));
        String name = Args.getString(args, "name");
        String value = Args.getString(args, "value");
        if (node == null || name == null || value == null) {
            throw new IllegalArgumentException("Unknown variable container");
        }
        String newValue = onServerThread(() -> node.setChild(name, value));
        // Refresh stale previews of parent containers and sibling views.
        invalidateVariables();
        return Map.of("value", newValue, "variablesReference", 0);
    }

    private Map<String, Object> handleSource(Map<String, Object> args) throws IOException {
        int ref = Args.getInt(args, "sourceReference", 0);
        if (ref == 0) {
            ref = Args.getInt(Args.getMap(args, "source"), "sourceReference", 0);
        }
        Identifier id = FunctionIndex.byReference(ref);
        String content = id != null ? FunctionIndex.content(id) : null;
        if (content == null) throw new IOException("Unknown sourceReference: " + ref);
        return Map.of("content", content, "mimeType", "text/plain");
    }

    // ------------------------------------------------------------------
    // Evaluate & completions
    // ------------------------------------------------------------------

    /**
     * Console evaluation responds asynchronously: a command that hits a
     * breakpoint never completes its future, and blocking the session thread
     * on it would stall the stackTrace/scopes requests VS Code sends right
     * after the stopped event — the debugger would feel seconds slower than
     * it is.
     */
    private void handleCommandEvaluate(int requestSeq, String command, String expression) {
        DebugSession.callOnServerThread(() -> CommandRunner.run(expression))
                .orTimeout(EVALUATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .whenComplete((result, error) -> {
                    try {
                        if (error != null) {
                            Throwable cause = error.getCause() != null ? error.getCause() : error;
                            if (cause instanceof TimeoutException || error instanceof TimeoutException) {
                                respond(requestSeq, command, Map.of(
                                        "result", "(no result: the command is still running — it may have hit a breakpoint)",
                                        "variablesReference", 0));
                            } else {
                                respondError(requestSeq, command,
                                        cause.getMessage() != null ? cause.getMessage() : cause.toString());
                            }
                            return;
                        }
                        if (DebugSession.isSuspended()) {
                            // Console commands may have changed watched game state.
                            invalidateVariables();
                        }
                        String text = result.output().isEmpty()
                                ? (result.success() ? "= " + result.value() : "(command failed)")
                                : result.output();
                        respond(requestSeq, command, Map.of("result", text, "variablesReference", 0));
                    } catch (IOException ignored) {
                        // Session is closing; nothing to deliver to.
                    }
                });
    }

    /**
     * Hover/watch evaluation. Never runs commands; resolves inspection
     * expressions (macro args, selectors, coordinates, storage/entity/block
     * paths, scores) against the given frame.
     */
    private Map<String, Object> handleInspectEvaluate(String expression, int frameId) throws Exception {
        StackSnapshot snapshot = DebugSession.currentSnapshot();
        StackSnapshot.Frame frame = snapshot != null && frameId >= 1 && frameId <= snapshot.frames().size()
                ? snapshot.frames().get(frameId - 1) : null;
        Map<String, Object> resolved = onServerThread(
                () -> VariableTree.resolveExpression(variables, expression, frame));
        return Map.of(
                "result", String.valueOf(resolved.get("value")),
                "variablesReference", resolved.getOrDefault("variablesReference", 0));
    }

    private Map<String, Object> handleCompletions(Map<String, Object> args) throws Exception {
        String text = Args.getString(args, "text");
        if (text == null) return Map.of("targets", List.of());
        int column = Args.getInt(args, "column", text.length() + 1);
        boolean slash = text.startsWith("/");
        String command = slash ? text.substring(1) : text;
        int cursor = Math.max(0, Math.min(column - 1 - (slash ? 1 : 0), command.length()));

        List<Map<String, Object>> targets = onServerThread(() -> {
            var server = DebugSession.server();
            var dispatcher = server.getCommands().getDispatcher();
            var parse = dispatcher.parse(command, server.createCommandSourceStack());
            // Vanilla suggestion providers complete synchronously on the
            // server thread; anything still pending is dropped.
            var suggestions = dispatcher.getCompletionSuggestions(parse, cursor).getNow(null);
            List<Map<String, Object>> result = new ArrayList<>();
            if (suggestions != null) {
                for (var suggestion : suggestions.getList()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("label", suggestion.getText());
                    item.put("start", suggestion.getRange().getStart() + 1 + (slash ? 1 : 0));
                    item.put("length", suggestion.getRange().getLength());
                    result.add(item);
                }
            }
            return result;
        });
        return Map.of("targets", targets);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private interface GameCall<T> {
        T get() throws Exception;
    }

    /** Runs on the server thread (pause-queue aware) with a bounded wait. */
    private <T> T onServerThread(GameCall<T> call) throws Exception {
        return DebugSession.<T>callOnServerThread(() -> {
            try {
                return call.get();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }).get(INTROSPECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /** Tells the client to re-fetch the Variables view after a state change. */
    private void invalidateVariables() {
        sendEvent("invalidated", Map.of("areas", List.of("variables"), "threadId", THREAD_ID));
    }

    /**
     * Says which world this session reached, in both the Debug Console and
     * the in-game chat — several instances on one machine (e.g. a dev server
     * plus a singleplayer world) would otherwise silently fight over the DAP
     * port, and edits would land in the wrong world.
     */
    private void announceAttach() {
        DebugSession.callOnServerThread(() -> {
            var server = DebugSession.server();
            String identity = "world '" + server.getWorldData().getLevelName() + "'"
                    + (server.isSingleplayer() ? " (singleplayer)" : " (dedicated)");
            onOutput("Saddle: debugger attached to " + identity);
            server.getPlayerList().broadcastSystemMessage(
                    net.minecraft.network.chat.Component.literal("[Saddle] Debugger attached"), false);
            return null;
        });
    }

    // ------------------------------------------------------------------
    // Wire helpers
    // ------------------------------------------------------------------

    private void respond(int requestSeq, String command, Map<String, Object> body) throws IOException {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "response");
        response.put("seq", seq.getAndIncrement());
        response.put("request_seq", requestSeq);
        response.put("success", true);
        response.put("command", command);
        response.put("body", body);
        write(response);
    }

    private void respondError(int requestSeq, String command, String message) throws IOException {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "response");
        response.put("seq", seq.getAndIncrement());
        response.put("request_seq", requestSeq);
        response.put("success", false);
        response.put("command", command);
        response.put("message", message);
        response.put("body", Map.of());
        write(response);
    }

    private void sendEvent(String event, Map<String, Object> body) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "event");
        message.put("seq", seq.getAndIncrement());
        message.put("event", event);
        message.put("body", body);
        try {
            write(message);
        } catch (IOException e) {
            if (!closed) LOGGER.warn("Failed to send DAP event '{}': {}", event, e.toString());
        }
    }

    private void write(Map<String, Object> message) throws IOException {
        synchronized (writeLock) {
            DapProtocol.writeMessage(out, message);
        }
    }
}
