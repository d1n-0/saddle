package me.d1n0.saddle.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.brigadier.CommandDispatcher;

import me.d1n0.saddle.debugger.DebugCommandAction;
import me.d1n0.saddle.debugger.FunctionIndex;

import net.minecraft.commands.ExecutionCommandSource;
import net.minecraft.commands.execution.UnboundEntryAction;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.commands.functions.FunctionBuilder;
import net.minecraft.resources.Identifier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Instruments datapack function parsing: records the raw source text and
 * wraps every plain command entry with {@link DebugCommandAction} so the
 * debugger observes each execution with its (function, line) origin.
 *
 * The {@code @Local(ordinal = 1) int} is the 1-based source line ("j" in
 * vanilla's fromLines loop), which stays on the first line of a command that
 * uses backslash line continuations.
 */
@Mixin(CommandFunction.class)
public interface CommandFunctionMixin {

    @Inject(method = "fromLines", at = @At("HEAD"))
    private static void saddle$indexSource(Identifier id, CommandDispatcher<?> dispatcher,
            ExecutionCommandSource<?> source, List<String> lines, CallbackInfoReturnable<?> cir) {
        FunctionIndex.beginFunction(id, lines);
    }

    @WrapOperation(method = "fromLines", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/commands/functions/FunctionBuilder;addCommand(Lnet/minecraft/commands/execution/UnboundEntryAction;)V"))
    private static void saddle$wrapCommand(FunctionBuilder<?> builder, UnboundEntryAction<?> action,
            Operation<Void> original, @Local(argsOnly = true) Identifier id, @Local(ordinal = 1) int line) {
        FunctionIndex.recordBreakableLine(id, line);
        original.call(builder, new DebugCommandAction<>(action, id, line));
    }

    @Inject(method = "fromLines", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/commands/functions/FunctionBuilder;addMacro(Ljava/lang/String;ILnet/minecraft/commands/ExecutionCommandSource;)V"))
    private static void saddle$recordMacroLine(Identifier id, CommandDispatcher<?> dispatcher,
            ExecutionCommandSource<?> source, List<String> lines, CallbackInfoReturnable<?> cir,
            @Local(ordinal = 1) int line) {
        FunctionIndex.recordBreakableLine(id, line);
    }
}
