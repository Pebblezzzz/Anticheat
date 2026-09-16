package dev.phantom.capture;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.entity.EntityPose;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Locale;

public final class ControlledPhase5ScenarioDriver {
    private static final String PROP = "phantom.capture.scenario";
    private static final String ALL = "all";
    private static final String NONE = "none";

    private static final String[] PHASES = {
            "walk", "sprint", "jump", "sneak", "diagonal", "collision", "water", "lava",
            "speed-effect", "slowness-effect", "jump-boost", "step", "tail", "stairs",
            "climbable", "edge-corner", "swim-transition", "glide", "correction"
    };

    private static final int[] DURATIONS = {
            100, 100, 100, 100, 100, 100, 120, 30, 80, 80,
            100, 110, 70, 90, 100, 100, 120, 120, 40
    };

    private static final int START_Z = -80;
    private static final int PHASE_SPACING = 140;
    private static final double RESET_EPSILON = 1.0e-4;
    private static final int REQUIRED_SETTLED_TICKS = 3;
    private static final int RESET_TIMEOUT_TICKS = 600;

    private int phaseIndex = -1;
    private int elapsed;
    private int driverTick;
    private int settledTicks;
    private int resetWaitTicks;
    private boolean prepared;
    private boolean done;
    private boolean waitingForReset;
    private double expectedX;
    private double expectedY;
    private double expectedZ;
    private double expectedYaw;
    private String scenario = NONE;

    private static volatile ControlledPhase5ScenarioDriver LAST;

    public ControlledPhase5ScenarioDriver() {
        LAST = this;
    }

    public static String phaseLabel() {
        ControlledPhase5ScenarioDriver d = LAST;
        if (d == null) return "unknown";
        if (d.phaseIndex < 0) return d.scenario + ":setup";
        if (d.phaseIndex >= d.PHASES.length) return d.scenario + ":done";
        String phase = d.PHASES[d.phaseIndex];
        return d.waitingForReset ? d.scenario + ":" + phase + ":warmup" : d.scenario + ":" + phase;
    }

    public void tick(MinecraftClient client) {
        if (!Boolean.parseBoolean(System.getProperty("phantom.capture.enabled", "false"))) return;

        String requested = System.getProperty(PROP, NONE).trim().toLowerCase(Locale.ROOT);
        if (requested.equals(NONE) || client.player == null || client.world == null) {
            release(client.options);
            return;
        }
        if (!requested.equals(ALL)) {
            throw new IllegalArgumentException("Unsupported Phase 5 scenario: " + requested);
        }

        if (!requested.equals(scenario)) {
            scenario = requested;
            phaseIndex = -1;
            elapsed = 0;
            driverTick = 0;
            settledTicks = 0;
            resetWaitTicks = 0;
            prepared = false;
            done = false;
            waitingForReset = false;
            release(client.options);
        }

        if (done) {
            release(client.options);
            return;
        }

        if (!prepared) {
            prepare(client);
            prepared = true;
            System.out.println("[Phase5-Debug] world preparation complete; waiting for scenario execution");
            return;
        }

        run(client);
    }

