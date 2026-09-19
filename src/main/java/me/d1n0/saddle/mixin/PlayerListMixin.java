package me.d1n0.saddle.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

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

    /**
     * Marks the broadcast for its whole duration so per-player deliveries are
     * not mirrored twice; try/finally keeps the marker from sticking when a
     * broadcast throws, which would silence per-player mirroring for good.
     */
    @WrapMethod(method = "broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Ljava/util/function/Function;Z)V")
    private void saddle$captureSystemBroadcast(Component message, Function<ServerPlayer, Component> perPlayer,
            boolean bypassHiddenChat, Operation<Void> original) {
        if (DebugSession.armed()) DebugSession.emitOutput(message.getString());
        ChatBridge.beginBroadcast();
        try {
            original.call(message, perPlayer, bypassHiddenChat);
        } finally {
            ChatBridge.endBroadcast();
        }
    }

    @Inject(method = "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Ljava/util/function/Predicate;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V",
            at = @At("HEAD"))
    private void saddle$captureChatBroadcast(PlayerChatMessage message, Predicate<ServerPlayer> filter,
            ServerPlayer sender, ChatType.Bound boundType, CallbackInfo ci) {
        if (DebugSession.armed()) {
            DebugSession.emitOutput(boundType.decorate(message.decoratedContent()).getString());
        }
    }
}
