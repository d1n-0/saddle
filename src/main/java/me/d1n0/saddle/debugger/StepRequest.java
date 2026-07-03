package me.d1n0.saddle.debugger;

/**
 * A pending step operation. {@code baseDepth} is the frame depth the execution
 * was suspended at when the step was requested.
 */
public record StepRequest(Mode mode, int baseDepth) {

    public enum Mode {
        STEP_IN,
        STEP_OVER,
        STEP_OUT
    }

    public boolean matches(int depth) {
        return switch (mode) {
            case STEP_IN -> true;
            case STEP_OVER -> depth <= baseDepth;
            case STEP_OUT -> depth < baseDepth;
        };
    }
}
