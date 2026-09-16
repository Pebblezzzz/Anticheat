package dev.phantom.capture;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.integrated.IntegratedServer;

import java.util.Locale;

/**
 * Deterministic input/world driver for empirical Phase 5 captures.
 *
 * The movement itself remains entirely inside vanilla Minecraft Java 1.21.11.
 * The harness only prepares a disposable test course through the integrated
 * server command API and then changes client KeyBinding pressed state before
 * each vanilla client tick.
 */
public final class VanillaScenarioDriver {
    private static final String PROP = "phantom.capture.scenario";
    private static final String ALL = "all";
    private static final String WATER = "water";
    private static final String NONE = "none";

    private static final int SETUP = 40;
    private static final int WALK = 100;
    private static final int SPRINT = 100;
    private static final int JUMP = 100;
    private static final int SNEAK = 100;
    private static final int DIAGONAL = 100;
    private static final int COLLISION = 100;
    private static final int WATER_MOVE = 120;
    private static final int LAVA_MOVE = 120;
    private static final int SPEED_EFFECT = 80;
    private static final int SLOWNESS_EFFECT = 80;
    private static final int JUMP_BOOST = 100;
    private static final int STEP = 110;
    private static final int TAIL = 40;

    private static final int ALL_TOTAL = SETUP + WALK + SPRINT + JUMP + SNEAK + DIAGONAL
            + COLLISION + WATER_MOVE + LAVA_MOVE + SPEED_EFFECT + SLOWNESS_EFFECT
            + JUMP_BOOST + STEP + TAIL;

    private long scenarioTick = -1;
    private String scenario = NONE;
    private String phase = "idle";
    private boolean finished;
    private boolean worldPrepared;

    private static volatile VanillaScenarioDriver LAST_INSTANCE;

    public static String phaseLabel() {
        VanillaScenarioDriver driver = LAST_INSTANCE;
        return driver == null ? "unknown" : driver.scenario + ":" + driver.phase;
    }

    public VanillaScenarioDriver() {
        LAST_INSTANCE = this;
    }

    public void tick(MinecraftClient client) {
        if (!Boolean.parseBoolean(System.getProperty("phantom.capture.enabled", "false"))) return;
        String requested = System.getProperty(PROP, NONE).trim().toLowerCase(Locale.ROOT);
        if (requested.equals(NONE) || client.player == null || client.world == null) {
            release(client.options);
            return;
        }
        if (!requested.equals(ALL) && !requested.equals(WATER)) {
            throw new IllegalArgumentException("Unsupported Phase 5 scenario: " + requested);
        }

        if (!requested.equals(scenario)) {
            scenario = requested;
            scenarioTick = 0;
            finished = false;
            worldPrepared = false;
            phase = "setup";
            release(client.options);
        } else if (scenarioTick < 0) {
            scenarioTick = 0;
        }
        if (finished) {
            release(client.options);
            return;
        }

        if (!worldPrepared) {
            prepareWorld(client);
            worldPrepared = true;
            return;
        }

        if (ALL.equals(scenario)) {
            runAll(client);
        } else {
            runWaterOnly(client);
        }
    }

    private void runWaterOnly(MinecraftClient client) {
        long t = scenarioTick;
        boolean forward = false;
        boolean right = false;
        boolean jump = false;

        if (t < 20) {
            phase = "water-baseline";
            if (t == 0) teleport(client, 25.5, 64.0, 4.5, 0.0f);
        } else if (t < 20 + WATER_MOVE) {
            phase = "water-straight";
            forward = true;
        } else if (t < 20 + WATER_MOVE + 40) {
            phase = "water-release";
        } else if (t < 20 + WATER_MOVE + 40 + 80) {
            phase = "water-diagonal";
            forward = true;
            right = true;
        } else {
            phase = "water-tail";
        }

        apply(client.options, forward, false, false, right, jump, false, false);
        finishIfDone(client, 20 + WATER_MOVE + 40 + 80 + 40);
    }

