package dev.phantom.ac.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import dev.phantom.ac.Contracts;
import dev.phantom.ac.geometry.BlockBox;

/**
 * Phase 4 world-snapshot tests: chunk/section representation, coverage,
 * immutability, malformed-data rejection, boundary coordinates and determinism.
 *
 * <p>The central invariant these tests defend is that missing world data is never
 * silently reported as air. Every test that touches an unloaded position asserts
 * on {@link Coverage} rather than on a block state, because coverage is the only
  * value that can honestly describe "the client has no data here".</p>
  */
 class WorldSnapshotTest {

   private static final int MIN_Y = WorldSnapshot.OVERWORLD_MIN_Y;
   private static final int MAX_Y = WorldSnapshot.OVERWORLD_MAX_Y;

   /** Decodes a real 1.21.11 state so tests never hand-build a fake block. */
   private static BlockState decode(String id, Map<String, String> properties) {
    return dev.phantom.ac.world.v12111.BlockCatalogue12111.decode(id, properties);
  }

  private static BlockState stoneBlock() {
    return decode("minecraft:stone", Map.of());
  }

  private static BlockState slab() {
    return decode("minecraft:oak_slab", Map.of("type", "bottom"));
  }

  // ------------------------------------------------------------------
  // Construction and validation
  // ------------------------------------------------------------------

  @Test
  void clientBlockPredictionCanOverrideKnownSolidWithoutChangingCoverage() {
    WorldSnapshot world = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .setBlock(0, 64, 1, stoneBlock())
        .build();

    assertEquals(Coverage.KNOWN, world.coverageAt(0, 64, 1));
    assertEquals(stoneBlock(), world.blockAtOrNull(0, 64, 1));

    WorldSnapshot predicted = world.withBlockOverride(0, 64, 1, BlockState.air());

    assertEquals(Coverage.KNOWN, predicted.coverageAt(0, 64, 1));
    assertNull(predicted.blockAtOrNull(0, 64, 1));
    assertTrue(predicted.collisionShapeAt(0, 64, 1).isEmpty());

    assertEquals(stoneBlock(), world.blockAtOrNull(0, 64, 1),
        "the client prediction overlay must not mutate the acknowledged snapshot");
  }

  @Test
  void backedSnapshotsAnswerQueriesWithoutMaterializingTheWholeWorld() {
    java.util.concurrent.atomic.AtomicInteger queries = new java.util.concurrent.atomic.AtomicInteger();
    dev.phantom.ac.world.BlockState stone = stoneBlock();

    WorldSnapshot.Backend backend = new WorldSnapshot.Backend() {
      @Override public String version() { return Contracts.TARGET_VERSION; }
      @Override public int minY() { return MIN_Y; }
      @Override public int maxY() { return MAX_Y; }
      @Override public java.util.Set<Chunk> loadedChunks() { return java.util.Set.of(new Chunk(0, 0)); }
      @Override public boolean hasChunk(int chunkX, int chunkZ) { return chunkX == 0 && chunkZ == 0; }
      @Override public Coverage coverageAt(int x, int y, int z) {
        queries.incrementAndGet();
        if (x == 0 && y == 64 && z == 0) return Coverage.KNOWN;
        return Coverage.KNOWN;
      }
      @Override public BlockState blockAtOrNull(int x, int y, int z) {
        queries.incrementAndGet();
        return x == 0 && y == 64 && z == 0 ? stone : null;
      }
    };

    WorldSnapshot world = WorldSnapshot.backed(Contracts.TARGET_VERSION, MIN_Y, MAX_Y, backend);
    assertEquals(0, queries.get());
    assertTrue(world.hasChunk(0, 0));
    assertEquals(0, queries.get());
    assertEquals(Coverage.KNOWN, world.coverageAt(0, 64, 0));
    assertEquals(stone, world.blockAtOrNull(0, 64, 0));
    assertTrue(queries.get() > 0);
  }

  @Test
  void packedSectionsMatchPacketEventsWholeLongLayout() {
    BlockState air = BlockState.air();
    BlockState stone = stoneBlock();
    BlockState dirt = decode("minecraft:dirt", Map.of());
    int bits = 5;
    int valuesPerLong = 64 / bits;
    int length = (4096 + valuesPerLong - 1) / valuesPerLong;
    long[] data = new long[length];

    // PacketEvents' BitStorage stores floor(64 / bits) whole entries per long;
    // an entry never crosses a long boundary.
    data[0] |= 1L << (11 * bits); // local index 11 = stone
    data[1] |= 2L;                // local index 12 = dirt

    var section = new dev.phantom.ac.Phase4WorldReplica.PackedSection(
        0, new BlockState[] {air, stone, dirt}, data, bits, Map.of());

    assertEquals(stone, section.stateAt(11));
    assertEquals(dirt, section.stateAt(12));
    assertEquals(air, section.stateAt(13));
  }

  @Test
  void backedSnapshotCarriesItsCausalVisibilityBoundary() {
    WorldSnapshot.Backend backend = new WorldSnapshot.Backend() {
      @Override public String version() { return Contracts.TARGET_VERSION; }
      @Override public int minY() { return MIN_Y; }
      @Override public int maxY() { return MAX_Y; }
      @Override public Set<Chunk> loadedChunks() { return Set.of(); }
      @Override public Coverage coverageAt(int x, int y, int z) { return Coverage.UNLOADED; }
      @Override public BlockState blockAtOrNull(int x, int y, int z) { return null; }
      @Override public long causalSequence() { return 42L; }
    };
    WorldSnapshot world = WorldSnapshot.backed(Contracts.TARGET_VERSION, MIN_Y, MAX_Y, backend);
    assertEquals(42L, world.causalSequence());
  }

  @Test
  void mergeCombinesDisjointCoverageAndRejectsConflictingOverlap(){
    BlockState stone=dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone",Map.of());
    WorldSnapshot left=WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0,0).setBlock(0,64,0,stone).build();
    WorldSnapshot right=WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(1,0).setBlock(16,64,0,stone).build();
    WorldSnapshot merged=WorldSnapshot.merge(left,right);
    assertTrue(merged.hasChunk(0,0));
    assertTrue(merged.hasChunk(1,0));
    assertEquals(stone,merged.blockAtOrNull(16,64,0));

    WorldSnapshot conflict=WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0,0)
        .setBlock(0,64,0,dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:dirt",Map.of())).build();
    assertThrows(IllegalArgumentException.class,()->WorldSnapshot.merge(left,conflict));
  }

  @Test void emptySnapshotReportsEveryPositionAsUnloadedAndNeverAir() {
    WorldSnapshot world = WorldSnapshot.emptyOverworld12111();
    assertEquals(Coverage.UNLOADED, world.coverageAt(0, 64, 0));
    assertNull(world.blockAtOrNull(0, 64, 0));
    assertFalse(world.isKnown(0, 64, 0));
    assertFalse(world.hasChunk(0, 0));
  }

  @Test void requireBlockAtRefusesToInventAirInAnUnloadedChunk() {
    WorldSnapshot world = WorldSnapshot.emptyOverworld12111();
    assertThrows(WorldSnapshot.UnloadedRegionException.class, () -> world.requireBlockAt(0, 64, 0));
    assertThrows(WorldSnapshot.UnloadedRegionException.class, () -> world.requireBlockAt(-1, -60, -1));
  }

  @Test void requireBlockAtReturnsAirOnlyWhenThePositionIsGenuinelyKnownToBeEmpty() {
    WorldSnapshot world = WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0, 0).build();
    assertEquals(Coverage.KNOWN, world.coverageAt(0, 64, 0));
    assertEquals(BlockState.air(), world.requireBlockAt(0, 64, 0));
    assertTrue(world.requireBlockAt(0, 64, 0).isAir());
  }

  @Test void aStoredBlockIsReturnedAndReportedAsKnown() {
    WorldSnapshot world = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .setBlock(3, 64, -7, stoneBlock()).build();
    assertEquals(Coverage.KNOWN, world.coverageAt(3, 64, -7));
    assertEquals(stoneBlock(), world.blockAtOrNull(3, 64, -7));
    assertTrue(world.hasChunk(0, -1));
  }

  @Test void settingABlockWithoutLoadingTheChunkStillLoadsIt() {
    WorldSnapshot world = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .setBlock(0, MIN_Y, 0, stoneBlock()).build();
    assertEquals(Coverage.KNOWN, world.coverageAt(0, MIN_Y, 0));
    assertTrue(world.hasChunk(0, 0));
  }

  @Test void unsupportedStatesAreStoredExplicitlyAndNeverAsAirOrACube() {
    WorldSnapshot world = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .setUnsupportedBlock(1, 64, 1, "minecraft:something_new").build();
    assertEquals(Coverage.UNSUPPORTED, world.coverageAt(1, 64, 1));
    assertNull(world.blockAtOrNull(1, 64, 1));
    assertThrows(WorldSnapshot.UnsupportedStateException.class, () -> world.requireBlockAt(1, 64, 1));
  }

  @Test void storingAnUnsupportedStateThroughTheTypedSetterIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .setBlock(0, 64, 0, BlockState.unsupported("minecraft:x")));
  }

  @Test void theSnapshotConstructorRejectsOutOfHeightAndMisplacedBlocks() {
    Map<Pos, BlockState> outOfHeight = new HashMap<>();
    outOfHeight.put(new Pos(0, MAX_Y + 5, 0), stoneBlock());
    Map<Chunk, Map<Pos, BlockState>> chunks = Map.of(new Chunk(0, 0), outOfHeight);
    assertThrows(IllegalArgumentException.class,
        () -> new WorldSnapshot(Contracts.TARGET_VERSION, chunks, MIN_Y, MAX_Y));

    assertThrows(IllegalArgumentException.class, () -> WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .setBlock(0, MAX_Y + 1, 0, stoneBlock()));
    assertThrows(IllegalArgumentException.class, () -> WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .setBlock(0, MIN_Y - 1, 0, stoneBlock()));
    assertThrows(IllegalArgumentException.class, () -> WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .setUnsupportedBlock(0, MAX_Y + 1, 0, "minecraft:x"));
  }

  @Test void aSnapshotBuilderRejectsAPositionThatDoesNotBelongToItsDeclaredChunk() {
    Map<Pos, BlockState> misplaced = new HashMap<>();
    misplaced.put(new Pos(20, 64, 0), stoneBlock());
    Map<Chunk, Map<Pos, BlockState>> chunks = Map.of(new Chunk(0, 0), misplaced);
    assertThrows(IllegalArgumentException.class,
        () -> new WorldSnapshot(Contracts.TARGET_VERSION, chunks, MIN_Y, MAX_Y));
  }

  @Test void dimensionHeightBoundsAreEnforcedOnBothEnds() {
    assertThrows(IllegalArgumentException.class,
        () -> WorldSnapshot.builder(Contracts.TARGET_VERSION, 10, 0));
    assertEquals(MIN_Y, WorldSnapshot.emptyOverworld12111().minY());
    assertEquals(MAX_Y, WorldSnapshot.emptyOverworld12111().maxY());
  }

  @Test void positionsOutsideTheDimensionHeightAreUnloadedEvenInsideALoadedChunk() {
    WorldSnapshot world = WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0, 0).build();
    assertEquals(Coverage.UNLOADED, world.coverageAt(0, MAX_Y + 1, 0));
    assertEquals(Coverage.UNLOADED, world.coverageAt(0, MIN_Y - 1, 0));
  }

  @Test void theMinimumAndMaximumHeightBlocksAreBothUsable() {
    WorldSnapshot world = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .setBlock(0, MIN_Y, 0, stoneBlock())
        .setBlock(0, MAX_Y, 0, stoneBlock())
        .build();
    assertEquals(Coverage.KNOWN, world.coverageAt(0, MIN_Y, 0));
    assertEquals(Coverage.KNOWN, world.coverageAt(0, MAX_Y, 0));
  }

  // ------------------------------------------------------------------
  // Immutability
  // ------------------------------------------------------------------

  @Test void mutatingTheInputMapAfterConstructionDoesNotChangeTheSnapshot() {
    Map<Pos, BlockState> source = new HashMap<>();
    source.put(new Pos(0, 64, 0), stoneBlock());
    Map<Chunk, Map<Pos, BlockState>> chunks = new HashMap<>();
    chunks.put(new Chunk(0, 0), source);
    WorldSnapshot world = new WorldSnapshot(Contracts.TARGET_VERSION, chunks, MIN_Y, MAX_Y);
    source.clear();
    chunks.clear();
    assertEquals(stoneBlock(), world.blockAtOrNull(0, 64, 0));
    assertTrue(world.hasChunk(0, 0));
  }

  @Test void theExposedCollectionsAreUnmodifiable() {
    WorldSnapshot world = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .setBlock(0, 64, 0, stoneBlock()).build();
    assertThrows(UnsupportedOperationException.class, () -> world.chunks().clear());
    assertThrows(UnsupportedOperationException.class, () -> world.loadedChunks().clear());
    assertThrows(UnsupportedOperationException.class,
        () -> world.chunkStates(new Chunk(0, 0)).put(new Pos(1, 64, 1), stoneBlock()));
  }

  @Test void unloadingAChunkRemovesItsBlocksSoTheyCannotLeakIntoLaterQueries() {
    WorldSnapshot world = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .setBlock(0, 64, 0, stoneBlock())
        .unloadChunk(0, 0)
        .build();
    assertEquals(Coverage.UNLOADED, world.coverageAt(0, 64, 0));
    assertFalse(world.hasChunk(0, 0));
  }

  @Test void aLoadedChunkWithNoStoredStateStaysLoadedAndReportsAir() {
    // This distinguishes "the packet sent no state here" from "no packet arrived".
    WorldSnapshot world = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .loadChunk(5, -3)
        .build();
    assertTrue(world.hasChunk(5, -3));
    assertEquals(Coverage.KNOWN, world.coverageAt(5 * 16, 70, -3 * 16));
    assertEquals(Coverage.UNLOADED, world.coverageAt(6 * 16, 70, -3 * 16));
  }

  // ------------------------------------------------------------------
  // Chunk and section boundaries, including negative coordinates
  // ------------------------------------------------------------------

  @Test void chunkCoordinatesUseFloorDivisionSoNegativesLandInTheRightChunk() {
    assertEquals(new Chunk(0, 0), Chunk.containing(0, 0));
    assertEquals(new Chunk(0, 0), Chunk.containing(15, 15));
    assertEquals(new Chunk(1, 1), Chunk.containing(16, 16));
    assertEquals(new Chunk(-1, -1), Chunk.containing(-1, -1));
    assertEquals(new Chunk(-1, -1), Chunk.containing(-16, -16));
    assertEquals(new Chunk(-2, -2), Chunk.containing(-17, -17));
  }

  @Test void chunkLocalCoordinatesAreAlwaysZeroToFifteenEvenForNegativeWorldCoordinates() {
    assertEquals(0, new Pos(-16, 0, -16).localX());
    assertEquals(15, new Pos(-1, 0, -1).localX());
    assertEquals(0, new Pos(-16, 0, -16).localZ());
    assertEquals(15, new Pos(-1, 0, -1).localZ());
    assertEquals(7, new Pos(-9, 0, -9).localX());
  }

  @Test void sectionIndexUsesFloorDivisionForNegativeHeights() {
    assertEquals(-4, Chunk.sectionIndex(-64));
    assertEquals(-1, Chunk.sectionIndex(-1));
    assertEquals(0, Chunk.sectionIndex(0));
    assertEquals(0, Chunk.sectionIndex(15));
    assertEquals(1, Chunk.sectionIndex(16));
    assertEquals(19, Chunk.sectionIndex(319));
    assertEquals(-64, Chunk.sectionMinY(-4));
    assertEquals(304, Chunk.sectionMinY(19));
  }

  @Test void aBlockPlacedOnAChunkBoundaryBelongsToTheExpectedChunk() {
    WorldSnapshot world = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .setBlock(15, 64, 0, stoneBlock())
        .setBlock(16, 64, 0, stoneBlock())
        .build();
    assertTrue(world.hasChunk(0, 0));
    assertTrue(world.hasChunk(1, 0));
    assertEquals(Coverage.KNOWN, world.coverageAt(15, 64, 0));
    assertEquals(Coverage.KNOWN, world.coverageAt(16, 64, 0));
    // x=16 loaded chunk (1,0), so its own columns are known-to-be-air, not unloaded.
    assertEquals(Coverage.KNOWN, world.coverageAt(17, 64, 0));
    // Chunk (2,0) was never delivered.
    assertEquals(Coverage.UNLOADED, world.coverageAt(32, 64, 0));
  }

  @Test void aBlockPlacedOnASectionBoundaryIsStoredAtTheRightHeight() {
    WorldSnapshot world = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .setBlock(0, 15, 0, stoneBlock())
        .setBlock(0, 16, 0, stoneBlock())
        .build();
    assertEquals(Coverage.KNOWN, world.coverageAt(0, 15, 0));
    assertEquals(Coverage.KNOWN, world.coverageAt(0, 16, 0));
    assertEquals(stoneBlock(), world.blockAtOrNull(0, 16, 0));
  }

  @Test void negativeChunksReportCoverageIndependentlyOfTheirNeighbours() {
    WorldSnapshot world = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .setBlock(-1, 64, -1, stoneBlock())
        .build();
    assertEquals(Coverage.KNOWN, world.coverageAt(-1, 64, -1));
    assertEquals(Coverage.UNLOADED, world.coverageAt(0, 64, 0));
  }

  // ------------------------------------------------------------------
  // Region coverage
  // ------------------------------------------------------------------

  @Test void positionsIntersectingUsesFloorBoundsAndClampsToDimensionHeight() {
    WorldSnapshot world = WorldSnapshot.emptyOverworld12111();
    List<Pos> positions = world.positionsIntersecting(BlockBox.of(0.5, 64.5, 0.5, 0.75, 64.75, 0.75));
    assertEquals(List.of(new Pos(0, 64, 0)), positions);

    // A column spanning the whole world height is clamped to the dimension, and
    // its X/Z extent keeps the inclusive floor(max) sweep on those axes.
    List<Pos> tall = world.positionsIntersecting(BlockBox.of(0, -64.5, 0, 1, 1000, 1));
    int columnsPerHeight = 2 * 2;
    assertEquals((MAX_Y - MIN_Y + 1) * columnsPerHeight, tall.size());
    assertEquals(new Pos(0, MIN_Y, 0), tall.getFirst());
    assertEquals(new Pos(1, MAX_Y, 1), tall.getLast());
  }

  @Test void positionsIntersectingIsInCanonicalXYZOrder() {
    WorldSnapshot world = WorldSnapshot.emptyOverworld12111();
    List<Pos> positions = world.positionsIntersecting(BlockBox.of(0, 0, 0, 1, 1, 1));
    assertEquals(List.of(
        new Pos(0, 0, 0), new Pos(0, 0, 1), new Pos(0, 1, 0), new Pos(0, 1, 1),
        new Pos(1, 0, 0), new Pos(1, 0, 1), new Pos(1, 1, 0), new Pos(1, 1, 1)), positions);
  }

  @Test void coverageInReportsEveryDistinctCoverageValuePresent() {
    WorldSnapshot world = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .setBlock(0, 64, 0, stoneBlock())
        .setUnsupportedBlock(1, 64, 0, "minecraft:x")
        .build();
    // A box covering only the stone cell reports no unsupported coverage.
    assertEquals(Set.of(Coverage.KNOWN), world.coverageIn(BlockBox.of(0, 64, 0, 0.5, 64.5, 0.5)));
    // A box reaching into the unsupported cell reports both values.
    assertEquals(Set.of(Coverage.KNOWN, Coverage.UNSUPPORTED),
        world.coverageIn(BlockBox.of(0, 64, 0, 2, 65, 1)));
    assertEquals(Set.of(Coverage.UNLOADED), world.coverageIn(BlockBox.of(100, 64, 100, 101, 65, 101)));
  }

  @Test void coverageProblemsInListsEveryMissingOrUnsupportedCell() {
    WorldSnapshot world = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .setUnsupportedBlock(1, 64, 0, "minecraft:unknown_shape")
        .loadUnknownChunk(2, 0)
        .setBlock(0, 64, 0, stoneBlock())
        .build();

    List<WorldSnapshot.CoverageProblem> problems = world.coverageProblemsIn(
        BlockBox.of(0, 64, 0, 35, 65, 1));

    WorldSnapshot.CoverageProblem unsupported = problems.stream()
        .filter(problem -> problem.position().equals(new Pos(1, 64, 0)))
        .findFirst().orElseThrow();
    assertEquals(Coverage.UNSUPPORTED, unsupported.coverage());
    assertTrue(unsupported.detail().contains("block=minecraft:unknown_shape"));

    WorldSnapshot.CoverageProblem unknown = problems.stream()
        .filter(problem -> problem.position().equals(new Pos(32, 64, 0)))
        .findFirst().orElseThrow();
    assertEquals(Coverage.UNKNOWN, unknown.coverage());
    assertEquals("UNKNOWN", unknown.detail());
    assertEquals(81, problems.size());
  }

  @Test void fullyKnownIsTrueOnlyWhenNothingInTheBoxIsMissingData() {
    WorldSnapshot world = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .setBlock(0, 64, 0, stoneBlock()).build();
    assertTrue(world.fullyKnown(BlockBox.of(0, 64, 0, 1, 65, 1)));
    assertFalse(world.fullyKnown(BlockBox.of(0, 64, 0, 20, 65, 1)));
  }

  @Test void hasUnknownOrUnsupportedDistinguishesBothKindsOfMissingData() {
    WorldSnapshot unsupported = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .setUnsupportedBlock(0, 64, 0, "minecraft:x").build();
    assertTrue(unsupported.hasUnknownOrUnsupported(BlockBox.of(0, 64, 0, 1, 65, 1)));

    WorldSnapshot loaded = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .setBlock(0, 64, 0, stoneBlock()).build();
    assertFalse(loaded.hasUnknownOrUnsupported(BlockBox.of(0, 64, 0, 1, 65, 1)));
    assertTrue(loaded.hasUnknownOrUnsupported(BlockBox.of(100, 64, 100, 101, 65, 101)));
    // A loaded chunk reports KNOWN, including where its state is air.
    assertEquals(Set.of(Coverage.KNOWN), loaded.coverageIn(BlockBox.of(0, 64, 0, 1, 65, 1)));
  }

  @Test void aQueryCoveringAlignedAndUnalignedBoundariesIsStillExact() {
    WorldSnapshot world = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .setBlock(-1, 64, -1, stoneBlock())
        .setBlock(0, 64, 0, stoneBlock())
        .build();
    // Vanilla's block sweep is inclusive of floor(max), so a box that ends exactly
    // on a block boundary does touch the cell above and beside it.
    List<Pos> positions = world.positionsIntersecting(BlockBox.of(-1, 64, -1, 0, 65, 0));
    assertEquals(8, positions.size());
    assertEquals(List.of(
        new Pos(-1, 64, -1), new Pos(-1, 64, 0), new Pos(-1, 65, -1), new Pos(-1, 65, 0),
        new Pos(0, 64, -1), new Pos(0, 64, 0), new Pos(0, 65, -1), new Pos(0, 65, 0)), positions);

    // A box wholly inside one cell at fractional coordinates covers exactly that cell.
    assertEquals(List.of(new Pos(3, 64, 3)),
        world.positionsIntersecting(BlockBox.of(3.25, 64.25, 3.25, 3.75, 64.75, 3.75)));
  }

  // ------------------------------------------------------------------
  // Block lookup and shape delegation
  // ------------------------------------------------------------------

  @Test void collisionShapeForAKnownBlockIsTheVanillaShapeAtWorldCoordinates() {
    WorldSnapshot world = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .setBlock(4, 70, -9, slab()).build();
    var shape = world.collisionShapeAt(4, 70, -9);
    assertEquals(BlockBox.of(4, 70, -9, 5, 70.5, -8), shape.boxes().getFirst());
  }

  @Test void collisionShapeForAnUnloadedOrUnsupportedPositionIsEmpty() {
    WorldSnapshot world = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .setUnsupportedBlock(0, 64, 0, "minecraft:x").build();
    assertTrue(world.collisionShapeAt(0, 64, 0).isEmpty());
    assertTrue(world.collisionShapeAt(500, 64, 500).isEmpty());
  }

  @Test void neighbourDependentCoverageIsResolvedByTheSnapshotItself() {
    // A fence beside a full cube must grow a connecting rail; the same fence in
    // isolation must not.
    WorldSnapshot connected = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .setBlock(0, 64, 0, decode("minecraft:oak_fence", Map.of()))
        .setBlock(1, 64, 0, stoneBlock())
        .build();
    WorldSnapshot isolated = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .setBlock(0, 64, 0, decode("minecraft:oak_fence", Map.of()))
        .build();
    assertTrue(connected.collisionShapeAt(0, 64, 0).boxes().size()
        > isolated.collisionShapeAt(0, 64, 0).boxes().size());

    // The connected rail is on the east side, spanning x=10/16..16/16.
    assertTrue(connected.collisionShapeAt(0, 64, 0).boxes().stream()
        .anyMatch(box -> box.maxX() == 1.0 && box.minX() == 10.0 / 16.0));
  }

  @Test void anUnloadedNeighbourPreventsAConnectionRatherThanAssumingOne() {
    WorldSnapshot world = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .setBlock(0, 64, 0, decode("minecraft:oak_fence", Map.of()))
        .loadChunk(1, 0)
        .build();
    // Chunk (1,0) is loaded but the neighbouring cell is air, so no rail appears.
    assertFalse(world.collisionShapeAt(0, 64, 0).boxes().stream().anyMatch(box -> box.maxX() == 1.0));
  }

  @Test void blockRequireKnownThrowsForPositionsWithNoStoredState() {
    WorldSnapshot world = WorldSnapshot.builder(Contracts.TARGET_VERSION).build();
    assertThrows(IllegalStateException.class, () -> world.blockRequireKnown(new Pos(0, 64, 0)));
  }

  @Test void chunkStatesReturnsCanonicalOrderAndAnEmptyMapForUnloadedChunks() {
    WorldSnapshot world = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .setBlock(1, 64, 1, stoneBlock())
        .setBlock(0, 64, 0, stoneBlock())
        .setBlock(0, 65, 0, slab())
        .build();
    assertEquals(List.of(new Pos(0, 64, 0), new Pos(0, 65, 0), new Pos(1, 64, 1)),
        new ArrayList<>(world.chunkStates(new Chunk(0, 0)).keySet()));
    assertEquals(Map.of(), world.chunkStates(new Chunk(9, 9)));
  }

  @Test void loadedChunkListIsSortedDeterministically() {
    WorldSnapshot world = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .loadChunk(2, -1).loadChunk(-5, 0).loadChunk(2, 3).build();
    assertEquals(List.of(new Chunk(-5, 0), new Chunk(2, -1), new Chunk(2, 3)), world.loadedChunkList());
  }

  // ------------------------------------------------------------------
  // Determinism
  // ------------------------------------------------------------------

  @Test void identicalBuildsProduceEqualSnapshotsRegardlessOfInsertionOrder() {
    WorldSnapshot first = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .setBlock(1, 64, 1, stoneBlock())
        .setBlock(0, 64, 0, slab())
        .setBlock(-3, 70, 5, stoneBlock())
        .build();
    WorldSnapshot second = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .setBlock(-3, 70, 5, stoneBlock())
        .setBlock(0, 64, 0, slab())
        .setBlock(1, 64, 1, stoneBlock())
        .build();
    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());
  }

  @Test void repeatedQueriesOverTheSameSnapshotReturnIdenticalResults() {
    WorldSnapshot world = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .setBlock(0, 64, 0, stoneBlock())
        .setBlock(1, 64, 0, decode("minecraft:oak_stairs", Map.of("facing", "north", "half", "bottom", "shape", "straight")))
        .build();
    BlockBox query = BlockBox.of(0.2, 63.5, 0.2, 1.8, 65, 0.8);
    var first = WorldQueries.collisions(world, query);
    for (int repeat = 0; repeat < 25; repeat++) {
      assertEquals(first, WorldQueries.collisions(world, query));
    }
    assertEquals(first.collisions(), WorldQueries.collisions(world, query).collisions());
  }

  @Test void aBuilderCanBeReusedToProduceIndependentSnapshots() {
    var builder = WorldSnapshot.builder(Contracts.TARGET_VERSION).setBlock(0, 64, 0, stoneBlock());
    WorldSnapshot first = builder.build();
    builder.setBlock(1, 64, 0, slab());
    WorldSnapshot second = builder.build();
    // The earlier snapshot must not observe the later mutation.
    assertEquals(Coverage.KNOWN, first.coverageAt(0, 64, 0));
    assertTrue(first.blockAtOrNull(1, 64, 0) == null);
    assertEquals(slab(), second.blockAtOrNull(1, 64, 0));
    assertFalse(first.equals(second));
  }

  @Test void snapshotToStringIsStableAndDoesNotDependOnHashOrdering() {
    WorldSnapshot world = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .loadChunk(0, 0).loadChunk(1, 1).build();
    assertEquals("WorldSnapshot[" + Contracts.TARGET_VERSION + " chunks=2 y=" + MIN_Y + ".." + MAX_Y + "]",
        world.toString());
  }

  @Test void chunkContainsAndOriginAreConsistentWithFloorDivision() {
    Chunk chunk = new Chunk(-1, -1);
    assertTrue(chunk.contains(-1, -1));
    assertTrue(chunk.contains(-16, -16));
    assertFalse(chunk.contains(0, 0));
    assertEquals(new Pos(-16, 0, -16), chunk.origin());
  }

  @Test void posOffsetAndChunkAccessorsAreConsistent() {
    Pos pos = new Pos(16, 64, -1);
    assertEquals(new Pos(17, 65, 0), pos.offset(1, 1, 1));
    assertEquals(new Chunk(1, -1), pos.chunk());
  }
}