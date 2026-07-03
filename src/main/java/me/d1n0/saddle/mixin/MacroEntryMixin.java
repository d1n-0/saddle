package me.d1n0.saddle.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;

import me.d1n0.saddle.debugger.DebugCommandAction;
import me.d1n0.saddle.debugger.MacroArgsContext;
import me.d1n0.saddle.debugger.MacroLineHolder;

import net.minecraft.commands.execution.UnboundEntryAction;
import net.minecraft.resources.Identifier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Wraps the command compiled from a macro line at instantiation time, so
 * breakpoints and stack traces work on {@code $...} lines. Instantiations are
 * cached per argument set by vanilla, so the wrapper is created once per
 * distinct argument combination.
 */
@Mixin(targets = "net.minecraft.commands.functions.MacroFunction$MacroEntry")
public abstract class MacroEntryMixin implements MacroLineHolder {

    @Unique
    private int saddle$line;

    @Override
    public void saddle$setLine(int line) {
        this.saddle$line = line;
    }

    @Override
    public int saddle$getLine() {
        return this.saddle$line;
    }

    @ModifyReturnValue(method = "instantiate", at = @At("RETURN"))
    private UnboundEntryAction<?> saddle$wrapMacroCommand(UnboundEntryAction<?> original,
            @Local(argsOnly = true) Identifier functionId) {
        if (original == null || saddle$line < 1) return original;
        return new DebugCommandAction<>(original, functionId, saddle$line, MacroArgsContext.current());
    }
}
