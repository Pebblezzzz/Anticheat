package dev.phantom.capture;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EntityPose;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Detailed, observation-only diagnostics for the controlled Phase 5 corpus. */
final class Phase5CaptureDebug {
    private static final String PROP = "phantom.capture.debug";
    private static final Path DEFAULT = Path.of("C:\\phase5\\phase5-driver-debug.tsv");
    private static final Object LOCK = new Object();
    private static Path path;
    private static boolean initialized;
    private static String activePhase = "unknown";
    private static long phaseRows;
    private static long climbingRows;
    private static long holdingRows;
    private static long swimmingRows;
    private static long glidingRows;
    private static double minY;
    private static double maxY;
    private static double minZ;
    private static double maxZ;

    private Phase5CaptureDebug() {}

    static void resetAndStart(MinecraftClient client, String phase, int index, int ladderZ) {
        synchronized (LOCK) {
            initialize();
            activePhase = phase;
            phaseRows = climbingRows = holdingRows = swimmingRows = glidingRows = 0;
            minY = Double.POSITIVE_INFINITY;
            maxY = Double.NEGATIVE_INFINITY;
            minZ = Double.POSITIVE_INFINITY;
            maxZ = Double.NEGATIVE_INFINITY;
            write("PHASE_START\tphase=" + phase + "\tindex=" + index + "\tplayer=" + describe(client.player)
                    + "\tladder_z=" + ladderZ);
        }
    }

    static void waiting(MinecraftClient client, String phase, int elapsed,
                        double expectedX, double expectedY, double expectedZ, double expectedYaw,
                        int settledTicks) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return;

        Vec3d velocity = player.getVelocity();
        double dx = player.getX() - expectedX;
        double dy = player.getY() - expectedY;
        double dz = player.getZ() - expectedZ;
        double yawDelta = wrapDegrees(player.getYaw() - (float) expectedYaw);

        String row = "WAITING"
                + "\tphase=" + phase
                + "\telapsed=" + elapsed
                + "\texpected=" + expectedX + "," + expectedY + "," + expectedZ + ",yaw=" + expectedYaw
                + "\tactual=" + player.getX() + "," + player.getY() + "," + player.getZ() + ",yaw=" + player.getYaw()
                + "\tdelta=" + dx + "," + dy + "," + dz + ",yawDelta=" + yawDelta
                + "\tvelocity=" + velocity.x + "," + velocity.y + "," + velocity.z
                + "\tground=" + player.isOnGround()
                + "\tclimbing=" + player.isClimbing()
                + "\tholding_ladder=" + player.isHoldingOntoLadder()
                + "\tclimbing_pos=" + player.getClimbingPos().map(BlockPos::toShortString).orElse("none")
                + "\tsettled_ticks=" + settledTicks;

