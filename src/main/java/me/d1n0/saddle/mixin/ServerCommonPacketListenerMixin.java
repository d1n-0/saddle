package me.d1n0.saddle.mixin;

import me.d1n0.saddle.deploy.DeployCommand;

import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Routes the /deploy dialog's custom click actions to {@link DeployCommand}.
 * Vanilla only logs custom actions and drops the sender, so the hook sits in
 * the packet handler — after its hop onto the server thread — where the
 * player is known.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerMixin {

    @Inject(method = "handleCustomClickAction",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;handleCustomClickAction(Lnet/minecraft/resources/Identifier;Ljava/util/Optional;)V"),
            cancellable = true)
    private void saddle$handleDeployAction(ServerboundCustomClickActionPacket packet, CallbackInfo ci) {
        if ((Object) this instanceof ServerGamePacketListenerImpl game
                && DeployCommand.handleDialogAction(game.player, packet.id(), packet.payload())) {
            ci.cancel();
        }
    }
}
