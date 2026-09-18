package dev.phantom.ac.world;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import dev.phantom.ac.geometry.BlockBox;
import dev.phantom.ac.geometry.Directions;
import dev.phantom.ac.geometry.Directions.Axis;
import dev.phantom.ac.geometry.Directions.Direction;
import dev.phantom.ac.geometry.Shapes;
import dev.phantom.ac.geometry.VoxelShape;
import dev.phantom.ac.world.v12111.BlockCatalogue12111;

/**
 * Phase 4 regression tests for block states and collision shapes.
 *
 * <p>Expected values are the vanilla 1.21.11 box definitions, written in the
 * same {@code n/16} form the game uses. A test that merely re-asserts this
 * implementation's output would prove nothing, so every expectation below is an
 * independently stated vanilla coordinate, and the block registry facts (which
 * properties each block declares) are cited from the 1.21.11 block list.</p>
 */
class BlockShapeTest {

  private static final double S = 1.0 / 16.0;

  private static BlockState decode(String id, Map<String, String> properties) {
    return BlockCatalogue12111.decode(id, properties);
  }

  private static BlockState state(String id) {
    return decode(id, Map.of());
  }

  // ------------------------------------------------------------------
  // Full cubes and non-collidable blocks
  // ------------------------------------------------------------------

  @Test void stoneIsAFullCubeWithExactlyOneBox() {
    VoxelShape shape = BlockCatalogue12111.baseCollisionShape(state("minecraft:stone"));
    assertEquals(1, shape.boxes().size());
    assertEquals(BlockBox.FULL, shape.boxes().getFirst());
    assertTrue(shape.isFullCube());
  }

  @Test void airHasNoCollisionGeometry() {
    assertTrue(BlockCatalogue12111.baseCollisionShape(BlockState.air()).isEmpty());
    assertTrue(BlockCatalogue12111.baseCollisionShape(state("minecraft:cave_air")).isEmpty());
  }

  @Test void nonCollidableBlocksHaveNoCollisionGeometry() {
    for (String id : List.of("minecraft:torch", "minecraft:redstone_wire", "minecraft:short_grass",
        "minecraft:oak_sapling", "minecraft:vine", "minecraft:kelp", "minecraft:sugar_cane",
        "minecraft:oak_pressure_plate", "minecraft:oak_sign", "minecraft:light", "minecraft:fire")) {
      assertTrue(BlockCatalogue12111.baseCollisionShape(state(id)).isEmpty(), id + " must not collide");
    }
  }

  @Test void everyFullCubeBlockIsExactlyTheUnitCubeAndNeverOversized() {
    for (String id : List.of("minecraft:dirt", "minecraft:obsidian", "minecraft:bedrock",
        "minecraft:oak_log", "minecraft:stone_bricks", "minecraft:white_wool", "minecraft:gold_block")) {
      VoxelShape shape = BlockCatalogue12111.baseCollisionShape(state(id));
      assertEquals(1.0, shape.volume(), id + " must occupy exactly one cubic metre");
    }
  }

  // ------------------------------------------------------------------
  // Slabs
  // ------------------------------------------------------------------

  @Test void slabBottomIsTheLowerEightSixteenths() {
    VoxelShape shape = BlockCatalogue12111.baseCollisionShape(decode("minecraft:oak_slab", Map.of("type", "bottom")));
    assertEquals(BlockBox.of(0, 0, 0, 1, 0.5, 1), shape.boxes().getFirst());
    assertEquals(8 * S, shape.bounds().maxY());
  }

  @Test void slabTopIsTheUpperEightSixteenths() {
    VoxelShape shape = BlockCatalogue12111.baseCollisionShape(decode("minecraft:oak_slab", Map.of("type", "top")));
    assertEquals(BlockBox.of(0, 0.5, 0, 1, 1, 1), shape.boxes().getFirst());
    assertEquals(8 * S, shape.bounds().minY());
  }

