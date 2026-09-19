package dev.phantom.ac.world;

import java.io.Serializable;
import java.util.Objects;
import java.util.Map;
import java.util.TreeMap;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;

import dev.phantom.ac.geometry.Directions.Direction;

/**
 * The block-state properties that materially affect collision geometry or
 * movement environment for Minecraft Java 1.21.11.
 *
 * <p>The property set is deliberately the union of the states the target
 * version exposes on blocks the movement simulator must understand, taken from
 * the 1.21.11 block registry (for example {@code oak_slab} declares
 * {@code type} and {@code waterlogged}; {@code oak_stairs} declares
 * {@code facing}, {@code half}, {@code shape} and {@code waterlogged};
 * {@code oak_fence} declares {@code east/north/south/west} and
 * {@code waterlogged}; {@code snow} declares {@code layers}).</p>
 *
 * <p>A state value that this type does not model is never guessed: the caller
 * must fall back to {@link BlockState#unsupported}, which the world snapshot
 * reports as explicitly unverifiable rather than silently treating it as air.</p>
 */
public record BlockState(
    String blockId,
    Variant variant,
    Direction facing,
    Half half,
    StairShape stairShape,
    int layers,
    int level,
    boolean waterlogged,
    boolean open,
    boolean powered,
    boolean up,
    boolean north,
    boolean south,
    boolean west,
    boolean east,
    boolean northTall,
    boolean southTall,
    boolean westTall,
    boolean eastTall,
    int candles,
    boolean poweredState,
    PropertySource provenance,
    Map<String,String> properties) implements Serializable {

  public BlockState {
    Objects.requireNonNull(blockId, "blockId");
    Objects.requireNonNull(variant, "variant");
    Objects.requireNonNull(provenance, "provenance");
    Objects.requireNonNull(properties, "properties");
    properties = Map.copyOf(new TreeMap<>(properties));
    if (layers < 0 || layers > 8) throw new IllegalArgumentException("snow/leaf layers must be 0..8 but was " + layers);
    // Vanilla fluid level is a 0..7 property ({@code FlowingFluid.LEVEL}); 8 is not
    // a valid value in any 1.21.11 fluid state.
    if (level < 0 || level > 7) throw new IllegalArgumentException("fluid level must be 0..7 but was " + level);
    if (candles < 0 || candles > 4) throw new IllegalArgumentException("candles must be 0..4 but was " + candles);
  }

  /** Which shape or behaviour family a block belongs to. One variant per vanilla block class. */
  public enum Variant {
    AIR,
    FULL_CUBE,
    SLAB,
    STAIRS,
    FENCE,
    WALL,
    PANE,
    DOOR,
    TRAPDOOR,
    FENCE_GATE,
    CARPET,
    SNOW_LAYER,
    BED,
    CHEST_LIKE,
    CACTUS,
    LADDER,
    SCAFFOLDING,
    BAMBOO,
    SOUL_SAND_LIKE,
    PATH_LIKE,
    END_ROD,
    LILY_PAD,
    CANDLE,
    SEA_PICKLE,
    PLATE,
    /** A fluid block: no collision, but environment state is present. */
    FLUID,
    /** Vanilla {@code Blocks.COBWEB}, {@code POWDER_SNOW}, {@code SWEET_BERRY_BUSH}. */
    NO_COLLISION_SPECIAL,
    /** A state received from the wire that this adapter cannot map to a verified shape. */
    UNSUPPORTED,
    /** A fully validated 1.21.11 registry state whose exact collision comes from the generated catalogue. */
    CATALOGUE
  }

  /**
   * Vanilla {@code SlabType} plus the door/trapdoor/stair half. A slab has three
   * values, a two-block-tall block has two, and both are stored in this one
   * field so {@link BlockState} has a single notion of "which half".
   *
   * <p>{@link #BOTTOM} and {@link #TOP} used together are the vanilla
   * {@code bottom}/{@code top} values for stairs, doors and trapdoors;
   * {@link #DOUBLE} exists only for slabs.</p>
   */
  public enum Half { BOTTOM, TOP, DOUBLE }

  /** Vanilla {@code StairsShape}. */
  public enum StairShape { STRAIGHT, INNER_LEFT, INNER_RIGHT, OUTER_LEFT, OUTER_RIGHT }

  /** How the state's values were obtained, so unavailable information stays visible. */
  public enum PropertySource {
    /** Decoded from a clientbound chunk/block packet, with every modelled property present. */
    WIRE,
    /** A property required to build the shape was absent from the packet. Shape is unverifiable. */
    INCOMPLETE,
    /** A test or replay fixture that declares its own state. */
    FIXTURE
  }


  /** Compatibility constructor for pre-Phase-4 state fixtures. */
  public BlockState(
      String blockId, Variant variant, Direction facing, Half half, StairShape stairShape,
      int layers, int level, boolean waterlogged, boolean open, boolean powered, boolean up,
      boolean north, boolean south, boolean west, boolean east, boolean northTall,
      boolean southTall, boolean westTall, boolean eastTall, int candles, boolean poweredState,
      PropertySource provenance) {
    this(blockId, variant, facing, half, stairShape, layers, level, waterlogged, open, powered, up,
        north, south, west, east, northTall, southTall, westTall, eastTall, candles, poweredState,
        provenance, Map.of());
  }

  public static final String AIR_ID = "minecraft:air";

  private static final BlockState AIR = new BlockState(
      AIR_ID, Variant.AIR, Direction.NORTH, Half.BOTTOM, StairShape.STRAIGHT, 0, 0,
      false, false, false, false, false, false, false, false, false, false, false, false,
      0, false, PropertySource.WIRE);

  /** Vanilla {@code Blocks.AIR} default state. */
  public static BlockState air() {
    return AIR;
  }

  /**
   * An explicitly unverifiable state. The world snapshot must surface this as
   * unknown information, never as air and never as a full cube.
   */
  public static BlockState unsupported(String blockId) {
    return new BlockState(blockId == null ? "minecraft:unknown" : blockId, Variant.UNSUPPORTED,
        Direction.NORTH, Half.BOTTOM, StairShape.STRAIGHT, 0, 0,
        false, false, false, false, false, false, false, false, false, false, false, false,
        0, false, PropertySource.INCOMPLETE);
  }

  /** Declaration helper used only by the version catalogue; keeps the record's arity manageable. */
  public static BlockState unsupportedIncomplete(String blockId) {
    return new BlockState(blockId, Variant.UNSUPPORTED, Direction.NORTH, Half.BOTTOM,
        StairShape.STRAIGHT, 0, 0, false, false, false, false, false, false, false, false,
        false, false, false, false, 0, false, PropertySource.INCOMPLETE);
  }

  /**
   * A named-property builder, so callers never have to pass a long positional
   * argument list where a misplaced value would silently become a different
   * property instead of a compile error.
   */
  public static final class Builder {
    private final String blockId;
    private Variant variant = Variant.FULL_CUBE;
    private Direction facing = Direction.NORTH;
    private Half half = Half.BOTTOM;
    private StairShape stairShape = StairShape.STRAIGHT;
    private int layers;
    private int level;
    private boolean waterlogged;
    private boolean open;
    private boolean powered;
    private boolean up;
    private boolean north;
    private boolean south;
    private boolean west;
    private boolean east;
    private boolean northTall;
    private boolean southTall;
    private boolean westTall;
    private boolean eastTall;
    private int candles;
    private boolean poweredState;
    private PropertySource provenance = PropertySource.FIXTURE;
    private final Set<String> explicitlySet = new HashSet<>();

    public Builder(String blockId) {
      this.blockId = Objects.requireNonNull(blockId, "blockId");
    }

    public Builder variant(Variant value) { this.variant = value; return this; }
    public Builder facing(Direction value) { this.facing = value; explicitlySet.add("facing"); return this; }
    public Builder half(Half value) { this.half = value; explicitlySet.add("half"); return this; }
    public Builder stairShape(StairShape value) { this.stairShape = value; explicitlySet.add("shape"); return this; }
    public Builder layers(int value) { this.layers = value; explicitlySet.add("layers"); return this; }
    public Builder level(int value) { this.level = value; explicitlySet.add("level"); return this; }
    public Builder waterlogged(boolean value) { this.waterlogged = value; explicitlySet.add("waterlogged"); return this; }
    public Builder open(boolean value) { this.open = value; explicitlySet.add("open"); return this; }
    public Builder powered(boolean value) { this.powered = value; explicitlySet.add("powered"); return this; }
    public Builder up(boolean value) { this.up = value; explicitlySet.add("up"); return this; }
    public Builder north(boolean value) { this.north = value; explicitlySet.add("north"); return this; }
    public Builder south(boolean value) { this.south = value; explicitlySet.add("south"); return this; }
    public Builder west(boolean value) { this.west = value; explicitlySet.add("west"); return this; }
    public Builder east(boolean value) { this.east = value; explicitlySet.add("east"); return this; }
    public Builder northTall(boolean value) { this.northTall = value; explicitlySet.add("northTall"); return this; }
    public Builder southTall(boolean value) { this.southTall = value; explicitlySet.add("southTall"); return this; }
    public Builder westTall(boolean value) { this.westTall = value; explicitlySet.add("westTall"); return this; }
    public Builder eastTall(boolean value) { this.eastTall = value; explicitlySet.add("eastTall"); return this; }
    public Builder candles(int value) { this.candles = value; explicitlySet.add("candles"); return this; }
    public Builder poweredState(boolean value) { this.poweredState = value; explicitlySet.add("poweredState"); return this; }
    public Builder provenance(PropertySource value) { this.provenance = value; return this; }

    public BlockState build() {
      Map<String,String> properties = new TreeMap<>();
      switch (variant) {
        case FENCE, WALL, PANE -> {
          if (explicitlySet.contains("north")) properties.put("north", Boolean.toString(north));
          if (explicitlySet.contains("south")) properties.put("south", Boolean.toString(south));
          if (explicitlySet.contains("west")) properties.put("west", Boolean.toString(west));
          if (explicitlySet.contains("east")) properties.put("east", Boolean.toString(east));
          if (explicitlySet.contains("waterlogged")) properties.put("waterlogged", Boolean.toString(waterlogged));
        }
        case SLAB -> {
          if (explicitlySet.contains("half"))
            properties.put("type", half == Half.DOUBLE ? "double" : half == Half.TOP ? "top" : "bottom");
          if (explicitlySet.contains("waterlogged")) properties.put("waterlogged", Boolean.toString(waterlogged));
        }
        case STAIRS -> {
          if (explicitlySet.contains("facing")) properties.put("facing", facing.name().toLowerCase(Locale.ROOT));
          if (explicitlySet.contains("half")) properties.put("half", half == Half.TOP ? "top" : "bottom");
          if (explicitlySet.contains("shape")) properties.put("shape", stairShape.name().toLowerCase(Locale.ROOT));
          if (explicitlySet.contains("waterlogged")) properties.put("waterlogged", Boolean.toString(waterlogged));
        }
        default -> {}
      }
      return new BlockState(blockId, variant, facing, half, stairShape, layers, level, waterlogged,
          open, powered, up, north, south, west, east, northTall, southTall, westTall, eastTall,
          candles, poweredState, provenance, properties);
    }
  }

  /**
   * Returns a copy with the complete wire-visible property map retained.
   * Property names and values are canonical lower-case strings.
   */
  public BlockState withProperties(Map<String,String> values) {
    return new BlockState(blockId, variant, facing, half, stairShape, layers, level, waterlogged,
        open, powered, up, north, south, west, east, northTall, southTall, westTall, eastTall,
        candles, poweredState, provenance, values == null ? Map.of() : values);
  }

  /**
   * Canonical Bukkit/Paper BlockData spelling. Unspecified vanilla properties are intentionally
   * omitted; the server fills them using the block's own default state.
   */
  public String bukkitDataString() {
    if (properties.isEmpty()) return blockId;
    StringBuilder out = new StringBuilder(blockId).append('[');
    boolean first = true;
    for (var entry : new TreeMap<>(properties).entrySet()) {
      if (!first) out.append(',');
      first = false;
      out.append(entry.getKey()).append('=').append(entry.getValue());
    }
    return out.append(']').toString();
  }

  public static Builder builder(String blockId) {
    return new Builder(blockId);
  }

  public boolean isAir() {
    return variant == Variant.AIR;
  }

  public boolean isUnsupported() {
    return variant == Variant.UNSUPPORTED;
  }

  /** True when this state is one of the target version's fluid blocks and names a fluid. */
  public boolean hasFluidName() {
    return variant == Variant.FLUID && (blockId.equals("minecraft:water") || blockId.equals("minecraft:lava"));
  }

  /**
   * The fluid this state is made of. Only valid when {@link #hasFluidName()}.
   * Waterlogged non-fluid blocks report water through {@link dev.phantom.ac.world.FluidState}
   * instead, because their fluid is a derived property rather than their identity.
   */
  public dev.phantom.ac.world.FluidState.Type fluidName() {
    if (blockId.equals("minecraft:water")) return dev.phantom.ac.world.FluidState.Type.WATER;
    if (blockId.equals("minecraft:lava")) return dev.phantom.ac.world.FluidState.Type.LAVA;
    throw new IllegalStateException("state is not a named fluid block: " + blockId);
  }

  /** True when the state carries water as its fluid, either as identity or by waterlogging. */
  public boolean carriesWater() {
    return (variant == Variant.FLUID && blockId.equals("minecraft:water")) || waterlogged;
  }

  /** True for a stairs/door/trapdoor state occupying the upper half of its two blocks. */
  public boolean upperHalf() {
    return half == Half.TOP;
  }
}
