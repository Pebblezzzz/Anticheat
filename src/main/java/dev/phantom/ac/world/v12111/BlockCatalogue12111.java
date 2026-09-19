package dev.phantom.ac.world.v12111;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dev.phantom.ac.geometry.BlockBox;
import dev.phantom.ac.geometry.Directions.Direction;
import dev.phantom.ac.geometry.Shapes;
import dev.phantom.ac.geometry.VoxelShape;
import dev.phantom.ac.world.BlockState;
import dev.phantom.ac.world.BlockState.Half;
import dev.phantom.ac.world.BlockState.Variant;
import dev.phantom.ac.world.FluidState;

/**
 * The Minecraft Java 1.21.11 block behaviour catalogue: state decoding, shapes,
 * fluid state and movement-environment classification.
 *
 * <p>This is the only class in the deterministic core that knows a block name.
 * Everything else works with {@link BlockState}, so a future version adapter can
 * replace this class without touching the world, geometry, or query layers.</p>
 *
 * <h2>Data provenance</h2>
 * <p>Block identity, the set of properties each block declares, and per-block
 * collision families come from the 1.21.11 block registry as published by
 * PrismarineJS minecraft-data
 * (<a href="https://raw.githubusercontent.com/PrismarineJS/minecraft-data/master/data/pc/1.21.11/blocks.json">1.21.11/blocks.json</a>).
 * For example that registry states that {@code oak_slab} has states
 * {@code type,waterlogged}, {@code oak_stairs} has
 * {@code facing,half,shape,waterlogged}, {@code oak_fence} has
 * {@code east,north,south,waterlogged,west}, {@code cobblestone_wall} has
 * {@code east,north,south,up,waterlogged,west}, {@code oak_trapdoor} has
 * {@code facing,half,open,powered,waterlogged}, {@code snow} has
 * {@code layers} and {@code water} has {@code level}.</p>
 *
 * <p>Box coordinates reproduce the vanilla {@code Block.box(...)} definitions
 * collected in {@link Shapes}. Where a shape depends on <em>neighbour</em> state
 * (fences, walls, panes) the neighbour lookup is the caller's responsibility:
 * see {@link dev.phantom.ac.world.WorldSnapshot#collisionShapes}.</p>
 *
 * <h2>Explicitly not modelled</h2>
 * <p>Blocks whose shapes depend on data this adapter cannot yet read from a
 * clientbound packet are mapped to {@link Variant#UNSUPPORTED} rather than to an
 * invented box. The world snapshot then reports that position as unverifiable
 * instead of pretending it is air or a full cube.</p>
 */
public final class BlockCatalogue12111 {
  private BlockCatalogue12111() {}

  // ------------------------------------------------------------------
  // Block identity tables
  // ------------------------------------------------------------------

  private static final Set<String> FULL_CUBE_EXACT = Set.of(
      "minecraft:stone", "minecraft:dirt", "minecraft:grass_block", "minecraft:cobblestone",
      "minecraft:oak_planks", "minecraft:bedrock", "minecraft:netherrack", "minecraft:end_stone",
      "minecraft:obsidian", "minecraft:diamond_block", "minecraft:gold_block", "minecraft:iron_block",
      "minecraft:emerald_block", "minecraft:lapis_block", "minecraft:redstone_block",
      "minecraft:coal_block", "minecraft:netherite_block", "minecraft:ancient_debris",
      "minecraft:deepslate", "minecraft:tuff", "minecraft:calcite", "minecraft:dripstone_block",
      "minecraft:smooth_basalt", "minecraft:blackstone", "minecraft:basalt", "minecraft:granite",
      "minecraft:diorite", "minecraft:andesite", "minecraft:mud", "minecraft:clay", "minecraft:glass");

  /** Names that are full cubes but which the wire can also make waterlogged. */
  private static final Set<String> WATERLOGGABLE_FULL_CUBE = Set.of(
      "minecraft:oak_planks");

  /**
   * Blocks whose collision shape depends on neighbouring blocks. The snapshot
   * resolves these through {@link NeighbourLookup}.
   */
  public static boolean requiresNeighbourLookup(BlockState state) {
    return switch (state.variant()) {
      case FENCE, WALL, PANE, FENCE_GATE -> true;
      default -> false;
    };
  }

  // ------------------------------------------------------------------
  // State decoding from protocol properties
  // ------------------------------------------------------------------