    private void run(MinecraftClient client) {
        if (phaseIndex < 0) {
            release(client.options);
            if (++driverTick > 40) begin(client, 0);
            return;
        }

        if (waitingForReset) {
            release(client.options);
            Phase5CaptureDebug.waiting(client, PHASES[phaseIndex], elapsed,
                    expectedX, expectedY, expectedZ, expectedYaw, settledTicks);

            resetWaitTicks++;
            if (resetHasSettled(client)) settledTicks++;
            else settledTicks = 0;

            if (settledTicks >= REQUIRED_SETTLED_TICKS) {
                waitingForReset = false;
                resetWaitTicks = 0;
                elapsed = 0;
                int ladderZ = baseZ(PHASES[phaseIndex]) + 29;
                Phase5CaptureDebug.resetAndStart(client, PHASES[phaseIndex], phaseIndex, ladderZ);
                System.out.println("[Phase5-Debug] PHASE_START index=" + phaseIndex
                        + " phase=" + PHASES[phaseIndex]
                        + " zBase=" + baseZ(PHASES[phaseIndex])
                        + " player=" + describe(client));
            } else if (resetWaitTicks >= RESET_TIMEOUT_TICKS) {
                String diagnostic = "RESET TIMEOUT phase=" + PHASES[phaseIndex]
                        + " expected=" + expectedX + "," + expectedY + "," + expectedZ + ",yaw=" + expectedYaw
                        + " actual=" + describe(client);
                System.err.println("[Phase5-Debug] " + diagnostic);
                throw new IllegalStateException(diagnostic);
            }
            driverTick++;
            return;
        }

        String phase = PHASES[phaseIndex];
        boolean forward = true;
        boolean back = false;
        boolean left = false;
        boolean right = false;
        boolean jump = false;
        boolean sneak = false;
        boolean sprint = false;

        switch (phase) {
            case "walk" -> { }
            case "sprint" -> sprint = true;
            case "jump" -> jump = elapsed < 2;
            case "sneak" -> sneak = true;
            case "diagonal" -> left = true;
            case "water" -> sprint = true;
            case "lava", "collision", "step", "tail", "speed-effect", "slowness-effect" -> { }
            case "jump-boost" -> jump = elapsed < 2;
            case "stairs" -> {
                sprint = true;
                jump = elapsed % 28 == 1;
            }
            case "climbable" -> forward = true;
            case "edge-corner" -> {
                left = elapsed < 50;
                right = !left;
            }
            case "swim-transition" -> {
                sprint = true;
                jump = elapsed % 24 < 6;
                sneak = elapsed % 24 >= 12 && elapsed % 24 < 18;
            }
            case "glide" -> sprint = true;
            case "correction" -> { }
            default -> throw new IllegalStateException(phase);
        }

        if (phase.equals("correction") && elapsed == 8) {
            reset(client, 2, 65, baseZ(phase) + 20, 90);
        }
        if (phase.equals("correction") && elapsed == 24) {
            reset(client, -2, 65, baseZ(phase) + 24, 270);
        }

        apply(client.options, forward, back, left, right, jump, sneak, sprint);
        Phase5CaptureDebug.tick(client, phase, elapsed, baseZ(phase) + 29);

        elapsed++;
        if (elapsed >= DURATIONS[phaseIndex]) {
            Phase5CaptureDebug.end(phase);
            phaseIndex++;
            if (phaseIndex < PHASES.length) begin(client, phaseIndex);
            else {
                done = true;
                release(client.options);
                client.scheduleStop();
            }
        }

        driverTick++;
    }

    private void begin(MinecraftClient client, int index) {
        phaseIndex = index;
        elapsed = 0;
        settledTicks = 0;
        resetWaitTicks = 0;
        waitingForReset = true;
        resetFor(client, PHASES[index]);
        release(client.options);
    }

    private void resetFor(MinecraftClient client, String phase) {
        if (phase.equals("climbable")) {
            expectedX = 0;
            expectedY = 64;
            expectedZ = baseZ(phase) + 29.1;
            expectedYaw = 0;
        } else {
            expectedX = 0;
            expectedY = (phase.equals("water") || phase.equals("lava") || phase.equals("swim-transition")) ? 63 : (phase.equals("glide") ? 90 : 64);
            expectedZ = baseZ(phase) + 12;
            expectedYaw = 0;
        }

        reset(client, expectedX, expectedY, expectedZ, expectedYaw);

        if (phase.equals("speed-effect")) effect(client, "minecraft:speed");
        else if (phase.equals("slowness-effect")) effect(client, "minecraft:slowness");
        else if (phase.equals("jump-boost")) effect(client, "minecraft:jump_boost");
        else if (phase.equals("lava")) {
            effect(client, "minecraft:fire_resistance");
            System.out.println("[Phase5-Debug] LAVA_PROTECTED; fire resistance enabled for controlled lava movement capture player=" + describe(client));
        } else if (phase.equals("glide")) {
            elytra(client);
            System.out.println("[Phase5-Debug] ELYTRA_PREPARED; awaiting airborne vanilla activation player=" + describe(client));
        }
    }

    private boolean resetHasSettled(MinecraftClient client) {
        if (client.player == null) return false;

        double dx = client.player.getX() - expectedX;
        double dy = client.player.getY() - expectedY;
        double dz = client.player.getZ() - expectedZ;
        var velocity = client.player.getVelocity();

        boolean positionOk = Math.abs(dx) <= RESET_EPSILON
                && Math.abs(dy) <= RESET_EPSILON
                && Math.abs(dz) <= RESET_EPSILON;
        boolean horizontalVelocityOk = Math.abs(velocity.x) <= RESET_EPSILON
                && Math.abs(velocity.z) <= RESET_EPSILON;
        boolean verticalVelocityOk = client.player.isOnGround() || Math.abs(velocity.y) <= RESET_EPSILON;
        boolean yawOk = Math.abs(wrapDegrees(client.player.getYaw() - (float) expectedYaw)) <= RESET_EPSILON;
        return positionOk && horizontalVelocityOk && verticalVelocityOk && yawOk;
    }

