package me.d1n0.saddle.debugger;

import com.mojang.brigadier.StringReader;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.NbtPathArgument;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.CommandStorage;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only game state dumps for the custom "minecraft/*" DAP requests.
 * All methods must run on the server thread.
 */
public final class MinecraftIntrospect {
    private static final int MAX_ENTITIES = 200;

    private MinecraftIntrospect() {}

    public static Map<String, Object> dumpScoreboard() {
        Scoreboard scoreboard = DebugSession.server().getScoreboard();
        List<Map<String, Object>> objectives = new ArrayList<>();
        for (Objective objective : scoreboard.getObjectives()) {
            List<Map<String, Object>> scores = new ArrayList<>();
            for (PlayerScoreEntry entry : scoreboard.listPlayerScores(objective)) {
                scores.add(Map.of("holder", entry.owner(), "value", entry.value()));
            }
            objectives.add(Map.of(
                    "name", objective.getName(),
                    "criteria", objective.getCriteria().getName(),
                    "displayName", objective.getDisplayName().getString(),
                    "scores", scores));
        }
        return Map.of("objectives", objectives);
    }

    public static Map<String, Object> dumpStorage(String id, String path) throws Exception {
        CommandStorage storage = DebugSession.server().getCommandStorage();
        if (id == null || id.isEmpty()) {
            List<String> keys = storage.keys().map(Identifier::toString).sorted().toList();
            return Map.of("keys", keys);
        }
        CompoundTag tag = storage.get(Identifier.parse(id));
        if (path == null || path.isEmpty()) {
            return Map.of("id", id, "value", tag.toString());
        }
        List<Tag> matches = NbtPathArgument.NbtPath.of(path).get(tag);
        List<String> values = matches.stream().map(Tag::toString).toList();
        return Map.of("id", id, "path", path, "values", values);
    }

    public static Map<String, Object> listEntities(String selector) throws Exception {
        MinecraftServer server = DebugSession.server();
        CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
        String sel = (selector == null || selector.isEmpty()) ? "@e" : selector;
        List<? extends Entity> entities =
                new EntitySelectorParser(new StringReader(sel), true).parse().findEntities(source);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Entity entity : entities) {
            if (result.size() >= MAX_ENTITIES) break;
            result.add(describeEntity(entity));
        }
        return Map.of("count", entities.size(), "entities", result);
    }

    public static Map<String, Object> getEntity(String uuid) {
        Entity entity = DebugSession.server().overworld().getEntityInAnyDimension(UUID.fromString(uuid));
        if (entity == null) {
            return Map.of("found", false);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", true);
        result.putAll(describeEntity(entity));
        try {
            result.put("nbt", String.valueOf(
                    new net.minecraft.server.commands.data.EntityDataAccessor(entity).getData()));
        } catch (Exception ignored) {
        }
        return result;
    }

    private static Map<String, Object> describeEntity(Entity entity) {
        return Map.of(
                "uuid", entity.getUUID().toString(),
                "type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                "name", entity.getName().getString(),
                "pos", List.of(entity.getX(), entity.getY(), entity.getZ()),
                "dimension", entity.level().dimension().identifier().toString());
    }
}
