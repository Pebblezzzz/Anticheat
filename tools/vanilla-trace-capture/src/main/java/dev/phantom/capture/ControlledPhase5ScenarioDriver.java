package dev.phantom.capture;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.integrated.IntegratedServer;

import java.util.Locale;

/** Fully deterministic Phase 5 course/input driver for the real 1.21.11 client. */
public final class ControlledPhase5ScenarioDriver {
    private static final String PROP = "phantom.capture.scenario";
    private static final String ALL = "all";
    private static final String WATER = "water";
    private static final String NONE = "none";

    private static final int SETUP = 40, WALK = 100, SPRINT = 100, JUMP = 100, SNEAK = 100,
            DIAGONAL = 100, COLLISION = 100, WATER_MOVE = 120, LAVA_MOVE = 60,
            SPEED_EFFECT = 80, SLOWNESS_EFFECT = 80, JUMP_BOOST = 100, STEP = 110, TAIL = 70;
    private static final int RESET_SETTLE = 6;

    /** Each phase owns a large 140-block-long arena on its own Z segment. */
    private static final int ARENA_SPACING = 140;
    private static final int ARENA_START_Z = -80;
    private static final int ARENA_LENGTH = 120;
    private static final int ARENA_MIN_X = -32;
    private static final int ARENA_MAX_X = 32;
    private static final int COURSE_MIN_Z = ARENA_START_Z - 8;
    private static final int COURSE_MAX_Z = ARENA_START_Z + 13 * ARENA_SPACING + ARENA_LENGTH + 8;

    private static final int WALK_START = SETUP;
    private static final int SPRINT_START = WALK_START + WALK;
    private static final int JUMP_START = SPRINT_START + SPRINT;
    private static final int SNEAK_START = JUMP_START + JUMP;
    private static final int DIAGONAL_START = SNEAK_START + SNEAK;
    private static final int COLLISION_START = DIAGONAL_START + DIAGONAL;
    private static final int WATER_START = COLLISION_START + COLLISION;
    private static final int LAVA_START = WATER_START + WATER_MOVE;
    private static final int SPEED_START = LAVA_START + LAVA_MOVE;
    private static final int SLOWNESS_START = SPEED_START + SPEED_EFFECT;
    private static final int JUMP_BOOST_START = SLOWNESS_START + SLOWNESS_EFFECT;
    private static final int STEP_START = JUMP_BOOST_START + JUMP_BOOST;
    private static final int TAIL_START = STEP_START + STEP;
    private static final int ALL_TOTAL = TAIL_START + TAIL;

    private static final String[] DRY_ARENAS = {
            "walk", "sprint", "jump", "sneak", "diagonal", "collision",
            "speed-effect", "slowness-effect", "jump-boost", "step", "tail"
    };

    private long scenarioTick = -1;
    private String scenario = NONE;
    private String phase = "idle";
    private boolean finished;
    private boolean worldPrepared;
    private long lastResetStart = Long.MIN_VALUE;
    private static volatile ControlledPhase5ScenarioDriver LAST_INSTANCE;

    public static String phaseLabel() {
        ControlledPhase5ScenarioDriver d = LAST_INSTANCE;
        return d == null ? "unknown" : d.scenario + ":" + d.phase;
    }

    public ControlledPhase5ScenarioDriver() { LAST_INSTANCE = this; }

    public void tick(MinecraftClient client) {
        if (!Boolean.parseBoolean(System.getProperty("phantom.capture.enabled", "false"))) return;
        String requested = System.getProperty(PROP, NONE).trim().toLowerCase(Locale.ROOT);
        if (requested.equals(NONE) || client.player == null || client.world == null) { release(client.options); return; }
        if (!requested.equals(ALL) && !requested.equals(WATER)) throw new IllegalArgumentException("Unsupported Phase 5 scenario: " + requested);
        if (!requested.equals(scenario)) {
            scenario = requested; scenarioTick = 0; finished = false; worldPrepared = false; phase = "setup";
            lastResetStart = Long.MIN_VALUE;
            release(client.options);
        }
        if (finished) { release(client.options); return; }
        if (!worldPrepared) {
            prepareWorld(client); worldPrepared = true; scenarioTick = 0; return;
        }
        if (ALL.equals(scenario)) runAll(client); else runWaterOnly(client);
    }

