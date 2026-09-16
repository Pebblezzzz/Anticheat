package dev.phantom.capture;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Observation-only recorder. It reads client state after ClientPlayerEntity.tick()
 * and writes TSV. It does not alter movement/input/world logic.
 */
public final class VanillaTraceCaptureClient implements ClientModInitializer {
    static final String MAGIC = "# phantom-phase5-trace version=2 protocol=minecraft-java-1.21.11 format=tsv";
    static final String HEADER = "tick\tclient_tick\treceive_nanos\tx\ty\tz\tvx\tvy\tvz\tyaw\tpitch\ton_ground\tforward\tstrafe\tjump\tsprint\tsneak\tpose\tgamemode\tfluid\tsubmerged\tclimbable\tgliding\tbase_movement_speed\tmodifiers\tspeed_amp\tslowness_amp\tjump_boost_amp\tlevitation\tslow_falling\tknockback_x\tknockback_y\tknockback_z\tvelocity_packet\tcorrection_id\tcorrection_pending\tworld_identity\tworld_tick\tcollision\tstep_attempted\tstep_succeeded\tcollision_x\tcollision_y\tcollision_z\tinput_source\tclient_version\tmissing_fields";

    private static final String PROP_ENABLED = "phantom.capture.enabled";
    private static final String PROP_OUTPUT = "phantom.capture.output";

    private static BufferedWriter writer;
    private static long tick;

    private VanillaTraceCaptureClient() {}

    @Override
    public void onInitializeClient() {
        if (!Boolean.parseBoolean(System.getProperty(PROP_ENABLED, "false"))) {
            return;
        }
        CaptureRuntime.initialize();
    }

    static final class CaptureRuntime {
        private CaptureRuntime() {}

        static synchronized void initialize() {
            if (writer != null) return;
            String output = System.getProperty(PROP_OUTPUT,
                    "phase5-vanilla-capture-" + Instant.now().toString().replace(':', '-') + ".tsv");
            try {
                Path path = Path.of(output).toAbsolutePath();
                if (path.getParent() != null) Files.createDirectories(path.getParent());
                writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
                writer.write(MAGIC);
                writer.newLine();
                writer.write("# source_id=vanilla-client-1.21.11");
                writer.newLine();
                writer.write("# captured_at_utc=" + Instant.now());
                writer.newLine();
                writer.write(HEADER);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                throw new IllegalStateException("Unable to open Phase 5 capture output: " + output, e);
            }
        }

        static synchronized void record(MinecraftClient client, ClientPlayerEntity player) {
            if (writer == null || client.world == null) return;

            PlayerInput input = player.input.playerInput;
            Vec3d velocity = player.getVelocity();
            EntityAttributeInstance speed = player.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);

            String fluid;
            if (player.isInLava()) fluid = "LAVA";
            else if (player.isTouchingWater()) fluid = "WATER";
            else fluid = "NONE";

            String gamemode = "UNKNOWN";
            if (client.interactionManager != null) {
                GameMode mode = client.interactionManager.getCurrentGameMode();
                if (mode != null) gamemode = mode.name().toLowerCase(Locale.ROOT);
            }

            List<String> missing = new ArrayList<>();
            // These values are not safely recoverable from a post-tick observation alone.
            // They stay explicitly marked missing instead of being invented.
            missing.add("knockback_x");
            missing.add("knockback_y");
            missing.add("knockback_z");
            missing.add("velocity_packet");
            missing.add("correction_id");
            missing.add("correction_pending");
            missing.add("step_attempted");
            missing.add("step_succeeded");
            missing.add("collision_x");
            missing.add("collision_y");
            missing.add("collision_z");
            if (speed == null) missing.add("base_movement_speed");
            if (gamemode.equals("UNKNOWN")) missing.add("gamemode");

            double baseSpeed = speed == null ? 0.0 : speed.getBaseValue();
            String modifiers = speed == null ? "-" : Phase5CaptureEncoding.modifiers(speed);
            int speedAmp = amplifier(player.getStatusEffect(StatusEffects.SPEED));
            int slownessAmp = amplifier(player.getStatusEffect(StatusEffects.SLOWNESS));
            int jumpAmp = amplifier(player.getStatusEffect(StatusEffects.JUMP_BOOST));

            long clientTick = player.age;
            long worldTick = client.world.getTime();
            tick++;

            String row = String.join("\t",
                    Long.toString(tick),
                    Long.toString(clientTick),
                    Long.toString(System.nanoTime()),
                    d(player.getX()), d(player.getY()), d(player.getZ()),
                    d(velocity.x), d(velocity.y), d(velocity.z),
                    d(player.getYaw()), d(player.getPitch()),
                    Boolean.toString(player.isOnGround()),
                    Integer.toString(input.forward() && !input.backward() ? 1 : input.backward() && !input.forward() ? -1 : 0),
                    Integer.toString(input.right() && !input.left() ? 1 : input.left() && !input.right() ? -1 : 0),
                    Boolean.toString(input.jump()),
                    Boolean.toString(input.sprint()),
                    Boolean.toString(input.sneak()),
                    player.getPose().name(),
                    gamemode,
                    fluid,
                    Boolean.toString(player.isSubmergedInWater()),
                    Boolean.toString(player.isClimbing()),
                    Boolean.toString(player.isFallFlying()),
                    d(baseSpeed),
                    modifiers,
                    Integer.toString(speedAmp),
                    Integer.toString(slownessAmp),
                    Integer.toString(jumpAmp),
                    Boolean.toString(player.hasStatusEffect(StatusEffects.LEVITATION)),
                    Boolean.toString(player.hasStatusEffect(StatusEffects.SLOW_FALLING)),
                    "0", "0", "0",
                    "false",
                    "-1",
                    "false",
                    Phase5CaptureEncoding.worldIdentity(client),
                    Long.toString(worldTick),
                    Boolean.toString(player.horizontalCollision || player.verticalCollision),
                    "false",
                    "false",
                    "false",
                    "false",
                    "false",
                    "capture-post-tick",
                    client.getGameVersion(),
                    String.join(",", missing)
            );

            try {
                writer.write(row);
                writer.newLine();
                if ((tick & 63) == 0) writer.flush();
            } catch (IOException e) {
                throw new IllegalStateException("Unable to write Phase 5 capture row", e);
            }
        }

        private static int amplifier(StatusEffectInstance effect) {
            return effect == null ? -1 : effect.getAmplifier();
        }

        private static String d(double value) {
            return Double.toString(value);
        }
    }
}
