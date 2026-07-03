package me.d1n0.saddle.debugger;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.execution.Frame;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Core debugger state machine.
 *
 * Every parsed command entry reports here (see {@link DebugCommandAction})
 * before executing. When a breakpoint, step or pause request matches, the
 * server thread is parked inside {@link #suspendServerThread} — the whole game
 * freezes mid-command — while a task queue keeps serving work submitted via
 * {@link #callOnServerThread}, so DAP requests that need game state still run
 * on the server thread during the suspension.
 */
public final class DebugSession {
    private static final Logger LOGGER = LoggerFactory.getLogger("saddle");
    private static final int MAX_FRAMES = 64;
    private static final int MAX_DEPTH = 1024;

    public interface Listener {
        void onStopped(String reason, StackSnapshot snapshot);

        void onTerminated();

        default void onOutput(String text) {}
    }

    private static final Object LOCK = new Object();
    private static final ArrayDeque<Runnable> pausedTasks = new ArrayDeque<>();
    private static boolean suspended; // guarded by LOCK

    private static volatile MinecraftServer server;
    private static volatile Listener listener;
    private static volatile boolean shuttingDown;
    private static volatile boolean pauseRequested;
    private static volatile StepRequest step;
    private static volatile StackSnapshot snapshot;

    // Server-thread-only state.
    private static final ArrayList<StackEntry> callStack = new ArrayList<>();
    private static boolean inSuspendLoop;

    private record StackEntry(Identifier functionId, int line, int depth, Object source,
            java.util.Map<String, String> macroArgs) {}

    private DebugSession() {}

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    public static void bind(MinecraftServer srv) {
        server = srv;
        shuttingDown = false;
    }

    public static void shutdown() {
        shuttingDown = true;
        resume(null);
        Listener l = listener;
        if (l != null) {
            l.onTerminated();
        }
    }

    public static MinecraftServer server() {
        MinecraftServer srv = server;
        if (srv == null) throw new IllegalStateException("No server bound");
        return srv;
    }

    public static void attach(Listener l) {
        listener = l;
    }

    public static void detach(Listener l) {
        if (listener == l) {
            listener = null;
            pauseRequested = false;
            resume(null);
        }
    }

    // ------------------------------------------------------------------
    // Debugger controls (called from DAP threads)
    // ------------------------------------------------------------------

    public static boolean armed() {
        return listener != null && !shuttingDown;
    }

    public static boolean isSuspended() {
        synchronized (LOCK) {
            return suspended;
        }
    }

    public static StackSnapshot currentSnapshot() {
        return snapshot;
    }

    public static void requestPause() {
        pauseRequested = true;
    }

    /** Forwards a chat-visible line to the DAP client as an output event. */
    public static void emitOutput(String text) {
        Listener l = listener;
        if (l == null) return;
        try {
            l.onOutput(text);
        } catch (Throwable t) {
            LOGGER.warn("Failed to deliver output event", t);
        }
    }

    /**
     * Resumes a suspended server thread; {@code nextStep} arms the next stop.
     * A no-op while running, so a stray step request cannot arm a stop on the
     * next arbitrary command.
     */
    public static boolean resume(StepRequest nextStep) {
        synchronized (LOCK) {
            if (!suspended) return false;
            step = nextStep;
            suspended = false;
            LOCK.notifyAll();
            return true;
        }
    }

    /**
     * Runs a task on the server thread. While execution is suspended, the task
     * is handed to the parked server thread instead of the (stalled) main task
     * queue.
     */
    public static <T> CompletableFuture<T> callOnServerThread(Supplier<T> fn) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                future.complete(fn.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        };
        MinecraftServer srv = server;
        if (srv == null) {
            future.completeExceptionally(new IllegalStateException("Server is not running"));
            return future;
        }
        if (Thread.currentThread() == srv.getRunningThread()) {
            task.run();
            return future;
        }
        synchronized (LOCK) {
            if (suspended) {
                pausedTasks.add(task);
                LOCK.notifyAll();
                return future;
            }
        }
        srv.execute(task);
        return future;
    }

    // ------------------------------------------------------------------
    // Hot path (server thread, one call per executed command)
    // ------------------------------------------------------------------

    public static void onCommand(Identifier functionId, int line, Frame frame, Object source,
            java.util.Map<String, String> macroArgs) {
        MinecraftServer srv = server;
        if (srv == null || Thread.currentThread() != srv.getRunningThread() || inSuspendLoop) return;
        int depth = frame.depth();
        trackStack(depth, functionId, line, source, macroArgs);
        TtdTrace.recordStep(functionId, line, depth, source, macroArgs);
        String reason = stopReason(functionId, line, depth);
        if (reason != null) {
            suspendServerThread(reason, depth);
        }
    }

    /** True when called from a task executed inside the suspend loop. */
    static boolean suspendedOnThisThread() {
        MinecraftServer srv = server;
        return inSuspendLoop && srv != null && Thread.currentThread() == srv.getRunningThread();
    }

    private static String stopReason(Identifier functionId, int line, int depth) {
        if (pauseRequested) return "pause";
        StepRequest s = step;
        if (s != null && s.matches(depth)) return "step";
        if (BreakpointManager.hit(functionId, line)) return "breakpoint";
        return null;
    }

    private static void trackStack(int depth, Identifier functionId, int line, Object source,
            java.util.Map<String, String> macroArgs) {
        if (depth < 0 || depth > MAX_DEPTH) return;
        while (callStack.size() > depth + 1) {
            callStack.removeLast();
        }
        while (callStack.size() <= depth) {
            callStack.add(null);
        }
        callStack.set(depth, new StackEntry(functionId, line, depth, source, macroArgs));
    }

    private static void suspendServerThread(String reason, int depth) {
        Listener l = listener;
        if (l == null) return;

        pauseRequested = false;
        step = null;
        StackSnapshot snap = buildSnapshot(depth);
        snapshot = snap;
        synchronized (LOCK) {
            suspended = true;
        }
        inSuspendLoop = true;
        try {
            try {
                l.onStopped(reason, snap);
            } catch (Throwable t) {
                LOGGER.warn("Failed to deliver stopped event", t);
            }
            while (true) {
                Runnable task;
                synchronized (LOCK) {
                    if (!suspended || shuttingDown || listener == null) {
                        suspended = false;
                        break;
                    }
                    task = pausedTasks.poll();
                    if (task == null) {
                        try {
                            LOCK.wait(50L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            suspended = false;
                            break;
                        }
                        continue;
                    }
                }
                runTask(task);
            }
        } finally {
            inSuspendLoop = false;
            snapshot = null;
            // Complete tasks that raced with the resume.
            while (true) {
                Runnable task;
                synchronized (LOCK) {
                    task = pausedTasks.poll();
                }
                if (task == null) break;
                runTask(task);
            }
        }
    }

    private static void runTask(Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            LOGGER.warn("Debugger task failed", t);
        }
    }

    // ------------------------------------------------------------------
    // Snapshots
    // ------------------------------------------------------------------

    private static StackSnapshot buildSnapshot(int stoppedDepth) {
        List<StackSnapshot.Frame> frames = new ArrayList<>();
        for (int depth = Math.min(stoppedDepth, callStack.size() - 1);
                depth >= 0 && frames.size() < MAX_FRAMES; depth--) {
            StackEntry entry = callStack.get(depth);
            if (entry == null) continue;
            String entityUuid = null;
            if (entry.source instanceof CommandSourceStack css && css.getEntity() != null) {
                entityUuid = css.getEntity().getUUID().toString();
            }
            frames.add(new StackSnapshot.Frame(entry.functionId, entry.line,
                    extractVariables(entry.source), entityUuid, entry.macroArgs, entry.source));
        }
        return new StackSnapshot(stoppedDepth, List.copyOf(frames));
    }

    private static List<StackSnapshot.Variable> extractVariables(Object source) {
        List<StackSnapshot.Variable> vars = new ArrayList<>();
        try {
            if (source instanceof CommandSourceStack css) {
                Entity entity = css.getEntity();
                vars.add(new StackSnapshot.Variable("executor", entity == null ? "server" : css.getTextName()));
                if (entity != null) {
                    vars.add(new StackSnapshot.Variable("uuid", entity.getUUID().toString()));
                }
                Vec3 pos = css.getPosition();
                vars.add(new StackSnapshot.Variable("position",
                        String.format(Locale.ROOT, "%.2f %.2f %.2f", pos.x, pos.y, pos.z)));
                Vec2 rot = css.getRotation();
                vars.add(new StackSnapshot.Variable("rotation",
                        String.format(Locale.ROOT, "%.1f %.1f", rot.x, rot.y)));
                vars.add(new StackSnapshot.Variable("dimension",
                        css.getLevel().dimension().identifier().toString()));
            }
        } catch (Exception e) {
            vars.add(new StackSnapshot.Variable("error", "failed to inspect source: " + e));
        }
        return List.copyOf(vars);
    }
}
