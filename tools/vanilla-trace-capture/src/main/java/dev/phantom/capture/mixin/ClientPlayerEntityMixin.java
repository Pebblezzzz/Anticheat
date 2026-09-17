package dev.phantom.capture.mixin;

import dev.phantom.capture.ControlledPhase5ScenarioDriver;
import dev.phantom.capture.VanillaTraceCaptureClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.integrated.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Reads pre-tick input and records vanilla post-tick state. */
@Mixin(ClientPlayerEntity.class)
final class ClientPlayerEntityMixin {
    private boolean phantom$part4GeometryInjected;
    private String phantom$lastPhase = "";
    private int phantom$phaseTicks;

    @Inject(method = "tick", at = @At("HEAD"))
    private void phantom$observeBeforeTick(CallbackInfo ci) {
        VanillaTraceCaptureClient.CaptureRuntime.observePreTickInput((ClientPlayerEntity) (Object) this);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void phantom$recordAfterTick(CallbackInfo ci) {
        ClientPlayerEntity player = (ClientPlayerEntity) (Object) this;
        MinecraftClient client = MinecraftClient.getInstance();
        String phase = ControlledPhase5ScenarioDriver.phaseLabel();

        if (!phase.equals(phantom$lastPhase)) {
            phantom$lastPhase = phase;
            phantom$part4GeometryInjected = false;
            phantom$phaseTicks = 0;
        }

        // World setup for the legacy Part 4 controlled capture is performed here
        // at phase start; movement input itself is set only before the client tick
        // by MinecraftClientMixin so the recorder can sample it causally at HEAD.
        if (phase.endsWith(":climbable") || phase.endsWith(":edge-corner")) {
            boolean settledAtPhaseStart = player.isOnGround()
                    && Math.abs(player.getY() - 64.0D) <= 0.75D;
            if (!phantom$part4GeometryInjected && settledAtPhaseStart && phantom$phaseTicks <= 2) {
                phantom$injectPart4Geometry(client, player, phase);
                phantom$part4GeometryInjected = true;
            }
        }

        VanillaTraceCaptureClient.CaptureRuntime.recordForMixin(client, player);
        phantom$phaseTicks++;
    }

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
                System.out.println("[Phase5] climbable geometry injected ladder_z=" + ladderZ + " wall_z=" + wallZ);
            } else {
                int startZ = z + 5;
                int cornerZ = startZ + 13;
                cmd(m, s, "fill 4 64 " + (z + 20) + " 4 68 " + (z + 70) + " minecraft:air");
                cmd(m, s, "fill 4 64 " + (z + 45) + " 8 68 " + (z + 45) + " minecraft:air");
                cmd(m, s, "fill -1 64 " + startZ + " 1 66 " + cornerZ + " minecraft:stone");
                cmd(m, s, "fill 1 64 " + cornerZ + " 6 66 " + cornerZ + " minecraft:stone");
                System.out.println("[Phase5] edge-corner geometry injected start_z=" + startZ + " corner_z=" + cornerZ);
            }
        });
    }

    private static void cmd(CommandManager m, ServerCommandSource s, String command) {
        m.parseAndExecute(s, command);
    }
}
