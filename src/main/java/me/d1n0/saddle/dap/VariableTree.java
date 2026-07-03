package me.d1n0.saddle.dap;

import me.d1n0.saddle.debugger.DataInspector;
import me.d1n0.saddle.debugger.DebugSession;
import me.d1n0.saddle.debugger.DebugTargets;
import me.d1n0.saddle.debugger.FunctionIndex;
import me.d1n0.saddle.debugger.StackSnapshot;
import me.d1n0.saddle.debugger.TtdTrace;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

import net.minecraft.nbt.CollectionTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * variablesReference handle table for one DAP session. References are handed
 * out lazily while execution is suspended and invalidated on every stop or
 * resume. Node reads/writes run on the server thread (via the debugger's
 * pause-aware dispatcher), so they see live game state.
 */
final class VariableTree {
    private static final int FIRST_REF = 1000;
    private static final int MAX_CHILDREN = 500;
    private static final int PREVIEW_LENGTH = 120;

    /** A variables container. Methods are invoked on the server thread. */
    interface Node {
        List<Map<String, Object>> children(VariableTree tree) throws Exception;

        default String setChild(String name, String value) throws Exception {
            throw new UnsupportedOperationException("This value is read-only");
        }
    }

    private final AtomicInteger nextRef = new AtomicInteger(FIRST_REF);
    private final Map<Integer, Node> nodes = new ConcurrentHashMap<>();

    int register(Node node) {
        int ref = nextRef.getAndIncrement();
        nodes.put(ref, node);
        return ref;
    }

    Node get(int ref) {
        return nodes.get(ref);
    }

    void clear() {
        nodes.clear();
        nextRef.set(FIRST_REF);
    }

    static Map<String, Object> leaf(String name, String value) {
        Map<String, Object> variable = new LinkedHashMap<>();
        variable.put("name", name);
        variable.put("value", value);
        variable.put("variablesReference", 0);
        return variable;
    }

    Map<String, Object> container(String name, String preview, Node node) {
        Map<String, Object> variable = leaf(name, preview);
        variable.put("variablesReference", register(node));
        return variable;
    }

