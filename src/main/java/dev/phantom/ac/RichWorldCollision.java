package dev.phantom.ac;

import dev.phantom.ac.geometry.BlockBox;
import dev.phantom.ac.geometry.Directions.Axis;
import dev.phantom.ac.geometry.VoxelShape;
import dev.phantom.ac.world.WorldSnapshot;

import java.io.Serializable;
import java.util.Objects;

import static dev.phantom.ac.Maths.*;

/** Collision resolver over the exact 1.21.11 world-snapshot layer. */
public final class RichWorldCollision {
    private RichWorldCollision() {}

    public record Result(Vec3 displacement, boolean collidedX, boolean collidedY, boolean collidedZ,
                         boolean stepAttempted, boolean stepSucceeded, boolean uncertain,
                         String diagnostic) implements Serializable {}

    public static Result resolve(WorldSnapshot world, Aabb start, Vec3 requested, double stepHeight) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(requested, "requested");
        if (!Double.isFinite(stepHeight) || stepHeight < 0) throw new IllegalArgumentException("stepHeight must be finite and non-negative");

        BlockBox startBox = box(start);
        BlockBox sweptBox = startBox.enclose(startBox.move(requested.x(), requested.y(), requested.z()));
        if (world.hasUnknownOrUnsupported(sweptBox)) {
            return new Result(Vec3.ZERO, false, false, false, false, false, true,
                    "rich world snapshot does not fully cover swept movement volume");
        }

        AxisResult vertical = clipAxis(world, startBox, Axis.Y, requested.y());
        BlockBox afterY = startBox.move(0, vertical.amount(), 0);
        AxisResult horizontalX = clipAxis(world, afterY, Axis.X, requested.x());
        BlockBox afterX = afterY.move(horizontalX.amount(), 0, 0);
        AxisResult horizontalZ = clipAxis(world, afterX, Axis.Z, requested.z());

        Vec3 direct = new Vec3(horizontalX.amount(), vertical.amount(), horizontalZ.amount());
        boolean collided = vertical.collided() || horizontalX.collided() || horizontalZ.collided();
        if (stepHeight <= 0 || requested.y() > 0 || !(horizontalX.collided() || horizontalZ.collided())) {
            return new Result(direct, horizontalX.collided(), vertical.collided(), horizontalZ.collided(), false, false, false,
                    collided ? "rich voxel collision" : "rich voxel movement");
        }

        // Step candidate: raise, resolve horizontal axes against exact voxel shapes,
        // then resolve the return movement. The candidate is accepted only when it
        // advances farther horizontally than the direct collision-resolved path.
        Aabb raised = start.move(new Vec3(0, stepHeight, 0));
        BlockBox raisedBox = box(raised);
        BlockBox raisedSweep = raisedBox.enclose(raisedBox.move(requested.x(), requested.y(), requested.z()));
        if (world.hasUnknownOrUnsupported(raisedSweep)) {
            return new Result(direct, horizontalX.collided(), vertical.collided(), horizontalZ.collided(), true, false, true,
                    "step candidate crosses unknown or unsupported world coverage");
        }
        AxisResult sx = clipAxis(world, raisedBox, Axis.X, requested.x());
        BlockBox bx = raisedBox.move(sx.amount(), 0, 0);
        AxisResult sz = clipAxis(world, bx, Axis.Z, requested.z());
        BlockBox steppedHorizontal = bx.move(0, 0, sz.amount());
        AxisResult sy = clipAxis(world, steppedHorizontal, Axis.Y, -stepHeight + requested.y());
        Vec3 stepped = new Vec3(sx.amount(), stepHeight + sy.amount(), sz.amount());
        double directHorizontal = horizontalX.amount() * horizontalX.amount() + horizontalZ.amount() * horizontalZ.amount();
        double steppedHorizontalDistance = stepped.x() * stepped.x() + stepped.z() * stepped.z();
        boolean success = steppedHorizontalDistance > directHorizontal + 1.0E-12 && stepped.y() >= 0;
        if (!success) return new Result(direct, horizontalX.collided(), vertical.collided(), horizontalZ.collided(), true, false, false,
                "rich voxel step candidate rejected; direct path retained");
        return new Result(stepped, sx.collided(), sy.collided(), sz.collided(), true, true, false,
                "rich voxel step candidate accepted");
    }

    private record AxisResult(double amount, boolean collided) {}

    private static AxisResult clipAxis(WorldSnapshot world, BlockBox moving, Axis axis, double requested) {
        if (requested == 0.0) return new AxisResult(0.0, false);
        double result = requested;
        BlockBox sweep = moving.move(axis == Axis.X ? requested : 0.0, axis == Axis.Y ? requested : 0.0, axis == Axis.Z ? requested : 0.0);
        int minX = (int) Math.floor(Math.min(moving.minX(), sweep.minX()));
        int maxX = (int) Math.floor(Math.max(moving.maxX(), sweep.maxX()));
        int minY = (int) Math.floor(Math.min(moving.minY(), sweep.minY()));
        int maxY = (int) Math.floor(Math.max(moving.maxY(), sweep.maxY()));
        int minZ = (int) Math.floor(Math.min(moving.minZ(), sweep.minZ()));
        int maxZ = (int) Math.floor(Math.max(moving.maxZ(), sweep.maxZ()));
        for (int x = minX; x <= maxX; x++) for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) {
            VoxelShape shape = world.collisionShapeAt(x, y, z);
            if (shape.isEmpty()) continue;
            double clipped = shape.clip(axis, moving, result);
            if (requested > 0) result = Math.min(result, clipped);
            else result = Math.max(result, clipped);
        }
        return new AxisResult(result, Math.abs(result - requested) > 1.0E-12);
    }

    private static BlockBox box(Aabb box) { return new BlockBox(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()); }
}
