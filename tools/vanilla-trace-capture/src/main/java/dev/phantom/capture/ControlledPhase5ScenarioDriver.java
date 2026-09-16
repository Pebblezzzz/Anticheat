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
            SPEED_EFFECT = 80, SLOWNESS_EFFECT = 80, JUMP_BOOST = 100, STEP = 110, TAIL = 40;
    private static final int ALL_TOTAL = SETUP + WALK + SPRINT + JUMP + SNEAK + DIAGONAL + COLLISION
            + WATER_MOVE + LAVA_MOVE + SPEED_EFFECT + SLOWNESS_EFFECT + JUMP_BOOST + STEP + TAIL;
    private long scenarioTick = -1;
    private String scenario = NONE;
    private String phase = "idle";
    private boolean finished;
    private boolean worldPrepared;
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
            scenario = requested; scenarioTick = 0; finished = false; worldPrepared = false; phase = "setup"; release(client.options);
        }
        if (finished) { release(client.options); return; }
        if (!worldPrepared) {
            prepareWorld(client); worldPrepared = true; scenarioTick = 0; return;
        }
        if (ALL.equals(scenario)) runAll(client); else runWaterOnly(client);
    }

    private void runWaterOnly(MinecraftClient client) {
        long t = scenarioTick; boolean forward = false, right = false;
        if (t < 20) { phase = "water-baseline"; if (t == 0) serverReset(client, -10.5, 65.0, -5.5, -90); }
        else if (t < 20 + WATER_MOVE) { phase = "water-straight"; forward = true; }
        else if (t < 20 + WATER_MOVE + 40) { phase = "water-release"; }
        else if (t < 20 + WATER_MOVE + 40 + 80) { phase = "water-diagonal"; forward = true; right = true; }
        else phase = "water-tail";
        apply(client.options, forward, false, false, right, false, false, false);
        finishIfDone(client, 20 + WATER_MOVE + 40 + 80 + 40);
    }

    private void runAll(MinecraftClient client) {
        long t = scenarioTick, cursor = SETUP;
        boolean forward = false, right = false, left = false, jump = false, sneak = false, sprint = false;
        if (t < cursor) phase = "setup";
        else if (t < cursor + WALK) { phase = "walk"; forward = true; }
        else if ((cursor += WALK) + SPRINT > t) { phase = "sprint"; forward = true; sprint = true; }
        else if ((cursor += SPRINT) + JUMP > t) { phase = "jump"; forward = true; jump = t == cursor || t == cursor + 1; }
        else if ((cursor += JUMP) + SNEAK > t) { phase = "sneak"; forward = true; sneak = true; }
        else if ((cursor += SNEAK) + DIAGONAL > t) { phase = "diagonal"; forward = true; left = true; }
        else if ((cursor += DIAGONAL) + COLLISION > t) { phase = "collision"; forward = true; }
        else if ((cursor += COLLISION) + WATER_MOVE > t) { phase = "water"; forward = true; }
        else if ((cursor += WATER_MOVE) + LAVA_MOVE > t) { phase = "lava"; forward = true; }
        else if ((cursor += LAVA_MOVE) + SPEED_EFFECT > t) { phase = "speed-effect"; forward = true; }
        else if ((cursor += SPEED_EFFECT) + SLOWNESS_EFFECT > t) { phase = "slowness-effect"; forward = true; }
        else if ((cursor += SLOWNESS_EFFECT) + JUMP_BOOST > t) { phase = "jump-boost"; forward = true; jump = t == cursor || t == cursor + 1; }
        else if ((cursor += JUMP_BOOST) + STEP > t) { phase = "step"; forward = true; }
        else phase = "tail";

        long start = phaseStartTick();
        if (t == start) {
            switch (phase) {
                case "walk" -> serverReset(client, -10.5, 64.0, 0.5, -90);
                case "sprint" -> serverReset(client, -10.5, 64.0, 2.5, -90);
                case "jump" -> serverReset(client, -10.5, 64.0, 4.5, -90);
                case "sneak" -> serverReset(client, -10.5, 64.0, 6.5, -90);
                case "diagonal" -> serverReset(client, -10.5, 64.0, 8.5, -90);
                case "collision" -> serverReset(client, -10.5, 64.0, 10.5, -90);
                case "water" -> serverReset(client, -10.5, 65.0, -5.5, -90);
                case "lava" -> serverReset(client, -10.5, 65.0, -12.5, -90);
                case "speed-effect" -> { serverReset(client, -10.5, 64.0, 13.5, -90); giveEffect(client, "minecraft:speed", 0); }
                case "slowness-effect" -> { serverReset(client, -10.5, 64.0, 15.5, -90); giveEffect(client, "minecraft:slowness", 0); }
                case "jump-boost" -> { serverReset(client, -10.5, 64.0, 17.5, -90); giveEffect(client, "minecraft:jump_boost", 0); }
                case "step" -> serverReset(client, -10.5, 64.0, 20.5, -90);
                default -> { }
            }
        }
        apply(client.options, forward, false, left, right, jump, sneak, sprint);
        if (t >= ALL_TOTAL - TAIL) phase = "tail";
        finishIfDone(client, ALL_TOTAL);
    }

    private void prepareWorld(MinecraftClient client) {
        IntegratedServer server = client.getServer();
        if (server == null) throw new IllegalStateException("Phase 5 requires a local singleplayer world (integrated server)");
        server.executeSync(() -> {
            CommandManager m = server.getCommandManager(); ServerCommandSource s = server.getCommandSource();
            command(m, s, "forceload add -16 -16 16 16");
            command(m, s, "difficulty peaceful"); command(m, s, "time set day"); command(m, s, "weather clear");
            command(m, s, "fill -16 60 -16 16 67 24 air");
            command(m, s, "fill -16 63 -16 16 63 24 minecraft:stone");
            command(m, s, "fill 0 64 9 0 66 12 minecraft:stone");
            command(m, s, "fill 0 64 20 4 64 21 minecraft:stone");
            command(m, s, "fill 0 65 20 4 65 21 minecraft:stone");
            command(m, s, "fill -15 64 -6 15 66 -5 minecraft:water");
            command(m, s, "fill -15 64 -13 15 66 -12 minecraft:lava");
            command(m, s, "gamemode survival @a"); command(m, s, "effect clear @a"); command(m, s, "tp @a -10.5 64 0.5 -90 0");
        });
    }

    private long phaseStartTick() {
        return switch (phase) {
            case "walk" -> SETUP; case "sprint" -> SETUP + WALK; case "jump" -> SETUP + WALK + SPRINT;
            case "sneak" -> SETUP + WALK + SPRINT + JUMP; case "diagonal" -> SETUP + WALK + SPRINT + JUMP + SNEAK;
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

    private void serverReset(MinecraftClient client, double x, double y, double z, double yaw) {
        IntegratedServer server = client.getServer();
        if (server == null) throw new IllegalStateException("Integrated server disappeared during Phase 5 reset");
        server.executeSync(() -> { CommandManager m = server.getCommandManager(); ServerCommandSource s = server.getCommandSource(); command(m, s, "effect clear @a"); command(m, s, "gamemode survival @a"); command(m, s, "tp @a " + x + " " + y + " " + z + " " + yaw + " 0"); });
    }
    private void giveEffect(MinecraftClient client, String id, int amplifier) {
        IntegratedServer server = client.getServer(); if (server == null) throw new IllegalStateException("Integrated server missing");
        server.executeSync(() -> command(server.getCommandManager(), server.getCommandSource(), "effect give @a " + id + " 120 " + amplifier + " true"));
    }
    private static void command(CommandManager m, ServerCommandSource s, String c) { m.parseAndExecute(s, c); }
    private void finishIfDone(MinecraftClient client, int total) { if (scenarioTick++ >= total - 1) { finished = true; release(client.options); client.scheduleStop(); } }
    private static void apply(GameOptions o, boolean f, boolean b, boolean l, boolean r, boolean j, boolean sn, boolean sp) { o.forwardKey.setPressed(f); o.backKey.setPressed(b); o.leftKey.setPressed(l); o.rightKey.setPressed(r); o.jumpKey.setPressed(j); o.sneakKey.setPressed(sn); o.sprintKey.setPressed(sp); }
    private static void release(GameOptions o) { apply(o, false, false, false, false, false, false, false); }
}
