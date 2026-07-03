package me.d1n0.saddle.mixin;

import me.d1n0.saddle.debugger.DebugSession;
import me.d1n0.saddle.debugger.TtdTrace;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.CommandStorage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records the previous content of a command storage before it is replaced. */
@Mixin(CommandStorage.class)
public abstract class CommandStorageMixin {

    @Inject(method = "set", at = @At("HEAD"))
    private void saddle$storageChanged(Identifier id, CompoundTag tag, CallbackInfo ci) {
        if (DebugSession.armed()) {
            CommandStorage self = (CommandStorage) (Object) this;
            TtdTrace.storageChanged(id, self.get(id).copy());
        }
    }
}