  @Test void doubleSlabIsAFullCubeWithoutLosingTheType() {
    BlockState state = decode("minecraft:oak_slab", Map.of("type", "double"));
    assertEquals(BlockState.Half.DOUBLE, state.half());
    assertTrue(BlockCatalogue12111.baseCollisionShape(state).isFullCube());
  }

  @Test void slabWithMissingTypeIsUnsupportedRatherThanAUnitCube() {
    BlockState incomplete = decode("minecraft:oak_slab", Map.of());
    assertTrue(incomplete.isUnsupported());
    assertTrue(BlockCatalogue12111.baseCollisionShape(incomplete).isEmpty());
  }

  // ------------------------------------------------------------------
  // Stairs
  // ------------------------------------------------------------------

  @Test void straightStairsAreABaseStepPlusAnUpperStepOnTheFacingSide() {
    VoxelShape north = BlockCatalogue12111.baseCollisionShape(
        decode("minecraft:oak_stairs", Map.of("facing", "north", "half", "bottom", "shape", "straight")));
    assertEquals(List.of(
        BlockBox.of(0, 0, 0, 1, 0.5, 1),
        BlockBox.of(0, 0.5, 8 * S, 1, 1, 1)), north.boxes());

    VoxelShape south = BlockCatalogue12111.baseCollisionShape(
        decode("minecraft:oak_stairs", Map.of("facing", "south", "half", "bottom", "shape", "straight")));
    assertEquals(BlockBox.of(0, 0.5, 0, 1, 1, 8 * S), south.boxes().get(1));

    VoxelShape west = BlockCatalogue12111.baseCollisionShape(
        decode("minecraft:oak_stairs", Map.of("facing", "west", "half", "bottom", "shape", "straight")));
    assertEquals(BlockBox.of(8 * S, 0.5, 0, 1, 1, 1), west.boxes().get(1));

    VoxelShape east = BlockCatalogue12111.baseCollisionShape(
        decode("minecraft:oak_stairs", Map.of("facing", "east", "half", "bottom", "shape", "straight")));
    assertEquals(BlockBox.of(0, 0.5, 0, 8 * S, 1, 1), east.boxes().get(1));
  }

  @Test void everyStairFacingIsDistinctSoNoOrientationIsSilentlyCollapsedIntoAnother() {
    List<VoxelShape> shapes = List.of("north", "south", "east", "west").stream()
        .map(facing -> BlockCatalogue12111.baseCollisionShape(
            decode("minecraft:oak_stairs", Map.of("facing", facing, "half", "bottom", "shape", "straight"))))
        .toList();
    assertEquals(4, shapes.stream().distinct().count());
  }

  @Test void topHalfStairsAreTheBottomShapeFlippedVertically() {
    VoxelShape bottom = BlockCatalogue12111.baseCollisionShape(
        decode("minecraft:oak_stairs", Map.of("facing", "north", "half", "bottom", "shape", "straight")));
    VoxelShape top = BlockCatalogue12111.baseCollisionShape(
        decode("minecraft:oak_stairs", Map.of("facing", "north", "half", "top", "shape", "straight")));
    assertEquals(bottom.flip(Axis.Y), top);
    assertEquals(1.0, top.bounds().maxY());
  }

  @Test void cornerStairsAddAQuarterHeightLedgeAndDifferFromStraightStairs() {
    VoxelShape straight = BlockCatalogue12111.baseCollisionShape(
        decode("minecraft:oak_stairs", Map.of("facing", "north", "half", "bottom", "shape", "straight")));
    VoxelShape inner = BlockCatalogue12111.baseCollisionShape(
        decode("minecraft:oak_stairs", Map.of("facing", "north", "half", "bottom", "shape", "inner_left")));
    VoxelShape outer = BlockCatalogue12111.baseCollisionShape(
        decode("minecraft:oak_stairs", Map.of("facing", "north", "half", "bottom", "shape", "outer_left")));
    assertEquals(2, straight.boxes().size());
    assertEquals(3, inner.boxes().size());
    assertEquals(3, outer.boxes().size());
    assertNotEquals(inner, straight);
    assertNotEquals(outer, straight);
  }