    private static double wrapDegrees(double value) {
        double wrapped = value % 360.0;
        if (wrapped >= 180.0) wrapped -= 360.0;
        if (wrapped < -180.0) wrapped += 360.0;
        return wrapped;
    }

    private static int baseZ(String phase) {
        for (int i = 0; i < PHASES.length; i++) {
            if (PHASES[i].equals(phase)) return START_Z + PHASE_SPACING * i;
        }
        throw new IllegalArgumentException(phase);
    }

    private void prepare(MinecraftClient client) {
        IntegratedServer server = client.getServer();
        if (server == null) throw new IllegalStateException("Integrated server required");

        server.executeSync(() -> {
            CommandManager manager = server.getCommandManager();
            ServerCommandSource source = server.getCommandSource();
            cmd(manager, source, "difficulty peaceful");
            cmd(manager, source, "time set day");
            cmd(manager, source, "weather clear");

            for (int i = 0; i < PHASES.length; i++) {
                int z = START_Z + PHASE_SPACING * i;
                cmd(manager, source, "forceload add -16 " + z + " 16 " + (z + 119));
                cmd(manager, source, "fill -16 64 " + z + " 16 67 " + (z + 119) + " air");
                cmd(manager, source, "fill -16 63 " + z + " 16 63 " + (z + 119) + " minecraft:stone");
            }

            int water = baseZ("water");
            cmd(manager, source, "fill -12 62 " + (water + 8) + " 12 62 " + (water + 70) + " minecraft:stone");
            cmd(manager, source, "fill -12 63 " + (water + 8) + " 12 65 " + (water + 70) + " minecraft:water");

            int lava = baseZ("lava");
            cmd(manager, source, "fill -12 62 " + (lava + 8) + " 12 62 " + (lava + 50) + " minecraft:stone");
            cmd(manager, source, "fill -12 63 " + (lava + 8) + " 12 65 " + (lava + 50) + " minecraft:lava");

            int collision = baseZ("collision");
            cmd(manager, source, "fill -2 64 " + (collision + 48) + " 2 66 " + (collision + 52) + " minecraft:stone");

            int step = baseZ("step");
            cmd(manager, source, "fill -4 64 " + (step + 32) + " 4 64 " + (step + 35) + " minecraft:oak_slab[type=bottom]");

            int stairs = baseZ("stairs");
            cmd(manager, source, "fill -2 64 " + (stairs + 30) + " 2 64 " + (stairs + 32) + " minecraft:oak_stairs[facing=south,half=bottom,shape=straight]");
            cmd(manager, source, "fill -2 65 " + (stairs + 33) + " 2 65 " + (stairs + 35) + " minecraft:oak_stairs[facing=south,half=bottom,shape=straight]");
            cmd(manager, source, "fill -2 66 " + (stairs + 36) + " 2 66 " + (stairs + 38) + " minecraft:oak_stairs[facing=south,half=bottom,shape=straight]");

            int climbable = baseZ("climbable");
            cmd(manager, source, "fill -2 64 " + (climbable + 30) + " 2 69 " + (climbable + 30) + " minecraft:stone");
            cmd(manager, source, "fill -2 64 " + (climbable + 29) + " 2 69 " + (climbable + 29) + " minecraft:ladder[facing=south]");

            int edge = baseZ("edge-corner");
            cmd(manager, source, "fill 3 64 " + (edge + 28) + " 3 67 " + (edge + 70) + " minecraft:stone");
            cmd(manager, source, "fill 3 64 " + (edge + 50) + " 8 67 " + (edge + 50) + " minecraft:stone");
            cmd(manager, source, "fill -3 64 " + (edge + 65) + " 2 67 " + (edge + 65) + " minecraft:stone");

            int swim = baseZ("swim-transition");
            cmd(manager, source, "fill -16 62 " + (swim + 6) + " 16 62 " + (swim + 50) + " minecraft:stone");
            cmd(manager, source, "fill -16 63 " + (swim + 6) + " 16 65 " + (swim + 50) + " minecraft:water");
            cmd(manager, source, "fill -16 66 " + (swim + 6) + " 16 66 " + (swim + 24) + " minecraft:air");
            cmd(manager, source, "fill -16 66 " + (swim + 25) + " 16 66 " + (swim + 50) + " minecraft:glass");

            int glide = baseZ("glide");
            cmd(manager, source, "fill -10 63 " + (glide + 6) + " 10 63 " + (glide + 60) + " minecraft:stone");
            cmd(manager, source, "fill -8 64 " + (glide + 6) + " 8 89 " + (glide + 60) + " minecraft:air");

            int correction = baseZ("correction");
            cmd(manager, source, "fill -8 64 " + (correction + 8) + " 8 67 " + (correction + 90) + " air");
            cmd(manager, source, "fill -8 63 " + (correction + 8) + " 8 63 " + (correction + 90) + " minecraft:stone");

            cmd(manager, source, "gamemode survival @a");
            cmd(manager, source, "effect clear @a");
        });
    }

