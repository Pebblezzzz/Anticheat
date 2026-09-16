package dev.phantom.capture;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;

/** Deterministic Phase 5 capture driver with independent capture parts and isolated arenas. */
public final class ControlledPhase5ScenarioDriver {
    private static final String PROP = "phantom.capture.scenario";
    private static final String NONE = "none";
    private static final String[] PHASES = {
            "walk","sprint","jump","sneak","diagonal","collision","water","lava",
            "speed-effect","slowness-effect","jump-boost","step","tail","stairs",
            "climbable","edge-corner","swim-transition","glide","correction"
    };
    private static final int[] DURATIONS = {
            100,100,100,100,100,100,120,30,80,80,100,110,70,90,100,100,120,120,40
    };
    private static final int START_Z = -40;
    private static final int SPACING = 180;
    private static final double FALL_CUTOFF_Y = 2.0D;
    private static final double RESET_TOLERANCE = 0.75D;
    private static final int RESET_TIMEOUT_TICKS = 100;
    private static volatile ControlledPhase5ScenarioDriver LAST;

    private String scenario = NONE;
    private int phaseIndex = -1;
    private int startIndex;
    private int endIndex;
    private int elapsed;
    private int resetWait;
    private double resetX;
    private double resetY;
    private double resetZ;
    private float resetYaw;
    private volatile boolean prepared;
    private volatile boolean done;
    private volatile boolean failureReported;
    private volatile boolean preparing;
    private boolean phaseResetPending;

    public ControlledPhase5ScenarioDriver() { LAST = this; }

    public static String phaseLabel() {
        ControlledPhase5ScenarioDriver d = LAST;
        if (d == null) return "unknown";
        if (d.phaseIndex < 0) return d.scenario + ":setup";
        if (d.phaseIndex >= PHASES.length) return d.scenario + ":done";
        return d.scenario + ":" + PHASES[d.phaseIndex];
    }

    public void tick(MinecraftClient client) {
        if (!Boolean.parseBoolean(System.getProperty("phantom.capture.enabled", "false"))) return;
        String requested = System.getProperty(PROP, NONE).trim().toLowerCase(Locale.ROOT);
        if (requested.equals(NONE) || client.player == null || client.world == null) { release(client.options); return; }
        if (!configureRange(requested)) throw new IllegalArgumentException("Unsupported Phase 5 scenario: " + requested + " (use all, part1, part2, part3, part4, or part5)");
        if (!requested.equals(scenario)) {
            scenario = requested;
            phaseIndex = startIndex - 1;
            elapsed = 0;
            resetWait = 0;
            prepared = false;
            done = false;
            failureReported = false;
            preparing = false;
            phaseResetPending = false;
            release(client.options);
        }
        if (done) { release(client.options); return; }
        if (!prepared) {
            if (!preparing) {
                preparing = true;
                prepare(client);
            }
            release(client.options);
            return;
        }
        if (phaseResetPending) {
            waitForReset(client);
            return;
        }
        if (phaseIndex < startIndex) {
            beginPhase(client, startIndex);
            return;
        }
        runPhase(client);
    }

    private boolean configureRange(String requested) {
        switch (requested) {
            case "all" -> { startIndex = 0; endIndex = PHASES.length - 1; }
            case "part1" -> { startIndex = 0; endIndex = 5; }
            case "part2" -> { startIndex = 6; endIndex = 9; }
            case "part3" -> { startIndex = 10; endIndex = 13; }
            case "part4" -> { startIndex = 14; endIndex = 16; }
            case "part5" -> { startIndex = 17; endIndex = 18; }
            default -> { return false; }
        }
        return true;
    }

