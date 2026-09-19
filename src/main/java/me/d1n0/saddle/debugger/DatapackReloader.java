package me.d1n0.saddle.debugger;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.repository.PackRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Reloads datapacks on behalf of the DAP client (reload on save), with the
 * same pack selection as vanilla {@code /reload}: the current packs plus any
 * newly discovered pack the world has not disabled.
 */
public final class DatapackReloader {

    private DatapackReloader() {}

    /** Reloads datapacks; completes when the new resources are live. */
    public static CompletableFuture<Void> reload() {
        return DebugSession.callOnServerThread(() -> {
            // Suspended at a breakpoint the vanilla execution context is
            // parked mid-function; swapping the function library under it is
            // unsafe, so the reload has to wait for the user to continue.
            if (DebugSession.suspendedOnThisThread()) {
                throw new IllegalStateException(
                        "Execution is stopped at a breakpoint; continue before reloading datapacks");
            }
            MinecraftServer server = DebugSession.server();
            PackRepository packs = server.getPackRepository();
            List<String> selected = new ArrayList<>(packs.getSelectedIds());
            packs.reload();
            Collection<String> disabled = server.getWorldData().getDataConfiguration().dataPacks().getDisabled();
            for (String id : packs.getAvailableIds()) {
                if (!disabled.contains(id) && !selected.contains(id)) selected.add(id);
            }
            // On the server thread reloadResources blocks until the reload
            // finishes, so the returned future is already complete; #load
            // functions run inside it and may themselves hit breakpoints.
            // Fabric's END_DATA_PACK_RELOAD only fires in a later server task,
            // so publish the new function index here: the client may run a
            // function from the reloaded files as soon as it gets the reply.
            return server.reloadResources(selected).thenRun(() -> commit(server));
        }).thenCompose(future -> future);
    }

    /**
     * Publishes the function index of a successful reload and drops
     * breakpoints of removed functions. Idempotent — runs for Saddle-started
     * reloads and again from END_DATA_PACK_RELOAD. Server thread only.
     */
    public static void commit(MinecraftServer server) {
        Set<Identifier> live = new HashSet<>();
        server.getFunctions().getFunctionNames().forEach(live::add);
        FunctionIndex.commitReload(live);
        BreakpointManager.retainAll(live);
    }
}
