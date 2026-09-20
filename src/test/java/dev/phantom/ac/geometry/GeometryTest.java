package dev.phantom.ac.geometry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import dev.phantom.ac.geometry.Directions.Axis;
import dev.phantom.ac.geometry.Directions.Direction;

/**
 * Phase 4 geometry tests: AABB construction, intersection, containment, clipping
 * and the integer-boundary and negative-coordinate cases.
 *
 * <p>The semantics asserted here are vanilla's, not this implementation's:
 * {@code AABB.intersects} is strict on every axis so touching faces do not
 * intersect, {@code AABB.contains} is inclusive on every face, and
 * {@code VoxelShape.collide} only ever moves a box out of an <em>overlapping</em>
 * collision, never out of mere contact.</p>
 */
class GeometryTest {

  // ------------------------------------------------------------------
  // BlockBox construction
  // ------------------------------------------------------------------

  @Test void boxesRejectInvertedOrNaNCoordinatesAtConstruction() {
    assertThrows(IllegalArgumentException.class, () -> BlockBox.of(1, 0, 0, 0, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> BlockBox.of(0, 1, 0, 1, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> BlockBox.of(0, 0, 1, 1, 1, 0));
    assertThrows(IllegalArgumentException.class, () -> BlockBox.of(Double.NaN, 0, 0, 1, 1, 1));
  }

  @Test void degenerateFlushBoxesAreRepresentable() {
    assertDoesNotThrow(() -> BlockBox.of(0, 0, 0, 0, 1, 1));
    assertEquals(0.0, BlockBox.of(0, 0, 0, 0, 1, 1).sizeX());
  }

  @Test void sixteenthsConversionIsExactForEveryVanillaFractionUsed() {
    assertEquals(1.0, BlockBox.ofSixteenths(0, 0, 0, 16, 16, 16).maxX(), 0.0);
    assertEquals(0.5, BlockBox.ofSixteenths(0, 0, 0, 16, 8, 16).maxY(), 0.0);
    assertEquals(0.0625, BlockBox.ofSixteenths(0, 0, 0, 16, 1, 16).maxY(), 0.0);
    assertEquals(0.3125, BlockBox.ofSixteenths(5, 0, 5, 11, 16, 11).minX(), 0.0);
    assertEquals(0.9375, BlockBox.ofSixteenths(0, 0, 15, 16, 16, 16).minZ(), 0.0);
  }

  // ------------------------------------------------------------------
  // Intersection: strict, so contact is not intersection
  // ------------------------------------------------------------------

  @Test void overlappingBoxesIntersect() {
    assertTrue(BlockBox.of(0, 0, 0, 1, 1, 1).intersects(BlockBox.of(0.5, 0.5, 0.5, 1.5, 1.5, 1.5)));
  }

  @Test void boxesThatOnlyTouchAFaceDoNotIntersectOnThatAxis() {
    // Vanilla AABB.intersects uses strict > and <, so sharing a face is not overlap.
    assertFalse(BlockBox.of(0, 0, 0, 1, 1, 1).intersects(BlockBox.of(1, 0, 0, 2, 1, 1)));
    assertFalse(BlockBox.of(0, 1, 0, 1, 2, 1).intersects(BlockBox.of(0, 0, 0, 1, 1, 1)));
    assertFalse(BlockBox.of(0, 0, 1, 1, 1, 2).intersects(BlockBox.of(0, 0, 0, 1, 1, 1)));
  }

  @Test void boxesTouchingOnlyOnAnEdgeOrCornerDoNotIntersect() {
    BlockBox unit = BlockBox.of(0, 0, 0, 1, 1, 1);
    assertFalse(unit.intersects(BlockBox.of(1, 1, 0, 2, 2, 1)));
    assertFalse(unit.intersects(BlockBox.of(1, 1, 1, 2, 2, 2)));
  }

  @Test void aBoxContainedInsideAnotherStillIntersectsIt() {
    assertTrue(BlockBox.of(0, 0, 0, 2, 2, 2).intersects(BlockBox.of(0.5, 0.5, 0.5, 1, 1, 1)));
  }

  @Test void zeroWidthBoxesNeverIntersectAnything() {
    BlockBox flat = BlockBox.of(0, 1, 0, 1, 1, 1);
    assertFalse(flat.intersects(BlockBox.of(0, 0, 0, 1, 1, 1)));
  }

  // ------------------------------------------------------------------
  // Containment: inclusive on every face
  // ------------------------------------------------------------------

  @Test void containmentIsInclusiveSoAFaceTouchingBoxIsContained() {
    assertTrue(BlockBox.of(0, 0, 0, 1, 1, 1).contains(BlockBox.of(0, 0, 0, 1, 1, 1)));
    assertTrue(BlockBox.of(0, 0, 0, 1, 1, 1).contains(BlockBox.of(0, 0, 0, 0.5, 1, 1)));
    assertFalse(BlockBox.of(0, 0, 0, 1, 1, 1).contains(BlockBox.of(0, 0, 0, 1.5, 1, 1)));
  }

  @Test void pointContainmentIsInclusive() {
    BlockBox box = BlockBox.of(0, 0, 0, 1, 1, 1);
    assertTrue(box.contains(0, 0, 0));
    assertTrue(box.contains(1, 1, 1));
    assertTrue(box.contains(0.5, 0.5, 0.5));
    assertFalse(box.contains(1.0001, 0.5, 0.5));
  }

  // ------------------------------------------------------------------
  // Enclose / bounds
  // ------------------------------------------------------------------

  @Test void encloseProducesTheSmallestContainingBox() {
    assertEquals(BlockBox.of(0, 0, 0, 2, 2, 2),
        BlockBox.of(0, 0, 0, 1, 1, 1).enclose(BlockBox.of(1, 1, 1, 2, 2, 2)));
  }

  @Test void encloseIsCommutativeAndIdempotent() {
    BlockBox a = BlockBox.of(0, 0, 0, 1, 1, 1);
    BlockBox b = BlockBox.of(-2, 1, 3, 4, 5, 6);
    assertEquals(a.enclose(b), b.enclose(a));
    assertEquals(a, a.enclose(a));
  }

  @Test void shapeBoundsCoverEveryBoxOfAMultiBoxShape() {
    VoxelShape fence = Shapes.fencePost(true, true, true, true);
    assertEquals(BlockBox.of(0, 0, 0, 1, 1, 1), fence.bounds());
    assertNotEquals(BlockBox.of(0, 0, 0, 1, 1, 1), Shapes.FENCE_POST.bounds());
  }

  // ------------------------------------------------------------------
  // Flip
  // ------------------------------------------------------------------

  @Test void flipMirrorsInsideTheUnitCubeOnExactlyOneAxis() {
    BlockBox low = BlockBox.ofSixteenths(0, 0, 0, 16, 8, 16);
    assertEquals(BlockBox.of(0, 0.5, 0, 1, 1, 1), low.flip(1));
    BlockBox north = BlockBox.ofSixteenths(0, 0, 8, 16, 16, 16);
    assertEquals(BlockBox.of(0, 0, 0, 1, 1, 0.5), north.flip(2));
  }

  @Test void flipIsItsOwnInverse() {
    BlockBox box = BlockBox.ofSixteenths(1, 2, 3, 11, 12, 13);
    for (int axis = 0; axis < 3; axis++) {
      assertEquals(box, box.flip(axis).flip(axis), "axis " + axis);
    }
  }

  @Test void flipRejectsAnAxisIndexOutsideXYZ() {
    assertThrows(IllegalArgumentException.class, () -> BlockBox.FULL.flip(3));
    assertThrows(IllegalArgumentException.class, () -> BlockBox.FULL.flip(-1));
  }

  // ------------------------------------------------------------------
  // VoxelShape: world translation and space guards
  // ------------------------------------------------------------------

  @Test void toWorldAddsIntegerBlockCoordinatesExactly() {
    VoxelShape world = Shapes.SLAB_BOTTOM.toWorld(10, 64, -30);
    assertEquals(BlockBox.of(10, 64, -30, 11, 64.5, -29), world.boxes().getFirst());
    assertTrue(world.isWorldSpace());
  }

  @Test void worldTranslationIsExactForLargeNegativeCoordinates() {
    VoxelShape world = Shapes.snow(3).toWorld(-1, 70, -16);
    // 3 layers = 6/16 = 0.375, and the block origin is exactly what was asked for.
    assertEquals(-1.0, world.boxes().getFirst().minX(), 0.0);
    assertEquals(70.375, world.boxes().getFirst().maxY(), 0.0);
    assertEquals(-15.0, world.boxes().getFirst().maxZ(), 0.0);
  }

  @Test void emptyShapeTranslationStaysEmptyAndSharesTheSingleton() {
    assertSame(VoxelShape.empty(), VoxelShape.empty().toWorld(1, 1, 1));
    assertTrue(VoxelShape.empty().isEmpty());
  }

  @Test void localShapesRejectBoxesOutsideTheUnitCube() {
    assertThrows(IllegalArgumentException.class, () -> VoxelShape.local(BlockBox.of(0, 0, 0, 2, 1, 1)));
    assertThrows(IllegalArgumentException.class, () -> VoxelShape.local(BlockBox.of(-0.5, 0, 0, 1, 1, 1)));
    assertThrows(IllegalArgumentException.class, () -> VoxelShape.local(BlockBox.of(0, 0, 0, 1, 1, 1.5)));
  }

  @Test void faceAndUnionOperationsAreRefusedOnWorldSpaceShapes() {
    VoxelShape world = Shapes.BLOCK.toWorld(0, 0, 0);
    assertThrows(IllegalStateException.class, () -> world.isFaceFull(Direction.UP));
    assertThrows(IllegalStateException.class, () -> world.rotateQuarterTurnCcW());
    assertThrows(IllegalStateException.class, () -> world.localUnion(BlockBox.FULL));
  }

  @Test void unioningShapesFromDifferentSpacesIsRefused() {
    assertThrows(IllegalStateException.class, () -> Shapes.BLOCK.union(Shapes.BLOCK.toWorld(0, 0, 0)));
  }

  // ------------------------------------------------------------------
  // Full-face detection
  // ------------------------------------------------------------------

  @Test void aFullCubeIsFullOnEveryFace() {
    for (Direction direction : Direction.VALUES) {
      assertTrue(Shapes.BLOCK.isFaceFull(direction), direction.name());
    }
  }

  @Test void aBottomSlabIsFullOnTheBottomFaceButNotTheTop() {
    assertTrue(Shapes.SLAB_BOTTOM.isFaceFull(Direction.DOWN));
    assertFalse(Shapes.SLAB_BOTTOM.isFaceFull(Direction.UP));
    assertFalse(Shapes.SLAB_BOTTOM.isFaceFull(Direction.NORTH));
    assertFalse(Shapes.SLAB_BOTTOM.isFaceFull(Direction.SOUTH));
  }

  @Test void aTopSlabIsFullOnTheTopFaceButNotTheBottom() {
    assertTrue(Shapes.SLAB_TOP.isFaceFull(Direction.UP));
    assertFalse(Shapes.SLAB_TOP.isFaceFull(Direction.DOWN));
  }

  @Test void aStraightStairCoversItsWholeUndersideButNoOtherFace() {
    VoxelShape stair = Shapes.stairs(Direction.NORTH, false, false, false);
    // The stair's base step spans the full footprint at y=0, so its underside is
    // a complete face; every other face is only partly covered.
    assertTrue(stair.isFaceFull(Direction.DOWN));
    assertFalse(stair.isFaceFull(Direction.UP));
    assertFalse(stair.isFaceFull(Direction.NORTH));
    assertFalse(stair.isFaceFull(Direction.SOUTH));
    assertFalse(stair.isFaceFull(Direction.WEST));
    assertFalse(stair.isFaceFull(Direction.EAST));
  }

  @Test void anUnconnectedFenceOnlyCoversPartsOfEveryFace() {
    assertFalse(Shapes.FENCE_POST.isFaceFull(Direction.UP));
    assertFalse(Shapes.FENCE_POST.isFaceFull(Direction.NORTH));
    assertFalse(Shapes.FENCE_POST.isFaceFull(Direction.DOWN));
  }

  @Test void aFenceRailDoesNotMakeTheFenceFullOnThatFace() {
    VoxelShape fence = Shapes.fencePost(true, false, false, false);
    assertFalse(fence.isFaceFull(Direction.NORTH), "a 2/16 rail does not cover a whole face");
  }

  // ------------------------------------------------------------------
  // Clipping: contact is not collision
  // ------------------------------------------------------------------

  @Test void clipDoesNotReversePositiveMotionForAnAlreadyOverlappingStart() {
    VoxelShape wall = Shapes.BLOCK.toWorld(1, 0, 0);
    BlockBox player = BlockBox.of(0.5, 0, 0.5, 1.1, 1.8, 1.1);
    // Grim's collideX leaves positive motion unchanged when the start already
    // overlaps the obstacle on the movement axis.
    assertEquals(1.0, wall.clip(Axis.X, player, 1.0), 0.0);
  }

  @Test void clipDoesNotReverseNegativeMotionForAnAlreadyOverlappingStart() {
    VoxelShape floor = Shapes.BLOCK.toWorld(0, -1, 0);
    BlockBox player = BlockBox.of(-0.1, -0.5, 0.2, 0.5, 1.3, 0.8);
    // The start overlaps the floor; a negative move is not turned into a
    // positive correction.
    assertEquals(-1.0, floor.clip(Axis.Y, player, -1.0), 0.0);
  }

  @Test void clipDoesNotMoveABoxThatMerelyTouchesACollisionFace() {
    VoxelShape floor = Shapes.BLOCK.toWorld(0, -1, 0);
    BlockBox player = BlockBox.of(0, 0, 0, 0.6, 1.8, 0.6);
    // A downward move of a full block must stop exactly at the floor top (already at y=0).
    assertEquals(0.0, floor.clip(Axis.Y, player, -1.0), 0.0);
  }

  @Test void clipReturnsTheRequestedAmountWhenNothingIsInTheWay() {
    VoxelShape block = Shapes.BLOCK.toWorld(10, 0, 0);
    BlockBox player = BlockBox.of(0, 0, 0, 0.6, 1.8, 0.6);
    assertEquals(0.5, block.clip(Axis.X, player, 0.5), 0.0);
  }

  @Test void clipIgnoresGapsOnThePerpendicularAxesEvenWhenTheBoundsOverlap() {
    // A collision at x=1..2 but y=5..6 cannot block a player standing at y=0..1.8.
    VoxelShape highBlock = Shapes.BLOCK.toWorld(1, 5, 0);
    BlockBox player = BlockBox.of(0.5, 0, 0.5, 1.1, 1.8, 1.1);
    assertEquals(1.0, highBlock.clip(Axis.X, player, 1.0), 0.0);
  }

  @Test void clipOfZeroIsZeroAndEmptyShapeDoesNotClip() {
    assertEquals(0.0, Shapes.BLOCK.toWorld(0, 0, 0).clip(Axis.X, BlockBox.of(0, 0, 0, 1, 1, 1), 0.0));
    assertEquals(0.4, VoxelShape.empty().clip(Axis.X, BlockBox.of(0, 0, 0, 1, 1, 1), 0.4));
  }

  @Test void clipChoosesTheNearestObstacleAlongTheMovementDirection() {
    VoxelShape near = Shapes.BLOCK.toWorld(1, 0, 0);
    VoxelShape far = Shapes.BLOCK.toWorld(3, 0, 0);
    VoxelShape both = near.union(far);
    BlockBox player = BlockBox.of(0.5, 0, 0.5, 1.1, 1.8, 1.1);
    // The near obstacle overlaps the starting box, so Grim's collision rule ignores
    // that backward correction; the far obstacle is the first forward clip.
    assertEquals(1.9, both.clip(Axis.X, player, 5.0), 1.0e-12);
    // A box that starts before both obstacles stops at the nearer one, 1 - 0.6.
    assertEquals(0.4, both.clip(Axis.X, BlockBox.of(0, 0, 0.5, 0.6, 1.8, 1.1), 5.0), 1.0e-12);
    // The far obstacle alone still stops the same box at 3 - 0.6.
    assertEquals(2.4, far.clip(Axis.X, BlockBox.of(0, 0, 0.5, 0.6, 1.8, 1.1), 5.0), 1.0e-12);
  }

  @Test void clipIsDeterministicAcrossRepeatedCalls() {
    VoxelShape shape = Shapes.stairs(Direction.NORTH, false, false, false).toWorld(0, 0, 0);
    BlockBox box = BlockBox.of(0.25, 0.25, 0.25, 0.75, 1.25, 0.75);
    double first = shape.clip(Axis.Y, box, -0.5);
    for (int repeat = 0; repeat < 20; repeat++) {
      assertEquals(first, shape.clip(Axis.Y, box, -0.5), 0.0);
    }
  }

  // ------------------------------------------------------------------
  // Intersection between shapes
  // ------------------------------------------------------------------

  @Test void shapeIntersectionIsStrictLikeBoxIntersection() {
    VoxelShape block = Shapes.BLOCK.toWorld(0, 0, 0);
    assertTrue(block.intersects(BlockBox.of(0.5, 0.5, 0.5, 1.5, 1.5, 1.5)));
    assertFalse(block.intersects(BlockBox.of(1, 0, 0, 2, 1, 1)));
  }

  @Test void shapeIntersectionWithAnotherShapeFindsAnyOverlappingBox() {
    VoxelShape fence = Shapes.fencePost(true, false, false, false);
    // The north rail spans x=7/16..9/16 at y=12/16..15/16, so a probe inside it hits.
    assertTrue(fence.intersects(BlockBox.of(0.45, 0.8, 0.1, 0.55, 0.85, 0.2)));
    // A probe outside both the post (x<=0.625) and the rail (x<=0.5625) misses.
    assertFalse(fence.intersects(BlockBox.of(0.6, 0.7, 0, 0.7, 0.9, 0.1)));
  }

  // ------------------------------------------------------------------
  // Rotation
  // ------------------------------------------------------------------

  @Test void fourQuarterTurnsReturnTheOriginalShape() {
    VoxelShape shape = Shapes.stairs(Direction.NORTH, false, false, false);
    assertEquals(shape, shape.rotateQuarterTurnCcW().rotateQuarterTurnCcW()
        .rotateQuarterTurnCcW().rotateQuarterTurnCcW());
  }

  @Test void rotateToFaceSurvivesEveryHorizontalPairAndPreservesVolume() {
    VoxelShape base = Shapes.ladder(Direction.NORTH);
    for (Direction from : new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
      for (Direction to : new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
        VoxelShape rotated = base.rotateToFace(from, to);
        assertEquals(base.volume(), rotated.volume(), 0.0, from + "->" + to);
        // Rotating a shape that already faces `from` to face `to` then back returns it.
        assertEquals(base, rotated.rotateToFace(to, from), from + "->" + to);
      }
    }
  }

  @Test void rotateToFaceRefusesVerticalDirections() {
    assertThrows(IllegalArgumentException.class,
        () -> Shapes.ladder(Direction.NORTH).rotateToFace(Direction.NORTH, Direction.UP));
    assertThrows(IllegalArgumentException.class,
        () -> Shapes.ladder(Direction.NORTH).rotateToFace(Direction.DOWN, Direction.NORTH));
  }

  // ------------------------------------------------------------------
  // Determinism and value semantics
  // ------------------------------------------------------------------

  @Test void shapesAreValueEqualAndIterateInDeclarationOrder() {
    List<BlockBox> boxes = List.of(BlockBox.of(0, 0, 0, 1, 0.5, 1), BlockBox.of(0, 0.5, 0, 1, 1, 1));
    VoxelShape first = VoxelShape.local(boxes);
    VoxelShape second = VoxelShape.local(boxes);
    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());
    assertEquals(boxes, first.boxes());
  }

  @Test void boxOrderIsPreservedEvenWhenGeometricallyIdentical() {
    // Declaration order is part of the value, so results never depend on hashing.
    VoxelShape a = VoxelShape.local(BlockBox.of(0, 0, 0, 0.5, 1, 1), BlockBox.of(0.5, 0, 0, 1, 1, 1));
    VoxelShape b = VoxelShape.local(BlockBox.of(0.5, 0, 0, 1, 1, 1), BlockBox.of(0, 0, 0, 0.5, 1, 1));
    assertNotEquals(a, b);
    assertEquals(a.sortedForDiagnostics(), b.sortedForDiagnostics());
  }

  @Test void boxListsAreImmutable() {
    VoxelShape shape = Shapes.BLOCK;
    assertThrows(UnsupportedOperationException.class, () -> shape.boxes().add(BlockBox.FULL));
  }

  @Test void volumeSumsEveryBoxOfAMultiBoxShape() {
    VoxelShape fence = Shapes.fencePost(true, true, true, true);
    double post = (4.0 / 16.0) * (4.0 / 16.0);
    double rails = 4 * (2.0 / 16.0) * (6.0 / 16.0) * (3.0 / 16.0);
    assertEquals(post + rails, fence.volume(), 1.0e-12);
  }

  @Test void emptyShapeHasNoVolumeAndNoBounds() {
    assertEquals(0.0, VoxelShape.empty().volume(), 0.0);
    assertNull(VoxelShape.empty().bounds());
    assertFalse(VoxelShape.empty().isFullCube());
  }

  @Test void fullCubeDetectionRequiresExactlyOneUnitBox() {
    assertTrue(VoxelShape.local(BlockBox.FULL).isFullCube());
    assertFalse(VoxelShape.local(BlockBox.of(0, 0, 0, 1, 1, 1), BlockBox.of(0, 0, 0, 1, 1, 1)).isFullCube());
    assertFalse(Shapes.SLAB_BOTTOM.isFullCube());
  }

  @Test void worldBoxBuilderProducesWorldSpaceBoxes() {
    VoxelShape world = VoxelShape.worldBoxes(List.of(BlockBox.of(1, 2, 3, 2, 3, 4)));
    assertTrue(world.isWorldSpace());
    assertEquals(BlockBox.of(1, 2, 3, 2, 3, 4), world.boxes().getFirst());
  }

  @Test void directionStepsMatchTheirNames() {
    assertEquals(-1, Direction.NORTH.stepZ());
    assertEquals(1, Direction.SOUTH.stepZ());
    assertEquals(-1, Direction.WEST.stepX());
    assertEquals(1, Direction.EAST.stepX());
    assertEquals(-1, Direction.DOWN.stepY());
    assertEquals(1, Direction.UP.stepY());
    assertEquals(Axis.Y, Direction.UP.axis());
    assertEquals(Axis.Z, Direction.NORTH.axis());
    assertEquals(Axis.X, Direction.EAST.axis());
  }

  @Test void directionValuesListIsTheCanonicalSix() {
    assertEquals(6, Direction.VALUES.size());
    assertEquals(Direction.DOWN, Direction.VALUES.getFirst());
    assertEquals(Direction.EAST, Direction.VALUES.getLast());
  }
}