    private void runPhase(MinecraftClient client) {
        if (client.player == null) return;
        if ((client.player.isDead() || client.player.getHealth() <= 0.0F || client.player.getY() < FALL_CUTOFF_Y) && !failureReported) {
            failureReported = true;
            String message = "CAPTURE_STOPPED part=" + scenario + " phase=" + PHASES[phaseIndex] + " elapsed=" + elapsed
                    + " reason=" + (client.player.isDead() || client.player.getHealth() <= 0.0F ? "player_dead" : "player_left_course")
                    + " state=" + describe(client);
            System.err.println("[Phase5] " + message);
            Phase5CaptureDebug.failure(PHASES[phaseIndex], message);
            done = true;
            release(client.options);
            client.scheduleStop();
            return;
        }

        String phase = PHASES[phaseIndex];
        configureInput(client.options, phase, elapsed);
        if (phase.equals("glide")) driveGlide(client);
        if (phase.equals("correction")) {
            if (elapsed == 8) requestReset(client, 2, 65, baseZ(phase) + 20, 90);
            if (elapsed == 24) requestReset(client, -2, 65, baseZ(phase) + 24, 270);
        }
        Phase5CaptureDebug.tick(client, phase, elapsed, baseZ(phase) + 80);
        elapsed++;

        if (elapsed >= DURATIONS[phaseIndex]) {
            Phase5CaptureDebug.end(phase);
            clearHazards(client, phase);
            if (phaseIndex >= endIndex) {
                done = true;
                release(client.options);
                System.out.println("[Phase5] COMPLETE " + scenario);
                client.scheduleStop();
            } else {
                beginPhase(client, phaseIndex + 1);
            }
        }
    }

    private void beginPhase(MinecraftClient client, int index) {
        phaseIndex = index;
        elapsed = 0;
        failureReported = false;
        requestPhaseReset(client, PHASES[index]);
    }

    private void requestPhaseReset(MinecraftClient client, String phase) {
        boolean fluidEntry = phase.equals("water") || phase.equals("lava") || phase.equals("swim-transition");
        double y = phase.equals("glide") ? 90.0D : 64.0D;
        double z = baseZ(phase) + (fluidEntry ? 2.0D : 2.0D);
        requestReset(client, 0.0D, y, z, 0.0D);
    }

    private void requestReset(MinecraftClient client, double x, double y, double z, double yaw) {
        resetX = x;
        resetY = y;
        resetZ = z;
        resetYaw = (float) yaw;
        resetWait = 0;
        phaseResetPending = true;
        reset(client, x, y, z, yaw);
        release(client.options);
    }

    private void waitForReset(MinecraftClient client) {
        if (client.player == null) return;
        resetWait++;
        if (resetWait == 1 || resetWait % 20 == 0) {
            System.out.println("[Phase5] waiting reset phase=" + PHASES[phaseIndex] + " tick=" + resetWait + " state=" + describe(client));
        }
        boolean atTarget = client.player.getX() - resetX == 0.0D && client.player.getY() - resetY == 0.0D && client.player.getZ() - resetZ == 0.0D;
        boolean grounded = PHASES[phaseIndex].equals("glide") || client.player.isOnGround();
        if (atTarget && grounded) {
            phaseResetPending = false;
            resetWait = 0;
            resetForPhase(client, PHASES[phaseIndex]);
            release(client.options);
            Phase5CaptureDebug.resetAndStart(client, PHASES[phaseIndex], phaseIndex, baseZ(PHASES[phaseIndex]) + 80);
            System.out.println("[Phase5] START " + scenario + " / " + PHASES[phaseIndex] + " (" + (phaseIndex - startIndex + 1) + "/" + (endIndex - startIndex + 1) + ")");
            return;
        }
        if (resetWait >= RESET_TIMEOUT_TICKS) {
            String message = "CAPTURE_STOPPED part=" + scenario + " phase=" + PHASES[phaseIndex] + " reason=reset_timeout target="
                    + resetX + "," + resetY + "," + resetZ + " state=" + describe(client);
            System.err.println("[Phase5] " + message);
            Phase5CaptureDebug.failure(PHASES[phaseIndex], message);
            done = true;
            release(client.options);
            client.scheduleStop();
        }
    }

    private void resetForPhase(MinecraftClient client, String phase) {
        if (phase.equals("speed-effect")) effect(client, "minecraft:speed");
        if (phase.equals("slowness-effect")) effect(client, "minecraft:slowness");
        if (phase.equals("jump-boost")) effect(client, "minecraft:jump_boost");
        if (phase.equals("lava")) effect(client, "minecraft:fire_resistance");
        if (phase.equals("glide")) {
            client.player.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
            client.player.setOnGround(false);
            client.player.setVelocity(0.0D, -0.12D, 0.0D);
            client.player.fallDistance = 2.0F;
        }
    }

