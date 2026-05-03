package com.aimassist;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.Comparator;
import java.util.List;

public class AimAssistMod implements ClientModInitializer {

    private static KeyBinding toggleKey;
    private static boolean aimAssistEnabled = false;
    private static Entity lockedTarget = null;

    // Lower = smoother and more delayed feel (0.08 ≈ ~0.1s lag behind)
    private static final float LERP_SPEED = 0.08f;
    private static final double RANGE = 8.0;

    // ~0.1 second delay before starting to follow (2 ticks at 20 TPS)
    private static int delayTicks = 0;
    private static final int DELAY_TICK_COUNT = 2;

    @Override
    public void onInitializeClient() {
        // Register Right Shift toggle key
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.aimassist.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_SHIFT,
                "category.aimassist"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;

            // Handle toggle press
            while (toggleKey.wasPressed()) {
                aimAssistEnabled = !aimAssistEnabled;
                lockedTarget = null;
                delayTicks = 0;

                // Show message on action bar (above hotbar)
                String message = aimAssistEnabled
                        ? "§aAim Assist: ON"
                        : "§cAim Assist: OFF";
                client.player.sendMessage(Text.literal(message), true);
            }

            if (!aimAssistEnabled) return;

            // Re-acquire target if lost, out of range, or dead
            if (lockedTarget == null
                    || !lockedTarget.isAlive()
                    || client.player.distanceTo(lockedTarget) > RANGE) {
                lockedTarget = findNearestTarget(client);
                delayTicks = DELAY_TICK_COUNT;
                return;
            }

            // Wait out the initial delay before following
            if (delayTicks > 0) {
                delayTicks--;
                return;
            }

            // Smoothly aim toward the locked target
            smoothAim(client, lockedTarget);
        });
    }

    /**
     * Finds the nearest living entity within RANGE blocks,
     * excluding the local player.
     */
    private Entity findNearestTarget(MinecraftClient client) {
        PlayerEntity player = client.player;
        Box searchBox = player.getBoundingBox().expand(RANGE);

        List<LivingEntity> entities = client.world.getEntitiesByClass(
                LivingEntity.class,
                searchBox,
                e -> e != player && e.isAlive() && !e.isSpectator()
        );

        return entities.stream()
                .filter(e -> player.distanceTo(e) <= RANGE)
                .min(Comparator.comparingDouble(player::distanceTo))
                .orElse(null);
    }

    /**
     * Smoothly interpolates the player's yaw and pitch toward the target,
     * giving a natural, slightly-delayed feel rather than hard snapping.
     */
    private void smoothAim(MinecraftClient client, Entity target) {
        PlayerEntity player = client.player;

        // Aim for ~60% up the entity's body (avoids feet/top of head)
        Vec3d targetPos = target.getPos().add(0, target.getHeight() * 0.6, 0);
        Vec3d eyePos = player.getEyePos();
        Vec3d delta = targetPos.subtract(eyePos);

        // Calculate angles to the target
        double horizontalDist = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float targetYaw   = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        float targetPitch = (float) (-Math.toDegrees(Math.atan2(delta.y, horizontalDist)));

        float currentYaw   = player.getYaw();
        float currentPitch = player.getPitch();

        // Normalise yaw difference into [-180, 180] to avoid spinning the long way
        float yawDiff = targetYaw - currentYaw;
        while (yawDiff > 180)  yawDiff -= 360;
        while (yawDiff < -180) yawDiff += 360;

        // Lerp toward target angles — feels smooth, not locked on
        float newYaw   = currentYaw   + yawDiff                        * LERP_SPEED;
        float newPitch = currentPitch + (targetPitch - currentPitch)   * LERP_SPEED;

        player.setYaw(newYaw);
        player.setPitch(newPitch);

        // Sync previous tick values so interpolation in rendering is clean
        player.prevYaw   = newYaw;
        player.prevPitch = newPitch;
    }
}
