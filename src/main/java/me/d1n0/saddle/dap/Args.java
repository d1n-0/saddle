package me.d1n0.saddle.dap;

import java.util.Map;

/** Accessors for loosely-typed DAP request argument maps. */
final class Args {

    private Args() {}

    static int getInt(Map<String, Object> map, String key, int def) {
        return map.get(key) instanceof Number n ? n.intValue() : def;
    }

    static int requireInt(Map<String, Object> map, String key) {
        if (map.get(key) instanceof Number n) return n.intValue();
        throw new IllegalArgumentException("Missing integer argument: " + key);
    }

    static String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }

    static String requireString(Map<String, Object> map, String key) {
        String value = getString(map, key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing argument: " + key);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> getMap(Map<String, Object> map, String key) {
        return map.get(key) instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
