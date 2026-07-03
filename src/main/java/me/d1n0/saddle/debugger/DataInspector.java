package me.d1n0.saddle.debugger;

import com.mojang.brigadier.StringReader;

import net.minecraft.commands.arguments.NbtPathArgument;
import net.minecraft.commands.arguments.NbtTagArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.commands.data.BlockDataAccessor;
import net.minecraft.server.commands.data.EntityDataAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.CommandStorage;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;

import java.util.List;
import java.util.UUID;

/**
 * Read/write access to the game data a datapack author debugs against:
 * scoreboards, command storage, entity NBT and block(-entity) NBT — the
 * debugger's equivalent of registers and memory. All methods must run on the
 * server thread; while execution is suspended they are dispatched through the
 * pause task queue.
 */
public final class DataInspector {

    /** NBT target addressed like the vanilla /data command. */
    public interface NbtTarget {
        CompoundTag read() throws Exception;

        void write(CompoundTag tag) throws Exception;

        String describe();

        /** Called with the live root just before an in-place mutation. */
        default void noteBeforeMutation(CompoundTag root) {}
    }

    private DataInspector() {}

    // ------------------------------------------------------------------
    // Scoreboard
    // ------------------------------------------------------------------

    public static int setScore(String objectiveName, String holder, int value) {
        Scoreboard scoreboard = DebugSession.server().getScoreboard();
        Objective objective = scoreboard.getObjective(objectiveName);
        if (objective == null) {
            throw new IllegalArgumentException("Unknown objective: " + objectiveName);
        }
        scoreboard.getOrCreatePlayerScore(ScoreHolder.forNameOnly(holder), objective).set(value);
        return value;
    }

    // ------------------------------------------------------------------
    // NBT targets
    // ------------------------------------------------------------------

    public static NbtTarget storageTarget(Identifier id) {
        return new NbtTarget() {
            @Override
            public CompoundTag read() {
                return DebugSession.server().getCommandStorage().get(id);
            }

            @Override
            public void write(CompoundTag tag) {
                DebugSession.server().getCommandStorage().set(id, tag);
            }

            @Override
            public String describe() {
                return "storage " + id;
            }

            @Override
            public void noteBeforeMutation(CompoundTag root) {
                // CommandStorage.get returns the live tag; preserve the true
                // before-value for the time-travel recording.
                TtdTrace.noteStorageBefore(id, root.copy());
            }
        };
    }

    public static NbtTarget entityTarget(UUID uuid) {
        return new NbtTarget() {
            private Entity entity() {
                Entity entity = DebugSession.server().overworld().getEntityInAnyDimension(uuid);
                if (entity == null) throw new IllegalArgumentException("Entity not found: " + uuid);
                return entity;
            }

            @Override
            public CompoundTag read() {
                return new EntityDataAccessor(entity()).getData();
            }

            @Override
            public void write(CompoundTag tag) throws Exception {
                new EntityDataAccessor(entity()).setData(tag);
            }

            @Override
            public String describe() {
                return "entity " + uuid;
            }
        };
    }

    public static NbtTarget blockTarget(ServerLevel level, BlockPos pos) {
        return new NbtTarget() {
            private BlockEntity blockEntity() {
                BlockEntity be = level.getBlockEntity(pos);
                if (be == null) {
                    throw new IllegalArgumentException("No block entity at " + pos.toShortString());
                }
                return be;
            }

            @Override
            public CompoundTag read() {
                return new BlockDataAccessor(blockEntity(), pos).getData();
            }

            @Override
            public void write(CompoundTag tag) {
                new BlockDataAccessor(blockEntity(), pos).setData(tag);
            }

            @Override
            public String describe() {
                return "block " + pos.toShortString();
            }
        };
    }

    /**
     * Resolves a target from the custom-request form:
     * type = "storage"|"entity"|"block", target = storage id / entity UUID /
     * "x y z[ dimension]".
     */
    public static NbtTarget resolveTarget(String type, String target) {
        if (target == null || target.isEmpty()) {
            throw new IllegalArgumentException("Missing target");
        }
        return switch (type == null ? "" : type) {
            case "storage" -> storageTarget(Identifier.parse(target));
            case "entity" -> entityTarget(UUID.fromString(target));
            case "block" -> {
                String[] parts = target.trim().split("\\s+");
                if (parts.length < 3) {
                    throw new IllegalArgumentException("Block target must be \"x y z [dimension]\"");
                }
                BlockPos pos = new BlockPos(Integer.parseInt(parts[0]),
                        Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                yield blockTarget(resolveLevel(parts.length > 3 ? parts[3] : null), pos);
            }
            default -> throw new IllegalArgumentException("Unknown data type: " + type);
        };
    }

    public static Tag getData(NbtTarget target, String path) throws Exception {
        CompoundTag root = target.read();
        if (path == null || path.isEmpty()) return root;
        List<Tag> tags = NbtPathArgument.NbtPath.of(path).get(root);
        return tags.size() == 1 ? tags.getFirst() : listToString(tags);
    }

    public static Tag setData(NbtTarget target, String path, String snbtValue) throws Exception {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("A non-empty NBT path is required for writes");
        }
        Tag value = parseSnbt(snbtValue);
        CompoundTag root = target.read();
        target.noteBeforeMutation(root);
        NbtPathArgument.NbtPath.of(path).set(root, value);
        target.write(root);
        return value;
    }

    public static Tag parseSnbt(String value) throws Exception {
        return NbtTagArgument.nbtTag().parse(new StringReader(value));
    }

    // ------------------------------------------------------------------
    // Blocks
    // ------------------------------------------------------------------

    public static String describeBlockState(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.toString();
    }

    public static ServerLevel resolveLevel(String dimension) {
        MinecraftServer server = DebugSession.server();
        if (dimension == null || dimension.isEmpty()) return server.overworld();
        ServerLevel level = server.getLevel(
                ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension)));
        if (level == null) throw new IllegalArgumentException("Unknown dimension: " + dimension);
        return level;
    }

    private static Tag listToString(List<Tag> tags) {
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        list.addAll(tags);
        return list;
    }
}
