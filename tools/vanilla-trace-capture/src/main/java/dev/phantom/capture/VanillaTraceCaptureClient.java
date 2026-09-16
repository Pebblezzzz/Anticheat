package dev.phantom.capture;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
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

/** Observation-only recorder for a real 1.21.11 client. */
public final class VanillaTraceCaptureClient implements ClientModInitializer {
    public static final String MAGIC = "# phantom-phase5-trace version=2 protocol=minecraft-java-1.21.11 format=tsv";
    public static final String HEADER = "tick\tclient_tick\treceive_nanos\tx\ty\tz\tvx\tvy\tvz\tyaw\tpitch\ton_ground\tforward\tstrafe\tjump\tsprint\tsneak\tpose\tgamemode\tfluid\tsubmerged\tclimbable\tgliding\tbase_movement_speed\tmodifiers\tspeed_amp\tslowness_amp\tjump_boost_amp\tlevitation\tslow_falling\tknockback_x\tknockback_y\tknockback_z\tvelocity_packet\tcorrection_id\tcorrection_pending\tworld_identity\tworld_tick\tcollision\tstep_attempted\tstep_succeeded\tcollision_x\tcollision_y\tcollision_z\tinput_source\tclient_version\tmissing_fields";
    public static final String EVENT_HEADER = "receive_nanos\tclient_tick\tevent_type\tx\ty\tz\taux_x\taux_y\taux_z\taux_id\tdetails";
    private static final String PROP_ENABLED = "phantom.capture.enabled";
    private static final String PROP_OUTPUT = "phantom.capture.output";
    private static final String PROP_EVENTS = "phantom.capture.events";
    private static BufferedWriter writer;
    private static BufferedWriter eventWriter;
    private static long tick;
    public VanillaTraceCaptureClient() {}
    @Override public void onInitializeClient() { if (Boolean.parseBoolean(System.getProperty(PROP_ENABLED, "false"))) CaptureRuntime.initialize(); }