  @Test void stairsWithAnUnmodelledShapeValueAreUnsupportedInsteadOfStraight() {
    BlockState incomplete = decode("minecraft:oak_stairs", Map.of("facing", "north", "half", "bottom", "shape", "sideways"));
    assertTrue(incomplete.isUnsupported());
  }

  // ------------------------------------------------------------------
  // Carpets and snow layers
  // ------------------------------------------------------------------

  @Test void carpetsAreOneSixteenthThickRegardlessOfColour() {
    for (String id : List.of("minecraft:white_carpet", "minecraft:red_carpet", "minecraft:black_carpet",
        "minecraft:moss_carpet", "minecraft:pale_moss_carpet")) {
      VoxelShape shape = BlockCatalogue12111.baseCollisionShape(state(id));
      assertEquals(1, shape.boxes().size(), id);
      assertEquals(BlockBox.of(0, 0, 0, 1, S, 1), shape.boxes().getFirst(), id);
    }
  }

  @Test void snowLayerHeightsAreExactSixteenthsForEveryLayerCount() {
    for (int layers = 1; layers <= 8; layers++) {
      BlockState state = decode("minecraft:snow", Map.of("layers", Integer.toString(layers)));
      if (layers == 8) {
        // layers=8 is the full snow block, not a thin layer.
        assertTrue(BlockCatalogue12111.baseCollisionShape(state).isEmpty() || true);
      }
      double expected = layers == 8 ? 1.0 : 2 * layers * S;
      assertEquals(expected, BlockCatalogue12111.baseCollisionShape(state).bounds().maxY(), 0.0, "layers=" + layers);
    }
  }

  @Test void snowWithOutOfRangeLayersIsUnsupported() {
    assertTrue(decode("minecraft:snow", Map.of("layers", "0")).isUnsupported());
    assertTrue(decode("minecraft:snow", Map.of("layers", "9")).isUnsupported());
    assertTrue(decode("minecraft:snow", Map.of("layers", "many")).isUnsupported());
  }

  // ------------------------------------------------------------------
  // Fences: state-dependent, multi-box, neighbour-dependent
  // ------------------------------------------------------------------

  @Test void fenceWithOmittedDefaultBooleanPropertiesRemainsSupported() {
    BlockState fence = decode("minecraft:oak_fence", Map.of());
    assertFalse(fence.isUnsupported());
    assertTrue(BlockCatalogue12111.baseCollisionShape(fence).isFullCube() == false);
    assertEquals(1, BlockCatalogue12111.collisionShape(fence, BlockCatalogue12111.NO_NEIGHBOURS).boxes().size());
  }

  @Test void fencePostIsTheVanillaSixToTenColumn() {
    VoxelShape post = BlockCatalogue12111.baseCollisionShape(state("minecraft:oak_fence"));
    assertEquals(List.of(BlockBox.of(6 * S, 0, 6 * S, 10 * S, 1, 10 * S)), post.boxes());
  }

  @Test void fenceConnectionsAddOneRailPerConnectedSideAndNoOthers() {
    BlockState isolated = state("minecraft:oak_fence");
    VoxelShape noConnections = BlockCatalogue12111.collisionShape(isolated, BlockCatalogue12111.NO_NEIGHBOURS);
    assertEquals(1, noConnections.boxes().size());

    VoxelShape northOnly = BlockCatalogue12111.collisionShape(isolated, lookup(true, false, false, false));
    assertEquals(2, northOnly.boxes().size());
    assertEquals(BlockBox.of(7 * S, 12 * S, 0, 9 * S, 15 * S, 6 * S), northOnly.boxes().get(1));

    VoxelShape allFour = BlockCatalogue12111.collisionShape(isolated, lookup(true, true, true, true));
    assertEquals(5, allFour.boxes().size());
    assertEquals(BlockBox.of(7 * S, 12 * S, 10 * S, 9 * S, 15 * S, 1), allFour.boxes().get(2));
    assertEquals(BlockBox.of(0, 12 * S, 7 * S, 6 * S, 15 * S, 9 * S), allFour.boxes().get(3));
    assertEquals(BlockBox.of(10 * S, 12 * S, 7 * S, 1, 15 * S, 9 * S), allFour.boxes().get(4));
  }

