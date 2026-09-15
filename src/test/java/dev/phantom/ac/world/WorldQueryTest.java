package dev.phantom.ac.world;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import dev.phantom.ac.Contracts;
import dev.phantom.ac.geometry.BlockBox;
import dev.phantom.ac.geometry.Directions.Axis;
import dev.phantom.ac.geometry.Directions.Direction;

/**
 * Phase 4 collision, environment and fluid query tests.
 *
 * <p>These tests assert world facts, not movement behaviour. Nothing here states
 * a gravity constant, a step height, or a tolerance: the queries report which
 * geometry exists and whether that report is complete, and Phase 5 decides what
 * a player does with it.</p>
 */
class WorldQueryTest {

  private static BlockState decode(String id, Map<String, String> properties) {
    return dev.phantom.ac.world.v12111.BlockCatalogue12111.decode(id, properties);
  }

  private static BlockState stone() {
    return decode("minecraft:stone", Map.of());
  }

  private static WorldSnapshot.Builder world() {
    return WorldSnapshot.builder(Contracts.TARGET_VERSION);
  }

  /** A flat stone floor at y=0 covering x,z in -4..4, plus the chunk it lives in. */
  private static WorldSnapshot flatFloor() {
    var builder = world();
    for (int x = -4; x <= 4; x++) {
      for (int z = -4; z <= 4; z++) {
        builder.setBlock(x, 0, z, stone());
      }
    }
    return builder.build();
  }

  /** A player-sized box standing with its feet exactly on y=1, i.e. on top of a floor at y=0. */
  private static BlockBox standingOnFloor() {
    return BlockBox.of(0.2, 1.0, 0.2, 0.8, 2.8, 0.8);
  }

  // ------------------------------------------------------------------
  // Collision queries
  // ------------------------------------------------------------------

  @Test void collisionsInAirAreEmptyAndDefinite() {
    var result = WorldQueries.collisions(flatFloor(), BlockBox.of(0.2, 5, 0.2, 0.8, 6, 0.8));
    assertTrue(result.isEmpty());
    assertTrue(result.isDefinite());
    assertFalse(result.isUncertain());
  }

  @Test void collisionsFindAFullCubeTheQueryActuallyOverlaps() {
    var result = WorldQueries.collisions(flatFloor(), BlockBox.of(0.2, 0.2, 0.2, 0.8, 0.8, 0.8));
    assertTrue(result.collides());
    assertEquals(1, result.collisions().size());
    assertEquals(new Pos(0, 0, 0), result.collisions().getFirst().position());
    assertEquals(BlockBox.of(0, 0, 0, 1, 1, 1), result.collisions().getFirst().box());
  }

  @Test void aQueryTouchingACollisionFaceButNotOverlappingItReportsNoCollision() {
    // Feet exactly on y=1 over a floor at y=0: contact, not overlap.
    var result = WorldQueries.collisions(flatFloor(), standingOnFloor());
    assertTrue(result.isEmpty(), "standing on a floor is contact, not collision");
    assertEquals(Set.of(Coverage.KNOWN), result.coverage());
  }

  @Test void collisionsOverAnUnloadedRegionAreUncertainAndNeverTreatedAsAir() {
    var result = WorldQueries.collisions(WorldSnapshot.emptyOverworld12111(),
        BlockBox.of(0, 64, 0, 1, 65, 1));
    assertTrue(result.isEmpty());
    assertFalse(result.isDefinite());
    assertTrue(result.isUncertain());
    assertEquals(Set.of(Coverage.UNLOADED), result.coverage());
  }

  @Test void collisionsReportUnsupportedCoverageWhenAStateCannotBeVerified() {
    var snapshot = world().setUnsupportedBlock(0, 64, 0, "minecraft:future_block").build();
    var result = WorldQueries.collisions(snapshot, BlockBox.of(0, 64, 0, 0.5, 64.5, 0.5));
    assertTrue(result.isUncertain());
    assertTrue(result.coverage().contains(Coverage.UNSUPPORTED));
  }

  @Test void aRegressionLadderAndFenceBothProduceRealCollisionGeometry() {
    // Both were wrong in the earlier catalogue: the ladder was empty and the
    // fence was taller and wider than a fence post.
    var snapshot = world()
        .setBlock(0, 64, 0, decode("minecraft:ladder", Map.of("facing", "north")))
        .setBlock(2, 64, 0, decode("minecraft:oak_fence", Map.of()))
        .build();

    var ladder = WorldQueries.collisions(snapshot, BlockBox.of(0, 64, 0.95, 1, 65, 1.0));
    assertTrue(ladder.collides(), "a ladder must have a collision plate");
    assertEquals(BlockBox.of(0, 64, 15.0 / 16.0, 1, 65, 1.0), ladder.collisions().getFirst().box());

    var fence = WorldQueries.collisions(snapshot, BlockBox.of(2.4, 64, 0.4, 2.6, 65, 0.6));
    assertTrue(fence.collides(), "a fence post must collide");
    assertEquals(BlockBox.of(2 + 6.0 / 16.0, 64, 6.0 / 16.0, 2 + 10.0 / 16.0, 65, 10.0 / 16.0),
        fence.collisions().getFirst().box());
  }

