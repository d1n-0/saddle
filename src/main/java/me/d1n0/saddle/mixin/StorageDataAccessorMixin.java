package me.d1n0.saddle.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import me.d1n0.saddle.debugger.DebugSession;
import me.d1n0.saddle.debugger.TtdTrace;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.commands.data.StorageDataAccessor;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla /data mutates the live tag returned by getData before calling
 * setData, so the time-travel before-value must be copied at read time.
 */
@Mixin(StorageDataAccessor.class)
public abstract class StorageDataAccessorMixin {

    @Shadow
    @Final
    private Identifier id;

    @ModifyReturnValue(method = "getData", at = @At("RETURN"))
    private CompoundTag saddle$noteBefore(CompoundTag original) {
        if (DebugSession.armed() && original != null) {
            TtdTrace.noteStorageBefore(id, original.copy());
        }
        return original;
    }
}
