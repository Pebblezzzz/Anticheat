package dev.phantom.capture.mixin;

import dev.phantom.capture.VanillaScenarioDriver;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Drives configured experiment inputs before vanilla consumes keyboard state for the tick. */
@Mixin(MinecraftClient.class)
final class MinecraftClientMixin {
    private final VanillaScenarioDriver phantom$scenarioDriver = new VanillaScenarioDriver();

    @Inject(method = "tick", at = @At("HEAD"))
    private void phantom$driveScenario(CallbackInfo ci) {
        phantom$scenarioDriver.tick((MinecraftClient) (Object) this);
    }
}
