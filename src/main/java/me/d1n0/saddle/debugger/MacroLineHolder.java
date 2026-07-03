package me.d1n0.saddle.debugger;

/**
 * Duck interface implemented onto {@code MacroFunction.MacroEntry} by mixin so
 * the source line survives from parse time to macro instantiation.
 */
public interface MacroLineHolder {
    void saddle$setLine(int line);

    int saddle$getLine();
}
