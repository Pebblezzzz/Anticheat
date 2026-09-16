package dev.phantom.capture.mixin;

import dev.phantom.capture.ControlledPhase5ScenarioDriver;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ensures the controlled glide scenario enters the real vanilla gliding state before a fall can kill it. */
@Mixin(ClientPlayerEntity.class)
final class ElytraGlideActivationMixin {
    private int phantom$activationAttempts;

    @Inject(method = "tick", at = @At("TAIL"))
    private void phantom$activateControlledGlide(CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = (ClientPlayerEntity) (Object) this;
        String phase = ControlledPhase5ScenarioDriver.phaseLabel();

        if (!phase.endsWith(":glide") || player.isDead() || player.isGliding()) {
            return;
        }

        ItemStack chest = player.getEquippedStack(EquipmentSlot.CHEST);
        if (!chest.isOf(Items.ELYTRA)) {
            player.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
            chest = player.getEquippedStack(EquipmentSlot.CHEST);
        }

        if (player.getY() < 100.0D) {
            System.err.println("[Phase5-Debug][ELYTRA-MIXIN] Refusing late activation below safety height: "
                    + "y=" + player.getY() + " state=" + describe(player));
            return;
        }

        Vec3d velocity = player.getVelocity();
        player.setOnGround(false);
        player.setVelocity(velocity.x, Math.min(velocity.y, -0.08D), velocity.z);
        if (player.fallDistance < 2.0F) {
            player.fallDistance = 2.0F;
        }

        boolean eligible = player.checkGliding();
        phantom$activationAttempts++;
        System.out.println("[Phase5-Debug][ELYTRA-MIXIN] attempt=" + phantom$activationAttempts
                + " eligible=" + eligible
                + " phase=" + phase
                + " chest=" + chest
                + " state=" + describe(player));

        if (eligible) {
            player.startGliding();
            System.out.println("[Phase5-Debug][ELYTRA-MIXIN] startGliding -> " + describe(player));
        }

        if (!player.isGliding() && phantom$activationAttempts >= 3) {
            // The public vanilla transition is the source of truth. Do not let the
            // observation harness kill the player while we are trying to capture it.
            player.startGliding();
            System.out.println("[Phase5-Debug][ELYTRA-MIXIN] forced vanilla state transition after repeated eligibility failure -> "
                    + describe(player));
        }
    }

    private static String describe(ClientPlayerEntity player) {
        Vec3d v = player.getVelocity();
        return "x=" + player.getX()
                + ",y=" + player.getY()
                + ",z=" + player.getZ()
                + ",pose=" + player.getPose()
                + ",ground=" + player.isOnGround()
                + ",gliding=" + player.isGliding()
                + ",fallDistance=" + player.fallDistance
                + ",vel=" + v.x + "," + v.y + "," + v.z;
    }
}
