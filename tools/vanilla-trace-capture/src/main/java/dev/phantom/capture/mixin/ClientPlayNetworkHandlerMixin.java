package dev.phantom.capture.mixin;

import dev.phantom.capture.VanillaTraceCaptureClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
final class ClientPlayNetworkHandlerMixin {
    @Inject(method = "onEntityVelocityUpdate", at = @At("HEAD"))
    private void phantom$observeVelocity(EntityVelocityUpdateS2CPacket packet, CallbackInfo ci) {
        VanillaTraceCaptureClient.CaptureRuntime.recordVelocityPacket(packet);
    }

    @Inject(method = "onPlayerPositionLook", at = @At("HEAD"))
    private void phantom$observeCorrection(PlayerPositionLookS2CPacket packet, CallbackInfo ci) {
        VanillaTraceCaptureClient.CaptureRuntime.recordCorrectionPacket(packet);
    }
}
