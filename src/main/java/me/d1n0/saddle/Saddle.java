package me.d1n0.saddle;

import me.d1n0.saddle.dap.DapServer;
import me.d1n0.saddle.debugger.BreakpointManager;
import me.d1n0.saddle.debugger.DebugSession;
import me.d1n0.saddle.debugger.FunctionIndex;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

public class Saddle implements ModInitializer {
	public static final String MOD_ID = "saddle";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		String host = System.getProperty("saddle.host", "127.0.0.1");
		int port = Integer.getInteger("saddle.port", 16352);

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			DebugSession.bind(server);
			DapServer.start(host, port);
		});

		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
			if (success) pruneRemovedFunctions(server);
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			DebugSession.shutdown();
			DapServer.stop();
		});
	}

	private static void pruneRemovedFunctions(MinecraftServer server) {
		Set<Identifier> live = new HashSet<>();
		server.getFunctions().getFunctionNames().forEach(live::add);
		FunctionIndex.retainAll(live);
		BreakpointManager.retainAll(live);
	}
}