  @Test void aSlabCollidesOnlyOverItsOwnHeight() {
    var snapshot = world()
        .setBlock(0, 64, 0, decode("minecraft:oak_slab", Map.of("type", "bottom")))
        .build();
    // Inside the slab's 8/16 height.
    assertTrue(WorldQueries.collisions(snapshot, BlockBox.of(0.2, 64.2, 0.2, 0.8, 64.4, 0.8)).collides());
    // Above the slab, still inside the same block cell.
    assertTrue(WorldQueries.collisions(snapshot, BlockBox.of(0.2, 64.6, 0.2, 0.8, 64.9, 0.8)).isEmpty());
  }

  @Test void aTopSlabCollidesOnlyOverItsUpperHeight() {
    var snapshot = world()
        .setBlock(0, 64, 0, decode("minecraft:oak_slab", Map.of("type", "top")))
        .build();
    assertTrue(WorldQueries.collisions(snapshot, BlockBox.of(0.2, 64.6, 0.2, 0.8, 64.9, 0.8)).collides());
    assertTrue(WorldQueries.collisions(snapshot, BlockBox.of(0.2, 64.1, 0.2, 0.8, 64.4, 0.8)).isEmpty());
  }

  @Test void aCarpetBarelyCollidesAndASnowLayerOneDoesToo() {
    var carpet = world().setBlock(0, 64, 0, decode("minecraft:white_carpet", Map.of())).build();
    assertTrue(WorldQueries.collisions(carpet, BlockBox.of(0.2, 64.0, 0.2, 0.8, 64.03, 0.8)).collides());
    assertTrue(WorldQueries.collisions(carpet, BlockBox.of(0.2, 64.1, 0.2, 0.8, 64.2, 0.8)).isEmpty());

    var snow = world().setBlock(0, 64, 0, decode("minecraft:snow", Map.of("layers", "1"))).build();
    assertTrue(WorldQueries.collisions(snow, BlockBox.of(0.2, 64.0, 0.2, 0.8, 64.1, 0.8)).collides());
    assertTrue(WorldQueries.collisions(snow, BlockBox.of(0.2, 64.2, 0.2, 0.8, 64.3, 0.8)).isEmpty());
  }

  @Test void multipleSimultaneousCollisionsAreAllReportedInCanonicalOrder() {
    var snapshot = world()
        .setBlock(0, 64, 0, stone())
        .setBlock(1, 64, 0, stone())
        .setBlock(0, 64, 1, stone())
        .build();
    var result = WorldQueries.collisions(snapshot, BlockBox.of(0.1, 64.1, 0.1, 1.9, 64.9, 1.9));
    assertEquals(3, result.collisions().size());
    assertEquals(List.of(new Pos(0, 64, 0), new Pos(0, 64, 1), new Pos(1, 64, 0)),
        result.collisions().stream().map(WorldQueries.Collision::position).toList());
  }

  @Test void collisionsAcrossAChunkBoundaryAreFoundOnBothSides() {
    var snapshot = world()
        .setBlock(15, 64, 0, stone())
        .setBlock(16, 64, 0, stone())
        .build();
    var result = WorldQueries.collisions(snapshot, BlockBox.of(15.5, 64.1, 0.1, 16.5, 64.9, 0.9));
    assertEquals(2, result.collisions().size());
    assertTrue(result.isDefinite());
  }

  @Test void collisionsAtNegativeCoordinatesAreFoundAtTheRightWorldPositions() {
    var snapshot = world()
        .setBlock(-1, 64, -1, stone())
        .setBlock(-2, 64, -1, stone())
        .build();
    var result = WorldQueries.collisions(snapshot, BlockBox.of(-2.5, 64.1, -1.5, -0.5, 64.9, -0.5));
    assertEquals(2, result.collisions().size());
    assertEquals(BlockBox.of(-2, 64, -1, -1, 65, 0), result.collisions().getFirst().box());
    assertEquals(BlockBox.of(-1, 64, -1, 0, 65, 0), result.collisions().getLast().box());
  }

  @Test void aMultiBoxShapeReportsEachBoxSeparately() {
    var snapshot = world()
        .setBlock(0, 64, 0, decode("minecraft:oak_stairs",
            Map.of("facing", "north", "half", "bottom", "shape", "straight")))
        .build();
    // A query covering the whole cell hits both the base step and the upper step.
    var result = WorldQueries.collisions(snapshot, BlockBox.of(0.1, 64.1, 0.1, 0.9, 64.9, 0.9));
    assertEquals(2, result.collisions().size());
    assertEquals(BlockBox.of(0, 64, 0, 1, 64.5, 1), result.collisions().getFirst().box());
    assertEquals(BlockBox.of(0, 64.5, 8.0 / 16.0, 1, 65, 1), result.collisions().getLast().box());
  }

