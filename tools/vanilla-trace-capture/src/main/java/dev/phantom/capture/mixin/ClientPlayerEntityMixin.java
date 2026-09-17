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

@Mixin(ClientPlayerEntity.class)
final class ClientPlayerEntityMixin {
    private boolean phantom$jumpBoostInjected;
    private boolean phantom$part4GeometryInjected;
    private String phantom$lastPhase = "";
    private int phantom$phaseTicks;

    @Inject(method = "tick", at = @At("TAIL"))
    private void phantom$recordAfterTick(CallbackInfo ci) {
        ClientPlayerEntity player = (ClientPlayerEntity) (Object) this;
        MinecraftClient client = MinecraftClient.getInstance();
        String phase = ControlledPhase5ScenarioDriver.phaseLabel();

        if (!phase.equals(phantom$lastPhase)) {
            phantom$lastPhase = phase;
            phantom$jumpBoostInjected = false;
            phantom$part4GeometryInjected = false;
            phantom$phaseTicks = 0;
        }

        if (phase.endsWith(":jump-boost")
                && !phantom$jumpBoostInjected
                && player.isOnGround()
                && player.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.JUMP_BOOST)) {
            double beforeY = player.getY();
            double beforeVy = player.getVelocity().y;
            player.jump();
            phantom$jumpBoostInjected = true;
            System.out.println("[Phase5] jump-boost client jump applied phase=" + phase
                    + " beforeY=" + beforeY
                    + " beforeVy=" + beforeVy
                    + " afterVy=" + player.getVelocity().y
                    + " onGround=" + player.isOnGround());
        }

        if (phase.equals("part4:climbable") || phase.equals("part4:edge-corner")) {
            double expectedStartZ = phase.equals("part4:climbable") ? -38.0D : 142.0D;
            boolean settledAtPhaseStart = player.isOnGround()
                    && Math.abs(player.getY() - 64.0D) <= 0.75D
                    && Math.abs(player.getZ() - expectedStartZ) <= 0.75D;

            if (!phantom$part4GeometryInjected && settledAtPhaseStart) {
                phantom$injectPart4Geometry(client, player, phase);
                phantom$part4GeometryInjected = true;
            }

            if (phase.equals("part4:edge-corner")) {
                boolean right = phantom$phaseTicks >= 31 && phantom$phaseTicks < 56;
                boolean left = phantom$phaseTicks >= 56 && phantom$phaseTicks < 81;
                client.options.rightKey.setPressed(right);
                client.options.leftKey.setPressed(left);
                client.options.forwardKey.setPressed(true);
                client.options.backKey.setPressed(false);
                client.options.sprintKey.setPressed(false);
            } else {
                client.options.forwardKey.setPressed(true);
                client.options.backKey.setPressed(false);
                client.options.leftKey.setPressed(false);
                client.options.rightKey.setPressed(false);
                client.options.sprintKey.setPressed(false);
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
            if (phase.equals("part4:climbable")) {
                int ladderZ = z + 4;
                int wallZ = ladderZ - 1;
                cmd(m, s, "fill -1 64 " + ladderZ + " 1 68 " + ladderZ + " minecraft:ladder[facing=south]");
                cmd(m, s, "fill -1 64 " + wallZ + " 1 68 " + wallZ + " minecraft:stone");
                System.out.println("[Phase5] climbable geometry injected ladder_z=" + ladderZ + " wall_z=" + wallZ);
            } else {
                int startZ = z + 5;
                int cornerZ = startZ + 13;
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
