package dev.phantom.capture;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.integrated.IntegratedServer;

import java.util.Locale;

/** Deterministic input/world driver for empirical Phase 5 captures. */
public final class VanillaScenarioDriver {
    private static final String PROP = "phantom.capture.scenario";
    private static final String ALL = "all";
    private static final String WATER = "water";
    private static final String NONE = "none";
    private static final int SETUP = 40, WALK = 100, SPRINT = 100, JUMP = 100, SNEAK = 100, DIAGONAL = 100,
            COLLISION = 100, WATER_MOVE = 120, LAVA_MOVE = 120, SPEED_EFFECT = 80, SLOWNESS_EFFECT = 80,
            JUMP_BOOST = 100, STEP = 110, TAIL = 40;
    private static final int ALL_TOTAL = SETUP + WALK + SPRINT + JUMP + SNEAK + DIAGONAL + COLLISION + WATER_MOVE + LAVA_MOVE
            + SPEED_EFFECT + SLOWNESS_EFFECT + JUMP_BOOST + STEP + TAIL;
    private long scenarioTick = -1;
    private String scenario = NONE;
    private String phase = "idle";
    private boolean finished;
    private boolean worldPrepared;
    private static volatile VanillaScenarioDriver LAST_INSTANCE;
    public static String phaseLabel() { VanillaScenarioDriver d = LAST_INSTANCE; return d == null ? "unknown" : d.scenario + ":" + d.phase; }
    public VanillaScenarioDriver() { LAST_INSTANCE = this; }

    public void tick(MinecraftClient client) {
        if (!Boolean.parseBoolean(System.getProperty("phantom.capture.enabled", "false"))) return;
        String requested = System.getProperty(PROP, NONE).trim().toLowerCase(Locale.ROOT);
        if (requested.equals(NONE) || client.player == null || client.world == null) { release(client.options); return; }
        if (!requested.equals(ALL) && !requested.equals(WATER)) throw new IllegalArgumentException("Unsupported Phase 5 scenario: " + requested);
        if (!requested.equals(scenario)) { scenario = requested; scenarioTick = 0; finished = false; worldPrepared = false; phase = "setup"; release(client.options); }
        if (finished) { release(client.options); return; }
        if (!worldPrepared) { prepareWorld(client); worldPrepared = true; scenarioTick = 0; return; }
        if (ALL.equals(scenario)) runAll(client); else runWaterOnly(client);
    }

    private void runWaterOnly(MinecraftClient client) {
        long t = scenarioTick; boolean forward = false, right = false;
        if (t < 20) { phase = "water-baseline"; }
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
                case "walk" -> serverReset(client, -30.5, 64, -6.5, -90);
                case "sprint" -> serverReset(client, -30.5, 64, 1.5, -90);
                case "jump" -> serverReset(client, -30.5, 64, 9.5, -90);
                case "sneak" -> serverReset(client, -30.5, 64, 17.5, -90);
                case "diagonal" -> serverReset(client, -30.5, 64, 25.5, -90);
                case "collision" -> serverReset(client, 0.5, 64, -18.5, -90);
                case "water" -> serverReset(client, 27.5, 66, 4.5, 0);
                case "lava" -> serverReset(client, 27.5, 66, 19.5, 0);
                case "speed-effect" -> { serverReset(client, -4.5, 64, 33.5, -90); giveEffect(client, "minecraft:speed", 0); }
                case "slowness-effect" -> { serverReset(client, -4.5, 64, 40.5, -90); giveEffect(client, "minecraft:slowness", 0); }
                case "jump-boost" -> { serverReset(client, -4.5, 64, 47.5, -90); giveEffect(client, "minecraft:jump_boost", 0); }
                case "step" -> serverReset(client, 12.5, 64, -18.5, -90);
                default -> { }
            }
        }
        apply(client.options, forward, false, left, right, jump, sneak, sprint);
        if (t >= ALL_TOTAL - TAIL) phase = "tail";
        finishIfDone(client, ALL_TOTAL);
    }

    private void prepareWorld(MinecraftClient client) {
        IntegratedServer server = client.getServer();
        if (server == null) throw new IllegalStateException("Phase 5 automated scenarios require a local singleplayer world (integrated server)");
        server.executeSync(() -> {
            CommandManager m = server.getCommandManager(); ServerCommandSource s = server.getCommandSource();
            command(m,s,"fill -40 60 -10 40 64 55 air");
            command(m,s,"fill -40 65 -10 40 69 55 air");
            command(m,s,"fill -40 70 -10 40 72 55 air");
            command(m,s,"fill -40 63 -10 40 63 55 minecraft:stone");
            command(m,s,"fill 2 64 -21 2 67 -14 minecraft:stone");
            command(m,s,"fill 12 64 -21 16 64 -14 minecraft:stone_slab[type=bottom]");
            command(m,s,"fill 17 64 -21 21 64 -14 minecraft:stone");
            command(m,s,"fill 25 64 0 31 70 8 minecraft:water");
            command(m,s,"fill 25 64 15 31 70 23 minecraft:lava");
            command(m,s,"fill 14 64 -19 14 64 -14 minecraft:stone");
            command(m,s,"fill 14 65 -19 14 65 -14 minecraft:stone");
            command(m,s,"gamemode survival @a");
            command(m,s,"effect clear @a");
            command(m,s,"tp @a -30.5 64 -6.5 -90 0");
        });
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

    private void serverReset(MinecraftClient client, double x, double y, double z, double yaw) {
        IntegratedServer server = client.getServer(); if (server == null) throw new IllegalStateException("Integrated server disappeared during Phase 5 reset");
        server.executeSync(() -> { CommandManager m=server.getCommandManager(); ServerCommandSource s=server.getCommandSource(); command(m,s,"effect clear @a"); command(m,s,"gamemode survival @a"); command(m,s,"tp @a "+x+" "+y+" "+z+" "+yaw+" 0"); });
    }
    private void giveEffect(MinecraftClient client, String id, int amplifier) { IntegratedServer server=client.getServer(); if(server==null)throw new IllegalStateException("Integrated server missing"); server.executeSync(()->command(server.getCommandManager(),server.getCommandSource(),"effect give @a "+id+" 120 "+amplifier+" true")); }
    private static void command(CommandManager m, ServerCommandSource s, String c) { int result=m.parseAndExecute(s,c); if(result<0) throw new IllegalStateException("Phase 5 setup command failed: "+c); }
    private void finishIfDone(MinecraftClient client,int total){if(scenarioTick++>=total-1){finished=true;release(client.options);client.scheduleStop();}}
    private static void apply(GameOptions o,boolean f,boolean b,boolean l,boolean r,boolean j,boolean sn,boolean sp){o.forwardKey.setPressed(f);o.backKey.setPressed(b);o.leftKey.setPressed(l);o.rightKey.setPressed(r);o.jumpKey.setPressed(j);o.sneakKey.setPressed(sn);o.sprintKey.setPressed(sp);}
    private static void release(GameOptions o){apply(o,false,false,false,false,false,false,false);}
}
