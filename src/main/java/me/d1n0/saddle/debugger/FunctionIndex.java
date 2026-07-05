package me.d1n0.saddle.debugger;

import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Source-level information about every parsed datapack function: raw text,
 * which lines compile to command entries (and therefore accept breakpoints),
 * client file paths learned from DAP requests, and DAP sourceReference ids.
 *
 * Populated from {@code CommandFunction.fromLines}, which runs on datapack
 * worker threads; each function id is only ever written by the thread that is
 * parsing it.
 */
public final class FunctionIndex {
    // Matches "data/<namespace>/function[s]/<path>.mcfunction" at the end of a path.
    private static final Pattern FUNCTION_PATH =
            Pattern.compile("data/([a-z0-9_.-]+)/functions?/([a-z0-9_./-]+)\\.mcfunction");

    private record Entry(List<String> lines, BitSet breakableLines) {}

    private static final Map<Identifier, Entry> FUNCTIONS = new ConcurrentHashMap<>();
    private static final Map<Identifier, String> CLIENT_PATHS = new ConcurrentHashMap<>();
    private static final Map<Identifier, Integer> REFS = new ConcurrentHashMap<>();
    private static final List<Identifier> BY_REF = new CopyOnWriteArrayList<>();
    private static final Object REF_LOCK = new Object();

    private FunctionIndex() {}

    /**
     * Staging area for an in-flight datapack reload. While non-null, freshly
     * parsed functions land here and readers keep serving the committed maps;
     * the staging is committed only when the reload succeeds, so a failed
     * reload (vanilla keeps running the old packs) never leaves the index
     * describing functions that were never applied.
     */
    private static volatile Map<Identifier, Entry> staging;

    /** Called at the start of parsing; replaces any data from a previous (re)load. */
    public static void beginFunction(Identifier id, List<String> rawLines) {
        Map<Identifier, Entry> target = staging;
        (target != null ? target : FUNCTIONS)
                .put(id, new Entry(List.copyOf(rawLines), new BitSet(rawLines.size() + 1)));
    }

    /** A /reload started: buffer new parses until the outcome is known. */
    public static void beginReload() {
        staging = new ConcurrentHashMap<>();
    }

    /** Reload succeeded: publish staged functions, drop removed ones. */
    public static void commitReload(java.util.Set<Identifier> liveFunctions) {
        Map<Identifier, Entry> staged = staging;
        if (staged != null) {
            FUNCTIONS.putAll(staged);
            staging = null;
        }
        FUNCTIONS.keySet().retainAll(liveFunctions);
        CLIENT_PATHS.keySet().retainAll(liveFunctions);
    }

    /** Reload failed: the old packs stay live, so the staged parses are void. */
    public static void discardReload() {
        staging = null;
    }

    /** Marks a line that maps to a runtime entry (plain command or macro line). */
    public static void recordBreakableLine(Identifier id, int line) {
        Map<Identifier, Entry> target = staging;
        Entry entry = target != null ? target.get(id) : null;
        if (entry == null) entry = FUNCTIONS.get(id);
        if (entry != null && line >= 1) {
            entry.breakableLines.set(line);
        }
    }

    public static boolean isKnown(Identifier id) {
        return FUNCTIONS.containsKey(id);
    }

    public static boolean isBreakableLine(Identifier id, int line) {
        Entry entry = FUNCTIONS.get(id);
        return entry != null && line >= 1 && entry.breakableLines.get(line);
    }

    /**
     * The first breakable line at or after {@code line}, or -1. Used to shift
     * breakpoints requested on comments/blank lines onto the next entry.
     */
    public static int nextBreakableLine(Identifier id, int line) {
        Entry entry = FUNCTIONS.get(id);
        return entry == null ? -1 : entry.breakableLines.nextSetBit(Math.max(1, line));
    }

    public static List<Integer> breakableLinesIn(Identifier id, int fromLine, int toLine) {
        Entry entry = FUNCTIONS.get(id);
        List<Integer> result = new ArrayList<>();
        if (entry == null) return result;
        int from = Math.max(1, fromLine);
        for (int line = entry.breakableLines.nextSetBit(from); line >= 0 && line <= toLine;
                line = entry.breakableLines.nextSetBit(line + 1)) {
            result.add(line);
        }
        return result;
    }

    public static String lineText(Identifier id, int line) {
        Entry entry = FUNCTIONS.get(id);
        if (entry == null || line < 1 || line > entry.lines.size()) return "";
        return entry.lines.get(line - 1);
    }

    /** Full function text for the DAP {@code source} request, or null if unknown. */
    public static String content(Identifier id) {
        Entry entry = FUNCTIONS.get(id);
        return entry == null ? null : String.join("\n", entry.lines);
    }

    /**
     * Maps a client file path to a function id by locating the
     * "data/&lt;ns&gt;/function/..." segment. Prefers candidates that refer to a
     * loaded function; scans right-to-left so nested datapack layouts resolve
     * to the innermost match.
     */
    public static Identifier resolveClientPath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) return null;
        String path = rawPath.replace('\\', '/');
        Identifier candidate = null;
        int idx = path.length();
        while ((idx = path.lastIndexOf("data/", idx - 1)) >= 0) {
            if (idx > 0 && path.charAt(idx - 1) != '/') continue;
            Matcher m = FUNCTION_PATH.matcher(path.substring(idx));
            if (!m.matches()) continue;
            Identifier id;
            try {
                id = Identifier.fromNamespaceAndPath(m.group(1), m.group(2));
            } catch (Exception e) {
                continue;
            }
            if (FUNCTIONS.containsKey(id)) return id;
            if (candidate == null) candidate = id;
            if (idx == 0) break;
        }
        return candidate;
    }

    public static void learnClientPath(Identifier id, String path) {
        if (path != null && !path.isEmpty()) {
            CLIENT_PATHS.put(id, path);
        }
    }

    public static String clientPath(Identifier id) {
        return CLIENT_PATHS.get(id);
    }

    public static int sourceReference(Identifier id) {
        Integer ref = REFS.get(id);
        if (ref != null) return ref;
        synchronized (REF_LOCK) {
            return REFS.computeIfAbsent(id, k -> {
                BY_REF.add(k);
                return BY_REF.size();
            });
        }
    }

    public static Identifier byReference(int ref) {
        return ref >= 1 && ref <= BY_REF.size() ? BY_REF.get(ref - 1) : null;
    }
}
