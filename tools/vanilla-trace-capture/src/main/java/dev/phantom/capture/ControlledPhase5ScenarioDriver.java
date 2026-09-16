package dev.phantom.capture;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Locale;

/** Fully deterministic Phase 5 course/input driver for the real 1.21.11 client. */
public final class ControlledPhase5ScenarioDriver {
    private static final String PROP = "phantom.capture.scenario";
    private static final String ALL = "all";
    private static final String WATER = "water";
    private static final String NONE = "none";

    private static final int SETUP = 40, WALK = 100, SPRINT = 100, JUMP = 100, SNEAK = 100,
            DIAGONAL = 100, COLLISION = 100, WATER_MOVE = 120, LAVA_MOVE = 30,
            SPEED_EFFECT = 80, SLOWNESS_EFFECT = 80, JUMP_BOOST = 100, STEP = 110, TAIL = 70,
            STAIRS = 90, CLIMBABLE = 100, EDGE_CORNER = 100, SWIM_TRANSITION = 120,
            GLIDE = 120, CORRECTION = 40, VELOCITY = 80;
    private static final int RESET_SETTLE = 10;

    /** Each phase owns a large 140-block-long arena on its own Z segment. */
    private static final int ARENA_SPACING = 140;
    private static final int ARENA_START_Z = -80;
    private static final int ARENA_LENGTH = 120;
    private static final int ARENA_MIN_X = -32;
    private static final int ARENA_MAX_X = 32;

    private static final String[] ALL_PHASES = {
            "walk", "sprint", "jump", "sneak", "diagonal", "collision",
            "water", "lava", "speed-effect", "slowness-effect", "jump-boost", "step", "tail",
            "stairs", "climbable", "edge-corner", "swim-transition", "glide", "correction", "velocity"
    };
    private static final int[] ALL_DURATIONS = {
            WALK, SPRINT, JUMP, SNEAK, DIAGONAL, COLLISION,
            WATER_MOVE, LAVA_MOVE, SPEED_EFFECT, SLOWNESS_EFFECT, JUMP_BOOST, STEP, TAIL,
            STAIRS, CLIMBABLE, EDGE_CORNER, SWIM_TRANSITION, GLIDE, CORRECTION, VELOCITY
    };

    private static final String[] DRY_ARENAS = {
            "walk", "sprint", "jump", "sneak", "diagonal", "collision",
            "speed-effect", "slowness-effect", "jump-boost", "step", "tail", "stairs", "climbable", "edge-corner", "glide", "correction", "velocity"
    };

