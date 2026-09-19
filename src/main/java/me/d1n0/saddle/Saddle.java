package me.d1n0.saddle;

import me.d1n0.saddle.dap.DapServer;
import me.d1n0.saddle.debugger.DatapackReloader;
import me.d1n0.saddle.debugger.DebugSession;
import me.d1n0.saddle.debugger.FunctionIndex;
import me.d1n0.saddle.deploy.DeployCommand;
import me.d1n0.saddle.deploy.WorldDeployer;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.world.level.storage.LevelResource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Saddle implements ModInitializer {
	public static final String MOD_ID = "saddle";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** The mod version, reported to DAP clients for compatibility warnings. */
	public static String version() {
		String version = net.fabricmc.loader.api.FabricLoader.getInstance()
				.getModContainer(MOD_ID)
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("unknown");
		// IDE builds copy fabric.mod.json without Gradle's processResources
		// expansion, leaving the literal placeholder; report "dev" so clients
		// skip the mismatch warning instead of comparing against "${version}".
		return version.contains("${") ? "dev" : version;
	}

	@Override
	public void onInitialize() {
		String host = System.getProperty("saddle.host", "127.0.0.1");
		int port = Integer.getInteger("saddle.port", 16352);

		CommandRegistrationCallback.EVENT.register(
				(dispatcher, registryAccess, environment) -> DeployCommand.register(dispatcher));

		ServerLifecycleEvents.SERVER_STARTING.register(server -> WorldDeployer.onServerStarting());

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			DebugSession.bind(server);
			DapServer.start(host, port);
		});

		ServerLifecycleEvents.START_DATA_PACK_RELOAD.register(
				(server, resourceManager) -> FunctionIndex.beginReload());

		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
			if (success) {
				DatapackReloader.commit(server);
			} else {
				FunctionIndex.discardReload();
			}
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			DebugSession.shutdown();
			DapServer.stop();
		});

		// Runs after the final save, so an in-place /deploy removes player
		// data the shutdown itself just wrote.
		ServerLifecycleEvents.SERVER_STOPPED.register(
				server -> WorldDeployer.onServerStopped(server.getWorldPath(LevelResource.ROOT)));
	}
}