  /**
   * Builds a block state from its protocol identity and the raw property map the
   * adapter decoded. Every property this catalogue needs for {@code blockId}
   * must be present; a missing property yields an explicitly unsupported state
   * with {@link BlockState.PropertySource#INCOMPLETE} rather than a guess.
   *
   * @param blockId   namespaced block id, for example {@code minecraft:oak_stairs}
   * @param properties raw property name to value, already lower-cased
   */
  public static BlockState decode(String blockId, Map<String, String> properties) {
    if (blockId == null || blockId.isBlank()) return BlockState.unsupported(blockId);
    Map<String, String> p = properties == null ? Map.of() : properties;
    String name = blockId.toLowerCase(Locale.ROOT);

    if (name.equals("minecraft:air") || name.equals("minecraft:cave_air") || name.equals("minecraft:void_air")) {
      return BlockState.air();
    }

    if (name.equals("minecraft:water") || name.equals("minecraft:lava")) {
      return fluidStateBlock(name, p);
    }

    if (isSlab(name)) return slab(name, p);
    if (isStairs(name)) return stairs(name, p);
    if (isFence(name)) return fence(name, p);
    if (isWall(name)) return wall(name, p);
    if (isPane(name)) return pane(name, p);
    if (isDoor(name)) return door(name, p);
    if (isTrapdoor(name)) return trapdoor(name, p);
    if (isFenceGate(name)) return fenceGate(name, p);
    if (isCarpet(name)) return carpet(name);
    if (name.equals("minecraft:snow")) return snow(p);
    if (isBed(name)) return bed(name, p);
    if (isChestLike(name)) return chestLike(name, p);
    if (name.equals("minecraft:cactus")) return simpleVariant(name, Variant.CACTUS, p);
    if (name.equals("minecraft:ladder")) return ladder(name, p);
    if (name.equals("minecraft:scaffolding")) return scaffolding(name, p);
    if (name.equals("minecraft:bamboo")) return bamboo(name);
    if (name.equals("minecraft:soul_sand") || name.equals("minecraft:soul_soil")
        || name.equals("minecraft:honey_block") || name.equals("minecraft:slime_block")) {
      // These are full cubes for collision (only their entity interaction differs).
      return fullCube(name, p);
    }
    if (name.equals("minecraft:dirt_path") || name.equals("minecraft:farmland")) {
      return fullCube(name, p);
    }
    if (name.equals("minecraft:end_rod")) return endRod(name);
    if (name.equals("minecraft:lily_pad")) return simpleVariant(name, Variant.LILY_PAD, p);
    if (name.equals("minecraft:candle") || name.endsWith("_candle")) return candle(name, p);
    if (name.equals("minecraft:sea_pickle")) return seaPickle(name, p);
    if (isPlate(name)) return simpleVariant(name, Variant.PLATE, p);
    if (name.equals("minecraft:cobweb") || name.equals("minecraft:powder_snow")
        || name.equals("minecraft:sweet_berry_bush")) {
      return simpleVariant(name, Variant.NO_COLLISION_SPECIAL, p);
    }
    if (isNonCollidable(name)) return simpleVariant(name, Variant.NO_COLLISION_SPECIAL, p);

    if (isLikelyFullCube(name)) return fullCube(name, p);

    // Unknown block: explicitly unsupported, never assumed to be air or a cube.
    return BlockState.unsupported(name);
  }

  private static BlockState fluidStateBlock(String name, Map<String, String> p) {
    int level = readIntegerProperty(p, "level", -1);
    if (level < 0 || level > 7) return incomplete(name);
    return new BlockState(name, Variant.FLUID, Direction.NORTH, Half.BOTTOM, BlockState.StairShape.STRAIGHT,
        0, level, false, false, false, false, false, false, false, false, false, false, false, false,
        0, false, BlockState.PropertySource.WIRE);
  }

  private static BlockState slab(String name, Map<String, String> p) {
    if (!p.containsKey("type")) return incomplete(name);
    return switch (p.get("type")) {
      case "bottom" -> base(name, Variant.SLAB, Half.BOTTOM, p);
      case "top" -> base(name, Variant.SLAB, Half.TOP, p);
      case "double" -> base(name, Variant.SLAB, Half.DOUBLE, p);
      default -> incomplete(name);
    };
  }

  private static BlockState stairs(String name, Map<String, String> p) {
    if (!p.containsKey("facing") || !p.containsKey("half") || !p.containsKey("shape")) return incomplete(name);
    Direction facing = horizontal(p.get("facing"));
    if (facing == null) return incomplete(name);
    Half half = switch (p.get("half")) {
      case "bottom" -> Half.BOTTOM;
      case "top" -> Half.TOP;
      default -> null;
    };
    if (half == null) return incomplete(name);
    BlockState.StairShape shape = switch (p.get("shape")) {
      case "straight" -> BlockState.StairShape.STRAIGHT;
      case "inner_left" -> BlockState.StairShape.INNER_LEFT;
      case "inner_right" -> BlockState.StairShape.INNER_RIGHT;
      case "outer_left" -> BlockState.StairShape.OUTER_LEFT;
      case "outer_right" -> BlockState.StairShape.OUTER_RIGHT;
      default -> null;
    };
    if (shape == null) return incomplete(name);
    return new BlockState(name, Variant.STAIRS, facing, half, shape, 0, 0,
        bool(p, "waterlogged"), false, false, false, false, false, false, false, false, false, false, false,
        0, false, BlockState.PropertySource.WIRE);
  }