    private long scenarioTick = -1;
    private String scenario = NONE;
    private String phase = "idle";
    private String pendingPhase = null;
    private long pendingResetStart = Long.MIN_VALUE;
    private boolean finished;
    private boolean worldPrepared;
    private int activePhaseIndex = -1;
    private int activePhaseElapsed;
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
            pendingPhase = null; pendingResetStart = Long.MIN_VALUE;
            activePhaseIndex = -1; activePhaseElapsed = 0;
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
        boolean forward = false, right = false, sprint = false, jump = false, sneak = false;
        int startZ = arenaStartFor("water");
        if (t < 20) {
            phase = "water-baseline";
            if (t == 0) serverReset(client, 0.0, 64.0, startZ + 12.0, 0);
        } else if (t < 20 + WATER_MOVE) {
            phase = "water"; forward = true; sprint = true;
        } else if (t < 20 + WATER_MOVE + 40) {
            phase = "water-release";
            jump = t % 12 < 6;
        } else if (t < 20 + WATER_MOVE + 40 + 80) {
            phase = "water-diagonal"; forward = true; right = true; sprint = true; jump = t % 20 < 5;
        } else phase = "water-tail";
        apply(client.options, forward, false, false, right, jump, sneak, sprint);
        finishIfDone(client, 20 + WATER_MOVE + 40 + 80 + 40);
    }

    private void runAll(MinecraftClient client) {
        if (activePhaseIndex < 0) {
            phase = "setup";
            apply(client.options, false, false, false, false, false, false, false);
            if (scenarioTick >= SETUP) beginPhaseTransition(client, 0);
            scenarioTick++;
            return;
        }

        if (pendingPhase != null) {
            int targetZ = arenaStartFor(pendingPhase);
            double targetY = arenaStartYFor(pendingPhase);
            double targetZExact = targetZ + 12.0;
            boolean landed = Math.abs(client.player.getX()) < 1.5
                    && Math.abs(client.player.getY() - targetY) < 1.5
                    && Math.abs(client.player.getZ() - targetZExact) < 1.5;
            phase = landed ? pendingPhase : "transition";
            if (!landed) {
                if (scenarioTick - pendingResetStart >= RESET_SETTLE) {
                    performResetForPhase(client, pendingPhase);
                    pendingResetStart = scenarioTick;
                }
                release(client.options);
                scenarioTick++;
                return;
            }

            pendingPhase = null;
            pendingResetStart = Long.MIN_VALUE;
            activePhaseElapsed = 0;
            phase = ALL_PHASES[activePhaseIndex];
        }

        String current = ALL_PHASES[activePhaseIndex];
        boolean forward = true, back = false, left = false, right = false, jump = false, sneak = false, sprint = false;
        switch (current) {
            case "walk" -> { }
            case "sprint" -> sprint = true;
            case "jump" -> jump = activePhaseElapsed < 2;
            case "sneak" -> sneak = true;
            case "diagonal" -> left = true;
            case "collision" -> { }
            case "water" -> sprint = true;
            case "lava", "speed-effect", "slowness-effect" -> { }
            case "jump-boost" -> jump = activePhaseElapsed < 2;
            case "step", "tail" -> { }
            case "stairs" -> { sprint = true; jump = activePhaseElapsed % 28 == 1; }
            case "climbable" -> { sprint = false; }
            case "edge-corner" -> { left = activePhaseElapsed < 50; right = !left; }
            case "swim-transition" -> { sprint = true; jump = activePhaseElapsed % 24 < 6; sneak = activePhaseElapsed % 24 >= 12 && activePhaseElapsed % 24 < 18; }
            case "glide" -> { sprint = true; }
            case "correction" -> { }
            case "velocity" -> { }
            default -> throw new IllegalStateException("Unknown active Phase 5 phase: " + current);
        }

        if (current.equals("correction") && activePhaseElapsed == 8) serverReset(client, 2.0, 65.0, arenaStartFor(current) + 20.0, 90);
        if (current.equals("correction") && activePhaseElapsed == 24) serverReset(client, -2.0, 65.0, arenaStartFor(current) + 24.0, 270);
        if (current.equals("velocity") && activePhaseElapsed == 12) applyWindChargeImpulse(client);
        if (current.equals("velocity") && activePhaseElapsed == 44) applyWindChargeImpulse(client);

        phase = current;
        apply(client.options, forward, back, left, right, jump, sneak, sprint);
        activePhaseElapsed++;
        scenarioTick++;

        if (activePhaseElapsed >= ALL_DURATIONS[activePhaseIndex]) {
            if (activePhaseIndex + 1 < ALL_PHASES.length) {
                beginPhaseTransition(client, activePhaseIndex + 1);
            } else {
                finished = true;
                release(client.options);
                client.scheduleStop();
            }
        }
    }

    private void beginPhaseTransition(MinecraftClient client, int nextIndex) {
        activePhaseIndex = nextIndex;
        String nextPhase = ALL_PHASES[nextIndex];
        pendingPhase = nextPhase;
        pendingResetStart = scenarioTick;
        phase = "transition";
        activePhaseElapsed = 0;
        performResetForPhase(client, nextPhase);
        release(client.options);
    }

    private void performResetForPhase(MinecraftClient client, String phaseName) {
        double y = arenaStartYFor(phaseName);
        serverReset(client, 0.0, y, arenaStartFor(phaseName) + 12.0, 0);
        if (phaseName.equals("speed-effect")) giveEffect(client, "minecraft:speed", 0);
        else if (phaseName.equals("slowness-effect")) giveEffect(client, "minecraft:slowness", 0);
        else if (phaseName.equals("jump-boost")) giveEffect(client, "minecraft:jump_boost", 0);
        else if (phaseName.equals("climbable")) {
            giveEffect(client, "minecraft:slow_falling", 0);
            facePhase(client, 180);
        } else if (phaseName.equals("glide")) {
            equipElytra(client);
            setFallFlying(client, true);
            facePhase(client, 0);
        } else if (phaseName.equals("velocity")) {
            facePhase(client, 0);
        }
    }

    private static double arenaStartYFor(String name) {
        return name.equals("glide") ? 110.0 : 64.0;
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
            case "stairs" -> ARENA_START_Z + ARENA_SPACING * 13;
            case "climbable" -> ARENA_START_Z + ARENA_SPACING * 14;
            case "edge-corner" -> ARENA_START_Z + ARENA_SPACING * 15;
            case "swim-transition" -> ARENA_START_Z + ARENA_SPACING * 16;
            case "glide" -> ARENA_START_Z + ARENA_SPACING * 17;
            case "correction" -> ARENA_START_Z + ARENA_SPACING * 18;
            case "velocity" -> ARENA_START_Z + ARENA_SPACING * 19;
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
                command(m, s, "fill " + ARENA_MIN_X + " 64 " + z + " " + ARENA_MAX_X + " 108 " + (z + ARENA_LENGTH - 1) + " air");
                command(m, s, "fill " + ARENA_MIN_X + " 63 " + z + " " + ARENA_MAX_X + " 63 " + (z + ARENA_LENGTH - 1) + " minecraft:stone");
            }

            int collisionZ = arenaStartFor("collision");
            command(m, s, "fill -2 64 " + (collisionZ + 48) + " 2 66 " + (collisionZ + 52) + " minecraft:stone");

            int stepZ = arenaStartFor("step");
            command(m, s, "fill -4 64 " + (stepZ + 32) + " 4 64 " + (stepZ + 35) + " minecraft:oak_slab[type=bottom]");

            int stairsZ = arenaStartFor("stairs");
            command(m, s, "fill -2 64 " + (stairsZ + 30) " 2 64 " + (stairsZ + 32) + " minecraft:oak_stairs[facing=south,half=bottom,shape=straight]");
            command(m, s, "fill -2 65 " + (stairsZ + 33) " 2 65 " + (stairsZ + 35) + " minecraft:oak_stairs[facing=south,half=bottom,shape=straight]");
            command(m, s, "fill -2 66 " + (stairsZ + 36) " 2 66 " + (stairsZ + 38) + " minecraft:oak_stairs[facing=south,half=bottom,shape=straight]");

            int climbZ = arenaStartFor("climbable");
            command(m, s, "fill -2 64 " + (climbZ + 30) " 2 69 " + (climbZ + 30) + " minecraft:stone");
            command(m, s, "fill -2 64 " + (climbZ + 29) " 2 69 " + (climbZ + 29) + " minecraft:ladder[facing=south]");
            command(m, s, "fill -1 64 " + (climbZ + 20) " 1 69 " + (climbZ + 20) + " minecraft:vine");

            int edgeZ = arenaStartFor("edge-corner");
            command(m, s, "fill 3 64 " + (edgeZ + 28) " 3 67 " + (edgeZ + 70) + " minecraft:stone");
            command(m, s, "fill 3 64 " + (edgeZ + 50) " 8 67 " + (edgeZ + 50) + " minecraft:stone");
            command(m, s, "fill -3 64 " + (edgeZ + 65) " 2 67 " + (edgeZ + 65) + " minecraft:stone");

            int swimZ = arenaStartFor("swim-transition");
            command(m, s, "fill -40 62 " + (swimZ + 6) " 40 62 " + (swimZ + 100) + " minecraft:stone");
            command(m, s, "fill -40 63 " + (swimZ + 6) " 40 67 " + (swimZ + 100) + " minecraft:water");
            command(m, s, "fill -40 68 " + (swimZ + 6) " 40 68 " + (swimZ + 45) + " minecraft:air");
            command(m, s, "fill -40 68 " + (swimZ + 46) " 40 68 " + (swimZ + 100) + " minecraft:glass");

            int glideZ = arenaStartFor("glide");
            command(m, s, "fill -30 63 " + (glideZ + 6) " 30 63 " + (glideZ + 110) + " minecraft:stone");
            command(m, s, "fill -30 64 " + (glideZ + 6) " 30 108 " + (glideZ + 110) + " minecraft:air");
            command(m, s, "fill -30 64 " + (glideZ + 90) " 30 90 " + (glideZ + 90) + " minecraft:glass");

            int correctionZ = arenaStartFor("correction");
            command(m, s, "fill -8 64 " + (correctionZ + 8) " 8 67 " + (correctionZ + 90) + " air");
            command(m, s, "fill -8 63 " + (correctionZ + 8) " 8 63 " + (correctionZ + 90) + " minecraft:stone");

            int velocityZ = arenaStartFor("velocity");
            command(m, s, "fill -12 64 " + (velocityZ + 8) " 12 67 " + (velocityZ + 90) + " air");
            command(m, s, "fill -12 63 " + (velocityZ + 8) " 12 63 " + (velocityZ + 90) + " minecraft:stone");

            command(m, s, "gamemode survival @a"); command(m, s, "effect clear @a");
            command(m, s, "execute as @a run data modify entity @s Fire set value 0s");
            command(m, s, "tp @a 0 64 " + (arenaStartFor("walk") + 12) + " 0 0");
            command(m, s, "forceload remove all");
        });
    }

    private void serverReset(MinecraftClient client, double x, double y, double z, double yaw) {
        IntegratedServer server = client.getServer();
        if (server == null) throw new IllegalStateException("Integrated server disappeared during Phase 5 reset");
        server.executeSync(() -> {
            CommandManager m = server.getCommandManager(); ServerCommandSource s = server.getCommandSource();
            command(m, s, "effect clear @a");
            command(m, s, "gamemode survival @a");
            command(m, s, "execute as @a run data modify entity @s Fire set value 0s");
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

    private void equipElytra(MinecraftClient client) {
        IntegratedServer server = client.getServer();
        if (server == null) throw new IllegalStateException("Integrated server disappeared during Phase 5 elytra setup");
        server.executeSync(() -> {
            CommandManager m = server.getCommandManager(); ServerCommandSource s = server.getCommandSource();
            command(m, s, "item replace entity @a armor.chest with minecraft:elytra");
        });
    }

    private void setFallFlying(MinecraftClient client, boolean value) {
        IntegratedServer server = client.getServer();
        if (server == null) throw new IllegalStateException("Integrated server disappeared during Phase 5 gliding setup");
        server.executeSync(() -> {
            CommandManager m = server.getCommandManager(); ServerCommandSource s = server.getCommandSource();
            command(m, s, "execute as @a run data modify entity @s FallFlying set value " + (value ? "1b" : "0b"));
        });
    }

    private void facePhase(MinecraftClient client, int yaw) {
        IntegratedServer server = client.getServer();
        if (server == null) throw new IllegalStateException("Integrated server disappeared during Phase 5 orientation setup");
        server.executeSync(() -> {
            CommandManager m = server.getCommandManager(); ServerCommandSource s = server.getCommandSource();
            command(m, s, "tp @a ~ ~ ~ " + yaw + " 0");
        });
    }

    private void applyWindChargeImpulse(MinecraftClient client) {
        IntegratedServer server = client.getServer();
        if (server == null) throw new IllegalStateException("Integrated server disappeared during Phase 5 velocity setup");
        server.executeSync(() -> {
            CommandManager m = server.getCommandManager(); ServerCommandSource s = server.getCommandSource();
            command(m, s, "execute as @a at @s run summon minecraft:wind_charge ~ ~1 ~ {Motion:[0.0d,0.0d,1.0d],AccelerationPower:1.0f}");
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
