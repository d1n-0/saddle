package me.d1n0.saddle.deploy;

import me.d1n0.saddle.debugger.DebugSession;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.action.CustomAll;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.dialog.input.TextInput;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * {@code /deploy} (permission level 4): saves the world as a distributable
 * map — builds, command blocks, entities, scoreboards, storage, datapacks and
 * world settings stay; player data goes.
 *
 * <ul>
 *   <li>{@code /deploy} — opens a dialog to pick the mode (players only); its
 *       buttons send custom click actions, see {@link #handleDialogAction}</li>
 *   <li>{@code /deploy copy [path]} — writes a cleaned copy; this world keeps running</li>
 *   <li>{@code /deploy apply} — cleans this world itself, which closes it: the
 *       game saves online players on shutdown, so the cleanup runs after that</li>
 * </ul>
 */
public final class DeployCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger("saddle");
    private static final int PATH_MAX_LENGTH = 200;
    private static final Identifier COPY_ACTION = Identifier.fromNamespaceAndPath("saddle", "deploy/copy");
    private static final Identifier APPLY_ACTION = Identifier.fromNamespaceAndPath("saddle", "deploy/apply");
    private static final SimpleCommandExceptionType SUSPENDED = new SimpleCommandExceptionType(Component.literal(
            "Execution is stopped at a breakpoint; continue in the debugger before deploying"));

    /**
     * Leaves the singleplayer world on the client ("Save and Quit to Title").
     * Installed by the client entrypoint; a dedicated server halts instead.
     */
    private static volatile Runnable localWorldCloser;

    private DeployCommand() {}

    public static void setLocalWorldCloser(Runnable closer) {
        localWorldCloser = closer;
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("deploy")
                .requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
                .executes(c -> openDialog(c.getSource()))
                .then(Commands.literal("apply")
                        .executes(c -> applyInPlace(c.getSource())))
                .then(Commands.literal("copy")
                        .executes(c -> copy(c.getSource(), ""))
                        .then(Commands.argument("path", StringArgumentType.greedyString())
                                .executes(c -> copy(c.getSource(), StringArgumentType.getString(c, "path"))))));
    }

    private static int openDialog(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        Path worldDir = worldDir(source.getServer());
        if (player == null) {
            // Consoles (server console, rcon, the Debug Console) cannot show dialogs.
            source.sendSuccess(() -> Component.literal(
                    "Usage: /deploy copy [path] — write a copy without player data (default: "
                            + WorldDeployer.defaultCopyTarget(worldDir) + ")\n"
                            + "       /deploy apply — remove player data from this world and close it"), false);
            return 0;
        }
        player.openDialog(Holder.direct(buildDialog(worldDir)));
        return 1;
    }

    private static MultiActionDialog buildDialog(Path worldDir) {
        String defaultPath = WorldDeployer.defaultCopyTarget(worldDir).getFileName().toString();
        CommonDialogData common = new CommonDialogData(
                Component.literal("Deploy World"),
                Optional.empty(),
                true,
                true,
                DialogAction.CLOSE,
                List.of(
                        new PlainMessage(Component.literal(
                                "Saves this world as a distributable map. Builds, command blocks, entities, "
                                        + "scoreboards, storage, datapacks and world settings are kept; player data "
                                        + "(inventories, positions, advancements, statistics, the singleplayer "
                                        + "owner and the last played time) is removed."), 300),
                        new PlainMessage(Component.literal(
                                "Relative destinations are created next to this world's folder."), 300)),
                List.of(new Input("path", new TextInput(300, Component.literal("Copy destination"), true,
                        defaultPath, PATH_MAX_LENGTH, Optional.empty()))));
        ActionButton copy = new ActionButton(
                new CommonButtonData(Component.literal("Create Copy"), Optional.of(Component.literal(
                        "Write the deployed world to the destination. This world stays open and unchanged.")), 150),
                Optional.of(new CustomAll(COPY_ACTION, Optional.empty())));
        ActionButton apply = new ActionButton(
                new CommonButtonData(Component.literal("Apply to This World"), Optional.of(Component.literal(
                        "Remove player data from this world itself. The world closes to finish.")), 150),
                Optional.of(new CustomAll(APPLY_ACTION, Optional.empty())));
        ActionButton cancel = new ActionButton(
                new CommonButtonData(Component.literal("Cancel"), 150), Optional.empty());
        return new MultiActionDialog(common, List.of(copy, apply), Optional.of(cancel), 2);
    }

    /**
     * Handles the dialog buttons. They send custom click actions rather than
     * run_command: the client asks for confirmation before running any
     * command that needs operator permissions, which would put a second
     * prompt behind the dialog. Any client can send these packets, so the
     * permission check the command tree would do happens here. Returns
     * whether {@code id} is a deploy action. Server thread only.
     */
    public static boolean handleDialogAction(ServerPlayer player, Identifier id, Optional<Tag> payload) {
        boolean isCopy = COPY_ACTION.equals(id);
        if (!isCopy && !APPLY_ACTION.equals(id)) return false;
        CommandSourceStack source = player.createCommandSourceStack();
        if (!Commands.LEVEL_OWNERS.check(source.permissions())) {
            LOGGER.warn("Ignoring deploy dialog action from {}: permission level 4 required",
                    player.getName().getString());
            return true;
        }
        try {
            if (isCopy) {
                // CustomAll sends every dialog input in the payload compound.
                String path = payload.orElse(null) instanceof CompoundTag tag ? tag.getStringOr("path", "") : "";
                if (path.length() > PATH_MAX_LENGTH) {
                    source.sendFailure(Component.literal("The destination path is too long"));
                    return true;
                }
                copy(source, path);
            } else {
                applyInPlace(source);
            }
        } catch (CommandSyntaxException e) {
            source.sendFailure(Component.literal(e.getMessage()));
        }
        return true;
    }

    private static int copy(CommandSourceStack source, String rawPath) throws CommandSyntaxException {
        requireRunning();
        MinecraftServer server = source.getServer();
        Path worldDir = worldDir(server);
        Path target;
        try {
            target = WorldDeployer.resolveCopyTarget(worldDir, rawPath, !isHostOperator(source));
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Deploying world to " + target + " ..."), true);
        // Flush everything to disk and copy synchronously on the server
        // thread: nothing (autosave, chunk unloads, saved data) can write to
        // the world folder mid-copy, so the copy is a consistent snapshot.
        if (!server.saveEverything(true, true, true)) {
            source.sendFailure(Component.literal("Deploy failed: the world could not be saved"));
            return 0;
        }
        try {
            WorldDeployer.CopyResult result = WorldDeployer.copyWithoutPlayerData(worldDir, target);
            source.sendSuccess(() -> Component.literal("World deployed to " + result.target()
                    + " (" + result.files() + " files, player data removed)"), true);
            return 1;
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Failed to deploy world to {}", target, e);
            source.sendFailure(Component.literal("Deploy failed: " + e));
            return 0;
        }
    }

    private static int applyInPlace(CommandSourceStack source) throws CommandSyntaxException {
        requireRunning();
        MinecraftServer server = source.getServer();
        // Closing a singleplayer world is the owner's call; LAN guests with
        // cheats have level 4 too but cannot otherwise stop the host's game.
        // (Dedicated server operators at this level can already /stop.)
        if (!server.isDedicatedServer() && !isHostOperator(source)) {
            source.sendFailure(Component.literal("Only the singleplayer owner can deploy this world in place"));
            return 0;
        }
        WorldDeployer.scheduleInPlace(worldDir(server));
        source.sendSuccess(() -> Component.literal(
                "Deploying this world: closing it to remove player data after the final save"), true);
        Runnable closer = localWorldCloser;
        if (!server.isDedicatedServer() && closer != null) {
            closer.run();
        } else {
            server.halt(false);
        }
        return 1;
    }

    /**
     * Sources that already control the host machine: the server console (and
     * rcon or the Debug Console, which run as it) and the singleplayer owner.
     */
    private static boolean isHostOperator(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player == null || source.getServer().isSingleplayerOwner(player.nameAndId());
    }

    private static void requireRunning() throws CommandSyntaxException {
        if (DebugSession.isSuspended()) throw SUSPENDED.create();
    }

    private static Path worldDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
    }
}
