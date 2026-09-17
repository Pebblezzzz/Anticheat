package dev.phantom.capture;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/** Persists a bounded, exact client-visible block-state window for deterministic collision replay. */
public final class VanillaWorldObservation {
    private static final int RADIUS_XZ = 16;
    private static final int RADIUS_Y = 8;
    private static BufferedWriter writer;
    private static long lastCaptureTick = Long.MIN_VALUE;
    private VanillaWorldObservation() {}

    public static synchronized void initialize(String tracePath) {
        if (writer != null) return;
        String worldPath = tracePath.replaceFirst("(?i)\\.tsv$", "-world.tsv");
        try {
            Path path = Path.of(worldPath).toAbsolutePath();
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
            writer.write("# phantom-phase5-world version=1 protocol=minecraft-java-1.21.11 format=tsv"); writer.newLine();
            writer.write("# captured_at_utc=" + Instant.now()); writer.newLine();
            writer.write("world_tick\tclient_tick\tx\ty\tz\tblock_id\tblock_state\tis_air\tis_solid\tfluid_state"); writer.newLine();
        } catch (IOException e) { throw new IllegalStateException("Unable to open Phase 5 world observation output", e); }
    }

    /** Capture every ten client ticks so moving scenarios retain the world around the actual path. */
    public static synchronized void capture(MinecraftClient client, ClientPlayerEntity player, long clientTick) {
        if (writer == null || client == null || client.world == null || player == null) return;
        long worldTick = client.world.getTime();
        if (worldTick - lastCaptureTick < 10) return;
        lastCaptureTick = worldTick;
        int cx = player.getBlockPos().getX(), cy = player.getBlockPos().getY(), cz = player.getBlockPos().getZ();
        try {
            for (int y = cy - RADIUS_Y; y <= cy + RADIUS_Y; y++) {
                for (int z = cz - RADIUS_XZ; z <= cz + RADIUS_XZ; z++) {
                    for (int x = cx - RADIUS_XZ; x <= cx + RADIUS_XZ; x++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        BlockState state = client.world.getBlockState(pos);
                        String blockId = String.valueOf(Registries.BLOCK.getId(state.getBlock()));
                        String fluid = state.getFluidState().isEmpty() ? "NONE" : state.getFluidState().toString();
                        writer.write(worldTick + "\t" + clientTick + "\t" + x + "\t" + y + "\t" + z + "\t" + blockId + "\t" + escape(state.toString()) + "\t" + state.isAir() + "\t" + state.isSolidBlock(client.world, pos) + "\t" + escape(fluid));
                        writer.newLine();
                    }
                }
            }
            writer.flush();
        } catch (IOException e) { throw new IllegalStateException("Unable to write Phase 5 world observation", e); }
    }

    private static String escape(String value) { return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n"); }
}
