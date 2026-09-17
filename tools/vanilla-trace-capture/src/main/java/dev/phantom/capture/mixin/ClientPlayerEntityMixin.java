package dev.phantom.capture.mixin;

import dev.phantom.capture.ControlledPhase5ScenarioDriver;
import dev.phantom.capture.VanillaCorpusScenarioDriver;
import dev.phantom.capture.VanillaTraceCaptureClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Reads causal input and movement results while leaving vanilla movement itself untouched. */
@Mixin(ClientPlayerEntity.class)
final class ClientPlayerEntityMixin {
    private boolean phantom$part4GeometryInjected;
    private String phantom$lastPhase = "";
    private int phantom$phaseTicks;
    private Vec3d phantom$moveStart = Vec3d.ZERO;
    private Vec3d phantom$moveRequested = Vec3d.ZERO;
    private boolean phantom$moveGroundBefore;

    @Inject(method = "tick", at = @At("HEAD"))
    private void phantom$observeBeforeTick(CallbackInfo ci) {
        VanillaTraceCaptureClient.CaptureRuntime.beginObservationTick((ClientPlayerEntity) (Object) this);
        VanillaTraceCaptureClient.CaptureRuntime.observePreTickInput((ClientPlayerEntity) (Object) this);
    }

    @Inject(method = "move", at = @At("HEAD"))
    private void phantom$observeMoveStart(MovementType type, Vec3d movement, CallbackInfo ci) {
        ClientPlayerEntity player = (ClientPlayerEntity) (Object) this;
        phantom$moveStart = positionOf(player);
        phantom$moveRequested = movement;
        phantom$moveGroundBefore = player.isOnGround();
    }

    @Inject(method = "move", at = @At("RETURN"))
    private void phantom$observeMoveEnd(MovementType type, Vec3d movement, CallbackInfo ci) {
        ClientPlayerEntity player = (ClientPlayerEntity) (Object) this;
        Vec3d actual = positionOf(player).subtract(phantom$moveStart);
        VanillaTraceCaptureClient.CaptureRuntime.observeMoveResult(type, phantom$moveRequested, actual, phantom$moveGroundBefore, player);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void phantom$recordAfterTick(CallbackInfo ci) {
        ClientPlayerEntity player = (ClientPlayerEntity) (Object) this;
        MinecraftClient client = MinecraftClient.getInstance();
        String phase = ControlledPhase5ScenarioDriver.phaseLabel();
        if ("corpus".equalsIgnoreCase(System.getProperty("phantom.capture.mode", "legacy"))) phase = VanillaCorpusScenarioDriver.phaseLabel();
        if (!phase.equals(phantom$lastPhase)) { phantom$lastPhase = phase; phantom$part4GeometryInjected = false; phantom$phaseTicks = 0; }

        // Legacy Part 4 geometry is setup, not movement simulation. Input is never changed here.
        if (phase.endsWith(":climbable") || phase.endsWith(":edge-corner")) {
            boolean settledAtPhaseStart = player.isOnGround() && Math.abs(player.getY() - 64.0D) <= 0.75D;
            if (!phantom$part4GeometryInjected && settledAtPhaseStart && phantom$phaseTicks <= 2) {
                phantom$injectPart4Geometry(client, player, phase);
                phantom$part4GeometryInjected = true;
            }
        }
        VanillaTraceCaptureClient.CaptureRuntime.recordForMixin(client, player);
        phantom$phaseTicks++;
    }

    private static Vec3d positionOf(ClientPlayerEntity player) { return new Vec3d(player.getX(), player.getY(), player.getZ()); }

    private void phantom$injectPart4Geometry(MinecraftClient client, ClientPlayerEntity player, String phase) {
        IntegratedServer server = client.getServer();
        if (server == null) return;
        int z = (int) Math.floor(player.getZ());
        server.executeSync(() -> {
            CommandManager m = server.getCommandManager();
            ServerCommandSource s = server.getCommandSource();
            if (phase.endsWith(":climbable")) {
                int ladderZ = z + 4;
                int wallZ = ladderZ + 1;
                cmd(m, s, "fill -1 64 " + ladderZ + " 1 68 " + ladderZ + " minecraft:ladder[facing=north]");
                cmd(m, s, "fill -1 64 " + wallZ + " 1 68 " + wallZ + " minecraft:stone");
            } else {
                int startZ = z + 5;
                int cornerZ = startZ + 13;
                cmd(m, s, "fill 4 64 " + (z + 20) + " 4 68 " + (z + 70) + " minecraft:air");
                cmd(m, s, "fill 4 64 " + (z + 45) + " 8 68 " + (z + 45) + " minecraft:air");
                cmd(m, s, "fill -1 64 " + startZ + " 1 66 " + cornerZ + " minecraft:stone");
                cmd(m, s, "fill 1 64 " + cornerZ + " 6 66 " + cornerZ + " minecraft:stone");
            }
        });
    }

    private static void cmd(CommandManager m, ServerCommandSource s, String command) { m.parseAndExecute(s, command); }
}