    private void runWaterOnly(MinecraftClient client) {
        long t = scenarioTick;
        boolean forward = false, right = false, sprint = false;
        int startZ = arenaStartFor("water");
        if (t < 20) {
            phase = "water-baseline";
            if (t == 0) serverReset(client, 0.0, 64.0, startZ + 12.0, 0);
        } else if (t < 20 + WATER_MOVE) {
            phase = "water"; forward = true; sprint = true;
        } else if (t < 20 + WATER_MOVE + 40) {
            phase = "water-release";
        } else if (t < 20 + WATER_MOVE + 40 + 80) {
            phase = "water-diagonal"; forward = true; right = true; sprint = true;
        } else phase = "water-tail";
        apply(client.options, forward, false, false, right, false, false, sprint);
        finishIfDone(client, 20 + WATER_MOVE + 40 + 80 + 40);
    }

    private void runAll(MinecraftClient client) {
        long t = scenarioTick;
        boolean forward = false, right = false, left = false, jump = false, sneak = false, sprint = false;
        if (t < SETUP) phase = "setup";
        else if (t < SPRINT_START) { phase = "walk"; forward = true; }
        else if (t < JUMP_START) { phase = "sprint"; forward = true; sprint = true; }
        else if (t < SNEAK_START) { phase = "jump"; forward = true; jump = t >= JUMP_START + RESET_SETTLE && t < JUMP_START + RESET_SETTLE + 2; }
        else if (t < DIAGONAL_START) { phase = "sneak"; forward = true; sneak = true; }
        else if (t < COLLISION_START) { phase = "diagonal"; forward = true; left = true; }
        else if (t < WATER_START) { phase = "collision"; forward = true; }
        else if (t < LAVA_START) { phase = "water"; forward = true; sprint = true; }
        else if (t < SPEED_START) { phase = "lava"; forward = true; }
        else if (t < SLOWNESS_START) { phase = "speed-effect"; forward = true; }
        else if (t < JUMP_BOOST_START) { phase = "slowness-effect"; forward = true; }
        else if (t < STEP_START) { phase = "jump-boost"; forward = true; jump = t >= JUMP_BOOST_START + RESET_SETTLE && t < JUMP_BOOST_START + RESET_SETTLE + 2; }
        else if (t < TAIL_START) { phase = "step"; forward = true; }
        else phase = "tail";

        long start = phaseStartTick();
        if (t == start) {
            lastResetStart = t;
            switch (phase) {
                case "walk" -> serverReset(client, 0.0, 64.0, arenaStartFor("walk") + 12.0, 0);
                case "sprint" -> serverReset(client, 0.0, 64.0, arenaStartFor("sprint") + 12.0, 0);
                case "jump" -> serverReset(client, 0.0, 64.0, arenaStartFor("jump") + 12.0, 0);
                case "sneak" -> serverReset(client, 0.0, 64.0, arenaStartFor("sneak") + 12.0, 0);
                case "diagonal" -> serverReset(client, 0.0, 64.0, arenaStartFor("diagonal") + 12.0, 0);
                case "collision" -> serverReset(client, 0.0, 64.0, arenaStartFor("collision") + 12.0, 0);
                case "water" -> serverReset(client, 0.0, 64.0, arenaStartFor("water") + 12.0, 0);
                case "lava" -> serverReset(client, 0.0, 64.0, arenaStartFor("lava") + 12.0, 0);
                case "speed-effect" -> { serverReset(client, 0.0, 64.0, arenaStartFor("speed-effect") + 12.0, 0); giveEffect(client, "minecraft:speed", 0); }
                case "slowness-effect" -> { serverReset(client, 0.0, 64.0, arenaStartFor("slowness-effect") + 12.0, 0); giveEffect(client, "minecraft:slowness", 0); }
                case "jump-boost" -> { serverReset(client, 0.0, 64.0, arenaStartFor("jump-boost") + 12.0, 0); giveEffect(client, "minecraft:jump_boost", 0); }
                case "step" -> serverReset(client, 0.0, 64.0, arenaStartFor("step") + 12.0, 0);
                default -> { }
            }
        }
        if (lastResetStart != Long.MIN_VALUE && t - lastResetStart < RESET_SETTLE) {
            forward = right = left = jump = sneak = sprint = false;
        }
        apply(client.options, forward, false, left, right, jump, sneak, sprint);
        finishIfDone(client, ALL_TOTAL);
    }

