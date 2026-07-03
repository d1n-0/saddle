package me.d1n0.saddle.mixin;

import me.d1n0.saddle.debugger.ChatBridge;
import me.d1n0.saddle.debugger.DebugSession;

import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Mirrors chat-visible messages to the DAP client: system broadcasts
 * (/say, deaths, joins, …) and player chat. The 2-arg broadcastSystemMessage
 * overload delegates to the Function overload hooked here.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    @Inject(method = "broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Ljava/util/function/Function;Z)V",
            at = @At("HEAD"))
    private void saddle$captureSystemBroadcast(Component message,
            Function<ServerPlayer, Component> perPlayer, boolean bypassHiddenChat, CallbackInfo ci) {
        ChatBridge.beginBroadcast();
        DebugSession.emitOutput(message.getString());
    }

    @Inject(method = "broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Ljava/util/function/Function;Z)V",
            at = @At("RETURN"))
    private void saddle$endSystemBroadcast(Component message,
            Function<ServerPlayer, Component> perPlayer, boolean bypassHiddenChat, CallbackInfo ci) {
        ChatBridge.endBroadcast();
    }

    @Inject(method = "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Ljava/util/function/Predicate;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V",
            at = @At("HEAD"))
    private void saddle$captureChatBroadcast(PlayerChatMessage message, Predicate<ServerPlayer> filter,
            ServerPlayer sender, ChatType.Bound boundType, CallbackInfo ci) {
        DebugSession.emitOutput(boundType.decorate(message.decoratedContent()).getString());
    }
}
