package dev.phantom.ac.paper;

import dev.phantom.ac.geometry.BlockBox;
import dev.phantom.ac.geometry.VoxelShape;
import dev.phantom.ac.world.BlockState;
import dev.phantom.ac.world.WorldSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges lossless client block states to Paper's native BlockData collision implementation.
 *
 * <p>Only the Bukkit main thread may warm this cache. Validation threads perform immutable
 * lookups only. Neighbour-dependent fence/wall/pane/gate geometry remains owned by the
 * compensated world resolver.</p>
 */
public final class PaperVanillaCollision {
  private PaperVanillaCollision() {}

  private static final ConcurrentHashMap<String, VoxelShape> SHAPES = new ConcurrentHashMap<>();
  private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

  public static void warm(World world, Collection<BlockState> states) {
    Objects.requireNonNull(world, "world");
    Objects.requireNonNull(states, "states");
    for (BlockState state : states) warm(world, state);
  }

  public static void warm(World world, BlockState state) {
    if (state == null || state.isUnsupported() || isNeighbourDependent(state)) return;
    String key = state.bukkitDataString();
    if (SHAPES.containsKey(key) || FAILED.contains(key)) return;
    try {
      BlockData data = Bukkit.createBlockData(key);
      org.bukkit.util.VoxelShape nativeShape =
          data.getCollisionShape(new Location(world, 0.0, 0.0, 0.0));
      List<BlockBox> boxes = new ArrayList<>();
      for (org.bukkit.util.BoundingBox box : nativeShape.getBoundingBoxes()) {
        boxes.add(new BlockBox(box.getMinX(), box.getMinY(), box.getMinZ(),
            box.getMaxX(), box.getMaxY(), box.getMaxZ()));
      }
      SHAPES.putIfAbsent(key, VoxelShape.local(boxes));
    } catch (RuntimeException failure) {
      FAILED.add(key);
    }
  }

  public static Optional<VoxelShape> resolve(BlockState state, int x, int y, int z) {
    if (state == null || state.isUnsupported() || isNeighbourDependent(state)) {
      return Optional.empty();
    }
    VoxelShape local = SHAPES.get(state.bukkitDataString());
    return local == null ? Optional.empty() : Optional.of(local.toWorld(x, y, z));
  }

  public static int cachedShapeCount() {
    return SHAPES.size();
  }

  private static boolean isNeighbourDependent(BlockState state) {
    return switch (state.variant()) {
      case FENCE, WALL, PANE, FENCE_GATE -> true;
      default -> false;
    };
  }
}
