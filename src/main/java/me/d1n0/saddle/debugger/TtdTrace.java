package me.d1n0.saddle.debugger;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Time-travel recording: a ring buffer of executed command steps plus
 * before-value deltas for scoreboard and command-storage writes, all stamped
 * from one global sequence. Historical state at step S is reconstructed by
 * applying the {@code before} values of every delta newer than S onto the
 * live state, newest first.
 *
 * Writers run on the server thread; readers are DAP threads while execution
 * is suspended. All buffer access is synchronized on {@code LOCK}.
 */
public final class TtdTrace {

    /** One executed command. {@code index} doubles as the global sequence stamp. */
    public record Step(long index, Identifier functionId, int line, int depth,
            String executor, String entityUuid, String position, String dimension,
            Map<String, String> macroArgs) {}

    private record ScoreDelta(long seq, String objective, String holder, Integer before) {}

    private record StorageDelta(long seq, Identifier id, CompoundTag before) {}

    /** Fixed-capacity ring addressed by absolute append index. */
    private static final class Ring<T> {
        private final Object[] items;
        private long appended;

        Ring(int capacity) {
            items = new Object[capacity];
        }

        void add(T item) {
            items[(int) (appended % items.length)] = item;
            appended++;
        }

        long newest() {
            return appended - 1;
        }

        long oldest() {
            return Math.max(0, appended - items.length);
        }

        @SuppressWarnings("unchecked")
        T get(long index) {
            if (index < oldest() || index > newest()) return null;
            return (T) items[(int) (index % items.length)];
        }
    }

    private static final Object LOCK = new Object();
    private static final Ring<Step> STEPS =
            new Ring<>(Integer.getInteger("saddle.ttd.steps", 20000));
    private static final Ring<ScoreDelta> SCORE_DELTAS =
            new Ring<>(Integer.getInteger("saddle.ttd.deltas", 20000));
    private static final Ring<StorageDelta> STORAGE_DELTAS =
            new Ring<>(Integer.getInteger("saddle.ttd.storage", 2000));
    private static long nextSeq;
    private static final Map<String, Integer> SCORE_SHADOW = new HashMap<>();

    private static final int MAX_FRAMES = 64;

    private TtdTrace() {}

    // ------------------------------------------------------------------
    // Recording (server thread)
    // ------------------------------------------------------------------

    public static void recordStep(Identifier functionId, int line, int depth, Object source,
            Map<String, String> macroArgs) {
        String executor = "server";
        String entityUuid = null;
        String position = "";
        String dimension = "";
        if (source instanceof CommandSourceStack css) {
            Entity entity = css.getEntity();
            if (entity != null) {
                executor = css.getTextName();
                entityUuid = entity.getUUID().toString();
            }
            Vec3 pos = css.getPosition();
            position = String.format(Locale.ROOT, "%.2f %.2f %.2f", pos.x, pos.y, pos.z);
            dimension = css.getLevel().dimension().identifier().toString();
        }
        synchronized (LOCK) {
            STEPS.add(new Step(nextSeq++, functionId, line, depth,
                    executor, entityUuid, position, dimension, macroArgs));
        }
    }

    public static void scoreChanged(String objective, String holder, int newValue) {
        String key = scoreKey(objective, holder);
        synchronized (LOCK) {
            Integer before = SCORE_SHADOW.put(key, newValue);
            SCORE_DELTAS.add(new ScoreDelta(nextSeq++, objective, holder, before));
        }
    }

    public static void scoreRemoved(String objective, String holder) {
        String key = scoreKey(objective, holder);
        synchronized (LOCK) {
            Integer before = SCORE_SHADOW.remove(key);
            if (before != null) {
                SCORE_DELTAS.add(new ScoreDelta(nextSeq++, objective, holder, before));
            }
        }
    }

    public static void allScoresRemoved(String holder) {
        String suffix = "\u0000" + holder;
        synchronized (LOCK) {
            for (var it = SCORE_SHADOW.entrySet().iterator(); it.hasNext(); ) {
                var entry = it.next();
                if (entry.getKey().endsWith(suffix)) {
                    String objective = entry.getKey().substring(0,
                            entry.getKey().length() - suffix.length());
                    SCORE_DELTAS.add(new ScoreDelta(nextSeq++, objective, holder, entry.getValue()));
                    it.remove();
                }
            }
        }
    }

    // CommandStorage.get hands out the live tag, so by the time set() runs the
    // "current" value may already be mutated in place (/data modify pattern:
    // get → mutate → set). Readers that are about to mutate register a copy
    // here, which set() prefers over the current value.
    private static Identifier pendingStorageId;
    private static CompoundTag pendingStorageBefore;

    /** Registers the pre-mutation content of a storage about to be written. */
    public static void noteStorageBefore(Identifier id, CompoundTag before) {
        synchronized (LOCK) {
            pendingStorageId = id;
            pendingStorageBefore = before;
        }
    }

    public static void storageChanged(Identifier id, CompoundTag current) {
        synchronized (LOCK) {
            CompoundTag before = current;
            if (id.equals(pendingStorageId) && pendingStorageBefore != null) {
                before = pendingStorageBefore;
            }
            pendingStorageId = null;
            pendingStorageBefore = null;
            STORAGE_DELTAS.add(new StorageDelta(nextSeq++, id, before));
        }
    }

    /** Seeds the score shadow so the first recorded write has a before-value. */
    public static void primeScoreboard() {
        Scoreboard scoreboard = DebugSession.server().getScoreboard();
        synchronized (LOCK) {
            SCORE_SHADOW.clear();
            for (Objective objective : scoreboard.getObjectives()) {
                for (PlayerScoreEntry entry : scoreboard.listPlayerScores(objective)) {
                    SCORE_SHADOW.put(scoreKey(objective.getName(), entry.owner()), entry.value());
                }
            }
        }
    }