    private void reset(MinecraftClient client, double x, double y, double z, double yaw) {
        IntegratedServer server = client.getServer();
        if (server == null) throw new IllegalStateException("Integrated server required");

        server.executeSync(() -> {
            ServerPlayerEntity serverPlayer = server.getPlayerManager().getPlayer(client.player.getUuid());
            if (serverPlayer == null) {
                throw new IllegalStateException("Server player not found for client UUID=" + client.player.getUuid());
            }

            serverPlayer.setOnGround(false);
            serverPlayer.setVelocity(0.0D, 0.0D, 0.0D);
            serverPlayer.fallDistance = 0.0F;
            serverPlayer.refreshPositionAndAngles(x, y, z, (float) yaw, 0.0F);
            serverPlayer.requestTeleport(x, y, z);

            CommandManager manager = server.getCommandManager();
            ServerCommandSource source = server.getCommandSource();
            cmd(manager, source, "effect clear @a");
            cmd(manager, source, "gamemode survival @a");

            System.out.println("[Phase5-Debug] RESET_SERVER uuid=" + serverPlayer.getUuid()
                    + " x=" + x + " y=" + y + " z=" + z + " yaw=" + yaw
                    + " serverPos=" + serverPlayer.getX() + "," + serverPlayer.getY() + "," + serverPlayer.getZ());
        });

        client.player.requestTeleport(x, y, z);
        client.player.setVelocity(0.0D, 0.0D, 0.0D);
        client.player.setOnGround(false);
        client.player.fallDistance = 0.0F;
        client.player.setYaw((float) yaw);
        client.player.setPitch(0.0F);
        System.out.println("[Phase5-Debug] RESET_CLIENT x=" + client.player.getX()
                + " y=" + client.player.getY() + " z=" + client.player.getZ()
                + " yaw=" + client.player.getYaw());
    }

    private void effect(MinecraftClient client, String id) {
        IntegratedServer server = client.getServer();
        server.executeSync(() -> cmd(server.getCommandManager(), server.getCommandSource(), "effect give @a " + id + " 30 0 true"));
        System.out.println("[Phase5-Debug] EFFECT_APPLIED id=" + id + " player=" + describe(client));
    }

    private void elytra(MinecraftClient client) {
        IntegratedServer server = client.getServer();
        server.executeSync(() -> cmd(server.getCommandManager(), server.getCommandSource(), "item replace entity @a armor.chest with minecraft:elytra"));
        client.player.equipStack(net.minecraft.entity.EquipmentSlot.CHEST, new net.minecraft.item.ItemStack(net.minecraft.item.Items.ELYTRA));
        System.out.println("[Phase5-Debug] ELYTRA_EQUIPPED player=" + describe(client));
    }

    private static void cmd(CommandManager manager, ServerCommandSource source, String value) {
        manager.parseAndExecute(source, value);
    }

    private static String describe(MinecraftClient client) {
        if (client.player == null) return "player=null";
        var player = client.player;
        var velocity = player.getVelocity();
        return "x=" + player.getX()
                + ",y=" + player.getY()
                + ",z=" + player.getZ()
                + ",yaw=" + player.getYaw()
                + ",pose=" + player.getPose()
                + ",ground=" + player.isOnGround()
                + ",climbing=" + player.isClimbing()
                + ",holding=" + player.isHoldingOntoLadder()
                + ",gliding=" + player.isGliding()
                + ",glidingPose=" + (player.getPose() == EntityPose.GLIDING)
                + ",vel=" + velocity.x + "," + velocity.y + "," + velocity.z;
    }

    private static void apply(GameOptions options, boolean forward, boolean back, boolean left, boolean right,
                              boolean jump, boolean sneak, boolean sprint) {
        options.forwardKey.setPressed(forward);
        options.backKey.setPressed(back);
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