  // ------------------------------------------------------------------
  // Clipping through the world
  // ------------------------------------------------------------------

  @Test void clipStopsAHorizontalMoveAtAWall() {
    var snapshot = world().setBlock(1, 0, 0, stone()).build();
    BlockBox box = BlockBox.of(0.2, 0, 0.2, 0.8, 1.8, 0.8);
    assertEquals(0.2, WorldQueries.clip(snapshot, Axis.X, box, 1.0), 1.0e-12);
  }

  @Test void clipDoesNotStopAVerticalFallAtTheFloorItStandsOn() {
    var snapshot = flatFloor();
    BlockBox box = BlockBox.of(0.2, 1.0, 0.2, 0.8, 2.8, 0.8);
    // The feet are already at the floor top, so a downward move of 0.5 is allowed
    // to be clipped exactly to zero: contact is not a collision.
    assertEquals(0.0, WorldQueries.clip(snapshot, Axis.Y, box, -0.5), 0.0);
  }

  @Test void clipAllowsAFallWhenThereIsNothingBelow() {
    var snapshot = world().loadChunk(0, 0).build();
    BlockBox box = BlockBox.of(0.2, 64, 0.2, 0.8, 65.8, 0.8);
    assertEquals(-0.5, WorldQueries.clip(snapshot, Axis.Y, box, -0.5), 0.0);
  }

  @Test void clipTreatsAnUnloadedRegionAsNoObstacleButTheQueryStaysUncertain() {
    var result = WorldQueries.pathCollision(WorldSnapshot.emptyOverworld12111(),
        BlockBox.of(0, 64, 0, 0.6, 65.8, 0.6), 1.0, 0.0, 0.0);
    assertEquals(1.0, result.clippedX());
    assertTrue(result.coverage().contains(Coverage.UNLOADED));
  }

  @Test void clipAtNegativeCoordinatesUsesFloorDivisionCorrectly() {
    var snapshot = world().setBlock(-1, 0, -1, stone()).build();
    BlockBox box = BlockBox.of(-1.8, 0, -0.8, -1.2, 1.8, -0.2);
    assertEquals(0.2, WorldQueries.clip(snapshot, Axis.X, box, 1.0), 1.0e-12);
  }

  @Test void pathCollisionReportsBothGeometryAndClippedDisplacement() {
    var snapshot = world().setBlock(1, 0, 0, stone()).build();
    var path = WorldQueries.pathCollision(snapshot, BlockBox.of(0.2, 0, 0.2, 0.8, 1.8, 0.8), 1.0, 0.0, 0.0);
    assertTrue(path.collided());
    assertEquals(0.2, path.clippedX(), 1.0e-12);
    assertEquals(0.0, path.clippedY(), 0.0);
    assertEquals(0.0, path.clippedZ(), 0.0);
    assertFalse(path.collisions().isEmpty());
  }

  @Test void pathCollisionAlongAChunkBoundaryFindsGeometryInBothChunks() {
    var snapshot = world().setBlock(0, 64, 0, stone()).setBlock(1, 64, 0, stone()).build();
    var path = WorldQueries.pathCollision(snapshot, BlockBox.of(-0.5, 64.1, 0.2, 0.1, 64.9, 0.8), 2.0, 0.0, 0.0);
    assertEquals(2, path.collisions().size());
    assertTrue(path.collided());
  }

  // ------------------------------------------------------------------
  // Directional probes
  // ------------------------------------------------------------------

  @Test void besideFindsAWallAndNothingWhenTheDirectionIsEmpty() {
    var snapshot = world().setBlock(1, 0, 0, stone()).build();
    BlockBox box = BlockBox.of(0.2, 0, 0.2, 0.8, 1.8, 0.8);
    assertTrue(WorldQueries.beside(snapshot, box, Direction.EAST).collides());
    assertTrue(WorldQueries.beside(snapshot, box, Direction.WEST).isEmpty());
    assertTrue(WorldQueries.beside(snapshot, box, Direction.NORTH).isEmpty());
  }

  @Test void besideRejectsVerticalDirections() {
    BlockBox box = BlockBox.of(0.2, 0, 0.2, 0.8, 1.8, 0.8);
    assertThrows(IllegalArgumentException.class, () -> WorldQueries.beside(flatFloor(), box, Direction.UP));
    assertThrows(IllegalArgumentException.class, () -> WorldQueries.beside(flatFloor(), box, Direction.DOWN));
  }

