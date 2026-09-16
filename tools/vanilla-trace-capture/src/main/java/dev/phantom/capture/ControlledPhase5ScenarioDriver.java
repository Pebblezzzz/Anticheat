package dev.phantom.capture;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;

/**
 * Deterministic Phase 5 capture driver. Resets are synchronous and never depend on
 * waiting for an exact client/server round-trip before a phase can begin.
 */
public final class ControlledPhase5ScenarioDriver {
    private static final String PROP = "phantom.capture.scenario";
    private static final String ALL = "all";
    private static final String NONE = "none";
    private static final String[] PHASES = {
            "walk","sprint","jump","sneak","diagonal","collision","water","lava",
            "speed-effect","slowness-effect","jump-boost","step","tail","stairs",
            "climbable","edge-corner","swim-transition","glide","correction"
    };
    private static final int[] DURATIONS = {
            100,100,100,100,100,100,120,30,80,80,100,110,70,90,100,100,120,120,40
    };
    private static final int START_Z = -40;
    private static final int SPACING = 40;
    private static volatile ControlledPhase5ScenarioDriver LAST;

    private String scenario = NONE;
    private int phaseIndex = -1;
    private int elapsed;
    private boolean prepared;
    private boolean done;

    public ControlledPhase5ScenarioDriver() { LAST = this; }

    public static String phaseLabel() {
        ControlledPhase5ScenarioDriver d = LAST;
        if (d == null) return "unknown";
        if (d.phaseIndex < 0) return d.scenario + ":setup";
        if (d.phaseIndex >= PHASES.length) return d.scenario + ":done";
        return d.scenario + ":" + PHASES[d.phaseIndex];
    }

    public void tick(MinecraftClient client) {
        if (!Boolean.parseBoolean(System.getProperty("phantom.capture.enabled","false"))) return;
        String requested = System.getProperty(PROP,NONE).trim().toLowerCase(Locale.ROOT);
        if (requested.equals(NONE) || client.player == null || client.world == null) { release(client.options); return; }
        if (!requested.equals(ALL)) throw new IllegalArgumentException("Unsupported Phase 5 scenario: " + requested);
        if (!requested.equals(scenario)) {
            scenario = requested; phaseIndex = -1; elapsed = 0; prepared = false; done = false;
            release(client.options);
        }
        if (done) { release(client.options); return; }
        if (!prepared) { prepare(client); prepared = true; beginPhase(client, 0); return; }
        runPhase(client);
    }

    private void runPhase(MinecraftClient client) {
        String phase = PHASES[phaseIndex];
        configureInput(client.options, phase, elapsed);
        if (phase.equals("glide")) driveGlide(client);
        if (phase.equals("correction")) {
            if (elapsed == 8) reset(client, 2, 65, baseZ(phase)+20, 90);
            if (elapsed == 24) reset(client, -2, 65, baseZ(phase)+24, 270);
        }
        Phase5CaptureDebug.tick(client, phase, elapsed, baseZ(phase)+20);
        elapsed++;
        if (elapsed >= DURATIONS[phaseIndex]) {
            Phase5CaptureDebug.end(phase);
            clearHazards(client, phase);
            phaseIndex++;
            if (phaseIndex >= PHASES.length) { done = true; release(client.options); client.scheduleStop(); }
            else beginPhase(client, phaseIndex);
        }
    }

    private void beginPhase(MinecraftClient client, int index) {
        phaseIndex = index;
        elapsed = 0;
        resetForPhase(client, PHASES[index]);
        release(client.options);
        Phase5CaptureDebug.resetAndStart(client, PHASES[index], index, baseZ(PHASES[index])+20);
        System.out.println("[Phase5] START " + PHASES[index] + " @ " + describe(client));
    }

    private void resetForPhase(MinecraftClient client, String phase) {
        double y = (phase.equals("water") || phase.equals("lava")) ? 64.0 : phase.equals("glide") ? 90.0 : 64.0;
        reset(client, 0.0, y, baseZ(phase)+2.0, 0.0);
        if (phase.equals("speed-effect")) effect(client,"minecraft:speed");
        if (phase.equals("slowness-effect")) effect(client,"minecraft:slowness");
        if (phase.equals("jump-boost")) effect(client,"minecraft:jump_boost");
        if (phase.equals("lava")) effect(client,"minecraft:fire_resistance");
        if (phase.equals("glide")) {
            client.player.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
            client.player.setOnGround(false);
            client.player.setVelocity(0.0,-0.15,0.0);
            client.player.fallDistance = 2.0f;
        }
    }