    private void driveGlide(MinecraftClient client) {
        var p = client.player;
        if (p.isGliding()) return;
        if (!p.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA)) {
            p.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
        }
        p.setOnGround(false);
        p.fallDistance = Math.max(p.fallDistance, 2.0F);
        if (p.checkGliding()) p.startGliding();
        if (p.isGliding() && elapsed == 0) System.out.println("[Phase5] GLIDE activated");
    }

    private void reset(MinecraftClient client, double x, double y, double z, double yaw) {
        IntegratedServer server = client.getServer();
        if (server == null) throw new IllegalStateException("Integrated server required");
        server.executeSync(() -> {
            ServerPlayerEntity sp = server.getPlayerManager().getPlayer(client.player.getUuid());
            if (sp == null) return;
            sp.setVelocity(Vec3d.ZERO);
            sp.setOnGround(true);
            sp.fallDistance = 0.0F;
            sp.extinguish();
            sp.refreshPositionAndAngles(x, y, z, (float) yaw, 0.0F);
            sp.requestTeleport(x, y, z);
            cmd(server.getCommandManager(), server.getCommandSource(), "effect clear @a");
        });
        client.player.requestTeleport(x, y, z);
        client.player.setVelocity(Vec3d.ZERO);
        client.player.setOnGround(true);
        client.player.fallDistance = 0.0F;
        client.player.extinguish();
        client.player.setYaw((float) yaw);
        client.player.setPitch(0.0F);
    }

    private void clearHazards(MinecraftClient client, String previous) {
        client.player.extinguish();
        if (previous.equals("lava")) clearEffects(client.getServer());
    }

    private void clearEffects(IntegratedServer server) {
        if (server != null) server.executeSync(() -> cmd(server.getCommandManager(), server.getCommandSource(), "effect clear @a"));
    }

    private void effect(MinecraftClient client, String id) {
        IntegratedServer server = client.getServer();
        if (server != null) server.executeSync(() -> cmd(server.getCommandManager(), server.getCommandSource(), "effect give @a " + id + " 600 0 true"));
    }

    private void prepare(MinecraftClient client) {
        IntegratedServer server = client.getServer();
        if (server == null) throw new IllegalStateException("Integrated server required");
        int minPhase = startIndex;
        int maxPhase = endIndex;
        server.execute(() -> {
            ServerWorld world = server.getOverworld();
            int minZ = baseZ(PHASES[minPhase]) - 8;
            int maxZ = baseZ(PHASES[maxPhase]) + 310;
            loadArenaChunks(world, minZ, maxZ);
            CommandManager m = server.getCommandManager();
            ServerCommandSource s = server.getCommandSource();
            System.out.println("[Phase5] arena chunks loaded z=" + minZ + ".." + maxZ);

            cmd(m, s, "difficulty peaceful");
            cmd(m, s, "time set day");
            cmd(m, s, "weather clear");
            fillZ(m, s, -24, 63, -48, 24, 63, maxZ, "minecraft:stone", 600);
            fillZ(m, s, -24, 64, minZ, 24, 67, maxZ, "air", 160);
            fillZ(m, s, -24, 64, minZ, -23, 72, maxZ, "minecraft:stone", 160);
            fillZ(m, s, 23, 64, minZ, 24, 72, maxZ, "minecraft:stone", 160);

            int water = baseZ("water");
            cmd(m, s, "fill -8 64 " + (water + 5) + " 8 65 " + (water + 135) + " minecraft:water");
            int lava = baseZ("lava");
            cmd(m, s, "fill -8 64 " + (lava + 5) + " 8 65 " + (lava + 135) + " minecraft:lava");
            int collision = baseZ("collision");
            cmd(m, s, "fill -3 64 " + (collision + 55) + " 3 66 " + (collision + 59) + " minecraft:stone");
            int step = baseZ("step");
            cmd(m, s, "fill -3 64 " + (step + 25) + " 3 64 " + (step + 29) + " minecraft:oak_slab[type=bottom]");
            int stairs = baseZ("stairs");
            cmd(m, s, "fill -3 64 " + (stairs + 30) + " 3 64 " + (stairs + 33) + " minecraft:oak_stairs[facing=south,half=bottom,shape=straight]");
            cmd(m, s, "fill -3 65 " + (stairs + 34) + " 3 65 " + (stairs + 37) + " minecraft:oak_stairs[facing=south,half=bottom,shape=straight]");
            int climb = baseZ("climbable");
            cmd(m, s, "fill -1 64 " + (climb + 25) + " 1 68 " + (climb + 25) + " minecraft:stone");
            cmd(m, s, "fill -1 64 " + (climb + 24) + " 1 68 " + (climb + 24) + " minecraft:ladder[facing=south]");
            int edge = baseZ("edge-corner");
            cmd(m, s, "fill 4 64 " + (edge + 20) + " 4 68 " + (edge + 70) + " minecraft:stone");
            cmd(m, s, "fill 4 64 " + (edge + 45) + " 8 68 " + (edge + 45) + " minecraft:stone");
            int swim = baseZ("swim-transition");
            cmd(m, s, "fill -8 64 " + (swim + 5) + " 8 65 " + (swim + 130) + " minecraft:water");
            int glide = baseZ("glide");
            cmd(m, s, "fill -10 63 " + (glide + 4) + " 10 63 " + (glide + 300) + " minecraft:stone");
            fillZ(m, s, -10, 64, glide + 4, 10, 120, glide + 300, "air", 160);
            cmd(m, s, "gamemode survival @a");
            cmd(m, s, "effect clear @a");
            prepared = true;
            preparing = false;
            System.out.println("[Phase5] arena preparation complete");
        });
    }

    private static void loadArenaChunks(ServerWorld world, int minZ, int maxZ) {
        int minChunkZ = Math.floorDiv(minZ, 16);
        int maxChunkZ = Math.floorDiv(maxZ, 16);
        int minChunkX = Math.floorDiv(-24, 16);
        int maxChunkX = Math.floorDiv(24, 16);
        for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
            for (int cx = minChunkX; cx <= maxChunkX; cx++) world.getChunk(cx, cz);
        }
    }

    private static void fillZ(CommandManager m, ServerCommandSource s, int x1, int y1, int z1,
                              int x2, int y2, int z2, String block, int maxDepth) {
        int step = Math.max(1, maxDepth);
        for (int start = z1; start <= z2; start += step) {
            int end = Math.min(z2, start + step - 1);
            cmd(m, s, "fill " + x1 + " " + y1 + " " + start + " " + x2 + " " + y2 + " " + end + " " + block);
        }
    }

    private static int baseZ(String phase) {
        for (int i = 0; i < PHASES.length; i++) if (PHASES[i].equals(phase)) return START_Z + SPACING * i;
        throw new IllegalArgumentException(phase);
    }

    private static void configureInput(GameOptions o, String phase, int t) {
        boolean forward = true, left = false, right = false, jump = false, sneak = false, sprint = false;
        switch (phase) {
            case "sprint", "water", "lava", "collision", "step", "stairs", "glide" -> sprint = true;
            case "jump", "jump-boost" -> jump = t < 2;
            case "sneak" -> sneak = true;
            case "diagonal" -> left = true;
            case "edge-corner" -> { left = t < 50; right = !left; }
            case "swim-transition" -> { sprint = true; jump = t % 24 < 6; sneak = t % 24 >= 12 && t % 24 < 18; }
            case "correction", "tail", "walk", "speed-effect", "slowness-effect", "climbable" -> { }
            default -> throw new IllegalStateException(phase);
        }
        apply(o, forward, false, left, right, jump, sneak, sprint);
    }

    private static void apply(GameOptions o, boolean f, boolean b, boolean l, boolean r, boolean j, boolean sn, boolean sp) {
        o.forwardKey.setPressed(f); o.backKey.setPressed(b); o.leftKey.setPressed(l); o.rightKey.setPressed(r);
        o.jumpKey.setPressed(j); o.sneakKey.setPressed(sn); o.sprintKey.setPressed(sp);
    }

    private static void release(GameOptions o) { apply(o, false, false, false, false, false, false, false); }

    private static void cmd(CommandManager m, ServerCommandSource s, String command) { m.parseAndExecute(s, command); }

    private static String describe(MinecraftClient client) {
        var p = client.player;
        return "x=" + p.getX() + ",y=" + p.getY() + ",z=" + p.getZ() + ",hp=" + p.getHealth()
                + ",ground=" + p.isOnGround() + ",gliding=" + p.isGliding() + ",vel=" + p.getVelocity();
    }
}
