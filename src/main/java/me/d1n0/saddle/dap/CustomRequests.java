package me.d1n0.saddle.dap;

import me.d1n0.saddle.debugger.DataInspector;
import me.d1n0.saddle.debugger.DebugSession;
import me.d1n0.saddle.debugger.FunctionIndex;
import me.d1n0.saddle.debugger.MinecraftIntrospect;
import me.d1n0.saddle.debugger.TtdTrace;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Non-standard requests: "minecraft/*" game-state access and "saddle/trace".
 * Game reads/writes are dispatched to the server thread (pause-queue aware).
 */
final class CustomRequests {
    private static final long TIMEOUT_MS = 5000;

    private CustomRequests() {}

    static boolean handles(String command) {
        return command.startsWith("minecraft/") || "saddle/trace".equals(command);
    }

    static Map<String, Object> execute(String command, Map<String, Object> args) throws Exception {
        return switch (command) {
            case "saddle/trace" -> trace(args);
            case "minecraft/getScoreboard" -> onServerThread(MinecraftIntrospect::dumpScoreboard);
            case "minecraft/setScore" -> onServerThread(() -> Map.of("value", DataInspector.setScore(
                    Args.requireString(args, "objective"),
                    Args.requireString(args, "holder"),
                    Args.requireInt(args, "value"))));
            case "minecraft/getStorage" -> onServerThread(() -> MinecraftIntrospect.dumpStorage(
                    Args.getString(args, "id"), Args.getString(args, "path")));
            case "minecraft/listEntities" -> onServerThread(
                    () -> MinecraftIntrospect.listEntities(Args.getString(args, "selector")));
            case "minecraft/getEntity" -> onServerThread(
                    () -> MinecraftIntrospect.getEntity(Args.requireString(args, "uuid")));
            // Unified /data-style access: type = storage|entity|block.
            case "minecraft/getData" -> onServerThread(() -> Map.of("value",
                    String.valueOf(DataInspector.getData(resolveTarget(args), Args.getString(args, "path")))));
            case "minecraft/setData" -> onServerThread(() -> Map.of("value",
                    String.valueOf(DataInspector.setData(resolveTarget(args),
                            Args.requireString(args, "path"), Args.requireString(args, "value")))));
            case "minecraft/getBlock" -> onServerThread(() -> getBlock(args));
            default -> throw new IllegalArgumentException("Unsupported request: " + command);
        };
    }

    private static DataInspector.NbtTarget resolveTarget(Map<String, Object> args) {
        return DataInspector.resolveTarget(
                Args.requireString(args, "type"), Args.requireString(args, "target"));
    }

    private static Map<String, Object> getBlock(Map<String, Object> args) {
        String[] parts = Args.requireString(args, "pos").trim().split("\\s+");
        if (parts.length < 3) throw new IllegalArgumentException("pos must be \"x y z\"");
        ServerLevel level = DataInspector.resolveLevel(Args.getString(args, "dimension"));
        BlockPos pos = new BlockPos(Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("state", DataInspector.describeBlockState(level, pos));
        try {
            body.put("nbt", String.valueOf(
                    DataInspector.getData(DataInspector.blockTarget(level, pos), "")));
        } catch (Exception e) {
            // Plain blocks have no block entity NBT.
        }
        return body;
    }

    private static Map<String, Object> trace(Map<String, Object> args) {
        int count = Args.getInt(args, "count", 100);
        long head = TtdTrace.newestStep();
        List<TtdTrace.Step> recent = TtdTrace.recent(count);
        List<Map<String, Object>> steps = new ArrayList<>();
        for (int i = 0; i < recent.size(); i++) {
            TtdTrace.Step step = recent.get(i);
            // "behind" counts step-ring positions; Step.index() is the global
            // sequence shared with score/storage deltas and must not be mixed
            // with ring positions.
            long behind = recent.size() - 1 - (long) i;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", head - behind);
            item.put("behind", behind);
            item.put("function", step.functionId().toString());
            item.put("line", step.line());
            item.put("depth", step.depth());
            item.put("executor", step.executor());
            item.put("command", FunctionIndex.lineText(step.functionId(), step.line()).strip());
            steps.add(item);
        }
        return Map.of("steps", steps);
    }

    private interface GameQuery {
        Map<String, Object> get() throws Exception;
    }

    private static Map<String, Object> onServerThread(GameQuery query) throws Exception {
        return DebugSession.callOnServerThread(() -> {
            try {
                return query.get();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }).get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }
}
