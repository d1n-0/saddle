package me.d1n0.saddle.debugger;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.ContextChain;

import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.execution.ExecutionContext;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.profiling.InactiveProfiler;

import java.util.ArrayList;
import java.util.List;

/**
 * Executes a command with captured output on behalf of the DAP client.
 * Must run on the server thread (see {@link DebugSession#callOnServerThread}).
 */
public final class CommandRunner {
    // Matches the vanilla defaults for maxCommandChainLength / maxCommandForkCount.
    private static final int COMMAND_LIMIT = 65536;

    public record Result(boolean success, int value, String output) {}

    private CommandRunner() {}

    public static Result run(String command) {
        MinecraftServer server = DebugSession.server();
        List<String> output = new ArrayList<>();
        boolean[] success = {false};
        int[] value = {0};

        CommandSource capture = new CommandSource() {
            @Override
            public void sendSystemMessage(Component component) {
                output.add(component.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        CommandResultCallback callback = (ok, result) -> {
            success[0] = ok;
            value[0] = result;
        };
        CommandSourceStack source = server.createCommandSourceStack()
                .withSource(capture)
                .withCallback(callback);
        String cmd = Commands.trimOptionalPrefix(command.strip());

        try {
            if (DebugSession.suspendedOnThisThread()) {
                // The vanilla execution context is parked on this thread; run
                // the command in an isolated context instead of enqueueing it
                // into the suspended one.
                CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
                ParseResults<CommandSourceStack> parse = dispatcher.parse(cmd, source);
                Commands.validateParseResults(parse);
                ContextChain<CommandSourceStack> chain = ContextChain.tryFlatten(parse.getContext().build(cmd))
                        .orElseThrow(() -> new IllegalArgumentException("Unknown or incomplete command"));
                try (ExecutionContext<CommandSourceStack> context =
                        new ExecutionContext<>(COMMAND_LIMIT, COMMAND_LIMIT, InactiveProfiler.INSTANCE)) {
                    ExecutionContext.queueInitialCommandExecution(context, cmd, chain, source, callback);
                    context.runCommandQueue();
                }
            } else {
                server.getCommands().performPrefixedCommand(source, cmd);
            }
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : e.toString();
            output.add(message);
        }
        return new Result(success[0], value[0], String.join("\n", output));
    }
}
