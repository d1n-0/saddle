package me.d1n0.saddle.debugger;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Carries the macro argument compound from {@code MacroFunction.instantiate}
 * to the per-line wrappers created during it (same thread). Instantiations
 * are cached by argument values, so args baked into a wrapper stay accurate
 * for every execution served from that cache entry.
 */
public final class MacroArgsContext {
    private static final ThreadLocal<Map<String, String>> CURRENT = new ThreadLocal<>();

    private MacroArgsContext() {}

    public static void begin(CompoundTag args) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : args.keySet()) {
            Tag tag = args.get(key);
            // Match macro substitution: strings are inserted without quotes.
            values.put(key, tag instanceof StringTag s ? s.value() : String.valueOf(tag));
        }
        CURRENT.set(values);
    }

    public static Map<String, String> current() {
        Map<String, String> values = CURRENT.get();
        return values == null ? Map.of() : values;
    }

    public static void end() {
        CURRENT.remove();
    }
}
