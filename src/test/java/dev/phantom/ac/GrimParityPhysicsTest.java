package dev.phantom.ac;

import dev.phantom.ac.Maths.Aabb;
import dev.phantom.ac.Maths.Vec3;
import dev.phantom.ac.Phase5Mechanics.MovementEnvironment;
import dev.phantom.ac.Phase5Mechanics.VehicleState;
import dev.phantom.ac.Phase5Mechanics.VehicleType;
import dev.phantom.ac.Phase6Reachability.Context;
import dev.phantom.ac.Phase6Reachability.MovementMode;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.EntityCollisions;
import dev.phantom.ac.world.WorldSnapshot;
import dev.phantom.ac.world.v12111.BlockCatalogue12111;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GrimParityPhysicsTest {
  private static WorldSnapshot loadedWorld() {
    return WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0, 0).build();
  }

  private static Player player(Vec3 position, Vec3 velocity, boolean ground) {
    return new Player(position, velocity, 0f, 0f, ground, "survival",
        Map.of(), OptionalInt.empty(), false, Optional.empty(),
        Simulation.Attributes.DEFAULT, Phase5Mechanics.Pose.STANDING,
        State.Environment.DRY, State.TickRange.unknown(),
        State.Provenance.UNKNOWN, Set.of());
  }

  @Test
  void waterFluidSamplingUsesClientVisibleNeighbourHeights() {
    var water = BlockCatalogue12111.decode("minecraft:water", Map.of("level", "0"));
    var shallow = BlockCatalogue12111.decode("minecraft:water", Map.of("level", "7"));

    var builder = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .loadChunk(-1, -1)
        .loadChunk(-1, 0)
        .loadChunk(0, -1)
        .loadChunk(0, 0);
    builder.setBlock(0, 64, 0, water);
    builder.setBlock(-1, 64, 0, water);
    builder.setBlock(1, 64, 0, shallow);
    builder.setBlock(0, 64, -1, water);
    builder.setBlock(0, 64, 1, water);
    WorldSnapshot world = builder.build();

    Aabb box = Aabb.playerAt(new Vec3(0.5, 64, 0.5), Phase5Mechanics.Pose.SWIMMING);
    GrimFluidPhysics.Sample sample =
        GrimFluidPhysics.sample(world, box, 64.4, true, false);

    assertEquals(GrimFluidPhysics.Type.WATER, sample.type());
    assertTrue(sample.hasFluid());
    assertTrue(sample.currentKnown(), sample.toString());
    assertTrue(Math.hypot(sample.current().x(), sample.current().z()) > 0.0, sample.toString());
  }

  @Test
  void swimmingSteeringMatchesGrimScalarBranches() {
    Vec3 velocity = new Vec3(0.1, 0.0, 0.0);
    Vec3 downwardLook = GrimFluidPhysics.applySwimmingSteering(velocity, -0.5, true);
    Vec3 levelLook = GrimFluidPhysics.applySwimmingSteering(velocity, 0.0, true);

    assertEquals(-0.0425, downwardLook.y(), 1e-12);
    assertEquals(0.0, levelLook.y(), 1e-12);
  }

  @Test
  void elytraUsesPitchDrivenLiftAndEndOfTickDrag() {
    Vec3 initial = new Vec3(0.0, -0.2, 0.4);
    Vec3 level = GrimElytraPhysics.tick(initial, 0f, 0f, 0.08, false);
    Vec3 dive = GrimElytraPhysics.tick(initial, 0f, 30f, 0.08, false);

    assertNotEquals(level, dive);
    assertNotEquals(initial, level);
    Vec3 dragged = GrimElytraPhysics.endOfTickDrag(level);
    assertEquals(level.x() * 0.99, dragged.x(), 1e-12);
    assertEquals(level.y() * 0.98, dragged.y(), 1e-12);
    assertEquals(level.z() * 0.99, dragged.z(), 1e-12);
  }

  @Test
  void vehicleTickerSeparatesRideableTypes() {
    MovementEnvironment env = MovementEnvironment.dry(false, false, false);
    Simulation.AdvancedInput forward = new Simulation.AdvancedInput(1, 0, false, false, false);

    VehicleState pig = new VehicleState(
        VehicleType.PIG, true, new Phase5Mechanics.Vec3Like(0, 0, 0),
        0f, 0f, false, 0.1, false, false);
    VehicleState horse = new VehicleState(
        VehicleType.HORSE, true, new Phase5Mechanics.Vec3Like(0, 0, 0),
        0f, 0f, false, 0.3, false, false);
    VehicleState none = VehicleState.NONE;

    Vec3 pigVelocity = GrimVehiclePhysics.tick(pig, forward, Vec3.ZERO, env).velocity();
    Vec3 horseVelocity = GrimVehiclePhysics.tick(horse, forward, Vec3.ZERO, env).velocity();
    Vec3 noneVelocity = GrimVehiclePhysics.tick(none, forward, Vec3.ZERO, env).velocity();

    assertNotEquals(pigVelocity, horseVelocity);
    assertEquals(Vec3.ZERO, noneVelocity);
  }

  @Test
  void predictionSeedsIncludeGrimStyleFluidAndClimbEntries() {
    Player waterPlayer = player(new Vec3(0.5, 64, 0.5), Vec3.ZERO, false);
    MovementEnvironment water = new MovementEnvironment(
        Phase5Mechanics.Fluid.WATER, true, false, false, false, false,
        true, false, 1.0, 0.8, 1.0, VehicleState.NONE);
    Context waterContext = new Context(
        0, waterPlayer, Simulation.Environment.WATER, waterPlayer.attributes(),
        Phase5Mechanics.MovementEffects.NONE, Phase5Mechanics.Pose.SWIMMING,
        water, false, EntityCollisions.NONE_TRACKED, Set.of());

    assertTrue(GrimPredictionSeeds.expand(waterContext).stream()
        .anyMatch(seed -> seed.cause().equals("SWIM_HOP")));

    MovementEnvironment climb = MovementEnvironment.vanillaClimbable(false, false, false);
    Context climbContext = new Context(
        0, waterPlayer, Simulation.Environment.CLIMBABLE, waterPlayer.attributes(),
        Phase5Mechanics.MovementEffects.NONE, Phase5Mechanics.Pose.STANDING,
        climb, false, EntityCollisions.NONE_TRACKED, Set.of());

    assertTrue(GrimPredictionSeeds.expand(climbContext).stream()
        .anyMatch(seed -> seed.cause().equals("CLIMBABLE_ENTRY")));
  }

  @Test
  void normalMovementUsesGrimLastOnGroundForAccelerationAfterLeavingEdge() {
    WorldSnapshot world = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .loadChunk(0, 0)
        .setBlock(0, 65, 0, BlockCatalogue12111.decode("minecraft:stone_slab", Map.of("type", "bottom")))
        .build();

    Player airborne = player(new Vec3(0.5, 65.1, 0.5), new Vec3(0.05, 0.1, 0.0), false);
    Simulation.AdvancedInput sprintForward =
        new Simulation.AdvancedInput(0, 0, false, true, false);
    MovementEnvironment airEnvironment =
        MovementEnvironment.dry(false, false, false);

    Vanilla12111RichPhysics.Context grimTemporalContext =
        new Vanilla12111RichPhysics.Context(
            0, airborne, sprintForward, world, Simulation.Environment.DRY,
            new Simulation.Attributes(0.1), Phase5Mechanics.MovementEffects.NONE,
            Phase5Mechanics.Pose.STANDING, airEnvironment, false, false,
            EntityCollisions.NONE_TRACKED, null, true);
    Vanilla12111RichPhysics.Context pureAirContext =
        new Vanilla12111RichPhysics.Context(
            0, airborne, sprintForward, world, Simulation.Environment.DRY,
            airborne.attributes(), Phase5Mechanics.MovementEffects.NONE,
            Phase5Mechanics.Pose.STANDING, airEnvironment, false, false,
            EntityCollisions.NONE_TRACKED, null, false);

    var temporal = new Vanilla12111RichPhysics().step(grimTemporalContext);
    var pureAir = new Vanilla12111RichPhysics().step(pureAirContext);

    assertFalse(temporal.state().onGround());
    assertFalse(pureAir.state().onGround());
    assertTrue(
        Math.abs(temporal.state().velocity().x())
            < Math.abs(pureAir.state().velocity().x()),
        () -> "lastOnGround=true must retain Grim ground friction while current onGround=false"
            + " temporal=" + temporal.state().velocity()
            + " air=" + pureAir.state().velocity());
  }

  @Test
  void collisionHandlesCornerContactWithoutSingleAxisOrderingAssumption() {
    WorldSnapshot world = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .loadChunk(0, 0)
        .setBlock(1, 64, 0, BlockCatalogue12111.decode("minecraft:stone", Map.of()))
        .setBlock(0, 64, 1, BlockCatalogue12111.decode("minecraft:stone", Map.of()))
        .build();

    Aabb start = Aabb.playerAt(new Vec3(0.5, 65, 0.5));
    RichWorldCollision.Result result =
        RichWorldCollision.resolve(world, start, new Vec3(0.5, -0.2, 0.5), 0.0);

    assertFalse(result.uncertain(), result.diagnostic());
    assertTrue(result.collidedX() || result.collidedY() || result.collidedZ(), result.diagnostic());
    assertTrue(Math.abs(result.displacement().x()) <= 0.5 + 1e-9);
    assertTrue(Math.abs(result.displacement().z()) <= 0.5 + 1e-9);
  }

  @Test
  void vehicleCandidateUsesExplicitVehicleMovementMode() {
    MovementEnvironment env = new MovementEnvironment(
        Phase5Mechanics.Fluid.NONE, false, false, true, false, false,
        false, false, 1.0, 1.0, 1.0,
        new VehicleState(
            VehicleType.PIG, true, new Phase5Mechanics.Vec3Like(0, 0, 0),
            0f, 0f, true, 0.1, false, false));
    Player p = player(Vec3.ZERO, Vec3.ZERO, true);
    Context context = new Context(
        0, p, Simulation.Environment.DRY, p.attributes(),
        Phase5Mechanics.MovementEffects.NONE, Phase5Mechanics.Pose.STANDING,
        env, false, EntityCollisions.NONE_TRACKED, Set.of());

    assertTrue(env.vehicle().active());
  }
}