  @Test void besideReportsUnloadedSpaceOnTheUnloadedSideOfAChunkBoundary() {
    // Only chunk (0,0) is loaded. Probes that cross its outer boundary are
    // uncertain; a probe between cells in the loaded chunk is definite.
    var snapshot = world().setBlock(1, 64, 1, stone()).build();
    BlockBox inside = BlockBox.of(15.2, 64, 15.2, 15.8, 64.8, 15.8);
    assertTrue(WorldQueries.beside(snapshot, inside, Direction.EAST).isUncertain());
    assertFalse(WorldQueries.beside(snapshot, inside, Direction.WEST).isUncertain());
    BlockBox interior = BlockBox.of(1.2, 64, 1.2, 1.8, 64.8, 1.8);
    assertFalse(WorldQueries.beside(snapshot, interior, Direction.WEST).isUncertain());

    // An empty snapshot has no data on any side at all.
    assertTrue(WorldQueries.beside(WorldSnapshot.emptyOverworld12111(), inside, Direction.EAST).isUncertain());
    assertTrue(WorldQueries.beside(WorldSnapshot.emptyOverworld12111(), inside, Direction.WEST).isUncertain());
  }

  @Test void aboveReportsTheCellDirectlyAboveTheBox() {
    // The box's top is at y=1.8, so the cell directly above is y=1..2.
    var adjacentCeiling = world().setBlock(0, 1, 0, stone()).build();
    var above = WorldQueries.above(adjacentCeiling, BlockBox.of(0.2, 0, 0.2, 0.8, 1.8, 0.8));
    assertTrue(above.collides());
    assertEquals(1.0, above.collisions().getFirst().box().minY());

    // A ceiling four blocks up is not in the adjacent cell, so `above` misses it
    // while `ceilings` finds it. That separation is deliberate: `above` answers
    // "what would I hit immediately", `ceilings` answers "what is overhead".
    var distantCeiling = world().setBlock(0, 4, 0, stone()).build();
    var box = BlockBox.of(0.2, 0, 0.2, 0.8, 1.8, 0.8);
    assertTrue(WorldQueries.above(distantCeiling, box).isEmpty());
    assertEquals(4.0, WorldQueries.ceilings(distantCeiling, box, 5.0).getFirst().topY());
  }

  @Test void aboveReportsNothingWhenTheSpaceIsClear() {
    var snapshot = world().loadChunk(0, 0).build();
    assertTrue(WorldQueries.above(snapshot, BlockBox.of(0.2, 64, 0.2, 0.8, 65.8, 0.8)).isEmpty());
  }

  // ------------------------------------------------------------------
  // Floor and ceiling detection
  // ------------------------------------------------------------------

  @Test void floorFindsTheHighestSupportSurfaceBeneathTheBox() {
    var snapshot = world()
        .setBlock(0, 0, 0, stone())
        .setBlock(0, 1, 0, stone())
        .build();
    var floor = WorldQueries.floor(snapshot, BlockBox.of(0.2, 2.0, 0.2, 0.8, 3.8, 0.8), 4.0).orElseThrow();
    assertEquals(2.0, floor.topY());
    assertEquals(new Pos(0, 1, 0), floor.position());
  }

  @Test void floorsAreReturnedHighestFirst() {
    var snapshot = world()
        .setBlock(0, 0, 0, stone())
        .setBlock(0, 1, 0, stone())
        .setBlock(0, 2, 0, stone())
        .build();
    var floors = WorldQueries.floors(snapshot, BlockBox.of(0.2, 3.0, 0.2, 0.8, 4.8, 0.8), 5.0);
    assertEquals(List.of(3.0, 2.0, 1.0), floors.stream().map(WorldQueries.Floor::topY).toList());
  }

  @Test void floorIgnoresCollisionSurfacesThatDoNotOverlapTheFootprint() {
    var snapshot = world().setBlock(5, 0, 5, stone()).build();
    assertTrue(WorldQueries.floor(snapshot, BlockBox.of(0.2, 2.0, 0.2, 0.8, 3.8, 0.8), 4.0).isEmpty());
  }

  @Test void floorOfASlabReportsTheSlabTopNotTheBlockTop() {
    var snapshot = world().setBlock(0, 0, 0, decode("minecraft:oak_slab", Map.of("type", "bottom"))).build();
    var floor = WorldQueries.floor(snapshot, BlockBox.of(0.2, 2.0, 0.2, 0.8, 3.8, 0.8), 4.0).orElseThrow();
    assertEquals(0.5, floor.topY());
  }

  @Test void floorOfASnowLayerReportsItsLayerHeight() {
    var snapshot = world().setBlock(0, 0, 0, decode("minecraft:snow", Map.of("layers", "4"))).build();
    var floor = WorldQueries.floor(snapshot, BlockBox.of(0.2, 2.0, 0.2, 0.8, 3.8, 0.8), 4.0).orElseThrow();
    assertEquals(0.5, floor.topY());
  }

  @Test void floorRespectsTheSearchDepth() {
    var snapshot = world().setBlock(0, 0, 0, stone()).build();
    assertTrue(WorldQueries.floor(snapshot, BlockBox.of(0.2, 10.0, 0.2, 0.8, 11.8, 0.8), 4.0).isEmpty());
    assertTrue(WorldQueries.floor(snapshot, BlockBox.of(0.2, 10.0, 0.2, 0.8, 11.8, 0.8), 12.0).isPresent());
  }

  @Test void floorRejectsANegativeSearchDepth() {
    assertThrows(IllegalArgumentException.class,
        () -> WorldQueries.floor(flatFloor(), standingOnFloor(), -1.0));
  }