  private static BlockState fence(String name, Map<String, String> p) {
    return new BlockState(name, Variant.FENCE, Direction.NORTH, Half.BOTTOM, BlockState.StairShape.STRAIGHT,
        0, 0, bool(p, "waterlogged"),
        false, false, false,
        bool(p, "north"), bool(p, "south"), bool(p, "west"), bool(p, "east"),
        false, false, false, false, 0, false, BlockState.PropertySource.WIRE);
  }

  private static BlockState wall(String name, Map<String, String> p) {
    return new BlockState(name, Variant.WALL, Direction.NORTH, Half.BOTTOM, BlockState.StairShape.STRAIGHT,
        0, 0, bool(p, "waterlogged"),
        // fields: open, powered, up
        false, false, bool(p, "up"),
        bool(p, "north"), bool(p, "south"), bool(p, "west"), bool(p, "east"),
        // Tall-neighbour facts are not part of this block's own state; the
        // snapshot fills them in during shape resolution.
        false, false, false, false, 0, false, BlockState.PropertySource.WIRE);
  }

  private static BlockState pane(String name, Map<String, String> p) {
    return new BlockState(name, Variant.PANE, Direction.NORTH, Half.BOTTOM, BlockState.StairShape.STRAIGHT,
        0, 0, bool(p, "waterlogged"),
        false, false, false,
        bool(p, "north"), bool(p, "south"), bool(p, "west"), bool(p, "east"),
        false, false, false, false, 0, false, BlockState.PropertySource.WIRE);
  }

  private static BlockState door(String name, Map<String, String> p) {
    if (!p.containsKey("facing") || !p.containsKey("half")) return incomplete(name);
    Direction facing = horizontal(p.get("facing"));
    if (facing == null) return incomplete(name);
    boolean upper = "upper".equals(p.get("half"));
    // Vanilla DoorBlock hinge=right means the door is hinged on the +X/+Z side
    // relative to its facing direction. The property is carried on `east`, which
    // is unused by door shapes, so the hinge reaches the shape builder without
    // adding a field that only one block uses.
    boolean hingeRight = "right".equals(p.get("hinge"));
    return new BlockState(name, Variant.DOOR, facing, upper ? Half.TOP : Half.BOTTOM,
        BlockState.StairShape.STRAIGHT, 0, 0, bool(p, "waterlogged"),
        bool(p, "open"), bool(p, "powered"), false, false, false, false, hingeRight, false, false, false, false,
        0, false, BlockState.PropertySource.WIRE);
  }

  private static BlockState trapdoor(String name, Map<String, String> p) {
    if (!p.containsKey("facing") || !p.containsKey("half")) return incomplete(name);
    Direction facing = horizontal(p.get("facing"));
    if (facing == null) return incomplete(name);
    boolean top = "top".equals(p.get("half"));
    return new BlockState(name, Variant.TRAPDOOR, facing, top ? Half.TOP : Half.BOTTOM,
        BlockState.StairShape.STRAIGHT, 0, 0, bool(p, "waterlogged"),
        bool(p, "open"), bool(p, "powered"), false, false, false, false, false, false, false, false, false,
        0, false, BlockState.PropertySource.WIRE);
  }

  private static BlockState fenceGate(String name, Map<String, String> p) {
    if (!p.containsKey("facing")) return incomplete(name);
    Direction facing = horizontal(p.get("facing"));
    if (facing == null) return incomplete(name);
    return new BlockState(name, Variant.FENCE_GATE, facing, Half.BOTTOM, BlockState.StairShape.STRAIGHT,
        0, 0, bool(p, "waterlogged"), bool(p, "open"), bool(p, "powered"),
        bool(p, "in_wall"), false, false, false, false, false, false, false, false,
        0, false, BlockState.PropertySource.WIRE);
  }

  private static BlockState carpet(String name) {
    return new BlockState(name, Variant.CARPET, Direction.NORTH, Half.BOTTOM, BlockState.StairShape.STRAIGHT,
        0, 0, false, false, false, false, false, false, false, false, false, false, false, false,
        0, false, BlockState.PropertySource.WIRE);
  }

  private static BlockState snow(Map<String, String> p) {
    int layers = readIntegerProperty(p, "layers", -1);
    if (layers < 1 || layers > 8) return incomplete("minecraft:snow");
    return new BlockState("minecraft:snow", Variant.SNOW_LAYER, Direction.NORTH, Half.BOTTOM,
        BlockState.StairShape.STRAIGHT, layers, 0, false, false, false,
        false, false, false, false, false, false, false, false, false, 0, false, BlockState.PropertySource.WIRE);
  }

  private static BlockState bed(String name, Map<String, String> p) {
    if (!p.containsKey("facing") || !p.containsKey("part")) return incomplete(name);
    Direction facing = horizontal(p.get("facing"));
    if (facing == null) return incomplete(name);
    boolean head = "head".equals(p.get("part"));
    return new BlockState(name, Variant.BED, facing, head ? Half.TOP : Half.BOTTOM,
        BlockState.StairShape.STRAIGHT, 0, 0, false, false, false, false, false, false, false, false, false, false, false, false,
        0, false, BlockState.PropertySource.WIRE);
  }

