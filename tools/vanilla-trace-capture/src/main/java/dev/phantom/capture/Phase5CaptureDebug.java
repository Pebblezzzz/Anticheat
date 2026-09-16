package dev.phantom.capture;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Disk-backed Phase 5 diagnostics; console output is intentionally compact. */
final class Phase5CaptureDebug {
    private static final String PROP = "phantom.capture.debug";
    private static final Path DEFAULT = Path.of("C:\\phase5\\phase5-driver-debug.tsv");
    private static final Object LOCK = new Object();
    private static BufferedWriter writer;
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
    private static boolean lastClimbing;
    private static boolean lastHolding;
    private static boolean lastSwimming;
    private static boolean lastGliding;

    private Phase5CaptureDebug() {}

    static void resetAndStart(MinecraftClient client, String phase, int index, int markerZ) {
        synchronized (LOCK) {
            initialize();
            activePhase = phase;
            phaseRows = climbingRows = holdingRows = swimmingRows = glidingRows = 0;
            minY = Double.POSITIVE_INFINITY;
            maxY = Double.NEGATIVE_INFINITY;
            minZ = Double.POSITIVE_INFINITY;
            maxZ = Double.NEGATIVE_INFINITY;
            lastClimbing = false;
            lastHolding = false;
            lastSwimming = false;
            lastGliding = false;
            write("PHASE_START\tphase=" + phase + "\tindex=" + index + "\tmarker_z=" + markerZ + "\tstate=" + describe(client.player));
        }
    }

    static void tick(MinecraftClient client, String phase, int elapsed, int markerZ) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null || !phase.equals(activePhase)) return;

        boolean climbing = player.isClimbing();
        boolean holding = player.isHoldingOntoLadder();
        boolean swimming = player.isSwimming();
        boolean gliding = player.isGliding();
        Vec3d velocity = player.getVelocity();
        BlockPos pos = player.getBlockPos();

        phaseRows++;
        if (climbing) climbingRows++;
        if (holding) holdingRows++;
        if (swimming) swimmingRows++;
        if (gliding) glidingRows++;
        minY = Math.min(minY, player.getY());
        maxY = Math.max(maxY, player.getY());
        minZ = Math.min(minZ, player.getZ());
        maxZ = Math.max(maxZ, player.getZ());

        StringBuilder row = new StringBuilder(1400);
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
        append(row, "gliding=" + gliding);
        append(row, "swimming=" + swimming);
        append(row, "climbing=" + climbing);
        append(row, "holding_ladder=" + holding);
        append(row, "climbing_pos=" + player.getClimbingPos().map(BlockPos::toShortString).orElse("none"));
        append(row, "fall_distance=" + player.fallDistance);
        append(row, "chest=" + player.getEquippedStack(EquipmentSlot.CHEST));
        append(row, "check_gliding=" + player.checkGliding());
        append(row, "bbox=" + box(player.getBoundingBox()));
        append(row, "block_pos=" + pos.getX() + "," + pos.getY() + "," + pos.getZ());
        append(row, "marker_z=" + markerZ);
        append(row, "forward=" + client.options.forwardKey.isPressed());
        append(row, "back=" + client.options.backKey.isPressed());
        append(row, "left=" + client.options.leftKey.isPressed());
        append(row, "right=" + client.options.rightKey.isPressed());
        append(row, "jump=" + client.options.jumpKey.isPressed());
        append(row, "sneak=" + client.options.sneakKey.isPressed());
        append(row, "sprint=" + client.options.sprintKey.isPressed());
        append(row, "nearby=" + nearby(client.world, pos, markerZ));

        boolean importantStateChange = climbing != lastClimbing || holding != lastHolding
                || swimming != lastSwimming || gliding != lastGliding;
        synchronized (LOCK) {
            write(row.toString());
            if (elapsed == 0 || importantStateChange || elapsed % 50 == 0) {
                System.out.println("[Phase5] " + phase + " t=" + elapsed + " pos=" + compact(player)
                        + (importantStateChange ? " state-change" : ""));
            }
        }
        lastClimbing = climbing;
        lastHolding = holding;
        lastSwimming = swimming;
        lastGliding = gliding;
    }

    static void failure(String phase, String message) {
        synchronized (LOCK) {
            initialize();
            write("FAILURE\tphase=" + phase + "\tmessage=" + Phase5CaptureEncoding.escape(message));
            System.err.println("[Phase5] FAILURE " + phase + " :: " + message);
        }
    }

    static void end(String phase) {
        synchronized (LOCK) {
            if (!phase.equals(activePhase)) return;
            flush();
            write("PHASE_END\tphase=" + phase + "\trows=" + phaseRows + "\tclimbing=" + climbingRows
                    + "\tholding_ladder=" + holdingRows + "\tswimming=" + swimmingRows
                    + "\tgliding=" + glidingRows + "\ty_range=" + minY + ".." + maxY
                    + "\tz_range=" + minZ + ".." + maxZ);
            flush();
            System.out.println("[Phase5] END " + phase + " rows=" + phaseRows
                    + " y=" + minY + ".." + maxY + " z=" + minZ + ".." + maxZ
                    + " swim=" + swimmingRows + " climb=" + climbingRows + " glide=" + glidingRows);
            activePhase = "unknown";
        }
    }

    private static void initialize() {
        if (writer != null) return;
        Path path = Path.of(System.getProperty(PROP, DEFAULT.toString())).toAbsolutePath();
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            writer.write("event\tdata");
            writer.newLine();
            writer.flush();
            System.out.println("[Phase5] debug=" + path);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to initialize Phase 5 debug trace: " + path, e);
        }
    }

    private static void write(String line) {
        try {
            writer.write(line);
            writer.newLine();
            if (phaseRows % 20 == 0) flush();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write Phase 5 debug trace", e);
        }
    }

    private static void flush() {
        try { writer.flush(); }
        catch (IOException e) { throw new IllegalStateException("Unable to flush Phase 5 debug trace", e); }
    }

    private static String compact(ClientPlayerEntity player) {
        return String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f v=%.3f,%.3f,%.3f ground=%s pose=%s", 
                player.getX(), player.getY(), player.getZ(), player.getVelocity().x, player.getVelocity().y,
                player.getVelocity().z, player.isOnGround(), player.getPose());
    }

    private static String describe(ClientPlayerEntity player) {
        if (player == null) return "null";
        return compact(player) + " hp=" + player.getHealth();
    }

    private static String box(Box box) {
        return box.minX + "," + box.minY + "," + box.minZ + ".." + box.maxX + "," + box.maxY + "," + box.maxZ;
    }

    private static String nearby(World world, BlockPos center, int markerZ) {
        StringBuilder out = new StringBuilder();
        for (int y = center.getY(); y <= center.getY() + 1; y++) {
            for (int z = markerZ - 2; z <= markerZ + 2; z++) {
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

    private static void append(StringBuilder out, String value) {
        out.append(out.length() == 0 ? "" : "\t").append(value.replace("\t", " ").replace("\n", " "));
    }
}
