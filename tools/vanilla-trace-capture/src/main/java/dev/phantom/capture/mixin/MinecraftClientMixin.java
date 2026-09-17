package dev.phantom.capture.mixin;

import dev.phantom.capture.ControlledPhase5ScenarioDriver;
import dev.phantom.capture.VanillaCorpusScenarioDriver;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Drives configured Phase 5 inputs before vanilla consumes keyboard state for the tick. */
@Mixin(MinecraftClient.class)
final class MinecraftClientMixin {
    private final ControlledPhase5ScenarioDriver phantom$scenarioDriver = new ControlledPhase5ScenarioDriver();
    private final VanillaCorpusScenarioDriver phantom$corpusDriver = new VanillaCorpusScenarioDriver();

    @Inject(method = "tick", at = @At("HEAD"))
    private void phantom$driveScenario(CallbackInfo ci) {
        MinecraftClient client = (MinecraftClient) (Object) this;
        if ("corpus".equalsIgnoreCase(System.getProperty("phantom.capture.mode", "legacy"))) {
            phantom$corpusDriver.tick(client);
        } else {
            phantom$scenarioDriver.tick(client);
        }
    }
}
