package dev.phantom.capture.mixin;

import dev.phantom.capture.VanillaTraceCaptureClient;
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.TeleportConfirmC2SPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes the exact client-side teleport confirmation packet before it is sent. */
@Mixin(ClientCommonNetworkHandler.class)
final class ClientCommonNetworkHandlerMixin {
    @Inject(method = "sendPacket", at = @At("HEAD"))
    private void phantom$observeOutgoing(Packet<?> packet, CallbackInfo ci) {
        if (packet instanceof TeleportConfirmC2SPacket confirm) {
            VanillaTraceCaptureClient.CaptureRuntime.recordTeleportConfirmPacket(confirm);
        }
    }
}