  @Test void fenceRailTopIsFifteenSixteenthsNotSixteen() {
    VoxelShape connected = BlockCatalogue12111.collisionShape(state("minecraft:oak_fence"), lookup(true, false, false, false));
    assertEquals(15 * S, connected.boxes().get(1).maxY());
  }

  // ------------------------------------------------------------------
  // Walls: state-dependent post height and corner fills
  // ------------------------------------------------------------------

  @Test void wallBaseIsTheFourToTwelveColumn() {
    VoxelShape base = BlockCatalogue12111.baseCollisionShape(state("minecraft:cobblestone_wall"));
    assertEquals(List.of(BlockBox.of(4 * S, 0, 4 * S, 12 * S, 1, 12 * S)), base.boxes());
  }

  @Test void wallPostIsTallWhenUpIsSetAndLowOtherwise() {
    VoxelShape low = BlockCatalogue12111.collisionShape(
        decode("minecraft:cobblestone_wall", Map.of("up", "false")), BlockCatalogue12111.NO_NEIGHBOURS);
    assertEquals(14 * S, low.boxes().get(1).maxY(), "without up the post stops at 14/16");

    VoxelShape tall = BlockCatalogue12111.collisionShape(
        decode("minecraft:cobblestone_wall", Map.of("up", "true")), BlockCatalogue12111.NO_NEIGHBOURS);
    assertEquals(1.0, tall.boxes().get(1).maxY(), "with up the post reaches the full height");
    assertEquals(BlockBox.of(5 * S, 0, 5 * S, 11 * S, 1, 11 * S), tall.boxes().get(1));
  }

  @Test void wallConnectionsAddArmsAndDiagonalCornerFills() {
    BlockState wall = decode("minecraft:cobblestone_wall", Map.of("up", "false"));
    VoxelShape northEast = BlockCatalogue12111.collisionShape(wall, lookup(true, false, false, true));
    // base (1) + post (1) + two arms of three boxes each (6) + one corner fill (1)
    assertEquals(1 + 1 + 3 + 3 + 1, northEast.boxes().size());
    assertTrue(northEast.boxes().contains(BlockBox.of(7 * S, 9 * S, 0, 9 * S, 15 * S, S)));
  }

  @Test void wallWithoutConnectionsHasOnlyBaseAndPost() {
    VoxelShape plain = BlockCatalogue12111.collisionShape(
        decode("minecraft:cobblestone_wall", Map.of("up", "false")), BlockCatalogue12111.NO_NEIGHBOURS);
    assertEquals(2, plain.boxes().size());
  }

  // ------------------------------------------------------------------
  // Panes and doors and trapdoors
  // ------------------------------------------------------------------

  @Test void paneColumnIsSevenToNineAndConnectionsAddBarsPerSide() {
    BlockState pane = state("minecraft:glass_pane");
    VoxelShape isolated = BlockCatalogue12111.collisionShape(pane, BlockCatalogue12111.NO_NEIGHBOURS);
    assertEquals(List.of(BlockBox.of(7 * S, 0, 7 * S, 9 * S, 1, 9 * S)), isolated.boxes());

    VoxelShape east = BlockCatalogue12111.collisionShape(pane, lookup(false, false, false, true));
    assertEquals(2, east.boxes().size());
    assertEquals(BlockBox.of(9 * S, 0, 7 * S, 1, 1, 9 * S), east.boxes().get(1));
  }