    private void driveGlide(MinecraftClient client) {
        var p = client.player;
        if (!p.isGliding() && p.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA)) {
            p.setOnGround(false);
            p.setVelocity(p.getVelocity().x, -0.2, p.getVelocity().z);
            p.fallDistance = Math.max(p.fallDistance, 2.0f);
            if (p.checkGliding()) p.startGliding();
        }
    }

    private void reset(MinecraftClient client, double x, double y, double z, double yaw) {
        IntegratedServer server = client.getServer();
        if (server == null) throw new IllegalStateException("Integrated server required");
        server.executeSync(() -> {
            ServerPlayerEntity sp = server.getPlayerManager().getPlayer(client.player.getUuid());
            if (sp == null) throw new IllegalStateException("Server player missing");
            sp.setVelocity(Vec3d.ZERO);
            sp.setOnGround(true);
            sp.fallDistance = 0.0f;
            sp.extinguish();
            sp.refreshPositionAndAngles(x,y,z,(float)yaw,0.0f);
            sp.requestTeleport(x,y,z);
            cmd(server.getCommandManager(), server.getCommandSource(), "effect clear @a");
        });
        client.player.requestTeleport(x,y,z);
        client.player.setVelocity(Vec3d.ZERO);
        client.player.setOnGround(true);
        client.player.fallDistance = 0.0f;
        client.player.extinguish();
        client.player.setYaw((float)yaw);
        client.player.setPitch(0.0f);
    }

    private void clearHazards(MinecraftClient client, String previous) {
        if (previous.equals("lava")) {
            client.player.extinguish();
            clearEffects(client.getServer());
        }
    }
    private void clearEffects(IntegratedServer server) {
        if (server != null) server.executeSync(() -> cmd(server.getCommandManager(), server.getCommandSource(), "effect clear @a"));
    }
    private void effect(MinecraftClient client, String id) {
        IntegratedServer server = client.getServer();
        if (server != null) server.executeSync(() -> cmd(server.getCommandManager(), server.getCommandSource(), "effect give @a " + id + " 600 0 true"));
    }
    private void prepare(MinecraftClient client) {
        IntegratedServer server = client.getServer();
        if (server == null) throw new IllegalStateException("Integrated server required");
        server.executeSync(() -> {
            CommandManager m=server.getCommandManager(); ServerCommandSource s=server.getCommandSource();
            cmd(m,s,"difficulty peaceful"); cmd(m,s,"time set day"); cmd(m,s,"weather clear");
            int min=START_Z-4, max=START_Z+SPACING*(PHASES.length-1)+36;
            cmd(m,s,"fill -20 63 " + min + " 20 63 " + max + " minecraft:stone");
            cmd(m,s,"fill -20 64 " + min + " 20 67 " + max + " air");
            int water=baseZ("water"); cmd(m,s,"fill -8 64 " + (water+6) + " 8 65 " + (water+28) + " minecraft:water");
            int lava=baseZ("lava"); cmd(m,s,"fill -8 64 " + (lava+6) + " 8 65 " + (lava+28) + " minecraft:lava");
            int c=baseZ("collision"); cmd(m,s,"fill -2 64 " + (c+14) + " 2 66 " + (c+18) + " minecraft:stone");
            int st=baseZ("step"); cmd(m,s,"fill -3 64 " + (st+10) + " 3 64 " + (st+12) + " minecraft:oak_slab[type=bottom]");
            int stairs=baseZ("stairs");
            cmd(m,s,"fill -2 64 " + (stairs+10) + " 2 64 " + (stairs+12) + " minecraft:oak_stairs[facing=south,half=bottom,shape=straight]");
            cmd(m,s,"fill -2 65 " + (stairs+13) + " 2 65 " + (stairs+15) + " minecraft:oak_stairs[facing=south,half=bottom,shape=straight]");
            int climb=baseZ("climbable"); cmd(m,s,"fill -1 64 " + (climb+15) + " 1 68 " + (climb+15) + " minecraft:stone"); cmd(m,s,"fill -1 64 " + (climb+14) + " 1 68 " + (climb+14) + " minecraft:ladder[facing=south]");
            int edge=baseZ("edge-corner"); cmd(m,s,"fill 3 64 " + (edge+12) + " 3 67 " + (edge+28) + " minecraft:stone"); cmd(m,s,"fill 3 64 " + (edge+20) + " 7 67 " + (edge+20) + " minecraft:stone");
            int swim=baseZ("swim-transition"); cmd(m,s,"fill -8 64 " + (swim+6) + " 8 65 " + (swim+24) + " minecraft:water");
            int glide=baseZ("glide"); cmd(m,s,"fill -8 63 " + (glide+4) + " 8 63 " + (glide+55) + " minecraft:stone"); cmd(m,s,"fill -8 64 " + (glide+4) + " 8 120 " + (glide+55) + " air");
            cmd(m,s,"gamemode survival @a"); cmd(m,s,"effect clear @a");
        });
    }
    private static int baseZ(String phase){ for(int i=0;i<PHASES.length;i++) if(PHASES[i].equals(phase)) return START_Z+SPACING*i; throw new IllegalArgumentException(phase); }
    private static void configureInput(GameOptions o,String phase,int t){ boolean f=true,l=false,r=false,j=false,sn=false,sp=false; switch(phase){case "sprint","water","lava","collision","step","stairs","glide"->sp=true; case "jump","jump-boost"->j=t<2; case "sneak"->sn=true; case "diagonal"->l=true; case "edge-corner"-> {l=t<50;r=!l;} case "swim-transition"-> {sp=true;j=t%24<6;sn=t%24>=12&&t%24<18;} case "correction","tail","walk","speed-effect","slowness-effect","climbable"->{} } apply(o,f,false,l,r,j,sn,sp); }
    private static void apply(GameOptions o,boolean f,boolean b,boolean l,boolean r,boolean j,boolean sn,boolean sp){o.forwardKey.setPressed(f);o.backKey.setPressed(b);o.leftKey.setPressed(l);o.rightKey.setPressed(r);o.jumpKey.setPressed(j);o.sneakKey.setPressed(sn);o.sprintKey.setPressed(sp);}
    private static void release(GameOptions o){apply(o,false,false,false,false,false,false,false);}
    private static void cmd(CommandManager m,ServerCommandSource s,String c){m.parseAndExecute(s,c);}
    private static String describe(MinecraftClient c){var p=c.player;return "x="+p.getX()+",y="+p.getY()+",z="+p.getZ()+",yaw="+p.getYaw()+",ground="+p.isOnGround()+",gliding="+p.isGliding();}
}