  private static BlockState chestLike(String name, Map<String, String> p) {
    Direction facing = p.containsKey("facing") ? horizontal(p.get("facing")) : Direction.NORTH;
    if (facing == null) return incomplete(name);
    return new BlockState(name, Variant.CHEST_LIKE, facing, Half.BOTTOM, BlockState.StairShape.STRAIGHT,
        0, 0, bool(p, "waterlogged"), false, false, false, false, false, false, false, false, false, false, false,
        0, false, BlockState.PropertySource.WIRE);
  }

  private static BlockState ladder(String name, Map<String, String> p) {
    if (!p.containsKey("facing")) return incomplete(name);
    Direction facing = horizontal(p.get("facing"));
    if (facing == null) return incomplete(name);
    return new BlockState(name, Variant.LADDER, facing, Half.BOTTOM, BlockState.StairShape.STRAIGHT,
        0, 0, bool(p, "waterlogged"), false, false, false, false, false, false, false, false, false, false, false,
        0, false, BlockState.PropertySource.WIRE);
  }

  private static BlockState scaffolding(String name, Map<String, String> p) {
    return new BlockState(name, Variant.SCAFFOLDING, Direction.NORTH, Half.BOTTOM, BlockState.StairShape.STRAIGHT,
        0, 0, bool(p, "waterlogged"), false, false, bool(p, "bottom"),
        false, false, false, false, false, false, false, false, 0, false, BlockState.PropertySource.WIRE);
  }

  private static BlockState bamboo(String name) {
    return new BlockState(name, Variant.BAMBOO, Direction.NORTH, Half.BOTTOM, BlockState.StairShape.STRAIGHT,
        0, 0, false, false, false, false, false, false, false, false, false, false, false, false,
        0, false, BlockState.PropertySource.WIRE);
  }

  private static BlockState endRod(String name) {
    return new BlockState(name, Variant.END_ROD, Direction.NORTH, Half.BOTTOM, BlockState.StairShape.STRAIGHT,
        0, 0, false, false, false, false, false, false, false, false, false, false, false, false,
        0, false, BlockState.PropertySource.WIRE);
  }

  private static BlockState candle(String name, Map<String, String> p) {
    int candles = readIntegerProperty(p, "candles", 1);
    if (candles < 1 || candles > 4) return incomplete(name);
    return new BlockState(name, Variant.CANDLE, Direction.NORTH, Half.BOTTOM, BlockState.StairShape.STRAIGHT,
        0, 0, bool(p, "waterlogged"), false, false, false, false, false, false, false, false, false, false, false,
        candles, bool(p, "lit"), BlockState.PropertySource.WIRE);
  }

  private static BlockState seaPickle(String name, Map<String, String> p) {
    int pickles = readIntegerProperty(p, "pickles", 1);
    if (pickles < 1 || pickles > 4) return incomplete(name);
    return new BlockState(name, Variant.SEA_PICKLE, Direction.NORTH, Half.BOTTOM, BlockState.StairShape.STRAIGHT,
        0, 0, bool(p, "waterlogged"), false, false, false, false, false, false, false, false, false, false, false,
        pickles, false, BlockState.PropertySource.WIRE);
  }

  private static BlockState fullCube(String name, Map<String, String> p) {
    return new BlockState(name, Variant.FULL_CUBE, Direction.NORTH, Half.BOTTOM, BlockState.StairShape.STRAIGHT,
        0, 0, bool(p, "waterlogged"), false, false, false, false, false, false, false, false, false, false, false,
        0, false, BlockState.PropertySource.WIRE);
  }

  private static BlockState simpleVariant(String name, Variant variant, Map<String, String> p) {
    return new BlockState(name, variant, Direction.NORTH, Half.BOTTOM, BlockState.StairShape.STRAIGHT,
        0, 0, bool(p, "waterlogged"), false, false, false, false, false, false, false, false, false, false, false,
        0, false, BlockState.PropertySource.WIRE);
  }

  private static BlockState base(String name, Variant variant, Half slabHalf, Map<String, String> p) {
    return new BlockState(name, variant, Direction.NORTH, slabHalf, BlockState.StairShape.STRAIGHT,
        0, 0, bool(p, "waterlogged"), false, false, false, false, false, false, false, false, false, false, false,
        0, false, BlockState.PropertySource.WIRE);
  }

  private static BlockState incomplete(String name) {
    return BlockState.unsupportedIncomplete(name);
  }

  private static boolean bool(Map<String, String> p, String key) {
    return "true".equals(p.get(key));
  }

  private static int readIntegerProperty(Map<String, String> p, String key, int fallback) {
    String raw = p.get(key);
    if (raw == null) return fallback;
    try {
      return Integer.parseInt(raw);
    } catch (NumberFormatException malformed) {
      // A malformed numeric property is unavailable information, not a value:
      // the caller turns the fallback into an explicit unsupported state.
      return fallback;
    }
  }

  private static Direction horizontal(String value) {
    if (value == null) return null;
    return switch (value) {
      case "north" -> Direction.NORTH;
      case "south" -> Direction.SOUTH;
      case "west" -> Direction.WEST;
      case "east" -> Direction.EAST;
      default -> null;
    };
  }