    private long phaseStartTick() {
        return switch (phase) {
            case "walk" -> WALK_START; case "sprint" -> SPRINT_START; case "jump" -> JUMP_START;
            case "sneak" -> SNEAK_START; case "diagonal" -> DIAGONAL_START; case "collision" -> COLLISION_START;
            case "water" -> WATER_START; case "lava" -> LAVA_START; case "speed-effect" -> SPEED_START;
            case "slowness-effect" -> SLOWNESS_START; case "jump-boost" -> JUMP_BOOST_START; case "step" -> STEP_START;
            case "tail" -> TAIL_START; default -> -1;
        };
    }

    private static int arenaStartFor(String name) {
        return switch (name) {
            case "walk" -> ARENA_START_Z;
            case "sprint" -> ARENA_START_Z + ARENA_SPACING;
            case "jump" -> ARENA_START_Z + ARENA_SPACING * 2;
            case "sneak" -> ARENA_START_Z + ARENA_SPACING * 3;
            case "diagonal" -> ARENA_START_Z + ARENA_SPACING * 4;
            case "collision" -> ARENA_START_Z + ARENA_SPACING * 5;
            case "water" -> ARENA_START_Z + ARENA_SPACING * 6;
            case "lava" -> ARENA_START_Z + ARENA_SPACING * 7;
            case "speed-effect" -> ARENA_START_Z + ARENA_SPACING * 8;
            case "slowness-effect" -> ARENA_START_Z + ARENA_SPACING * 9;
            case "jump-boost" -> ARENA_START_Z + ARENA_SPACING * 10;
            case "step" -> ARENA_START_Z + ARENA_SPACING * 11;
            case "tail" -> ARENA_START_Z + ARENA_SPACING * 12;
            default -> throw new IllegalArgumentException("Unknown Phase 5 arena: " + name);
        };
    }

