package me.d1n0.saddle.mixin;

import com.mojang.brigadier.CommandDispatcher;

import me.d1n0.saddle.debugger.MacroArgsContext;

import net.minecraft.commands.functions.MacroFunction;
import net.minecraft.nbt.CompoundTag;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Publishes the macro argument compound for the duration of an instantiation
 * so {@link MacroEntryMixin} can bake the values into each line's wrapper.
 */
@Mixin(MacroFunction.class)
public abstract class MacroFunctionMixin {

    @Inject(method = "instantiate", at = @At("HEAD"))
    private void saddle$beginArgs(CompoundTag args, CommandDispatcher<?> dispatcher,
            CallbackInfoReturnable<?> cir) {
        MacroArgsContext.begin(args);
    }

    @Inject(method = "instantiate", at = @At("RETURN"))
    private void saddle$endArgs(CompoundTag args, CommandDispatcher<?> dispatcher,
            CallbackInfoReturnable<?> cir) {
        MacroArgsContext.end();
    }
}