  @Test void intersectingFloorsReportsBlocksTheBoxIsAlreadyInside() {
    var snapshot = world().setBlock(0, 64, 0, stone()).build();
    var inside = WorldQueries.intersectingFloors(snapshot, BlockBox.of(0.2, 64.2, 0.2, 0.8, 64.8, 0.8));
    assertEquals(1, inside.size());
    assertEquals(new Pos(0, 64, 0), inside.getFirst().position());
  }

  @Test void ceilingsAreReturnedLowestFirstAndRespectTheirSearchHeight() {
    var snapshot = world()
        .setBlock(0, 4, 0, stone())
        .setBlock(0, 6, 0, stone())
        .build();
    var box = BlockBox.of(0.2, 0, 0.2, 0.8, 1.8, 0.8);
    assertEquals(List.of(4.0, 6.0),
        WorldQueries.ceilings(snapshot, box, 7.0).stream().map(WorldQueries.Floor::topY).toList());
    assertEquals(List.of(4.0),
        WorldQueries.ceilings(snapshot, box, 3.0).stream().map(WorldQueries.Floor::topY).toList());
  }

  @Test void ceilingRejectsANegativeSearchHeight() {
    assertThrows(IllegalArgumentException.class,
        () -> WorldQueries.ceilings(flatFloor(), standingOnFloor(), -0.5));
  }

  @Test void floorAndCeilingAreEmptyOverUnloadedSpace() {
    var empty = WorldSnapshot.emptyOverworld12111();
    assertTrue(WorldQueries.floor(empty, BlockBox.of(0.2, 64, 0.2, 0.8, 65.8, 0.8), 5.0).isEmpty());
    assertTrue(WorldQueries.ceilings(empty, BlockBox.of(0.2, 64, 0.2, 0.8, 65.8, 0.8), 5.0).isEmpty());
  }

  // ------------------------------------------------------------------
  // Block samples
  // ------------------------------------------------------------------

  @Test void blocksInReturnsStatesInCanonicalOrderAndReportsCoverage() {
    var snapshot = world()
        .setBlock(0, 64, 0, stone())
        .setBlock(0, 65, 0, decode("minecraft:oak_slab", Map.of("type", "bottom")))
        .build();
    var sample = WorldQueries.blocksIn(snapshot, BlockBox.of(0.2, 64.2, 0.2, 0.8, 65.8, 0.8));
    assertEquals(List.of(new Pos(0, 64, 0), new Pos(0, 65, 0)), sample.positions());
    assertEquals(2, sample.states().size());
    assertTrue(sample.isDefinite());
  }

  @Test void blocksInOmitsPositionsWithMissingDataInsteadOfSubstitutingAir() {
    var snapshot = world().setBlock(0, 64, 0, stone()).build();
    // A box reaching past chunk (0,0) touches never-delivered chunks, so the
    // sample is neither definite nor allowed to invent air for those cells.
    var sample = WorldQueries.blocksIn(snapshot, BlockBox.of(0.2, 64.2, 0.2, 40, 64.8, 40));
    assertFalse(sample.isDefinite());
    assertTrue(sample.coverage().contains(Coverage.UNLOADED));
    assertTrue(sample.positions().contains(new Pos(0, 64, 0)));
    assertTrue(sample.states().contains(stone()));

    // Confined to the loaded chunk, every covered cell is known, including air;
    // only the explicitly stored stone appears in the state list.
    var insideLoadedChunk = WorldQueries.blocksIn(snapshot, BlockBox.of(0.2, 64.2, 0.2, 4.0, 64.8, 4.0));
    assertTrue(insideLoadedChunk.isDefinite());
    assertEquals(25, insideLoadedChunk.positions().size());
    assertEquals(25, insideLoadedChunk.states().size());
    assertTrue(insideLoadedChunk.states().contains(stone()));
  }

  // ------------------------------------------------------------------
  // Environment
  // ------------------------------------------------------------------

  @Test void dryAirReportsNoEnvironmentFacts() {
    var env = WorldQueries.environment(world().loadChunk(0, 0).build(),
        BlockBox.of(0.2, 64, 0.2, 0.8, 65.8, 0.8));
    assertFalse(env.water());
    assertFalse(env.lava());
    assertFalse(env.inFluid());
    assertFalse(env.climbable());
    assertTrue(env.isDefinite());
    assertTrue(env.cells().isEmpty());
  }

  @Test void aLadderInsideTheBoxIsReportedAsClimbable() {
    var snapshot = world()
        .setBlock(0, 64, 0, decode("minecraft:ladder", Map.of("facing", "north")))
        .setBlock(0, 65, 0, decode("minecraft:ladder", Map.of("facing", "north")))
        .build();
    var env = WorldQueries.environment(snapshot, BlockBox.of(0.2, 64, 0.85, 0.8, 65.8, 0.99));
    assertTrue(env.climbable());
    assertFalse(env.water());
  }

