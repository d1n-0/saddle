package me.d1n0.saddle;

import me.d1n0.saddle.deploy.DeployCommand;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class SaddleClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// "/deploy apply" in singleplayer: leave the world the same way
		// "Save and Quit to Title" does, so the integrated server stops cleanly
		// and the deployment finishes after its final save.
		DeployCommand.setLocalWorldCloser(() -> {
			Minecraft minecraft = Minecraft.getInstance();
			minecraft.execute(() -> minecraft.disconnectFromWorld(Component.literal("World deployed")));
		});
	}
}