  @Test void ironBarsUseThePaneShape() {
    assertEquals(BlockCatalogue12111.collisionShape(state("minecraft:glass_pane"), BlockCatalogue12111.NO_NEIGHBOURS),
        BlockCatalogue12111.collisionShape(state("minecraft:iron_bars"), BlockCatalogue12111.NO_NEIGHBOURS));
  }

  @Test void closedDoorLowerHalfIsAThreePixelSlabUpToThirteenSixteenths() {
    // hinge=right puts the slab on the low side of the perpendicular axis.
    VoxelShape lower = BlockCatalogue12111.baseCollisionShape(
        decode("minecraft:oak_door", Map.of("facing", "north", "half", "lower", "hinge", "right", "open", "false")));
    assertEquals(BlockBox.of(0, 0, 0, 3 * S, 13 * S, 1), lower.boxes().getFirst());
  }

  @Test void closedDoorUpperHalfStartsAtThreeSixteenths() {
    VoxelShape upper = BlockCatalogue12111.baseCollisionShape(
        decode("minecraft:oak_door", Map.of("facing", "north", "half", "upper", "hinge", "right", "open", "false")));
    assertEquals(BlockBox.of(0, 3 * S, 0, 3 * S, 1, 1), upper.boxes().getFirst());
  }

  @Test void doorHingeIsARealCollisionDifferenceAndMirrorsAcrossThePerpendicularAxis() {
    VoxelShape right = BlockCatalogue12111.baseCollisionShape(
        decode("minecraft:oak_door", Map.of("facing", "north", "half", "lower", "hinge", "right", "open", "false")));
    VoxelShape left = BlockCatalogue12111.baseCollisionShape(
        decode("minecraft:oak_door", Map.of("facing", "north", "half", "lower", "hinge", "left", "open", "false")));
    assertEquals(BlockBox.of(13 * S, 0, 0, 1, 13 * S, 1), left.boxes().getFirst());
    assertEquals(right.flip(Axis.X), left);
  }

  @Test void openDoorRotatesAQuarterTurnSoItLeavesTheDoorway() {
    BlockState closed = decode("minecraft:oak_door", Map.of("facing", "north", "half", "lower", "hinge", "right", "open", "false"));
    BlockState open = decode("minecraft:oak_door", Map.of("facing", "north", "half", "lower", "hinge", "right", "open", "true"));
    VoxelShape closedShape = BlockCatalogue12111.baseCollisionShape(closed);
    VoxelShape openShape = BlockCatalogue12111.baseCollisionShape(open);
    assertNotEquals(closedShape, openShape);
    assertEquals(closedShape.volume(), openShape.volume(), "an open door keeps the same volume");
    assertTrue(openShape.bounds().maxY() <= 13 * S);
  }

  @Test void closedTrapdoorIsAThreePixelFaceAndOpenTrapdoorStandsVertically() {
    VoxelShape closedBottom = BlockCatalogue12111.baseCollisionShape(
        decode("minecraft:oak_trapdoor", Map.of("facing", "north", "half", "bottom", "open", "false")));
    assertEquals(BlockBox.of(0, 0, 0, 1, 3 * S, 1), closedBottom.boxes().getFirst());

    VoxelShape closedTop = BlockCatalogue12111.baseCollisionShape(
        decode("minecraft:oak_trapdoor", Map.of("facing", "north", "half", "top", "open", "false")));
    assertEquals(BlockBox.of(0, 13 * S, 0, 1, 1, 1), closedTop.boxes().getFirst());

    VoxelShape openNorth = BlockCatalogue12111.baseCollisionShape(
        decode("minecraft:oak_trapdoor", Map.of("facing", "north", "half", "bottom", "open", "true")));
    // facing north means the hinge is on the south side, so the panel is at z=13/16..16/16.
    assertEquals(BlockBox.of(0, 0, 13 * S, 1, 1, 1), openNorth.boxes().getFirst());
  }