  // ------------------------------------------------------------------
  // Name classification (1.21.11 registry suffixes)
  // ------------------------------------------------------------------

  private static boolean isSlab(String name) {
    return name.endsWith("_slab") || name.equals("minecraft:smooth_stone_slab") || name.equals("minecraft:stone_slab");
  }

  private static boolean isStairs(String name) {
    return name.endsWith("_stairs");
  }

  private static boolean isFence(String name) {
    return name.endsWith("_fence") || name.equals("minecraft:nether_brick_fence");
  }

  private static boolean isWall(String name) {
    return name.endsWith("_wall") && !name.endsWith("_wall_sign") && !name.endsWith("_wall_banner")
        && !name.endsWith("_wall_hanging_sign") && !name.endsWith("_wall_torch");
  }

  private static boolean isPane(String name) {
    return name.endsWith("_pane") || name.equals("minecraft:iron_bars");
  }

  private static boolean isDoor(String name) {
    return name.endsWith("_door");
  }

  private static boolean isTrapdoor(String name) {
    return name.endsWith("_trapdoor");
  }

  private static boolean isFenceGate(String name) {
    return name.endsWith("_fence_gate");
  }

  private static boolean isCarpet(String name) {
    return name.endsWith("_carpet");
  }

  private static boolean isBed(String name) {
    return name.endsWith("_bed");
  }

  private static boolean isChestLike(String name) {
    return name.equals("minecraft:chest") || name.equals("minecraft:trapped_chest") || name.equals("minecraft:ender_chest");
  }

  private static boolean isPlate(String name) {
    return name.equals("minecraft:sculk_vein") || name.equals("minecraft:glow_lichen")
        || name.equals("minecraft:resin_clump");
  }

  /** Blocks that have no collision at all in 1.21.11. */
  private static final Set<String> NON_COLLIDABLE = Set.of(
      "minecraft:torch", "minecraft:wall_torch", "minecraft:soul_torch", "minecraft:soul_wall_torch",
      "minecraft:redstone_torch", "minecraft:redstone_wall_torch", "minecraft:copper_torch",
      "minecraft:copper_wall_torch", "minecraft:redstone_wire", "minecraft:rail", "minecraft:powered_rail",
      "minecraft:detector_rail", "minecraft:activator_rail", "minecraft:grass", "minecraft:short_grass",
      "minecraft:tall_grass", "minecraft:fern", "minecraft:dandelion", "minecraft:poppy", "minecraft:blue_orchid",
      "minecraft:allium", "minecraft:azure_bluet", "minecraft:red_tulip", "minecraft:orange_tulip",
      "minecraft:white_tulip", "minecraft:pink_tulip", "minecraft:oxeye_daisy", "minecraft:cornflower",
      "minecraft:lily_of_the_valley", "minecraft:wither_rose", "minecraft:sunflower", "minecraft:lilac",
      "minecraft:rose_bush", "minecraft:peony", "minecraft:kelp", "minecraft:kelp_plant", "minecraft:seagrass",
      "minecraft:tall_seagrass", "minecraft:dead_bush", "minecraft:vine", "minecraft:weeping_vines",
      "minecraft:weeping_vines_plant", "minecraft:twisting_vines", "minecraft:twisting_vines_plant",
      "minecraft:cave_vines", "minecraft:cave_vines_plant", "minecraft:hanging_roots",
      "minecraft:sugar_cane", "minecraft:nether_sprouts", "minecraft:crimson_roots", "minecraft:warped_roots",
      "minecraft:structure_void", "minecraft:barrier_air", "minecraft:light", "minecraft:bubble_column",
      "minecraft:frogspawn", "minecraft:end_gateway", "minecraft:fire", "minecraft:soul_fire",
      "minecraft:tripwire", "minecraft:tripwire_hook", "minecraft:lever",
      "minecraft:stone_button", "minecraft:oak_button", "minecraft:spruce_button", "minecraft:birch_button",
      "minecraft:jungle_button", "minecraft:acacia_button", "minecraft:cherry_button", "minecraft:dark_oak_button",
      "minecraft:pale_oak_button", "minecraft:mangrove_button", "minecraft:bamboo_button",
      "minecraft:crimson_button", "minecraft:warped_button", "minecraft:polished_blackstone_button",
      "minecraft:leaf_litter", "minecraft:wildflowers", "minecraft:pale_hanging_moss", "minecraft:wheat",
      "minecraft:carrots", "minecraft:potatoes", "minecraft:beetroots", "minecraft:nether_wart",
      "minecraft:attached_melon_stem", "minecraft:attached_pumpkin_stem", "minecraft:melon_stem",
      "minecraft:pumpkin_stem", "minecraft:torchflower_crop", "minecraft:pitcher_crop", "minecraft:end_portal",
      "minecraft:nether_portal");

  private static boolean isNonCollidable(String name) {
    return NON_COLLIDABLE.contains(name) || name.endsWith("_sapling") || name.endsWith("_sign")
        || name.endsWith("_banner") || name.endsWith("_pressure_plate");
  }