  @Test void waterInsideTheBoxIsReportedAsFluid() {
    var snapshot = world()
        .setBlock(0, 64, 0, decode("minecraft:water", Map.of("level", "0")))
        .build();
    var env = WorldQueries.environment(snapshot, BlockBox.of(0.2, 64.2, 0.2, 0.8, 64.8, 0.8));
    assertTrue(env.water());
    assertTrue(env.inFluid());
    assertFalse(env.lava());
  }

  @Test void lavaInsideTheBoxIsReportedAsLavaNotWater() {
    var snapshot = world().setBlock(0, 64, 0, decode("minecraft:lava", Map.of("level", "0"))).build();
    var env = WorldQueries.environment(snapshot, BlockBox.of(0.2, 64.2, 0.2, 0.8, 64.8, 0.8));
    assertTrue(env.lava());
    assertFalse(env.water());
  }

  @Test void environmentReportsEachMovementRelevantBlockKind() {
    record Case(String block, Map<String, String> properties, java.util.function.Predicate<WorldQueries.EnvironmentSample> check) {}
    List<Case> cases = List.of(
        new Case("minecraft:slime_block", Map.of(), WorldQueries.EnvironmentSample::slime),
        new Case("minecraft:honey_block", Map.of(), WorldQueries.EnvironmentSample::honey),
        new Case("minecraft:soul_sand", Map.of(), WorldQueries.EnvironmentSample::soulSand),
        new Case("minecraft:soul_soil", Map.of(), WorldQueries.EnvironmentSample::soulSand),
        new Case("minecraft:cobweb", Map.of(), WorldQueries.EnvironmentSample::cobweb),
        new Case("minecraft:powder_snow", Map.of(), WorldQueries.EnvironmentSample::powderSnow),
        new Case("minecraft:scaffolding", Map.of("bottom", "false"), WorldQueries.EnvironmentSample::scaffolding),
        new Case("minecraft:sweet_berry_bush", Map.of("age", "0"), WorldQueries.EnvironmentSample::sweetBerryBush),
        new Case("minecraft:cactus", Map.of(), WorldQueries.EnvironmentSample::cactus),
        new Case("minecraft:ice", Map.of(), WorldQueries.EnvironmentSample::ice),
        new Case("minecraft:packed_ice", Map.of(), WorldQueries.EnvironmentSample::packedIce),
        new Case("minecraft:blue_ice", Map.of(), WorldQueries.EnvironmentSample::blueIce),
        new Case("minecraft:magma_block", Map.of(), WorldQueries.EnvironmentSample::magmaBlock),
        new Case("minecraft:lily_pad", Map.of(), WorldQueries.EnvironmentSample::lilyPad),
        new Case("minecraft:bubble_column", Map.of("drag", "false"), WorldQueries.EnvironmentSample::bubbleColumn));
    for (Case testCase : cases) {
      var snapshot = world().setBlock(0, 64, 0, decode(testCase.block(), testCase.properties())).build();
      var env = WorldQueries.environment(snapshot, BlockBox.of(0.2, 64.2, 0.2, 0.8, 64.8, 0.8));
      assertTrue(testCase.check().test(env), testCase.block());
    }
  }

  @Test void iceSlipperinessValuesMatchTheTargetVersion() {
    assertEquals(0.98, WorldQueries.environment(world().setBlock(0, 64, 0,
        decode("minecraft:ice", Map.of())).build(),
        BlockBox.of(0.2, 64.2, 0.2, 0.8, 64.8, 0.8)).cells().getFirst().slipperiness(), 0.0);
    assertEquals(0.989, dev.phantom.ac.world.v12111.BlockCatalogue12111.slipperiness(
        decode("minecraft:blue_ice", Map.of())), 0.0);
    assertEquals(0.6, dev.phantom.ac.world.v12111.BlockCatalogue12111.slipperiness(stone()), 0.0);
  }

  @Test void environmentOverUnloadedSpaceIsNotDefiniteSoCallersCannotTrustIt() {
    var env = WorldQueries.environment(WorldSnapshot.emptyOverworld12111(),
        BlockBox.of(0, 64, 0, 1, 65, 1));
    assertFalse(env.isDefinite());
    assertFalse(env.inFluid());
    assertTrue(env.coverage().contains(Coverage.UNLOADED));
  }

  @Test void aWaterloggedBlockCarriesWaterEvenThoughItIsNotAWaterBlock() {
    var snapshot = world().setBlock(0, 64, 0,
        decode("minecraft:oak_slab", Map.of("type", "bottom", "waterlogged", "true"))).build();
    var env = WorldQueries.environment(snapshot, BlockBox.of(0.2, 64.2, 0.2, 0.8, 64.4, 0.8));
    assertTrue(env.water());
    // A waterlogged slab is still solid for collision.
    assertTrue(WorldQueries.collisions(snapshot, BlockBox.of(0.2, 64.2, 0.2, 0.8, 64.4, 0.8)).collides());
  }

