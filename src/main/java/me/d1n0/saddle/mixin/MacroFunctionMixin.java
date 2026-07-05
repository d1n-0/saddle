package me.d1n0.saddle.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.brigadier.CommandDispatcher;

import me.d1n0.saddle.debugger.MacroArgsContext;

import net.minecraft.commands.functions.InstantiatedFunction;
import net.minecraft.commands.functions.MacroFunction;
import net.minecraft.nbt.CompoundTag;

import org.spongepowered.asm.mixin.Mixin;

/**
 * Publishes the macro argument compound for the duration of an instantiation
 * so {@link MacroEntryMixin} can bake the values into each line's wrapper.
 * try/finally keeps the ThreadLocal clean when instantiation throws
 * (FunctionInstantiationException on bad macro input).
 */
@Mixin(MacroFunction.class)
public abstract class MacroFunctionMixin {

    @WrapMethod(method = "instantiate")
    private InstantiatedFunction<?> saddle$argsScope(CompoundTag args, CommandDispatcher<?> dispatcher,
            Operation<InstantiatedFunction<?>> original) {
        MacroArgsContext.begin(args);
        try {
            return original.call(args, dispatcher);
        } finally {
            MacroArgsContext.end();
        }
    }
}
