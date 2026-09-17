package dev.phantom.capture;

import net.minecraft.block.Blocks;
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
import net.minecraft.util.math.BlockPos;
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

    private int laneZ(String phase) {
        for (int i = 0; i < PHASES.length; i++) {
            if (PHASES[i].equals(phase)) return START_Z + SPACING * (i - startIndex);
        }
        throw new IllegalArgumentException(phase);
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
            if (elapsed == 8) requestReset(client, 2, 65, laneZ(phase) + 20, 90);
            if (elapsed == 24) requestReset(client, -2, 65, laneZ(phase) + 24, 270);
        }
        Phase5CaptureDebug.tick(client, phase, elapsed, laneZ(phase) + 80);
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
        double y = phase.equals("glide") ? 90.0D : 64.0D;
        double z = laneZ(phase) + (phase.equals("water") || phase.equals("swim-transition") ? 6.0D : 2.0D);
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
        client.player.requestTeleport(resetX, resetY, resetZ);
        client.player.setVelocity(Vec3d.ZERO);
        client.player.setYaw(resetYaw);
        client.player.setPitch(0.0F);

        if (resetWait == 1 || resetWait % 20 == 0) {
            System.out.println("[Phase5] waiting reset phase=" + PHASES[phaseIndex] + " tick=" + resetWait + " state=" + describe(client));
        }

        boolean atTarget = Math.abs(client.player.getX() - resetX) <= RESET_TOLERANCE
                && Math.abs(client.player.getY() - resetY) <= RESET_TOLERANCE
                && Math.abs(client.player.getZ() - resetZ) <= RESET_TOLERANCE;
        BlockPos below = BlockPos.ofFloored(resetX, resetY - 0.05D, resetZ);
        boolean blockPresent = !client.world.getBlockState(below).isAir();
        boolean grounded = PHASES[phaseIndex].equals("glide") || blockPresent;

        if (atTarget && grounded) {
            phaseResetPending = false;
            resetWait = 0;
            resetForPhase(client, PHASES[phaseIndex]);
            release(client.options);
            Phase5CaptureDebug.resetAndStart(client, PHASES[phaseIndex], phaseIndex, laneZ(PHASES[phaseIndex]) + 80);
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
        int minZ = laneZ(PHASES[startIndex]) - 8;
        int maxZ = laneZ(PHASES[endIndex]) + 150;
        server.execute(() -> {
            ServerWorld world = server.getOverworld();
            loadArenaChunks(world, minZ, maxZ);
            CommandManager m = server.getCommandManager();
            ServerCommandSource s = server.getCommandSource();
            System.out.println("[Phase5] arena chunks loaded z=" + minZ + ".." + maxZ);

            cmd(m, s, "difficulty peaceful");
            cmd(m, s, "time set day");
            cmd(m, s, "weather clear");
            fillZ(m, s, -24, 63, minZ, 24, 63, maxZ, "minecraft:stone", 600);
            fillZ(m, s, -24, 64, minZ, 24, 67, maxZ, "air", 160);
            fillZ(m, s, -24, 64, minZ, -23, 72, maxZ, "minecraft:stone", 160);
            fillZ(m, s, -24, 64, minZ, 23, 72, maxZ, "minecraft:stone", 160);

            if (includes(6, 9)) {
                int water = laneZ("water");
                setBlockBox(world, -8, 64, water + 5, 8, 65, water + 135, Blocks.WATER);
                int lava = laneZ("lava");
                setBlockBox(world, -8, 64, lava + 5, 8, 65, lava + 135, Blocks.LAVA);
            }
            if (includes(5, 5)) {
                int collision = laneZ("collision");
                fillZ(m, s, -3, 64, collision + 55, 3, 66, collision + 59, "minecraft:stone", 32);
            }
            if (includes(11, 11)) {
                int step = laneZ("step");
                fillZ(m, s, -3, 64, step + 25, 3, 64, step + 29, "minecraft:oak_slab[type=bottom]", 32);
            }
            if (includes(13, 13)) {
                int stairs = laneZ("stairs");
                fillZ(m, s, -3, 64, stairs + 12, 3, 64, stairs + 15, "minecraft:oak_stairs[facing=south,half=bottom,shape=straight]", 32);
                fillZ(m, s, -3, 65, stairs + 16, 3, 65, stairs + 19, "minecraft:oak_stairs[facing=south,half=bottom,shape=straight]", 32);
            }
            if (includes(14, 14)) {
                int climb = laneZ("climbable");
                fillZ(m, s, -1, 64, climb + 25, 1, 68, climb + 25, "minecraft:stone", 32);
                fillZ(m, s, -1, 64, climb + 24, 1, 68, climb + 24, "minecraft:ladder[facing=south]", 32);
            }
            if (includes(15, 15)) {
                int edge = laneZ("edge-corner");
                fillZ(m, s, 4, 64, edge + 20, 4, 68, edge + 70, "minecraft:stone", 64);
                fillZ(m, s, 4, 64, edge + 45, 8, 68, edge + 45, "minecraft:stone", 32);
            }
            if (includes(16, 16)) {
                int swim = laneZ("swim-transition");
                setBlockBox(world, -8, 64, swim + 5, 8, 65, swim + 130, Blocks.WATER);
            }
            if (includes(17, 17)) {
                int glide = laneZ("glide");
                fillZ(m, s, -10, 63, glide + 4, 10, 63, glide + 300, "minecraft:stone", 300);
                fillZ(m, s, -10, 64, glide + 4, 10, 120, glide + 300, "air", 160);
            }
            cmd(m, s, "gamemode survival @a");
            cmd(m, s, "effect clear @a");
            prepared = true;
            preparing = false;
            System.out.println("[Phase5] arena preparation complete");
        });
    }

    private boolean includes(int first, int last) {
        return startIndex <= last && endIndex >= first;
    }

    private static void setBlockBox(ServerWorld world, int x1, int y1, int z1, int x2, int y2, int z2, net.minecraft.block.Block block) {
        for (int z = z1; z <= z2; z++) {
            for (int y = y1; y <= y2; y++) {
                for (int x = x1; x <= x2; x++) {
                    world.setBlockState(new BlockPos(x, y, z), block.getDefaultState());
                }
            }
        }
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