    private void runAll(MinecraftClient client) {
        long t = scenarioTick;
        long cursor = SETUP;
        boolean forward = false;
        boolean right = false;
        boolean left = false;
        boolean jump = false;
        boolean sneak = false;
        boolean sprint = false;

        if (t < cursor) {
            phase = "setup";
        } else if (t < cursor + WALK) {
            phase = "walk";
            teleportAtPhaseStart(client, -30.5, 64.0, -6.5, -90.0f);
            forward = true;
        } else if (t < (cursor += WALK) + SPRINT) {
            phase = "sprint";
            teleportAtPhaseStart(client, -30.5, 64.0, 1.5, -90.0f);
            forward = true;
            sprint = true;
        } else if (t < (cursor += SPRINT) + JUMP) {
            phase = "jump";
            teleportAtPhaseStart(client, -30.5, 64.0, 9.5, -90.0f);
            forward = true;
            jump = t == cursor || t == cursor + 1;
        } else if (t < (cursor += JUMP) + SNEAK) {
            phase = "sneak";
            teleportAtPhaseStart(client, -30.5, 64.0, 17.5, -90.0f);
            forward = true;
            sneak = true;
        } else if (t < (cursor += SNEAK) + DIAGONAL) {
            phase = "diagonal";
            teleportAtPhaseStart(client, -30.5, 64.0, 25.5, -90.0f);
            forward = true;
            left = true;
        } else if (t < (cursor += DIAGONAL) + COLLISION) {
            phase = "collision";
            teleportAtPhaseStart(client, 0.5, 64.0, -18.5, -90.0f);
            forward = true;
        } else if (t < (cursor += COLLISION) + WATER_MOVE) {
            phase = "water";
            teleportAtPhaseStart(client, 25.5, 64.0, 4.5, 0.0f);
            forward = true;
        } else if (t < (cursor += WATER_MOVE) + LAVA_MOVE) {
            phase = "lava";
            teleportAtPhaseStart(client, 25.5, 64.0, 19.5, 0.0f);
            forward = true;
        } else if (t < (cursor += LAVA_MOVE) + SPEED_EFFECT) {
            phase = "speed-effect";
            teleportAtPhaseStart(client, -4.5, 64.0, 33.5, -90.0f);
            giveEffect(client, "minecraft:speed", 0);
            forward = true;
        } else if (t < (cursor += SPEED_EFFECT) + SLOWNESS_EFFECT) {
            phase = "slowness-effect";
            teleportAtPhaseStart(client, -4.5, 64.0, 40.5, -90.0f);
            clearEffects(client);
            giveEffect(client, "minecraft:slowness", 0);
            forward = true;
        } else if (t < (cursor += SLOWNESS_EFFECT) + JUMP_BOOST) {
            phase = "jump-boost";
            teleportAtPhaseStart(client, -4.5, 64.0, 47.5, -90.0f);
            clearEffects(client);
            giveEffect(client, "minecraft:jump_boost", 0);
            forward = true;
            jump = t == cursor || t == cursor + 1;
        } else if (t < (cursor += JUMP_BOOST) + STEP) {
            phase = "step";
            teleportAtPhaseStart(client, 12.5, 64.5, -18.5, -90.0f);
            clearEffects(client);
            forward = true;
        } else {
            phase = "tail";
        }

        apply(client.options, forward, false, left, right, jump, sneak, sprint);
        if (t >= ALL_TOTAL - TAIL) phase = "tail";
        finishIfDone(client, ALL_TOTAL);
    }