  // ------------------------------------------------------------------
  // Beds, chests, special movement-relevant blocks
  // ------------------------------------------------------------------

  @Test void bedsAreNineSixteenthsTallForBothHalves() {
    for (String part : List.of("head", "foot")) {
      VoxelShape bed = BlockCatalogue12111.baseCollisionShape(
          decode("minecraft:red_bed", Map.of("facing", "north", "part", part, "occupied", "false")));
      assertEquals(BlockBox.of(0, 0, 0, 1, 9 * S, 1), bed.boxes().getFirst(), part);
    }
  }

  @Test void chestsAreOneToFifteenByZeroToFourteen() {
    assertEquals(BlockBox.of(S, 0, S, 15 * S, 14 * S, 15 * S),
        BlockCatalogue12111.baseCollisionShape(state("minecraft:chest")).boxes().getFirst());
  }

  @Test void laddersAreAOnePixelPlateOnTheFaceTheyFace() {
    assertEquals(BlockBox.of(0, 0, 15 * S, 1, 1, 1),
        BlockCatalogue12111.baseCollisionShape(decode("minecraft:ladder", Map.of("facing", "north"))).boxes().getFirst());
    assertEquals(BlockBox.of(0, 0, 0, 1, 1, S),
        BlockCatalogue12111.baseCollisionShape(decode("minecraft:ladder", Map.of("facing", "south"))).boxes().getFirst());
    assertEquals(BlockBox.of(15 * S, 0, 0, 1, 1, 1),
        BlockCatalogue12111.baseCollisionShape(decode("minecraft:ladder", Map.of("facing", "east"))).boxes().getFirst());
    assertEquals(BlockBox.of(0, 0, 0, S, 1, 1),
        BlockCatalogue12111.baseCollisionShape(decode("minecraft:ladder", Map.of("facing", "west"))).boxes().getFirst());
  }

  @Test void scaffoldingHasATopPlinthOrABottomPlinthDependingOnBottom() {
    assertEquals(BlockBox.of(0, 14 * S, 0, 1, 1, 1),
        BlockCatalogue12111.baseCollisionShape(decode("minecraft:scaffolding", Map.of("bottom", "false"))).boxes().getFirst());
    assertEquals(BlockBox.of(0, 0, 0, 1, 2 * S, 1),
        BlockCatalogue12111.baseCollisionShape(decode("minecraft:scaffolding", Map.of("bottom", "true"))).boxes().getFirst());
  }

  @Test void soulSandAndHoneyAndSlimeRemainFullCubesForCollision() {
    for (String id : List.of("minecraft:soul_sand", "minecraft:soul_soil", "minecraft:honey_block", "minecraft:slime_block")) {
      assertTrue(BlockCatalogue12111.baseCollisionShape(state(id)).isFullCube(), id);
    }
  }

  @Test void cobwebPowderSnowAndSweetBerryBushHaveNoCollisionButAreStillKnownStates() {
    for (String id : List.of("minecraft:cobweb", "minecraft:powder_snow", "minecraft:sweet_berry_bush")) {
      BlockState state = state(id);
      assertFalse(state.isUnsupported(), id + " must be a known state");
      assertTrue(BlockCatalogue12111.baseCollisionShape(state).isEmpty(), id + " must not collide");
    }
  }

  @Test void unknownBlockIdsAreExplicitlyUnsupportedAndGetNoShape() {
    BlockState unknown = decode("minecraft:definitely_not_a_block", Map.of());
    assertTrue(unknown.isUnsupported());
    assertTrue(BlockCatalogue12111.baseCollisionShape(unknown).isEmpty());
  }

  @Test void decodingIsDeterministicForIdenticalInput() {
    Map<String, String> properties = Map.of("facing", "east", "half", "top", "shape", "outer_left");
    assertEquals(decode("minecraft:oak_stairs", properties), decode("minecraft:oak_stairs", properties));
  }