  // ------------------------------------------------------------------
  // Fluids
  // ------------------------------------------------------------------

  @Test void aFluidBlockWithAllNeighboursVisibleDerivesItsHeight() {
    var builder = world();
    for (int x = -1; x <= 1; x++) {
      for (int z = -1; z <= 1; z++) {
        builder.setBlock(x, 64, z, decode("minecraft:water", Map.of("level", "0")));
      }
    }
    var fluid = WorldQueries.fluidAt(builder.build(), 0, 64, 0);
    assertEquals(FluidState.Type.WATER, fluid.type());
    assertEquals(FluidState.HeightSource.SOURCE, fluid.heightSource());
    assertEquals(1.0, fluid.height(), 0.0);
  }

  @Test void aFlowingFluidHeightUsesTheVanillaEighthsOverNineRule() {
    assertEquals(8.0 / 9.0, dev.phantom.ac.world.v12111.BlockCatalogue12111.flowingHeight(0, 0), 0.0);
    assertEquals(7.0 / 9.0, dev.phantom.ac.world.v12111.BlockCatalogue12111.flowingHeight(1, 0), 0.0);
    assertEquals(1.0 / 9.0, dev.phantom.ac.world.v12111.BlockCatalogue12111.flowingHeight(7, 0), 0.0);
    assertEquals(1.0, dev.phantom.ac.world.v12111.BlockCatalogue12111.flowingHeight(0, 4), 0.0);
  }

  @Test void flowingHeightRejectsOutOfRangeInput() {
    assertThrows(IllegalArgumentException.class,
        () -> dev.phantom.ac.world.v12111.BlockCatalogue12111.flowingHeight(-1, 0));
    assertThrows(IllegalArgumentException.class,
        () -> dev.phantom.ac.world.v12111.BlockCatalogue12111.flowingHeight(8, 0));
    assertThrows(IllegalArgumentException.class,
        () -> dev.phantom.ac.world.v12111.BlockCatalogue12111.flowingHeight(0, 5));
  }

  @Test void aFluidHeightIsUnavailableNotGuessedWhenANeighbourIsNotVisible() {
    // Only the centre block is loaded, so the four neighbours are unknowable.
    var snapshot = world().setBlock(0, 64, 0, decode("minecraft:water", Map.of("level", "0"))).build();
    var fluid = WorldQueries.fluidAt(snapshot, 0, 64, 0);
    assertEquals(FluidState.HeightSource.UNAVAILABLE, fluid.heightSource());
    assertFalse(fluid.hasKnownHeight());
    assertTrue(Double.isNaN(fluid.height()));
  }

  @Test void aFluidStateWithAnUnknownHeightAlwaysUsesNaN() {
    assertThrows(IllegalArgumentException.class, () -> new FluidState(FluidState.Type.WATER, 0,
        FluidState.HeightSource.UNAVAILABLE, 0.5, false));
    assertThrows(IllegalArgumentException.class, () -> new FluidState(FluidState.Type.WATER, 0,
        FluidState.HeightSource.DERIVED, FluidState.HEIGHT_UNKNOWN, false));
    assertThrows(IllegalArgumentException.class, () -> new FluidState(FluidState.Type.WATER, 8,
        FluidState.HeightSource.SOURCE, 1.0, false));
  }

  @Test void nonFluidBlocksReportNoFluid() {
    var snapshot = world().setBlock(0, 64, 0, stone()).build();
    var fluid = WorldQueries.fluidAt(snapshot, 0, 64, 0);
    assertEquals(FluidState.Type.NONE, fluid.type());
    assertFalse(fluid.isFluid());
    assertFalse(fluid.hasKnownHeight());
  }

  @Test void unloadedPositionsReportNoFluidRatherThanDryness() {
    assertFalse(WorldQueries.fluidAt(WorldSnapshot.emptyOverworld12111(), 0, 64, 0).isFluid());
  }

  @Test void fluidPositionsListsEveryFluidBlockInARegionInCanonicalOrder() {
    var builder = world();
    for (int x = 0; x <= 1; x++) {
      builder.setBlock(x, 64, 0, decode("minecraft:water", Map.of("level", "0")));
    }
    builder.setBlock(2, 64, 0, stone());
    var positions = WorldQueries.fluidPositions(builder.build(), BlockBox.of(0, 64, 0, 3, 65, 1));
    assertEquals(List.of(new Pos(0, 64, 0), new Pos(1, 64, 0)), positions);
  }

  @Test void aWaterloggedBlockExposesAFullSourceHeightSoItsFluidIsNotEmpty() {
    var snapshot = world().setBlock(0, 64, 0,
        decode("minecraft:oak_stairs", Map.of("facing", "north", "half", "bottom", "shape", "straight", "waterlogged", "true"))).build();
    var fluid = WorldQueries.fluidAt(snapshot, 0, 64, 0);
    assertEquals(FluidState.Type.WATER, fluid.type());
    assertEquals(FluidState.HeightSource.SOURCE, fluid.heightSource());
    assertEquals(1.0, fluid.height(), 0.0);
  }