    private static String scoreKey(String objective, String holder) {
        return objective + "\u0000" + holder;
    }

    // ------------------------------------------------------------------
    // Navigation (any thread)
    // ------------------------------------------------------------------

    public static long newestStep() {
        synchronized (LOCK) {
            return STEPS.newest();
        }
    }

    public static long oldestStep() {
        synchronized (LOCK) {
            return STEPS.oldest();
        }
    }

    public static Step step(long index) {
        synchronized (LOCK) {
            return STEPS.get(index);
        }
    }

    /** Newest matching step at or before {@code from}, or -1. */
    public static long previousMatching(long from, Predicate<Step> filter) {
        synchronized (LOCK) {
            for (long i = Math.min(from, STEPS.newest()); i >= STEPS.oldest(); i--) {
                Step step = STEPS.get(i);
                if (step != null && filter.test(step)) return i;
            }
            return -1;
        }
    }

    /** Oldest matching step in {@code (from, toInclusive]}, or -1. */
    public static long nextMatching(long from, long toInclusive, Predicate<Step> filter) {
        synchronized (LOCK) {
            long end = Math.min(toInclusive, STEPS.newest());
            for (long i = Math.max(from + 1, STEPS.oldest()); i <= end; i++) {
                Step step = STEPS.get(i);
                if (step != null && filter.test(step)) return i;
            }
            return -1;
        }
    }

    /** Reconstructed call stack at a step, innermost first. */
    public static List<Step> stackAt(long index) {
        synchronized (LOCK) {
            List<Step> frames = new ArrayList<>();
            Step current = STEPS.get(index);
            if (current == null) return frames;
            frames.add(current);
            int wantedDepth = current.depth - 1;
            for (long i = index - 1; i >= STEPS.oldest() && frames.size() < MAX_FRAMES
                    && wantedDepth >= 0; i--) {
                Step step = STEPS.get(i);
                if (step == null) break;
                if (step.depth == wantedDepth) {
                    frames.add(step);
                    wantedDepth--;
                } else if (step.depth < wantedDepth) {
                    break; // caller chain left the recording window
                }
            }
            return frames;
        }
    }

    public static List<Step> recent(int count) {
        synchronized (LOCK) {
            List<Step> result = new ArrayList<>();
            for (long i = Math.max(STEPS.oldest(), STEPS.newest() - count + 1);
                    i <= STEPS.newest(); i++) {
                Step step = STEPS.get(i);
                if (step != null) result.add(step);
            }
            return result;
        }
    }

    // ------------------------------------------------------------------
    // Historical state reconstruction
    // ------------------------------------------------------------------

    /** Scoreboard values as they were just before step {@code index} executed. Server thread. */
    public static Map<String, Map<String, Integer>> scoreboardAt(long index) {
        Step step = step(index);
        long seq = step == null ? Long.MAX_VALUE : step.index;

        Map<String, Map<String, Integer>> result = new TreeMap<>();
        Scoreboard scoreboard = DebugSession.server().getScoreboard();
        for (Objective objective : scoreboard.getObjectives()) {
            Map<String, Integer> scores = new TreeMap<>();
            for (PlayerScoreEntry entry : scoreboard.listPlayerScores(objective)) {
                scores.put(entry.owner(), entry.value());
            }
            result.put(objective.getName(), scores);
        }
        synchronized (LOCK) {
            for (long i = SCORE_DELTAS.newest(); i >= SCORE_DELTAS.oldest(); i--) {
                ScoreDelta delta = SCORE_DELTAS.get(i);
                if (delta == null || delta.seq <= seq) break;
                Map<String, Integer> scores =
                        result.computeIfAbsent(delta.objective, k -> new TreeMap<>());
                if (delta.before == null) {
                    scores.remove(delta.holder);
                } else {
                    scores.put(delta.holder, delta.before);
                }
            }
        }
        result.values().removeIf(Map::isEmpty);
        return result;
    }

    /** Storage ids known live or in the recorded window. Server thread. */
    public static List<Identifier> storageIdsAt(long index) {
        TreeSet<Identifier> ids = new TreeSet<>(
                java.util.Comparator.comparing(Identifier::toString));
        DebugSession.server().getCommandStorage().keys().forEach(ids::add);
        synchronized (LOCK) {
            for (long i = STORAGE_DELTAS.oldest(); i <= STORAGE_DELTAS.newest(); i++) {
                StorageDelta delta = STORAGE_DELTAS.get(i);
                if (delta != null) ids.add(delta.id);
            }
        }
        return List.copyOf(ids);
    }

    /** Storage content as it was just before step {@code index} executed. Server thread. */
    public static CompoundTag storageAt(long index, Identifier id) {
        Step step = step(index);
        long seq = step == null ? Long.MAX_VALUE : step.index;

        CompoundTag tag = DebugSession.server().getCommandStorage().get(id);
        synchronized (LOCK) {
            for (long i = STORAGE_DELTAS.newest(); i >= STORAGE_DELTAS.oldest(); i--) {
                StorageDelta delta = STORAGE_DELTAS.get(i);
                if (delta == null || delta.seq <= seq) break;
                if (delta.id.equals(id)) {
                    tag = delta.before;
                }
            }
        }
        return tag;
    }

    /** Executor summary leaves for a recorded step. */
    public static Map<String, String> describeStep(Step step) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("executor", step.executor());
        if (step.entityUuid() != null) values.put("uuid", step.entityUuid());
        if (!step.position().isEmpty()) values.put("position", step.position());
        if (!step.dimension().isEmpty()) values.put("dimension", step.dimension());
        return values;
    }
}
