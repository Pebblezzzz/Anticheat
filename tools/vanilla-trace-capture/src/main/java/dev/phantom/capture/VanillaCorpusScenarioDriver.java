package dev.phantom.capture;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;

/**
 * Corpus-grade controlled scenario driver. It prepares world/effect fixtures and
 * drives only keyboard/server events; the recorder observes the resulting vanilla
 * client state. Run with phantom.capture.mode=corpus.
 */
public final class VanillaCorpusScenarioDriver {
    private static final String PROP = "phantom.capture.scenario";
    private static final String NONE = "none";
    private static final int TICKS = 120;
    private static final int RESET_SETTLE = 2;
    private static final String[] SCENARIOS = {
            "idle","walk-forward","walk-backward","strafe-left","strafe-right","diagonal",
            "sprint-forward","sprint-strafe","sprint-diagonal","jump","sprint-jump","repeated-jumps",
            "ascent-apex-fall","landing","sneak","slab-up","stairs-up","step-up","partial-collision",
            "edge","corner","corner-sprint","water-surface","deep-swimming","water-sprint","swim-transition",
            "lava","ladder","ladder-sprint","vines","speed-effect","slowness-effect","jump-boost",
            "slow-falling","levitation","attribute-modifier","knockback-ground","knockback-air",
            "teleport-correction","teleport-water","glide","sleeping","step-sprint-jump","water-jump",
            "climb-jump","correction-after-knockback"
    };

    private String scenario = NONE;
    private int index = -1;
    private int elapsed;
    private boolean prepared;
    private boolean resetting;
    private int resetTicks;

    public void tick(MinecraftClient client) {
        if (!Boolean.parseBoolean(System.getProperty("phantom.capture.enabled", "false"))) return;
        String requested = System.getProperty(PROP, NONE).trim().toLowerCase(Locale.ROOT);
        if (requested.equals(NONE) || client.player == null || client.world == null) { release(client.options); return; }
        if (!requested.equals("all") && !contains(requested)) throw new IllegalArgumentException("Unknown corpus scenario: " + requested);

        if (!requested.equals(scenario)) {
            scenario = requested;
            index = requested.equals("all") ? 0 : position(requested);
            elapsed = 0;
            prepared = false;
            resetting = false;
            resetTicks = 0;
            release(client.options);
        }
        if (index < 0 || index >= SCENARIOS.length) { release(client.options); return; }
        String id = SCENARIOS[index];

        if (!prepared) {
            if (!resetting) {
                prepareScenario(client, id);
                resetting = true;
                resetTicks = 0;
                release(client.options);
            } else if (++resetTicks >= RESET_SETTLE) {
                prepared = true;
                resetting = false;
                elapsed = 0;
            }
            return;
        }

        configureInput(client.options, id, elapsed);
        triggerScenarioEvent(client, id, elapsed);
        elapsed++;
        if (elapsed >= TICKS) {
            release(client.options);
            if (scenario.equals("all")) {
                index++;
                if (index >= SCENARIOS.length) {
                    System.out.println("[Phase5] CORPUS COMPLETE");
                    client.scheduleStop();
                    return;
                }
                prepared = false;
                resetting = false;
            } else {
                System.out.println("[Phase5] CORPUS COMPLETE scenario=" + id);
                client.scheduleStop();
            }
        }
    }

    public static String phaseLabel() {
        // The vanilla recorder uses the legacy driver's phase label today. This
        // method is kept public so the mode switch can migrate labels without
        // changing the 47-column trace schema.
        return "corpus:" + (INSTANCE == null ? "unknown" : INSTANCE.currentScenario());
    }

    private static VanillaCorpusScenarioDriver INSTANCE;

    public VanillaCorpusScenarioDriver() { INSTANCE = this; }
    private String currentScenario() { return index < 0 || index >= SCENARIOS.length ? scenario : SCENARIOS[index]; }

