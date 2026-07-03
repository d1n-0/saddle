package me.d1n0.saddle.debugger;

import net.minecraft.commands.execution.ExecutionContext;
import net.minecraft.commands.execution.Frame;
import net.minecraft.commands.execution.UnboundEntryAction;
import net.minecraft.resources.Identifier;

import java.util.Map;

/**
 * Wraps a parsed function command so the debugger sees every execution.
 * Created once per command line at datapack parse time (plain lines) or per
 * cached macro instantiation ({@code $...} lines, which also carry the macro
 * argument values). When no debug client is attached the overhead is a single
 * volatile read.
 */
public final class DebugCommandAction<T> implements UnboundEntryAction<T> {
    private final UnboundEntryAction<T> delegate;
    private final Identifier functionId;
    private final int line;
    private final Map<String, String> macroArgs;

    public DebugCommandAction(UnboundEntryAction<T> delegate, Identifier functionId, int line) {
        this(delegate, functionId, line, Map.of());
    }

    public DebugCommandAction(UnboundEntryAction<T> delegate, Identifier functionId, int line,
            Map<String, String> macroArgs) {
        this.delegate = delegate;
        this.functionId = functionId;
        this.line = line;
        this.macroArgs = macroArgs;
    }

    @Override
    public void execute(T source, ExecutionContext<T> context, Frame frame) {
        if (DebugSession.armed()) {
            DebugSession.onCommand(functionId, line, frame, source, macroArgs);
        }
        delegate.execute(source, context, frame);
    }
}