        synchronized (LOCK) {
            initialize();
            write(row);
            if (elapsed % 10 == 0 || settledTicks > 0) {
                System.out.println("[Phase5-Debug] " + row);
            }
        }
    }

    static void tick(MinecraftClient client, String phase, int elapsed, int ladderZ) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return;
        if (!phase.equals(activePhase)) return;
        BlockPos pos = player.getBlockPos();
        Vec3d velocity = player.getVelocity();
        boolean climbing = player.isClimbing();
        boolean holding = player.isHoldingOntoLadder();
        phaseRows++;
        if (climbing) climbingRows++;
        if (holding) holdingRows++;
        if (player.isSwimming()) swimmingRows++;
        if (player.getPose() == EntityPose.GLIDING) glidingRows++;
        minY = Math.min(minY, player.getY());
        maxY = Math.max(maxY, player.getY());
        minZ = Math.min(minZ, player.getZ());
        maxZ = Math.max(maxZ, player.getZ());

        StringBuilder row = new StringBuilder(1024);
        append(row, "TICK");
        append(row, "phase=" + phase);
        append(row, "elapsed=" + elapsed);
        append(row, "x=" + player.getX());
        append(row, "y=" + player.getY());
        append(row, "z=" + player.getZ());
        append(row, "vx=" + velocity.x);
        append(row, "vy=" + velocity.y);
        append(row, "vz=" + velocity.z);
        append(row, "yaw=" + player.getYaw());
        append(row, "pitch=" + player.getPitch());
        append(row, "on_ground=" + player.isOnGround());
        append(row, "pose=" + player.getPose());
        append(row, "swimming=" + player.isSwimming());
        append(row, "climbing=" + climbing);
        append(row, "holding_ladder=" + holding);
        append(row, "climbing_pos=" + player.getClimbingPos().map(BlockPos::toShortString).orElse("none"));
        append(row, "fall_flying=" + (player.getPose() == EntityPose.GLIDING));
        append(row, "bbox=" + box(player.getBoundingBox()));
        append(row, "block_pos=" + pos.getX() + "," + pos.getY() + "," + pos.getZ());
        append(row, "ladder_z=" + ladderZ);
        append(row, "dz_to_ladder=" + (player.getZ() - ladderZ));
        append(row, "forward=" + client.options.forwardKey.isPressed());
        append(row, "back=" + client.options.backKey.isPressed());
        append(row, "left=" + client.options.leftKey.isPressed());
        append(row, "right=" + client.options.rightKey.isPressed());
        append(row, "jump=" + client.options.jumpKey.isPressed());
        append(row, "sneak=" + client.options.sneakKey.isPressed());
        append(row, "sprint=" + client.options.sprintKey.isPressed());
        append(row, "nearby=" + nearby(client.world, pos, ladderZ));
        synchronized (LOCK) {
            write(row.toString());
            if (elapsed % 10 == 0 || climbing || holding) {
                System.out.println("[Phase5-Debug] " + row);
            }
        }
    }

    static void end(String phase) {
        synchronized (LOCK) {
            if (!phase.equals(activePhase)) return;
            write("PHASE_END\tphase=" + phase + "\trows=" + phaseRows + "\tclimbing_rows=" + climbingRows
                    + "\tholding_ladder_rows=" + holdingRows + "\tswimming_rows=" + swimmingRows
                    + "\tgliding_rows=" + glidingRows + "\tmin_y=" + minY + "\tmax_y=" + maxY
                    + "\tmin_z=" + minZ + "\tmax_z=" + maxZ);
            System.out.println("[Phase5-Debug] PHASE_END phase=" + phase
                    + " rows=" + phaseRows + " climbing=" + climbingRows
                    + " holding_ladder=" + holdingRows + " yRange=" + minY + ".." + maxY
                    + " zRange=" + minZ + ".." + maxZ);
            activePhase = "unknown";
        }
    }

    private static void initialize() {
        if (initialized) return;
        synchronized (LOCK) {
            if (initialized) return;
            path = Path.of(System.getProperty(PROP, DEFAULT.toString())).toAbsolutePath();
            try {
                if (path.getParent() != null) Files.createDirectories(path.getParent());
                Files.writeString(path, "event\tphase\tdata\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                initialized = true;
                System.out.println("[Phase5-Debug] debug_file=" + path);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to initialize Phase 5 debug trace: " + path, e);
            }
        }
    }

    private static void write(String line) {
        try {
            Files.writeString(path, line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write Phase 5 debug trace: " + path, e);
        }
    }

    private static String describe(ClientPlayerEntity player) {
        if (player == null) return "null";
        Vec3d v = player.getVelocity();
        return "x=" + player.getX() + ",y=" + player.getY() + ",z=" + player.getZ()
                + ",yaw=" + player.getYaw() + ",pose=" + player.getPose()
                + ",ground=" + player.isOnGround() + ",climbing=" + player.isClimbing()
                + ",holding=" + player.isHoldingOntoLadder()
                + ",climbing_pos=" + player.getClimbingPos().map(BlockPos::toShortString).orElse("none")
                + ",vel=" + v.x + "," + v.y + "," + v.z;
    }

    private static String box(Box box) {
        return box.minX + "," + box.minY + "," + box.minZ + ".." + box.maxX + "," + box.maxY + "," + box.maxZ;
    }

    private static String nearby(World world, BlockPos center, int ladderZ) {
        StringBuilder out = new StringBuilder();
        for (int y = center.getY(); y <= center.getY() + 1; y++) {
            for (int z = ladderZ - 2; z <= ladderZ + 2; z++) {
                for (int x = -1; x <= 1; x++) {
                    if (out.length() > 0) out.append('|');
                    BlockPos p = new BlockPos(x, y, z);
                    out.append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ())
                            .append('=').append(Phase5CaptureEncoding.escape(world.getBlockState(p).toString()));
                }
            }
        }
        return out.toString();
    }

    private static double wrapDegrees(double value) {
        double wrapped = value % 360.0;
        if (wrapped >= 180.0) wrapped -= 360.0;
        if (wrapped < -180.0) wrapped += 360.0;
        return wrapped;
    }

    private static void append(StringBuilder out, String value) {
        out.append(out.length() == 0 ? "" : "\t").append(value.replace("\t", " ").replace("\n", " "));
    }
}