    private void prepareScenario(MinecraftClient client, String id) {
        IntegratedServer server = client.getServer();
        if (server == null) throw new IllegalStateException("Integrated server required");
        server.executeSync(() -> {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(client.player.getUuid());
            ServerWorld world = server.getOverworld();
            if (player == null) return;
            clearEffects(player, server);
            player.wakeUp(true, true);
            player.setVelocity(Vec3d.ZERO);
            player.setOnGround(true);
            player.fallDistance = 0.0F;
            player.extinguish();
            player.changeDimension(world);
            player.refreshPositionAndAngles(0.0D, 64.0D, 0.0D, 0.0F, 0.0F);
            player.requestTeleport(0.0D, 64.0D, 0.0D);
            cmd(server.getCommandManager(), server.getCommandSource(), "gamemode survival @a");
            cmd(server.getCommandManager(), server.getCommandSource(), "time set day");
            cmd(server.getCommandManager(), server.getCommandSource(), "weather clear");
            cmd(server.getCommandManager(), server.getCommandSource(), "fill -8 63 0 8 63 100 minecraft:stone");
            cmd(server.getCommandManager(), server.getCommandSource(), "fill -8 64 0 8 70 100 air");
            setupWorld(server, world, id);
            setupEffects(server, id);
            if (id.equals("landing") || id.equals("slow-falling") || id.equals("levitation") || id.equals("knockback-air") || id.equals("glide")) {
                player.refreshPositionAndAngles(0.0D, 74.0D, 0.0D, 0.0F, 0.0F);
                player.requestTeleport(0.0D, 74.0D, 0.0D);
                player.setOnGround(false);
                player.setVelocity(Vec3d.ZERO);
                player.fallDistance = 0.0F;
            }
            if (id.equals("glide")) {
                player.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
            }
            if (id.equals("attribute-modifier")) {
                cmd(server.getCommandManager(), server.getCommandSource(), "attribute @a minecraft:generic.movement_speed base set 0.14");
            }
        });
        System.out.println("[Phase5] CORPUS START scenario=" + id);
    }

    private void setupWorld(IntegratedServer server, ServerWorld world, String id) {
        CommandManager m = server.getCommandManager();
        ServerCommandSource s = server.getCommandSource();
        switch (id) {
            case "slab-up" -> cmd(m, s, "fill -2 64 25 2 64 29 minecraft:oak_slab[type=bottom]");
            case "stairs-up" -> {
                cmd(m, s, "fill -2 64 25 2 64 28 minecraft:oak_stairs[facing=south,half=bottom,shape=straight]");
                cmd(m, s, "fill -2 65 29 2 65 32 minecraft:oak_stairs[facing=south,half=bottom,shape=straight]");
            }
            case "step-up", "step-sprint-jump" -> cmd(m, s, "fill -2 64 25 2 64 29 minecraft:oak_planks");
            case "partial-collision" -> cmd(m, s, "fill 1 64 25 1 66 29 minecraft:oak_planks");
            case "edge" -> cmd(m, s, "fill 0 64 25 4 64 30 minecraft:stone");
            case "corner", "corner-sprint", "correction-after-knockback" -> {
                cmd(m, s, "fill -2 64 25 -2 67 45 minecraft:stone");
                cmd(m, s, "fill -2 64 35 5 67 35 minecraft:stone");
            }
            case "water-surface", "swim-transition", "water-jump", "teleport-water" -> fillFluid(world, Blocks.WATER, 25, 45, 64, 65);
            case "deep-swimming", "water-sprint" -> fillFluid(world, Blocks.WATER, 25, 70, 63, 66);
            case "lava" -> fillFluid(world, Blocks.LAVA, 25, 45, 64, 65);
            case "ladder", "ladder-sprint" -> cmd(m, s, "fill -1 64 30 1 68 30 minecraft:ladder[facing=north]");
            case "vines" -> cmd(m, s, "fill -1 64 30 1 68 30 minecraft:vine[east=true]");
            case "sleeping" -> {
                cmd(m, s, "setblock 0 64 30 minecraft:white_bed[facing=south,part=foot,occupied=false]");
                cmd(m, s, "setblock 0 64 31 minecraft:white_bed[facing=south,part=head,occupied=false]");
            }
            default -> { }
        }
    }

    private void setupEffects(IntegratedServer server, String id) {
        CommandManager m = server.getCommandManager();
        ServerCommandSource s = server.getCommandSource();
        switch (id) {
            case "speed-effect" -> cmd(m, s, "effect give @a minecraft:speed 600 1 true");
            case "slowness-effect" -> cmd(m, s, "effect give @a minecraft:slowness 600 1 true");
            case "jump-boost" -> cmd(m, s, "effect give @a minecraft:jump_boost 600 2 true");
            case "slow-falling" -> cmd(m, s, "effect give @a minecraft:slow_falling 600 0 true");
            case "levitation" -> cmd(m, s, "effect give @a minecraft:levitation 600 1 true");
            case "lava" -> cmd(m, s, "effect give @a minecraft:fire_resistance 600 0 true");
            default -> { }
        }
    }