    /**
     * Cheap one-line summary. Containers are described by size instead of
     * being serialized — stringifying large NBT trees on every variables
     * request is what made big storages slow to browse.
     */
    static String preview(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            int size = compound.size();
            return "{" + size + (size == 1 ? " entry}" : " entries}");
        }
        if (tag instanceof CollectionTag collection) {
            int size = collection.size();
            return "[" + size + (size == 1 ? " item]" : " items]");
        }
        String text = String.valueOf(tag);
        return text.length() > PREVIEW_LENGTH ? text.substring(0, PREVIEW_LENGTH) + "…" : text;
    }

    // ------------------------------------------------------------------
    // Node implementations
    // ------------------------------------------------------------------

    /** Frame scope: executor summary plus a live NBT subtree for the entity. */
    record ExecutorNode(StackSnapshot.Frame frame) implements Node {
        @Override
        public List<Map<String, Object>> children(VariableTree tree) throws Exception {
            List<Map<String, Object>> result = new ArrayList<>();
            for (StackSnapshot.Variable variable : frame.variables()) {
                result.add(leaf(variable.name(), variable.value()));
            }
            if (frame.entityUuid() != null) {
                DataInspector.NbtTarget target =
                        DataInspector.entityTarget(UUID.fromString(frame.entityUuid()));
                Tag data = DataInspector.getData(target, "");
                result.add(tree.container("nbt", preview(data), new NbtNode(target, "")));
            }
            return result;
        }
    }

    /** Macro argument values for a {@code $...} frame; read-only. */
    record MacroArgsNode(Map<String, String> args) implements Node {
        @Override
        public List<Map<String, Object>> children(VariableTree tree) {
            List<Map<String, Object>> result = new ArrayList<>();
            args.forEach((key, value) -> result.add(leaf("$(" + key + ")", value)));
            return result;
        }
    }

    /**
     * The command about to execute, with every entity selector and coordinate
     * triple it mentions resolved live against the frame's command source.
     */
    record CommandNode(StackSnapshot.Frame frame) implements Node {
        @Override
        public List<Map<String, Object>> children(VariableTree tree) {
            String text = FunctionIndex.lineText(frame.functionId(), frame.line()).strip();
            List<Map<String, Object>> result = new ArrayList<>();
            result.add(leaf("command", text));
            for (String selector : DebugTargets.findSelectors(text)) {
                result.add(describeSelector(tree, selector, frame.source()));
            }
            for (String coords : DebugTargets.findCoordTriples(text)) {
                result.add(describeBlock(tree, coords, frame.source()));
            }
            return result;
        }
    }

    static Map<String, Object> describeSelector(VariableTree tree, String selector, Object source) {
        try {
            List<? extends Entity> entities = DebugTargets.selectEntities(selector, source);
            return tree.container(selector,
                    entities.size() + (entities.size() == 1 ? " entity" : " entities"),
                    new SelectorNode(selector, source));
        } catch (Exception e) {
            return leaf(selector, "(unresolvable: " + e.getMessage() + ")");
        }
    }

    static Map<String, Object> describeBlock(VariableTree tree, String coords, Object source) {
        try {
            BlockPos pos = DebugTargets.resolveBlockPos(coords, source);
            CommandSourceStack css = DebugTargets.sourceOrServer(source);
            return tree.container(coords,
                    pos.toShortString() + " = " + DataInspector.describeBlockState(css.getLevel(), pos),
                    new BlockNode(pos, source));
        } catch (Exception e) {
            return leaf(coords, "(unresolvable: " + e.getMessage() + ")");
        }
    }

    /** Entities matched by a selector; each expands into its live NBT. */
    record SelectorNode(String selector, Object source) implements Node {
        @Override
        public List<Map<String, Object>> children(VariableTree tree) throws Exception {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Entity entity : DebugTargets.selectEntities(selector, source)) {
                if (result.size() >= MAX_CHILDREN) break;
                result.add(tree.container(
                        "[" + result.size() + "] " + entity.getName().getString(),
                        net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                                .getKey(entity.getType()) + " " + entity.getUUID(),
                        new NbtNode(DataInspector.entityTarget(entity.getUUID()), "")));
            }
            return result;
        }
    }

    /** Block at a resolved position: state plus block-entity NBT when present. */
    record BlockNode(BlockPos pos, Object source) implements Node {
        @Override
        public List<Map<String, Object>> children(VariableTree tree) {
            CommandSourceStack css = DebugTargets.sourceOrServer(source);
            List<Map<String, Object>> result = new ArrayList<>();
            result.add(leaf("pos", pos.toShortString()));
            result.add(leaf("state", DataInspector.describeBlockState(css.getLevel(), pos)));
            try {
                DataInspector.NbtTarget target = DataInspector.blockTarget(css.getLevel(), pos);
                result.add(tree.container("nbt", preview(target.read()), new NbtNode(target, "")));
            } catch (Exception ignored) {
                // Plain blocks have no block entity.
            }
            return result;
        }
    }

    // ------------------------------------------------------------------
    // Watch expressions & pinned targets
    // ------------------------------------------------------------------

    /**
     * Resolves a watch/pin expression against live game state (server thread).
     * Supported forms: {@code $(macroArg)}, {@code @selector}, coordinate
     * triples, {@code storage <id> [path]}, {@code entity <uuid> [path]},
     * {@code block <x> <y> <z> [path]}, {@code score <objective> <holder>}.
     */
    static Map<String, Object> resolveExpression(VariableTree tree, String rawExpr,
            StackSnapshot.Frame frame) throws Exception {
        String expr = rawExpr.trim();
        Object source = frame != null ? frame.source() : null;

        java.util.regex.Matcher macro = java.util.regex.Pattern.compile("\\$\\((\\w+)\\)").matcher(expr);
        if (macro.matches()) {
            String value = frame != null ? frame.macroArgs().get(macro.group(1)) : null;
            if (value == null) throw new IllegalArgumentException("No macro argument " + expr);
            return leaf(expr, value);
        }
        if (expr.startsWith("@")) {
            return describeSelector(tree, expr, source);
        }
        if (expr.startsWith("storage ") || expr.startsWith("entity ")) {
            String[] parts = expr.split("\\s+", 3);
            if (parts.length < 2) throw new IllegalArgumentException("Expected: " + parts[0] + " <target> [path]");
            String path = parts.length > 2 ? parts[2] : "";
            DataInspector.NbtTarget target = DataInspector.resolveTarget(parts[0], parts[1]);
            return describeNbt(tree, expr, target, path);
        }
        if (expr.startsWith("block ")) {
            String[] parts = expr.split("\\s+");
            if (parts.length < 4) throw new IllegalArgumentException("Expected: block <x> <y> <z> [path]");
            net.minecraft.core.BlockPos pos = DebugTargets.resolveBlockPos(
                    parts[1] + " " + parts[2] + " " + parts[3], source);
            CommandSourceStack css = DebugTargets.sourceOrServer(source);
            if (parts.length > 4) {
                String path = String.join(" ", java.util.Arrays.copyOfRange(parts, 4, parts.length));
                return describeNbt(tree, expr, DataInspector.blockTarget(css.getLevel(), pos), path);
            }
            return tree.container(expr,
                    pos.toShortString() + " = " + DataInspector.describeBlockState(css.getLevel(), pos),
                    new BlockNode(pos, source));
        }
        if (expr.startsWith("score ")) {
            String[] parts = expr.split("\\s+");
            if (parts.length != 3) throw new IllegalArgumentException("Expected: score <objective> <holder>");
            var scoreboard = DebugSession.server().getScoreboard();
            var objective = scoreboard.getObjective(parts[1]);
            if (objective == null) throw new IllegalArgumentException("Unknown objective: " + parts[1]);
            var info = scoreboard.getPlayerScoreInfo(
                    net.minecraft.world.scores.ScoreHolder.forNameOnly(parts[2]), objective);
            return leaf(expr, info == null ? "(not set)" : String.valueOf(info.value()));
        }
        if (!DebugTargets.findCoordTriples(expr).isEmpty()) {
            return describeBlock(tree, expr, source);
        }
        throw new IllegalArgumentException(
                "Cannot resolve: " + expr + " (try @selector, storage <id> [path], entity <uuid> [path],"
                        + " block <x> <y> <z> [path], score <objective> <holder>, $(macroArg), or coordinates)");
    }

    private static Map<String, Object> describeNbt(VariableTree tree, String name,
            DataInspector.NbtTarget target, String path) throws Exception {
        Tag tag = DataInspector.getData(target, path);
        if (tag instanceof CompoundTag || tag instanceof CollectionTag) {
            return tree.container(name, preview(tag), new NbtNode(target, path));
        }
        return leaf(name, preview(tag));
    }

    /** User-pinned expressions, re-resolved live on every request. */
    record WatchedNode(List<String> pins, StackSnapshot.Frame frame) implements Node {
        @Override
        public List<Map<String, Object>> children(VariableTree tree) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (String pin : pins) {
                try {
                    result.add(resolveExpression(tree, pin, frame));
                } catch (Exception e) {
                    result.add(leaf(pin, "(unresolvable: " + e.getMessage() + ")"));
                }
            }
            return result;
        }
    }

    // ------------------------------------------------------------------
    // Time-travel (recorded) nodes — all read-only
    // ------------------------------------------------------------------

    /** Executor summary of a recorded step. */
    record RecordedExecutorNode(TtdTrace.Step step) implements Node {
        @Override
        public List<Map<String, Object>> children(VariableTree tree) {
            List<Map<String, Object>> result = new ArrayList<>();
            TtdTrace.describeStep(step).forEach((name, value) -> result.add(leaf(name, value)));
            return result;
        }
    }

    /** The recorded command line text (targets are not re-resolved in the past). */
    record RecordedCommandNode(TtdTrace.Step step) implements Node {
        @Override
        public List<Map<String, Object>> children(VariableTree tree) {
            return List.of(leaf("command",
                    FunctionIndex.lineText(step.functionId(), step.line()).strip()));
        }
    }

    /** Scoreboard values reconstructed as of a recorded step. */
    record ScoreboardAtNode(long stepIndex) implements Node {
        @Override
        public List<Map<String, Object>> children(VariableTree tree) {
            List<Map<String, Object>> result = new ArrayList<>();
            TtdTrace.scoreboardAt(stepIndex).forEach((objective, scores) -> result.add(
                    tree.container(objective, scores.size() + " scores",
                            new ObjectiveAtNode(stepIndex, objective))));
            return result;
        }
    }

    record ObjectiveAtNode(long stepIndex, String objective) implements Node {
        @Override
        public List<Map<String, Object>> children(VariableTree tree) {
            List<Map<String, Object>> result = new ArrayList<>();
            Map<String, Integer> scores = TtdTrace.scoreboardAt(stepIndex)
                    .getOrDefault(objective, Map.of());
            scores.forEach((holder, value) -> result.add(leaf(holder, String.valueOf(value))));
            return result;
        }
    }

    /** Command storage reconstructed as of a recorded step. */
    record StorageAtNode(long stepIndex) implements Node {
        @Override
        public List<Map<String, Object>> children(VariableTree tree) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (var id : TtdTrace.storageIdsAt(stepIndex)) {
                CompoundTag tag = TtdTrace.storageAt(stepIndex, id);
                DataInspector.NbtTarget target = recordedTarget(stepIndex, id);
                result.add(tree.container(id.toString(), preview(tag), new NbtNode(target, "")));
            }
            return result;
        }

        private static DataInspector.NbtTarget recordedTarget(long stepIndex, Identifier id) {
            return new DataInspector.NbtTarget() {
                @Override
                public CompoundTag read() {
                    return TtdTrace.storageAt(stepIndex, id);
                }

                @Override
                public void write(CompoundTag tag) {
                    throw new UnsupportedOperationException("Recorded state is read-only");
                }

                @Override
                public String describe() {
                    return "storage " + id + " @ step " + stepIndex;
                }
            };
        }
    }

    record ScoreboardNode() implements Node {
        @Override
        public List<Map<String, Object>> children(VariableTree tree) {
            Scoreboard scoreboard = DebugSession.server().getScoreboard();
            List<Map<String, Object>> result = new ArrayList<>();
            for (Objective objective : scoreboard.getObjectives()) {
                int count = scoreboard.listPlayerScores(objective).size();
                result.add(tree.container(objective.getName(),
                        objective.getCriteria().getName() + " (" + count + " scores)",
                        new ObjectiveNode(objective.getName())));
            }
            return result;
        }
    }

    /** Scores of one objective; values are writable (parsed as int). */
    record ObjectiveNode(String objectiveName) implements Node {
        @Override
        public List<Map<String, Object>> children(VariableTree tree) {
            Scoreboard scoreboard = DebugSession.server().getScoreboard();
            Objective objective = scoreboard.getObjective(objectiveName);
            List<Map<String, Object>> result = new ArrayList<>();
            if (objective == null) return result;
            for (PlayerScoreEntry entry : scoreboard.listPlayerScores(objective)) {
                result.add(leaf(entry.owner(), String.valueOf(entry.value())));
                if (result.size() >= MAX_CHILDREN) break;
            }
            return result;
        }

        @Override
        public String setChild(String name, String value) {
            return String.valueOf(DataInspector.setScore(objectiveName, name, Integer.parseInt(value.trim())));
        }
    }

    record StorageRootNode() implements Node {
        @Override
        public List<Map<String, Object>> children(VariableTree tree) {
            List<Map<String, Object>> result = new ArrayList<>();
            DebugSession.server().getCommandStorage().keys().sorted(
                    java.util.Comparator.comparing(Identifier::toString)).forEach(id -> {
                if (result.size() >= MAX_CHILDREN) return;
                DataInspector.NbtTarget target = DataInspector.storageTarget(id);
                CompoundTag tag = DebugSession.server().getCommandStorage().get(id);
                result.add(tree.container(id.toString(), preview(tag), new NbtNode(target, "")));
            });
            return result;
        }
    }

    /**
     * Live NBT tree over any {@link DataInspector.NbtTarget}. Leaf values are
     * writable with SNBT; containers expand lazily.
     */
    record NbtNode(DataInspector.NbtTarget target, String path) implements Node {
        @Override
        public List<Map<String, Object>> children(VariableTree tree) throws Exception {
            Tag tag = DataInspector.getData(target, path);
            List<Map<String, Object>> result = new ArrayList<>();
            if (tag instanceof CompoundTag compound) {
                for (String key : compound.keySet().stream().sorted().toList()) {
                    if (result.size() >= MAX_CHILDREN) break;
                    result.add(describe(tree, key, childPath(key), compound.get(key)));
                }
            } else if (tag instanceof CollectionTag collection) {
                for (int i = 0; i < collection.size() && result.size() < MAX_CHILDREN; i++) {
                    result.add(describe(tree, "[" + i + "]", path + "[" + i + "]", collection.get(i)));
                }
            }
            return result;
        }

        private Map<String, Object> describe(VariableTree tree, String name, String fullPath, Tag tag) {
            if (tag instanceof CompoundTag || tag instanceof CollectionTag) {
                return tree.container(name, preview(tag), new NbtNode(target, fullPath));
            }
            return leaf(name, preview(tag));
        }

        @Override
        public String setChild(String name, String value) throws Exception {
            String fullPath = name.startsWith("[") ? path + name : childPath(name);
            return preview(DataInspector.setData(target, fullPath, value));
        }

        private String childPath(String key) {
            String segment = key.matches("[A-Za-z0-9_+-]+")
                    ? key
                    : '"' + key.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
            return path.isEmpty() ? segment : path + "." + segment;
        }
    }
}