    public static final class CaptureRuntime {
        private CaptureRuntime() {}
        static synchronized void initialize() {
            if (writer != null) return;
            String output = System.getProperty(PROP_OUTPUT, "phase5-vanilla-capture-" + Instant.now().toString().replace(':', '-') + ".tsv");
            String events = System.getProperty(PROP_EVENTS, output.replaceFirst("(?i)\\.tsv$", "-events.tsv"));
            try {
                Path path = Path.of(output).toAbsolutePath();
                if (path.getParent() != null) Files.createDirectories(path.getParent());
                writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
                Path eventPath = Path.of(events).toAbsolutePath();
                if (eventPath.getParent() != null) Files.createDirectories(eventPath.getParent());
                eventWriter = Files.newBufferedWriter(eventPath, StandardCharsets.UTF_8);
                writer.write(MAGIC); writer.newLine(); writer.write("# source_id=vanilla-client-1.21.11"); writer.newLine(); writer.write("# captured_at_utc=" + Instant.now()); writer.newLine(); writer.write(HEADER); writer.newLine();
                eventWriter.write("# phantom-phase5-events version=1 protocol=minecraft-java-1.21.11 format=tsv"); eventWriter.newLine(); eventWriter.write("# captured_at_utc=" + Instant.now()); eventWriter.newLine(); eventWriter.write(EVENT_HEADER); eventWriter.newLine();
                writer.flush(); eventWriter.flush();
            } catch (IOException e) { throw new IllegalStateException("Unable to open Phase 5 capture output", e); }
        }
        public static synchronized void recordForMixin(MinecraftClient client, ClientPlayerEntity player) { record(client, player); }
        static synchronized void record(MinecraftClient client, ClientPlayerEntity player) {
            if (writer == null || client.world == null) return;
            PlayerInput input = player.input.playerInput;
            Vec3d velocity = player.getVelocity();
            EntityAttributeInstance speed = player.getAttributeInstance(EntityAttributes.MOVEMENT_SPEED);
            String fluid = player.isInLava() ? "LAVA" : player.isTouchingWater() ? "WATER" : "NONE";
            String gamemode = "UNKNOWN";
            if (client.interactionManager != null) { GameMode mode = client.interactionManager.getCurrentGameMode(); if (mode != null) gamemode = mode.name().toLowerCase(Locale.ROOT); }
            List<String> missing = new ArrayList<>(List.of("knockback_x","knockback_y","knockback_z","velocity_packet","correction_id","correction_pending","step_attempted","step_succeeded","collision_x","collision_y","collision_z"));
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
            String row = String.join("\t", Long.toString(tick), Long.toString(clientTick), Long.toString(System.nanoTime()), d(player.getX()), d(player.getY()), d(player.getZ()), d(velocity.x), d(velocity.y), d(velocity.z), d(player.getYaw()), d(player.getPitch()), Boolean.toString(player.isOnGround()), Integer.toString(input.forward() && !input.backward() ? 1 : input.backward() && !input.forward() ? -1 : 0), Integer.toString(input.right() && !input.left() ? 1 : input.left() && !input.right() ? -1 : 0), Boolean.toString(input.jump()), Boolean.toString(player.isSprinting()), Boolean.toString(input.sneak()), player.getPose().name(), gamemode, fluid, Boolean.toString(player.isSubmergedInWater()), Boolean.toString(player.isClimbing()), Boolean.toString(player.getPose() == EntityPose.GLIDING), d(baseSpeed), modifiers, Integer.toString(speedAmp), Integer.toString(slownessAmp), Integer.toString(jumpAmp), Boolean.toString(player.hasStatusEffect(StatusEffects.LEVITATION)), Boolean.toString(player.hasStatusEffect(StatusEffects.SLOW_FALLING)), "0", "0", "0", "false", "-1", "false", Phase5CaptureEncoding.worldIdentity(client), Long.toString(worldTick), Boolean.toString(player.horizontalCollision || player.verticalCollision), "false", "false", "false", "false", "false", "capture-post-tick:" + ControlledPhase5ScenarioDriver.phaseLabel(), "1.21.11", String.join(",", missing));
            try { writer.write(row); writer.newLine(); if ((tick & 63) == 0) writer.flush(); } catch (IOException e) { throw new IllegalStateException("Unable to write Phase 5 capture row", e); }
        }
        public static synchronized void recordVelocityPacket(EntityVelocityUpdateS2CPacket packet) { MinecraftClient client = MinecraftClient.getInstance(); if (writer == null || client.player == null || packet.getEntityId() != client.player.getId()) return; Vec3d velocity = packet.getVelocity(); writeEvent(System.nanoTime(), client.player.age, "ENTITY_VELOCITY", 0, 0, 0, velocity.x, velocity.y, velocity.z, packet.getEntityId(), "server velocity packet"); }
        public static synchronized void recordCorrectionPacket(PlayerPositionLookS2CPacket packet) { MinecraftClient client = MinecraftClient.getInstance(); if (writer == null) return; long clientTick = client.player == null ? -1 : client.player.age; Vec3d position = packet.change().position(); writeEvent(System.nanoTime(), clientTick, "POSITION_CORRECTION", position.x, position.y, position.z, packet.change().yaw(), packet.change().pitch(), 0, packet.teleportId(), "relatives=" + packet.relatives()); }
        private static void writeEvent(long nanos, long clientTick, String type, double x, double y, double z, double auxX, double auxY, double auxZ, long auxId, String details) { try { eventWriter.write(String.join("\t", Long.toString(nanos), Long.toString(clientTick), type, d(x), d(y), d(z), d(auxX), d(auxY), d(auxZ), Long.toString(auxId), Phase5CaptureEncoding.escape(details))); eventWriter.newLine(); eventWriter.flush(); } catch (IOException e) { throw new IllegalStateException("Unable to write Phase 5 event", e); } }
        private static int amplifier(StatusEffectInstance effect) { return effect == null ? -1 : effect.getAmplifier(); }
        private static String d(double value) { return Double.toString(value); }
    }
}
