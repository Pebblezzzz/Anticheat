package dev.phantom.capture;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;

/**
 * Deterministic input driver for empirical Phase 5 captures.
 * It changes only KeyBinding pressed state before a vanilla client tick; the
 * actual movement remains entirely inside Minecraft Java 1.21.11.
 */
public final class VanillaScenarioDriver {
    private static final String PROP = "phantom.capture.scenario";
    private static final String WATER = "water";
    private static final String NONE = "none";

    private static final int WATER_IDLE = 40;
    private static final int WATER_FORWARD = 100;
    private static final int WATER_RELEASE = 30;
    private static final int WATER_JUMP = 100;
    private static final int WATER_DIAGONAL = 100;
    private static final int WATER_TAIL = 30;
    private static final int WATER_TOTAL = WATER_IDLE + WATER_FORWARD + WATER_RELEASE + WATER_JUMP + WATER_DIAGONAL + WATER_TAIL;

    private long scenarioTick = -1;
    private String scenario = NONE;
    private boolean finished;

    public void tick(MinecraftClient client) {
        if (!Boolean.parseBoolean(System.getProperty("phantom.capture.enabled", "false"))) return;
        String requested = System.getProperty(PROP, NONE).trim().toLowerCase(java.util.Locale.ROOT);
        if (requested.equals(NONE) || client.player == null || client.world == null) {
            release(client.options);
            return;
        }
        if (!requested.equals(scenario)) {
            scenario = requested;
            scenarioTick = 0;
            finished = false;
            release(client.options);
        } else if (scenarioTick < 0) {
            scenarioTick = 0;
        }
        if (finished) {
            release(client.options);
            return;
        }

        boolean forward = false, backward = false, left = false, right = false, jump = false, sneak = false, sprint = false;
        if (WATER.equals(scenario)) {
            long t = scenarioTick;
            if (t < WATER_IDLE) {
                // No input: establishes a clean local baseline before the course starts.
            } else if (t < WATER_IDLE + WATER_FORWARD) {
                forward = true;
            } else if (t < WATER_IDLE + WATER_FORWARD + WATER_RELEASE) {
                // Release all movement: measures vanilla drag.
            } else if (t < WATER_IDLE + WATER_FORWARD + WATER_RELEASE + WATER_JUMP) {
                forward = true;
                jump = true;
            } else if (t < WATER_TOTAL - WATER_TAIL) {
                forward = true;
                right = true;
            } else {
                // Tail release: records post-input drag and the terminal water state.
            }
        } else {
            throw new IllegalArgumentException("Unsupported Phase 5 scenario: " + scenario);
        }

        apply(client.options, forward, backward, left, right, jump, sneak, sprint);
        if (scenarioTick++ >= totalTicks(scenario) - 1) {
            finished = true;
            release(client.options);
            client.scheduleStop();
        }
    }

    private static int totalTicks(String scenario) {
        return switch (scenario) {
            case WATER -> WATER_TOTAL;
            default -> throw new IllegalArgumentException("Unsupported Phase 5 scenario: " + scenario);
        };
    }

    private static void apply(GameOptions options, boolean forward, boolean backward,
                              boolean left, boolean right, boolean jump, boolean sneak, boolean sprint) {
        options.forwardKey.setPressed(forward);
        options.backKey.setPressed(backward);
        options.leftKey.setPressed(left);
        options.rightKey.setPressed(right);
        options.jumpKey.setPressed(jump);
        options.sneakKey.setPressed(sneak);
        options.sprintKey.setPressed(sprint);
    }

    private static void release(GameOptions options) {
        apply(options, false, false, false, false, false, false, false);
    }
}
