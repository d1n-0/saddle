package me.d1n0.saddle.mixin;

import me.d1n0.saddle.debugger.MacroLineHolder;

import net.minecraft.commands.ExecutionCommandSource;
import net.minecraft.commands.functions.FunctionBuilder;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Tags each freshly created {@code MacroEntry} with its 1-based source line.
 * {@code addMacro} always appends the new entry last, after any plain-entry
 * migration.
 */
@Mixin(FunctionBuilder.class)
public abstract class FunctionBuilderMixin {

    @Shadow
    private List<?> macroEntries;

    @Inject(method = "addMacro", at = @At("TAIL"))
    private void saddle$tagMacroLine(String command, int lineNumber, ExecutionCommandSource<?> source, CallbackInfo ci) {
        if (macroEntries != null && !macroEntries.isEmpty()
                && macroEntries.getLast() instanceof MacroLineHolder holder) {
            holder.saddle$setLine(lineNumber);
        }
    }
}