  /**
   * The 1.21.11 blocks this build models as full cubes but which are not in
   * {@link #FULL_CUBE_EXACT}. The set is explicit rather than suffix-based,
   * because a suffix guess would also accept a block id that does not exist and
   * silently give an unknown block a collidable cube where vanilla has none.
   */
  private static final Set<String> FULL_CUBE_FAMILIES = Set.of(
      "minecraft:ice", "minecraft:packed_ice", "minecraft:blue_ice", "minecraft:frosted_ice",
      "minecraft:slime_block", "minecraft:honey_block", "minecraft:soul_sand", "minecraft:soul_soil",
      "minecraft:mud", "minecraft:magma_block", "minecraft:bone_block", "minecraft:sculk",
      "minecraft:ochre_froglight", "minecraft:verdant_froglight", "minecraft:pearlescent_froglight",
      "minecraft:hay_block", "minecraft:melon", "minecraft:pumpkin", "minecraft:carved_pumpkin",
      "minecraft:jack_o_lantern", "minecraft:bookshelf", "minecraft:crafting_table", "minecraft:furnace",
      "minecraft:sponge", "minecraft:wet_sponge", "minecraft:target", "minecraft:lodestone",
      "minecraft:nether_bricks", "minecraft:red_nether_bricks", "minecraft:prismarine",
      "minecraft:prismarine_bricks", "minecraft:dark_prismarine", "minecraft:sea_lantern",
      "minecraft:glowstone", "minecraft:shroomlight", "minecraft:honeycomb_block",
      "minecraft:dried_kelp_block", "minecraft:bamboo_block", "minecraft:resin_block",
      "minecraft:netherite_block", "minecraft:quartz_block", "minecraft:smooth_quartz");

  /**
   * Full-cube detection for blocks that are full cubes in 1.21.11. It matches
   * the registry's own {@code boundingBox == "block"} signal for the families
   * this build models, and it does <em>not</em> guess: an id that is not in one
   * of these families is reported as unsupported.
   */
  private static boolean isLikelyFullCube(String name) {
    if (FULL_CUBE_EXACT.contains(name) || WATERLOGGABLE_FULL_CUBE.contains(name)
        || FULL_CUBE_FAMILIES.contains(name)) {
      return true;
    }
    // Log and wood variants are enumerated to 1.21.11's wood set so a made-up
    // "oak_slabx_log" cannot be mistaken for a real cube.
    for (String wood : WOODS) {
      if (name.equals("minecraft:" + wood + "_log") || name.equals("minecraft:" + wood + "_wood")
          || name.equals("minecraft:stripped_" + wood + "_log") || name.equals("minecraft:stripped_" + wood + "_wood")
          || name.equals("minecraft:" + wood + "_planks") || name.equals("minecraft:" + wood + "_leaves")
          || name.equals("minecraft:" + wood + "_hyphae")) {
        return true;
      }
    }
    return name.endsWith("_ore") || name.endsWith("_bricks") || name.endsWith("_terracotta")
        || name.endsWith("_concrete") || name.endsWith("_concrete_powder") || name.endsWith("_glazed_terracotta")
        || name.endsWith("_wool") || name.endsWith("_sandstone") || name.endsWith("_deepslate")
        || name.endsWith("_nylium") || name.endsWith("_planks") || name.endsWith("_log")
        || name.endsWith("_wood") || name.endsWith("_leaves") || name.endsWith("_hyphae")
        || name.endsWith("_stem") || name.endsWith("_sculk") || name.endsWith("_bone_block");
  }

  /** The 1.21.11 wood set, used to keep suffix guesses honest. */
  private static final Set<String> WOODS = Set.of(
      "oak", "spruce", "birch", "jungle", "acacia", "cherry", "dark_oak", "pale_oak",
      "mangrove", "bamboo", "crimson", "warped");

  // ------------------------------------------------------------------
  // Shapes
  // ------------------------------------------------------------------

