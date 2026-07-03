package me.d1n0.saddle.debugger;

/**
 * Marks system-message broadcasts so the per-player delivery hook can skip
 * them, keeping each chat line as a single DAP output event. Server thread
 * only.
 */
public final class ChatBridge {
    private static boolean broadcasting;

    private ChatBridge() {}

    public static void beginBroadcast() {
        broadcasting = true;
    }

    public static void endBroadcast() {
        broadcasting = false;
    }

    public static boolean isBroadcasting() {
        return broadcasting;
    }
}