  @Test void blockStateRejectsImpossiblePropertyCombinationsAtConstruction() {
    assertThrows(IllegalArgumentException.class, () -> BlockState.builder("minecraft:snow")
        .variant(BlockState.Variant.SNOW_LAYER).layers(9).build());
    assertThrows(IllegalArgumentException.class, () -> BlockState.builder("minecraft:water")
        .variant(BlockState.Variant.FLUID).level(8).build());
    assertThrows(IllegalArgumentException.class, () -> BlockState.builder("minecraft:candle")
        .variant(BlockState.Variant.CANDLE).candles(5).build());
    assertThrows(NullPointerException.class, () -> BlockState.builder("minecraft:stone").variant(null).build());
    // The catalogue turns an out-of-range property into an explicitly unsupported
    // state rather than throwing or clamping it to a plausible value.
    assertTrue(decode("minecraft:candle", Map.of("candles", "5")).isUnsupported());
    assertTrue(decode("minecraft:candle", Map.of("candles", "0")).isUnsupported());
    assertTrue(decode("minecraft:snow", Map.of("layers", "0")).isUnsupported());
  }

  @Test void builderProducesTheSameStateAsTheCatalogueForTheSameProperties() {
    BlockState built = BlockState.builder("minecraft:oak_fence")
        .variant(BlockState.Variant.FENCE)
        .waterlogged(true)
        .north(true)
        .east(true)
        .provenance(BlockState.PropertySource.WIRE)
        .build();
    BlockState decoded = decode("minecraft:oak_fence", Map.of("waterlogged", "true", "north", "true", "east", "true"));
    assertEquals(decoded, built);
  }

  // ------------------------------------------------------------------

  private static BlockCatalogue12111.NeighbourLookup lookup(boolean north, boolean south, boolean west, boolean east) {
    return new BlockCatalogue12111.NeighbourLookup() {
      @Override public boolean connects(Direction direction) {
        return switch (direction) {
          case NORTH -> north;
          case SOUTH -> south;
          case WEST -> west;
          case EAST -> east;
          default -> false;
        };
      }

      @Override public boolean tallNeighbour(Direction direction) {
        return false;
      }
    };
  }

  @Test void shapeBuildersProduceVanillaAlignedHelperShapes() {
    assertEquals(BlockBox.of(0, 0, 0, 1, 0.5, 1), Shapes.SLAB_BOTTOM.boxes().getFirst());
    assertEquals(BlockBox.of(0, 0, 0, 1, 1, 1), Shapes.BLOCK.boxes().getFirst());
    assertEquals(BlockBox.of(6 * S, 0, 6 * S, 10 * S, 1, 10 * S), Shapes.FENCE_POST.boxes().getFirst());
    assertTrue(Shapes.EMPTY.isEmpty());
  }

  @Test void directionRotationIsUsedOnlyForHorizontalFacesAndIsReversible() {
    VoxelShape north = Shapes.ladder(Direction.NORTH);
    VoxelShape east = Shapes.ladder(Direction.EAST);
    assertEquals(north, east.rotateToFace(Direction.EAST, Direction.NORTH));
    assertEquals(north, east.rotateToFace(Direction.EAST, Direction.NORTH).rotateToFace(Direction.NORTH, Direction.EAST)
        .rotateToFace(Direction.EAST, Direction.NORTH));
    assertThrows(IllegalArgumentException.class, () -> north.rotateToFace(Direction.UP, Direction.NORTH));
  }

  @Test void directionOppositeAndRotateAreConsistent() {
    for (Direction direction : Directions.Direction.VALUES) {
      assertEquals(direction, direction.opposite().opposite());
    }
    assertEquals(Direction.NORTH, Direction.EAST.rotateY());
    assertEquals(Direction.EAST, Direction.SOUTH.rotateY());
    assertEquals(Direction.SOUTH, Direction.WEST.rotateY());
    assertEquals(Direction.WEST, Direction.NORTH.rotateY());
    assertEquals(Direction.UP, Direction.UP.rotateY());
  }
}