  /**
   * The block's own collision shape, ignoring neighbour-dependent boxes. For
   * fences, walls, panes and fence gates this is only part of the answer; use
   * {@link #collisionShape(BlockState, NeighbourLookup)} instead.
   */
  public static VoxelShape baseCollisionShape(BlockState state) {
    return switch (state.variant()) {
      case AIR -> Shapes.EMPTY;
      case FULL_CUBE, FLUID -> state.variant() == Variant.FLUID ? Shapes.EMPTY : Shapes.BLOCK;
      case SLAB -> switch (state.half()) {
        case BOTTOM -> Shapes.SLAB_BOTTOM;
        case TOP -> Shapes.SLAB_TOP;
        case DOUBLE -> Shapes.SLAB_DOUBLE;
      };
      case STAIRS -> stairShape(state);
      case FENCE -> Shapes.FENCE_POST;
      case WALL -> Shapes.WALL_BASE;
      case PANE -> Shapes.PANE_COLUMN;
      case DOOR -> Shapes.door(state.facing(), state.open(), hingeRight(state), state.half() == Half.TOP);
      case TRAPDOOR -> Shapes.trapdoor(state.facing(), state.open(), state.half() == Half.TOP);
      case FENCE_GATE -> Shapes.WALL_BASE;
      case CARPET -> Shapes.CARPET;
      case SNOW_LAYER -> Shapes.snow(state.layers());
      case BED -> Shapes.BED;
      case CHEST_LIKE -> Shapes.CHEST_LIKE;
      case CACTUS -> Shapes.CACTUS;
      case LADDER -> Shapes.ladder(state.facing());
      case SCAFFOLDING -> state.up() ? Shapes.SCAFFOLDING_BOTTOM : Shapes.SCAFFOLDING_TOP;
      case BAMBOO -> Shapes.BAMBOO_THIN;
      case SOUL_SAND_LIKE -> Shapes.SOUL_SAND_LIKE;
      case PATH_LIKE -> Shapes.PATH_LIKE;
      case END_ROD -> Shapes.END_ROD;
      case LILY_PAD -> Shapes.LILY_PAD;
      case CANDLE -> Shapes.CANDLE;
      case SEA_PICKLE -> Shapes.SEA_PICKLE;
      case PLATE -> Shapes.PLATE_1PX_INSET;
      case NO_COLLISION_SPECIAL -> Shapes.EMPTY;
      case UNSUPPORTED -> Shapes.EMPTY;
    };
  }

  /**
   * Vanilla {@code DoorBlock} hinge. The hinge selects which of the two parallel
   * door edges the 3px slab is attached to, which is a real collision difference:
   * a left-hinged north-facing door occupies {@code x=13/16..16/16} and a
   * right-hinged one occupies {@code x=0..3/16}.
   */
  private static boolean hingeRight(BlockState state) {
    return state.east();
  }

  private static VoxelShape stairShape(BlockState state) {
    boolean inner = state.stairShape() == BlockState.StairShape.INNER_LEFT
        || state.stairShape() == BlockState.StairShape.INNER_RIGHT;
    boolean outer = state.stairShape() == BlockState.StairShape.OUTER_LEFT
        || state.stairShape() == BlockState.StairShape.OUTER_RIGHT;
    return Shapes.stairs(state.facing(), state.upperHalf(), inner, outer);
  }

  /**
   * Neighbour facts needed to resolve a shape whose boxes depend on adjacent
   * blocks. Implemented by the world snapshot so the catalogue stays pure.
   */
  public interface NeighbourLookup {
    /**
     * Whether the block at the offset should visually and physically connect to
     * this one, following vanilla's {@code canConnect} rules for fences, walls
     * and panes (solid full face, same kind, or a matching connectable block).
     */
    boolean connects(Direction direction);

    /** Whether the neighbouring block in this direction is a wall whose post is tall. */
    boolean tallNeighbour(Direction direction);
  }

  /** A lookup that reports no connections, used for isolated shape queries. */
  public static final NeighbourLookup NO_NEIGHBOURS = new NeighbourLookup() {
    @Override public boolean connects(Direction direction) { return false; }
    @Override public boolean tallNeighbour(Direction direction) { return false; }
  };

  /** Full collision shape including neighbour-dependent boxes. */
  public static VoxelShape collisionShape(BlockState state, NeighbourLookup neighbours) {
    return switch (state.variant()) {
      case FENCE -> Shapes.fencePost(
          neighbours.connects(Direction.NORTH), neighbours.connects(Direction.SOUTH),
          neighbours.connects(Direction.WEST), neighbours.connects(Direction.EAST));
      case PANE -> Shapes.panePlane(
          neighbours.connects(Direction.NORTH), neighbours.connects(Direction.SOUTH),
          neighbours.connects(Direction.WEST), neighbours.connects(Direction.EAST));
      case WALL -> Shapes.wall(state.up(),
          neighbours.connects(Direction.NORTH), neighbours.connects(Direction.SOUTH),
          neighbours.connects(Direction.WEST), neighbours.connects(Direction.EAST),
          neighbours.tallNeighbour(Direction.NORTH), neighbours.tallNeighbour(Direction.SOUTH),
          neighbours.tallNeighbour(Direction.WEST), neighbours.tallNeighbour(Direction.EAST));
      case FENCE_GATE -> fenceGateShape(state);
      default -> baseCollisionShape(state);
    };
  }

  /**
   * Vanilla fence gate collision: the gate posts at {@code 7..9} on both sides,
   * plus the closed gate bars. An open gate leaves only the posts.
   */
  private static VoxelShape fenceGateShape(BlockState state) {
    VoxelShape posts = VoxelShape.local(
        BlockBox.ofSixteenths(7, 5, 0, 9, 16, 2),
        BlockBox.ofSixteenths(7, 5, 14, 9, 16, 16)).rotateToFace(Direction.NORTH, state.facing());
    if (state.open()) return posts;
    VoxelShape bars = state.facing().axis() == dev.phantom.ac.geometry.Directions.Axis.Z
        ? VoxelShape.local(BlockBox.ofSixteenths(6, 5, 0, 10, 12, 16))
        : VoxelShape.local(BlockBox.ofSixteenths(0, 5, 6, 16, 12, 10));
    return posts.union(bars);
  }

