package dev.phantom.capture.mixin;

import dev.phantom.capture.ControlledPhase5ScenarioDriver;
import dev.phantom.capture.VanillaTraceCaptureClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.effect.StatusEffects;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerEntity.class)
final class ClientPlayerEntityMixin {
    private boolean phantom$jumpBoostInjected;
    private String phantom$lastPhase = "";

    @Inject(method = "tick", at = @At("TAIL"))
    private void phantom$recordAfterTick(CallbackInfo ci) {
        ClientPlayerEntity player = (ClientPlayerEntity) (Object) this;
        MinecraftClient client = MinecraftClient.getInstance();
        String phase = ControlledPhase5ScenarioDriver.phaseLabel();

        if (!phase.equals(phantom$lastPhase)) {
            phantom$lastPhase = phase;
            phantom$jumpBoostInjected = false;
        }

        if (phase.endsWith(":jump-boost")
                && !phantom$jumpBoostInjected
                && player.isOnGround()
                && player.hasStatusEffect(StatusEffects.JUMP_BOOST)) {
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

        VanillaTraceCaptureClient.CaptureRuntime.recordForMixin(client, player);
    }
}
