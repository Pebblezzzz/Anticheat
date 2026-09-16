package dev.phantom.capture.mixin;

import dev.phantom.capture.VanillaTraceCaptureClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerEntity.class)
final class ClientPlayerEntityMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void phantom$recordAfterTick(CallbackInfo ci) {
        ClientPlayerEntity player = (ClientPlayerEntity) (Object) this;
        VanillaTraceCaptureClient.CaptureRuntime.recordForMixin(MinecraftClient.getInstance(), player);
    }
}
