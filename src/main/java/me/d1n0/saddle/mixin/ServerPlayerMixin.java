package me.d1n0.saddle.mixin;

import me.d1n0.saddle.debugger.ChatBridge;
import me.d1n0.saddle.debugger.DebugSession;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mirrors messages sent to a single player (tellraw, /msg feedback, …).
 * Broadcast deliveries are skipped — they are already captured centrally in
 * {@link PlayerListMixin}.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {

    @Inject(method = "sendSystemMessage(Lnet/minecraft/network/chat/Component;Z)V", at = @At("HEAD"))
    private void saddle$capturePlayerMessage(Component message, boolean overlay, CallbackInfo ci) {
        if (ChatBridge.isBroadcasting() || overlay || !DebugSession.armed()) return;
        ServerPlayer self = (ServerPlayer) (Object) this;
        DebugSession.emitOutput("[→ " + self.getName().getString() + "] " + message.getString());
    }
}