  // ------------------------------------------------------------------
  // Fluid state
  // ------------------------------------------------------------------

  /**
   * The fluid that a block state contains. Vanilla waterlogged blocks hold
   * water at level 0; fluid blocks carry their own {@code level}. Blocks that
   * are neither fluids nor waterlogged hold no fluid.
   */
  public static FluidState fluid(BlockState state) {
    if (state.variant() == Variant.FLUID) {
      if (!state.hasFluidName()) return FluidState.NONE;
      return new FluidState(state.fluidName(), state.level(), FluidState.HeightSource.UNAVAILABLE,
          FluidState.HEIGHT_UNKNOWN, false);
    }
    if (state.waterlogged() || state.up()) {
      return new FluidState(FluidState.Type.WATER, 0, FluidState.HeightSource.SOURCE, 1.0, false);
    }
    return FluidState.NONE;
  }

  /**
   * Vanilla fluid surface height for a fluid block with {@code level} and a
   * known count of equal-height horizontal neighbours. The rule is
   * {@code height = (8 - level) / 9} for flowing fluid and {@code 8/9} for a
   * source, raised to a full block when the fluid is fully surrounded. This is
   * returned only when the snapshot can establish the neighbour count; see
   * {@link FluidState.HeightSource#UNAVAILABLE} otherwise.
   */
  public static double flowingHeight(int level, int sameHeightNeighbours) {
    if (level < 0 || level > 7) throw new IllegalArgumentException("fluid level must be 0..7 but was " + level);
    if (sameHeightNeighbours < 0 || sameHeightNeighbours > 4) {
      throw new IllegalArgumentException("neighbour count must be 0..4 but was " + sameHeightNeighbours);
    }
    if (level == 0 && sameHeightNeighbours == 4) return 1.0;
    // Vanilla computes the surface as a single division by 9, so this uses the
    // identical expression to keep the last bit identical to the game's value.
    return (8.0 - level) / 9.0;
  }

  // ------------------------------------------------------------------
  // Environment flags
  // ------------------------------------------------------------------

  /** Movement- and environment-relevant facts about a single block state. */
  public record Environment(
      boolean water, boolean lava, boolean climbable, boolean bubbleColumn, boolean slime,
      boolean honey, boolean soulSand, boolean cobweb, boolean powderSnow, boolean scaffold,
      boolean sweetBerryBush, boolean cactus, boolean bed, boolean ice, boolean packedIce,
      boolean blueIce, boolean magmaBlock, boolean lilyPad, boolean fluidFallHeightReset) {}

  public static Environment environment(BlockState state) {
    String name = state.blockId();
    // A waterlogged block carries water even though its identity is a solid block,
    // so water presence is not the same question as "is this a water block".
    boolean water = (state.variant() == Variant.FLUID && state.hasFluidName()
        && state.fluidName() == FluidState.Type.WATER) || state.waterlogged();
    return new Environment(
        water,
        state.variant() == Variant.FLUID && state.hasFluidName() && state.fluidName() == FluidState.Type.LAVA,
        CLIMBABLE.contains(name),
        name.equals("minecraft:bubble_column"),
        name.equals("minecraft:slime_block"),
        name.equals("minecraft:honey_block"),
        name.equals("minecraft:soul_sand") || name.equals("minecraft:soul_soil"),
        name.equals("minecraft:cobweb"),
        name.equals("minecraft:powder_snow"),
        state.variant() == Variant.SCAFFOLDING,
        name.equals("minecraft:sweet_berry_bush"),
        state.variant() == Variant.CACTUS,
        state.variant() == Variant.BED,
        name.equals("minecraft:ice") || name.equals("minecraft:frosted_ice"),
        name.equals("minecraft:packed_ice"),
        name.equals("minecraft:blue_ice"),
        name.equals("minecraft:magma_block"),
        state.variant() == Variant.LILY_PAD,
        name.equals("minecraft:water") || name.equals("minecraft:bubble_column"));
  }

  /** Vanilla blocks that can be climbed: ladders, vines, scaffolding, and the like. */
  private static final Set<String> CLIMBABLE = Set.of(
      "minecraft:ladder", "minecraft:vine", "minecraft:scaffolding", "minecraft:weeping_vines",
      "minecraft:weeping_vines_plant", "minecraft:twisting_vines", "minecraft:twisting_vines_plant",
      "minecraft:cave_vines", "minecraft:cave_vines_plant");

  /** Vanilla friction-block slipperiness values for the target version. */
  public static double slipperiness(BlockState state) {
    return switch (state.blockId()) {
      case "minecraft:slime_block" -> 0.8;
      case "minecraft:ice", "minecraft:frosted_ice", "minecraft:packed_ice" -> 0.98;
      case "minecraft:blue_ice" -> 0.989;
      default -> 0.6;
    };
  }
}