    private void prepareWorld(MinecraftClient client) {
        IntegratedServer server = client.getServer();
        if (server == null) throw new IllegalStateException("Phase 5 requires a local singleplayer world (integrated server)");
        server.executeSync(() -> {
            CommandManager m = server.getCommandManager(); ServerCommandSource s = server.getCommandSource();
            command(m, s, "difficulty peaceful"); command(m, s, "time set day"); command(m, s, "weather clear");

            for (String arena : DRY_ARENAS) {
                int z = arenaStartFor(arena);
                command(m, s, "forceload add " + ARENA_MIN_X + " " + z + " " + ARENA_MAX_X + " " + (z + ARENA_LENGTH - 1));
                command(m, s, "fill " + ARENA_MIN_X + " 64 " + z + " " + ARENA_MAX_X + " 67 " + (z + ARENA_LENGTH - 1) + " air");
                command(m, s, "fill " + ARENA_MIN_X + " 63 " + z + " " + ARENA_MAX_X + " 63 " + (z + ARENA_LENGTH - 1) + " minecraft:stone");
            }

            int collisionZ = arenaStartFor("collision");
            command(m, s, "fill -2 64 " + (collisionZ + 48) + " 2 66 " + (collisionZ + 52) + " minecraft:stone");

            int stepZ = arenaStartFor("step");
            command(m, s, "fill -4 64 " + (stepZ + 32) + " 4 64 " + (stepZ + 35) + " minecraft:oak_slab[type=bottom]");

            int waterZ = arenaStartFor("water");
            command(m, s, "forceload add -104 " + (waterZ + 4) + " 104 " + (waterZ + 116));
            command(m, s, "fill -100 62 " + (waterZ + 8) + " 100 62 " + (waterZ + 112) + " minecraft:stone");
            command(m, s, "fill -100 63 " + (waterZ + 8) + " 0 65 " + (waterZ + 112) + " minecraft:water");
            command(m, s, "fill 1 63 " + (waterZ + 8) + " 100 65 " + (waterZ + 112) + " minecraft:water");
            command(m, s, "fill -104 63 " + (waterZ + 4) + " 104 66 " + (waterZ + 4) + " minecraft:stone");
            command(m, s, "fill -104 63 " + (waterZ + 116) + " 104 66 " + (waterZ + 116) + " minecraft:stone");
            command(m, s, "fill -104 63 " + (waterZ + 4) + " -101 66 " + (waterZ + 116) + " minecraft:stone");
            command(m, s, "fill 101 63 " + (waterZ + 4) + " 104 66 " + (waterZ + 116) + " minecraft:stone");

            int lavaZ = arenaStartFor("lava");
            command(m, s, "forceload add -104 " + (lavaZ + 4) + " 104 " + (lavaZ + 72));
            command(m, s, "fill -100 62 " + (lavaZ + 8) + " 100 62 " + (lavaZ + 68) + " minecraft:stone");
            command(m, s, "fill -100 63 " + (lavaZ + 8) + " 0 65 " + (lavaZ + 68) + " minecraft:lava");
            command(m, s, "fill 1 63 " + (lavaZ + 8) + " 100 65 " + (lavaZ + 68) + " minecraft:lava");
            command(m, s, "fill -104 63 " + (lavaZ + 4) + " 104 66 " + (lavaZ + 4) + " minecraft:stone");
            command(m, s, "fill -104 63 " + (lavaZ + 72) + " 104 66 " + (lavaZ + 72) + " minecraft:stone");
            command(m, s, "fill -104 63 " + (lavaZ + 4) + " -101 66 " + (lavaZ + 72) + " minecraft:stone");
            command(m, s, "fill 101 63 " + (lavaZ + 4) + " 104 66 " + (lavaZ + 72) + " minecraft:stone");

            command(m, s, "gamemode survival @a"); command(m, s, "effect clear @a");
            command(m, s, "tp @a 0 64 " + (arenaStartFor("walk") + 12) + " 0 0");
            command(m, s, "forceload remove all");
        });
    }

    private void serverReset(MinecraftClient client, double x, double y, double z, double yaw) {
        IntegratedServer server = client.getServer();
        if (server == null) throw new IllegalStateException("Integrated server disappeared during Phase 5 reset");
        server.executeSync(() -> {
            CommandManager m = server.getCommandManager(); ServerCommandSource s = server.getCommandSource();
            command(m, s, "effect clear @a"); command(m, s, "gamemode survival @a");
            command(m, s, "tp @a " + x + " " + y + " " + z + " " + yaw + " 0");
        });
    }

    private void giveEffect(MinecraftClient client, String id, int amplifier) {
        IntegratedServer server = client.getServer();
        if (server == null) throw new IllegalStateException("Integrated server disappeared during Phase 5 effect setup");
        server.executeSync(() -> {
            CommandManager m = server.getCommandManager(); ServerCommandSource s = server.getCommandSource();
            command(m, s, "effect give @a " + id + " 30 " + amplifier + " true");
        });
    }

    private static void command(CommandManager m, ServerCommandSource s, String value) {
        m.parseAndExecute(s, value);
    }

    private static void finishIfDone(MinecraftClient client, long total) {
        ControlledPhase5ScenarioDriver d = LAST_INSTANCE;
        if (d != null && d.scenarioTick >= total) {
            d.finished = true;
            release(client.options);
            client.scheduleStop();
        }
        if (d != null) d.scenarioTick++;
    }

    private static void apply(GameOptions o, boolean forward, boolean back, boolean left, boolean right,
                              boolean jump, boolean sneak, boolean sprint) {
        o.forwardKey.setPressed(forward); o.backKey.setPressed(back); o.leftKey.setPressed(left); o.rightKey.setPressed(right);
        o.jumpKey.setPressed(jump); o.sneakKey.setPressed(sneak); o.sprintKey.setPressed(sprint);
    }

    private static void release(GameOptions o) { apply(o, false, false, false, false, false, false, false); }
}