    private void prepareWorld(MinecraftClient client) {
        IntegratedServer server = client.getServer();
        if (server == null) {
            throw new IllegalStateException("Phase 5 automated scenarios require a local singleplayer world (integrated server)");
        }
        server.executeSync(() -> {
            CommandManager commands = server.getCommandManager();
            var source = server.getCommandSource();
            command(commands, source, "fill -48 60 -8 48 72 55 air");
            command(commands, source, "fill -48 63 -8 48 63 55 minecraft:stone");
            command(commands, source, "fill 5 64 -21 5 67 -14 minecraft:stone");
            command(commands, source, "fill 12 64 -21 16 64 -14 minecraft:stone_slab[type=bottom]");
            command(commands, source, "fill 17 64 -21 21 64 -14 minecraft:stone");
            command(commands, source, "fill 25 64 0 31 65 8 minecraft:water");
            command(commands, source, "fill 25 64 15 31 65 23 minecraft:lava");
            command(commands, source, "gamemode survival @a");
            command(commands, source, "effect clear @a");
            command(commands, source, "tp @a -30.5 64 -6.5 -90 0");
        });
    }

    private void teleportAtPhaseStart(MinecraftClient client, double x, double y, double z, float yaw) {
        if (scenarioTick == phaseStartTick()) teleport(client, x, y, z, yaw);
    }

    private void teleport(MinecraftClient client, double x, double y, double z, float yaw) {
        issueCommand(client, "tp @a " + x + " " + y + " " + z + " " + yaw + " 0");
    }

    private long phaseStartTick() {
        return switch (phase) {
            case "walk" -> SETUP;
            case "sprint" -> SETUP + WALK;
            case "jump" -> SETUP + WALK + SPRINT;
            case "sneak" -> SETUP + WALK + SPRINT + JUMP;
            case "diagonal" -> SETUP + WALK + SPRINT + JUMP + SNEAK;
            case "collision" -> SETUP + WALK + SPRINT + JUMP + SNEAK + DIAGONAL;
            case "water" -> SETUP + WALK + SPRINT + JUMP + SNEAK + DIAGONAL + COLLISION;
            case "lava" -> SETUP + WALK + SPRINT + JUMP + SNEAK + DIAGONAL + COLLISION + WATER_MOVE;
            case "speed-effect" -> SETUP + WALK + SPRINT + JUMP + SNEAK + DIAGONAL + COLLISION + WATER_MOVE + LAVA_MOVE;
            case "slowness-effect" -> SETUP + WALK + SPRINT + JUMP + SNEAK + DIAGONAL + COLLISION + WATER_MOVE + LAVA_MOVE + SPEED_EFFECT;
            case "jump-boost" -> SETUP + WALK + SPRINT + JUMP + SNEAK + DIAGONAL + COLLISION + WATER_MOVE + LAVA_MOVE + SPEED_EFFECT + SLOWNESS_EFFECT;
            case "step" -> SETUP + WALK + SPRINT + JUMP + SNEAK + DIAGONAL + COLLISION + WATER_MOVE + LAVA_MOVE + SPEED_EFFECT + SLOWNESS_EFFECT + JUMP_BOOST;
            default -> -1;
        };
    }

    private void giveEffect(MinecraftClient client, String id, int amplifier) {
        if (scenarioTick != phaseStartTick()) return;
        issueCommand(client, "effect give @a " + id + " 120 " + amplifier + " true");
    }

    private void clearEffects(MinecraftClient client) {
        if (scenarioTick != phaseStartTick()) return;
        issueCommand(client, "effect clear @a");
    }

    private void issueCommand(MinecraftClient client, String command) {
        IntegratedServer server = client.getServer();
        if (server == null) throw new IllegalStateException("Integrated server disappeared during Phase 5 setup");
        server.executeSync(() -> command(server.getCommandManager(), server.getCommandSource(), command));
    }

    private static void command(CommandManager manager, net.minecraft.server.command.ServerCommandSource source, String command) {
        manager.parseAndExecute(source, command);
    }

    private void finishIfDone(MinecraftClient client, int total) {
        if (scenarioTick++ >= total - 1) {
            finished = true;
            release(client.options);
            client.scheduleStop();
        }
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