    private void triggerScenarioEvent(MinecraftClient client, String id, int t) {
        IntegratedServer server = client.getServer();
        if (server == null || client.player == null) return;
        server.executeSync(() -> {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(client.player.getUuid());
            if (player == null) return;
            if (id.equals("knockback-ground") && t == 20) player.setVelocity(0.8D, 0.32D, 0.25D);
            if (id.equals("knockback-air") && t == 20) player.setVelocity(0.8D, 0.32D, 0.25D);
            if (id.equals("correction-after-knockback") && t == 20) {
                player.setVelocity(0.8D, 0.32D, 0.25D);
            }
            if ((id.equals("teleport-correction") || id.equals("teleport-water")) && (t == 20 || t == 60)) {
                double z = id.equals("teleport-water") ? 32.0D : 40.0D;
                player.refreshPositionAndAngles(0.0D, 65.0D, z, 90.0F, 0.0F);
                player.requestTeleport(0.0D, 65.0D, z);
            }
            if (id.equals("sleeping") && t == 5) player.trySleep(new BlockPos(0, 64, 30));
            if (id.equals("sleeping") && t == 80) player.wakeUp(true, true);
        });
        if (id.equals("glide") && t == 4 && client.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA)) {
            client.player.setOnGround(false);
            client.player.fallDistance = 2.0F;
            if (client.player.checkGliding()) client.player.startGliding();
        }
        if (id.equals("landing") && t == 0) {
            client.player.setVelocity(0.0D, -0.08D, 0.0D);
        }
    }

    private static void configureInput(GameOptions o, String id, int t) {
        boolean f = false, b = false, l = false, r = false, j = false, sneak = false, sprint = false;
        switch (id) {
            case "idle", "landing", "sleeping" -> { }
            case "walk-forward", "water-surface", "deep-swimming", "water-sprint", "swim-transition", "lava", "speed-effect", "slowness-effect", "attribute-modifier", "teleport-correction", "teleport-water" -> f = true;
            case "walk-backward" -> b = true;
            case "strafe-left" -> l = true;
            case "strafe-right" -> r = true;
            case "diagonal", "sprint-diagonal", "corner", "corner-sprint" -> { f = true; l = true; sprint = id.startsWith("sprint") || id.equals("corner-sprint"); }
            case "sprint-forward" -> { f = true; sprint = true; }
            case "sprint-strafe" -> { l = true; sprint = true; }
            case "jump", "jump-boost", "water-jump" -> { f = true; j = t < 3; }
            case "sprint-jump", "step-sprint-jump" -> { f = true; sprint = true; j = t < 3; }
            case "repeated-jumps" -> { f = true; j = t % 32 < 3; }
            case "ascent-apex-fall", "slow-falling", "levitation" -> { f = true; j = t < 3; }
            case "sneak" -> { f = true; sneak = true; }
            case "slab-up", "stairs-up", "step-up", "partial-collision", "edge" -> f = true;
            case "ladder", "ladder-sprint", "vines", "climb-jump" -> { f = true; sprint = id.equals("ladder-sprint"); j = id.equals("climb-jump") && t < 3; }
            case "glide" -> { f = true; sprint = true; }
            case "knockback-ground", "knockback-air", "correction-after-knockback" -> f = true;
            default -> { }
        }
        apply(o, f, b, l, r, j, sneak, sprint);
    }

    private static void fillFluid(ServerWorld world, net.minecraft.block.Block block, int z1, int z2, int y1, int y2) {
        for (int z = z1; z <= z2; z++) for (int y = y1; y <= y2; y++) for (int x = -2; x <= 2; x++) world.setBlockState(new BlockPos(x, y, z), block.getDefaultState());
    }

    private static void clearEffects(ServerPlayerEntity player, IntegratedServer server) {
        cmd(server.getCommandManager(), server.getCommandSource(), "effect clear @a");
    }

    private static void apply(GameOptions o, boolean f, boolean b, boolean l, boolean r, boolean j, boolean sn, boolean sp) {
        o.forwardKey.setPressed(f); o.backKey.setPressed(b); o.leftKey.setPressed(l); o.rightKey.setPressed(r);
        o.jumpKey.setPressed(j); o.sneakKey.setPressed(sn); o.sprintKey.setPressed(sp);
    }

    private static void release(GameOptions o) { apply(o, false, false, false, false, false, false, false); }
    private static void cmd(CommandManager m, ServerCommandSource s, String command) { m.parseAndExecute(s, command); }
    private static boolean contains(String id) { return position(id) >= 0; }
    private static int position(String id) { for (int i = 0; i < SCENARIOS.length; i++) if (SCENARIOS[i].equals(id)) return i; return -1; }
}