  @Test void fluidQueriesAreDeterministicAcrossRepeatedCalls() {
    var snapshot = world().setBlock(0, 64, 0, decode("minecraft:water", Map.of("level", "3"))).build();
    var first = WorldQueries.fluidAt(snapshot, 0, 64, 0);
    for (int repeat = 0; repeat < 20; repeat++) {
      assertEquals(first, WorldQueries.fluidAt(snapshot, 0, 64, 0));
    }
  }

  // ------------------------------------------------------------------
  // Entity collision architecture
  // ------------------------------------------------------------------

  @Test void theDefaultEntityProviderReportsNoEntitiesAndSaysSo() {
    var result = EntityCollisions.NONE_TRACKED.boxesIn(BlockBox.of(0, 0, 0, 1, 1, 1));
    assertTrue(result.isEmpty());
    assertFalse(result.isDefinite(), "an untracked provider must not claim completeness");
  }

  @Test void aRecordedEntityProviderFindsOverlappingBoxesInIdOrder() {
    var provider = EntityCollisions.of(List.of(
        new EntityCollisions.EntityBox(7, BlockBox.of(5, 0, 0, 6, 2, 1)),
        new EntityCollisions.EntityBox(2, BlockBox.of(0.2, 0, 0.2, 0.8, 2, 0.8)),
        new EntityCollisions.EntityBox(4, BlockBox.of(0.3, 0, 0.3, 0.9, 2, 0.9))));
    var result = provider.boxesIn(BlockBox.of(0, 0, 0, 1, 2, 1));
    assertEquals(List.of(2, 4), result.boxes().stream().map(EntityCollisions.EntityBox::entityId).toList());
    assertTrue(result.isDefinite());
  }

  @Test void theRecordedEntityProviderIsDeterministicRegardlessOfInputOrder() {
    var a = EntityCollisions.of(List.of(
        new EntityCollisions.EntityBox(3, BlockBox.of(0, 0, 0, 1, 1, 1)),
        new EntityCollisions.EntityBox(1, BlockBox.of(0, 0, 0, 1, 1, 1))));
    var b = EntityCollisions.of(List.of(
        new EntityCollisions.EntityBox(1, BlockBox.of(0, 0, 0, 1, 1, 1)),
        new EntityCollisions.EntityBox(3, BlockBox.of(0, 0, 0, 1, 1, 1))));
    assertEquals(a.boxesIn(BlockBox.of(0, 0, 0, 1, 1, 1)).boxes(),
        b.boxesIn(BlockBox.of(0, 0, 0, 1, 1, 1)).boxes());
  }

  @Test void entityBoxesThatMerelyTouchTheQueryAreExcludedLikeBlocks() {
    var provider = EntityCollisions.of(List.of(
        new EntityCollisions.EntityBox(1, BlockBox.of(1, 0, 0, 2, 1, 1))));
    assertTrue(provider.boxesIn(BlockBox.of(0, 0, 0, 1, 1, 1)).isEmpty());
  }

  // ------------------------------------------------------------------
  // WorldView
  // ------------------------------------------------------------------

  @Test void worldViewDelegatesEveryQueryToTheSnapshotItWraps() {
    var snapshot = flatFloor();
    var view = WorldView.of(snapshot);
    assertSame(snapshot, view.snapshot());
    assertEquals(snapshot.coverageAt(0, 0, 0), view.coverageAt(0, 0, 0));
    assertEquals(snapshot.blockAtOrNull(0, 0, 0), view.blockAtOrNull(0, 0, 0));
    assertEquals(stone(), view.requireBlockAt(0, 0, 0));
    assertTrue(view.fullyKnown(BlockBox.of(0, 0, 0, 1, 1, 1)));
    assertTrue(view.collisions(BlockBox.of(0.2, 0.2, 0.2, 0.8, 0.8, 0.8)).collides());
    assertTrue(view.fluidAt(0, 0, 0).isFluid() == false);
    assertTrue(view.floors(standingOnFloor(), 4.0).size() == 1);
  }

  @Test void worldViewEqualityDependsOnSnapshotAndEntityProvider() {
    var snapshot = flatFloor();
    assertEquals(WorldView.of(snapshot), WorldView.of(snapshot));
    assertEquals(WorldView.of(snapshot).hashCode(), WorldView.of(snapshot).hashCode());
    assertEquals(WorldView.of(snapshot, EntityCollisions.NONE_TRACKED), WorldView.of(snapshot));
    assertFalse(WorldView.of(snapshot).equals(WorldView.of(WorldSnapshot.emptyOverworld12111())));
  }

  @Test void worldViewRepeatedQueriesAreIdentical() {
    var view = WorldView.of(flatFloor());
    BlockBox query = BlockBox.of(0.2, 0.2, 0.2, 0.8, 0.8, 0.8);
    var first = view.collisions(query);
    for (int repeat = 0; repeat < 15; repeat++) {
      assertEquals(first, view.collisions(query));
    }
  }
}
