package dev.phantom.ac;

import java.io.Serializable; import java.util.*; import static dev.phantom.ac.Maths.*;
public final class World {
  private World() {}
  /**
   * Legacy shape-key enumeration retained for replay compatibility.
   *
   * <p>New code must use {@link dev.phantom.ac.world.BlockState} and
   * {@link dev.phantom.ac.world.WorldSnapshot}, which carry the real 1.21.11
   * block identity and property set. This enum exists because the Phase 0-3
   * replay codec and test fixtures address block states by ordinal, and
   * changing that tag layout would invalidate already-recorded captures.</p>
   */
  public enum Block {
    AIR, FULL, ICE, SLAB_BOTTOM, SLAB_TOP,
    STAIRS_NORTH, STAIRS_SOUTH, STAIRS_EAST, STAIRS_WEST,
    CARPET, SNOW_LAYER_1, SNOW_LAYER_2, SNOW_LAYER_3, SNOW_LAYER_4,
    SNOW_LAYER_5, SNOW_LAYER_6, SNOW_LAYER_7, SNOW_LAYER_8, FENCE, WATER, LADDER,
    /** The client has not received the containing chunk. Never simulate through it. */
    UNKNOWN,
    /** The client received this state, but this version adapter has no verified shape for it. */
    UNSUPPORTED
  }
  public record Pos(int x,int y,int z) implements Serializable {
    public dev.phantom.ac.world.Pos toWorldPos() { return new dev.phantom.ac.world.Pos(x,y,z); }
  }
  public record Chunk(int x,int z) implements Serializable {
    public static Chunk containing(int blockX, int blockZ) { return new Chunk(Math.floorDiv(blockX,16),Math.floorDiv(blockZ,16)); }
    public dev.phantom.ac.world.Chunk toWorldChunk() { return new dev.phantom.ac.world.Chunk(x,z); }
  }
  public record Change(long visibleFromClientTick, Pos position, Block block) implements Serializable {}
  public record Snapshot(Map<Pos,Block> blocks, Set<Chunk> visibleChunks) implements Serializable {
    public Snapshot {
      blocks=Map.copyOf(blocks); visibleChunks=Set.copyOf(visibleChunks);
      if(blocks.values().stream().anyMatch(block -> block==Block.UNKNOWN)) throw new IllegalArgumentException("UNKNOWN is a coverage result, not a stored block state");
    }
    /** Compatibility constructor: chunks holding supplied states are known to the client. */
    public Snapshot(Map<Pos,Block> blocks) { this(blocks, blocks.keySet().stream().map(pos -> Chunk.containing(pos.x(),pos.z())).collect(java.util.stream.Collectors.toSet())); }
    public static Snapshot emptyVisibleChunks(Collection<Chunk> chunks) { return new Snapshot(Map.of(),Set.copyOf(chunks)); }
    public Block blockAt(int x,int y,int z) {
      if(!visibleChunks.contains(Chunk.containing(x,z))) return Block.UNKNOWN;
      return blocks.getOrDefault(new Pos(x,y,z),Block.AIR);
    }
    public boolean supports(Aabb query) {
      for(int x=(int)Math.floor(query.minX());x<=Math.floor(query.maxX());x++) for(int y=(int)Math.floor(query.minY());y<=Math.floor(query.maxY());y++) for(int z=(int)Math.floor(query.minZ());z<=Math.floor(query.maxZ());z++) {
        Block block=blockAt(x,y,z); if(block==Block.UNKNOWN||block==Block.UNSUPPORTED) return false;
      }
      return true;
    }
    public boolean hasUnsupported(Aabb query) {
      for(int x=(int)Math.floor(query.minX());x<=Math.floor(query.maxX());x++) for(int y=(int)Math.floor(query.minY());y<=Math.floor(query.maxY());y++) for(int z=(int)Math.floor(query.minZ());z<=Math.floor(query.maxZ());z++) {
        Block block=blockAt(x,y,z); if(block==Block.WATER||block==Block.LADDER||block==Block.UNKNOWN||block==Block.UNSUPPORTED) return true;
      }
      return false;
    }
    public List<Aabb> collisions(Aabb query) { List<Aabb> out=new ArrayList<>(); for(int x=(int)Math.floor(query.minX());x<=Math.floor(query.maxX());x++) for(int y=(int)Math.floor(query.minY());y<=Math.floor(query.maxY());y++) for(int z=(int)Math.floor(query.minZ());z<=Math.floor(query.maxZ());z++) for(Aabb box:BlockShapes.shapes(blockAt(x,y,z),x,y,z)) if(box.intersects(query))out.add(box); return out; }
    public boolean hasUnsupportedAt(Vec3 p) { return hasUnsupported(Aabb.playerAt(p)); }
  }

  /**
   * Legacy shape catalogue retained for the Phase 0-3 API surface.
   *
   * <p>New code must use {@link dev.phantom.ac.world.v12111.BlockCatalogue12111},
   * which carries the complete 1.21.11 shape set with neighbour-dependent
   * geometry. The shapes below were corrected during Phase 4: the previous
   * ladder shape was a zero-width box and the fence shape used a wrong height,
   * so both were replaced with the vanilla values from the new catalogue. They
   * now mirror the legacy keys only.</p>
   */
  public static final class BlockShapes {
    private BlockShapes() {}
    public static List<Aabb> shapes(Block block, int x, int y, int z) {
      return switch (block) {
        case FULL, ICE -> List.of(box(x,y,z,1,1,1));
        case SLAB_BOTTOM -> List.of(box(x,y,z,1,.5,1));
        case SLAB_TOP -> List.of(box(x,y+.5,z,1,.5,1));
        case CARPET -> List.of(box(x,y,z,1,.0625,1));
        case SNOW_LAYER_1 -> List.of(snow(x,y,z,1));
        case SNOW_LAYER_2 -> List.of(snow(x,y,z,2));
        case SNOW_LAYER_3 -> List.of(snow(x,y,z,3));
        case SNOW_LAYER_4 -> List.of(snow(x,y,z,4));
        case SNOW_LAYER_5 -> List.of(snow(x,y,z,5));
        case SNOW_LAYER_6 -> List.of(snow(x,y,z,6));
        case SNOW_LAYER_7 -> List.of(snow(x,y,z,7));
        case SNOW_LAYER_8 -> List.of(snow(x,y,z,8));
        // Vanilla fence post: Block.box(6,0,6,10,16,10). Previous value 0.375/1.5 was wrong.
        case FENCE -> List.of(box(x+6/16.0,y,z+6/16.0,4/16.0,1,4/16.0));
        // Vanilla straight north stair: full-width base plus the upper half on the north side.
        case STAIRS_NORTH -> stairs(x,y,z,0,null);
        case STAIRS_SOUTH -> stairs(x,y,z,8/16.0,null);
        case STAIRS_WEST -> eastWestStairs(x,y,z,0,null);
        case STAIRS_EAST -> eastWestStairs(x,y,z,0,null);
        // Vanilla ladder: Block.box(0,0,15,16,16,16), a 1px-thick plate. Previous value was empty.
        case LADDER -> List.of(box(x,y,z+15/16.0,1,1,1/16.0));
        case AIR, WATER, UNKNOWN, UNSUPPORTED -> List.of();
      };
    }
    /**
     * Vanilla straight stair, facing north by default: the full-width lower step
     * plus the upper half on the side the stair faces.
     */
    private static List<Aabb> stairs(int x,int y,int z,double upperZ,Object ignored) { return List.of(box(x,y,z,1,.5,1),box(x,y+.5,z+upperZ,1,.5,.5)); }
    private static List<Aabb> eastWestStairs(int x,int y,int z,double upperX,Object ignored) { return List.of(box(x,y,z,1,.5,1),box(x+upperX,y+.5,z,.5,.5,1)); }
    private static Aabb snow(int x,int y,int z,int layers) { return box(x,y,z,1,layers/8.0,1); }
    private static Aabb box(double x,double y,double z,double width,double height,double depth) { return new Aabb(x,y,z,x+width,y+height,z+depth); }
  }

  // ------------------------------------------------------------------
  // Bridge to the Phase 4 world model
  // ------------------------------------------------------------------

  /**
   * Converts a legacy key-based snapshot to the Phase 4 world model, mapping
   * each legacy key onto the real 1.21.11 block state it stands for.
   *
   * <p>A legacy {@code UNKNOWN} key is not a block state and cannot be converted:
   * it is coverage information, and the legacy snapshot already reports it
   * through {@code visibleChunks}. A legacy {@code UNSUPPORTED} key becomes an
   * explicitly unsupported state so the new model reports it as unverifiable
   * rather than as air.</p>
   */
  public static dev.phantom.ac.world.WorldSnapshot toWorldModel(Snapshot legacy) {
    var builder = dev.phantom.ac.world.WorldSnapshot.builder(Contracts.TARGET_VERSION);
    for (Chunk chunk : legacy.visibleChunks()) builder.loadChunk(chunk.x(), chunk.z());
    for (Map.Entry<Pos, Block> entry : legacy.blocks().entrySet()) {
      Pos key = entry.getKey();
      Block block = entry.getValue();
      if (block == Block.UNKNOWN) {
        throw new IllegalArgumentException("legacy UNKNOWN is coverage information, not a block state at " + key);
      }
      if (block == Block.UNSUPPORTED) {
        builder.setUnsupportedBlock(key.x(), key.y(), key.z(), "minecraft:legacy_unsupported");
        continue;
      }
      builder.setBlock(key.x(), key.y(), key.z(), legacyBlockState(block));
    }
    return builder.build();
  }

  /** The 1.21.11 block state a legacy shape key represents. */
  public static dev.phantom.ac.world.BlockState legacyBlockState(Block block) {
    return switch (block) {
      case AIR -> dev.phantom.ac.world.BlockState.air();
      case FULL -> dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone", Map.of());
      case ICE -> dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:ice", Map.of());
      case SLAB_BOTTOM -> dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:oak_slab", Map.of("type", "bottom"));
      case SLAB_TOP -> dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:oak_slab", Map.of("type", "top"));
      case STAIRS_NORTH -> dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:oak_stairs", Map.of("facing", "north", "half", "bottom", "shape", "straight"));
      case STAIRS_SOUTH -> dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:oak_stairs", Map.of("facing", "south", "half", "bottom", "shape", "straight"));
      case STAIRS_EAST -> dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:oak_stairs", Map.of("facing", "east", "half", "bottom", "shape", "straight"));
      case STAIRS_WEST -> dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:oak_stairs", Map.of("facing", "west", "half", "bottom", "shape", "straight"));
      case CARPET -> dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:white_carpet", Map.of());
      case SNOW_LAYER_1, SNOW_LAYER_2, SNOW_LAYER_3, SNOW_LAYER_4, SNOW_LAYER_5, SNOW_LAYER_6, SNOW_LAYER_7, SNOW_LAYER_8 ->
          dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:snow",
              Map.of("layers", Integer.toString(block.ordinal() - Block.SNOW_LAYER_1.ordinal() + 1)));
      case FENCE -> dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:oak_fence", Map.of());
      case WATER -> dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:water", Map.of("level", "0"));
      case LADDER -> dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:ladder", Map.of("facing", "north"));
      case UNKNOWN -> throw new IllegalArgumentException("UNKNOWN is not a block state");
      case UNSUPPORTED -> throw new IllegalArgumentException("UNSUPPORTED is not a block state");
    };
  }
  /** Per-player visibility history. A server mutation only exists in the simulation once it is visible to that client. */
  public static final class History {
    private final Snapshot initial; private final List<Change> changes;
    public History(Snapshot initial, Collection<Change> changes) { this.initial=initial; this.changes=changes.stream().sorted(Comparator.comparingLong(Change::visibleFromClientTick)).toList(); }
    public Snapshot at(long clientTick) { Map<Pos,Block> blocks=new HashMap<>(initial.blocks()); for(Change c:changes) { if(c.visibleFromClientTick()>clientTick) break; if(c.block()==Block.AIR) blocks.remove(c.position()); else blocks.put(c.position(),c.block()); } return new Snapshot(blocks,initial.visibleChunks()); }
  }

  /**
   * Thread-safe per-player world history. Visibility is explicit: a chunk only
   * becomes usable after its outbound chunk packet, and unloading removes it.
   * This is deliberately independent of Bukkit's live world, which represents
   * server truth rather than what the client could have known.
   */
  public static final class VisibilityHistory {
    private final Map<Pos,Block> initialBlocks;
    private final Set<Chunk> initialChunks;
    private final List<ChunkChange> chunks=new ArrayList<>();
    private final List<Change> changes=new ArrayList<>();
    public VisibilityHistory() { this(Map.of(),Set.of()); }
    public VisibilityHistory(Map<Pos,Block> initialBlocks, Collection<Chunk> initialChunks) { this.initialBlocks=Map.copyOf(initialBlocks); this.initialChunks=Set.copyOf(initialChunks); }
    public synchronized void chunkVisible(long clientTick,Chunk chunk) { chunks.add(new ChunkChange(clientTick,chunk,true)); }
    public synchronized void chunkUnloaded(long clientTick,Chunk chunk) { chunks.add(new ChunkChange(clientTick,chunk,false)); }
    public synchronized void blockChanged(long clientTick,Pos position,Block block) {
      if(block==Block.UNKNOWN) throw new IllegalArgumentException("cannot store UNKNOWN");
      // A delta sent before the chunk is visible cannot be treated as a state the
      // client knew. A subsequent full chunk packet is the authoritative baseline.
      if(isVisibleAt(clientTick,Chunk.containing(position.x(),position.z()))) changes.add(new Change(clientTick,position,block));
    }
    public synchronized Snapshot at(long clientTick) {
      Map<Pos,Block> blocks=new HashMap<>(initialBlocks); Set<Chunk> visible=new HashSet<>(initialChunks);
      chunks.stream().sorted(Comparator.comparingLong(ChunkChange::visibleFromClientTick)).filter(change -> change.visibleFromClientTick()<=clientTick).forEach(change -> { if(change.visible()) visible.add(change.chunk()); else { visible.remove(change.chunk()); blocks.keySet().removeIf(pos -> Chunk.containing(pos.x(),pos.z()).equals(change.chunk())); } });
      changes.stream().sorted(Comparator.comparingLong(Change::visibleFromClientTick)).filter(change -> change.visibleFromClientTick()<=clientTick).forEach(change -> { if(visible.contains(Chunk.containing(change.position().x(),change.position().z()))) { if(change.block()==Block.AIR) blocks.remove(change.position()); else blocks.put(change.position(),change.block()); } });
      return new Snapshot(blocks,visible);
    }
    public synchronized void chunkData(long clientTick, Chunk chunk, Map<Pos,Block> data) {
      chunkVisible(clientTick,chunk);
      data.forEach((position,block) -> blockChanged(clientTick,position,block));
    }

    // ----------------------------------------------------------------
    // Phase 4 world states
    // ----------------------------------------------------------------

    private final Map<dev.phantom.ac.world.Pos, dev.phantom.ac.world.BlockState> stateBlocks = new HashMap<>();
    private final Map<dev.phantom.ac.world.Pos, Long> stateVisibleFrom = new HashMap<>();
    private final Set<Chunk> stateChunks = new LinkedHashSet<>();

    /** The chunks currently visible in the Phase 4 model. */
    public synchronized Set<Chunk> stateChunks() {
      return Set.copyOf(stateChunks);
    }

    /** Records a full client-visible chunk carrying real 1.21.11 block states. */
    public synchronized void chunkStates(long clientTick, dev.phantom.ac.world.Chunk chunk, Map<dev.phantom.ac.world.Pos, dev.phantom.ac.world.BlockState> states) {
      stateChunks.add(new Chunk(chunk.x(), chunk.z()));
      for (Map.Entry<dev.phantom.ac.world.Pos, dev.phantom.ac.world.BlockState> entry : states.entrySet()) {
        stateBlocks.put(entry.getKey(), entry.getValue());
        stateVisibleFrom.put(entry.getKey(), clientTick);
      }
    }

    /**
     * Records a single block state becoming visible at a client tick. A change
     * that arrives before its chunk is visible is dropped, because the client
     * could not have known it; a later chunk payload is the authoritative
     * baseline, exactly as for the legacy history.
     */
    public synchronized void blockStateChanged(long clientTick, dev.phantom.ac.world.Pos position, dev.phantom.ac.world.BlockState state) {
      if (!stateChunks.contains(Chunk.containing(position.x(), position.z()))) return;
      stateBlocks.put(position, state);
      stateVisibleFrom.put(position, clientTick);
    }

    /** Declares a chunk visible to the client in the Phase 4 model. */
    public synchronized void stateChunkVisible(long clientTick, Chunk chunk) {
      stateChunks.add(chunk);
    }

    /** Unloads a chunk from the Phase 4 model, discarding its states. */
    public synchronized void stateChunkUnloaded(long clientTick, Chunk chunk) {
      stateChunks.remove(chunk);
      stateBlocks.keySet().removeIf(position -> Chunk.containing(position.x(), position.z()).equals(chunk));
    }

    /**
     * The Phase 4 snapshot of this client's world at a client tick.
     *
     * <p>Only chunks the client actually received are present. A position in an
     * absent chunk reports {@link dev.phantom.ac.world.Coverage#UNLOADED}, never
     * air.</p>
     */
    public synchronized dev.phantom.ac.world.WorldSnapshot statesAt(long clientTick) {
      var builder = dev.phantom.ac.world.WorldSnapshot.builder(Contracts.TARGET_VERSION);
      for (Chunk chunk : stateChunks) builder.loadChunk(chunk.x(), chunk.z());
      for (Map.Entry<dev.phantom.ac.world.Pos, dev.phantom.ac.world.BlockState> entry : stateBlocks.entrySet()) {
        Long visibleFrom = stateVisibleFrom.get(entry.getKey());
        if (visibleFrom == null || visibleFrom > clientTick) continue;
        dev.phantom.ac.world.Pos position = entry.getKey();
        builder.setBlock(position.x(), position.y(), position.z(), entry.getValue());
      }
      return builder.build();
    }

    /**
     * The same client-visible world at a client tick, projected onto the Phase 4
     * world model. This is how the replay system hands a world to anything that
     * needs real 1.21.11 states, shapes, fluids or environment facts.
     *
     * <p>Chunks that are not visible in the legacy snapshot stay absent from the
     * Phase 4 snapshot, so they report {@link dev.phantom.ac.world.Coverage#UNLOADED}
     * rather than air.</p>
     */
    public synchronized dev.phantom.ac.world.WorldSnapshot worldAt(long clientTick) {
      return World.toWorldModel(at(clientTick));
    }
    private boolean isVisibleAt(long clientTick, Chunk sought) {
      boolean visible=initialChunks.contains(sought);
      return chunks.stream().filter(change -> change.chunk().equals(sought) && change.visibleFromClientTick()<=clientTick).max(Comparator.comparingLong(ChunkChange::visibleFromClientTick)).map(ChunkChange::visible).orElse(visible);
    }
  }
  public record ChunkChange(long visibleFromClientTick,Chunk chunk,boolean visible) implements Serializable {}
  /**
   * Rebuilds exactly the client-visible world represented by a recorded
   * timeline, in both the legacy shape-key model and the Phase 4 state model.
   *
   * <p>Both are populated from the same events, so a capture that used real
   * 1.21.11 block states yields a Phase 4 snapshot with full shape, fluid and
   * environment information, while a legacy capture that only recorded shape
   * keys still produces the legacy snapshot.</p>
   */
  public static VisibilityHistory fromTimeline(Timeline.Snapshot timeline) {
    VisibilityHistory history=new VisibilityHistory();
    for(Timeline.Event event:timeline.events()) {
      var packet=event.packet().packet();
      long tick=event.serverTick();
      if(packet instanceof Packets.ChunkData data) history.chunkData(tick,data.chunk(),data.blocks());
      else if(packet instanceof Packets.ChunkStates states) history.chunkStates(tick,states.chunk(),states.states());
      else if(packet instanceof Packets.ChunkUnload unload) { history.chunkUnloaded(tick,unload.chunk()); history.stateChunkUnloaded(tick,unload.chunk()); }
      else if(packet instanceof Packets.BlockChange change) history.blockChanged(tick,change.position(),change.block());
      else if(packet instanceof Packets.BlockStateChange change) history.blockStateChanged(tick,change.position(),change.state());
    }
    return history;
  }
  public record CollisionResult(Vec3 attempted, Vec3 resolved, boolean collidedX, boolean collidedY, boolean collidedZ, boolean stepped) {
    public boolean collidedHorizontally() { return collidedX || collidedZ; }
  }
  public static final class Resolver implements Contracts.CollisionResolver {
    @Override public CollisionResult resolve(Snapshot world,Aabb playerBox,Vec3 desiredDisplacement,boolean stepped) { return World.resolve(world,playerBox,desiredDisplacement,stepped); }
  }

  public static Vec3 collide(Snapshot world,Aabb box,Vec3 desired) { return resolve(world,box,desired,false).resolved(); }

  public static CollisionResult resolve(Snapshot world,Aabb box,Vec3 desired,boolean stepped) {
    double dx=clip(world,box,desired.x(),0); box=box.move(new Vec3(dx,0,0));
    double dy=clip(world,box,desired.y(),1); box=box.move(new Vec3(0,dy,0));
    double dz=clip(world,box,desired.z(),2);
    return new CollisionResult(desired,new Vec3(dx,dy,dz),Double.compare(dx,desired.x())!=0,Double.compare(dy,desired.y())!=0,Double.compare(dz,desired.z())!=0,stepped);
  }

  /**
   * Selects a step path only when it improves horizontal progress. The caller
   * supplies the exact version-specific step height; this method contains no
   * game-version constant.
   */
  public static CollisionResult resolveWithStep(Snapshot world,Aabb box,Vec3 desired,double stepHeight) {
    if(stepHeight < 0) throw new IllegalArgumentException("step height must be non-negative");
    CollisionResult direct=resolve(world,box,desired,false);
    if(!direct.collidedHorizontally() || stepHeight==0) return direct;
    double rise=clip(world,box,stepHeight,1);
    if(rise<=0) return direct;
    Aabb raised=box.move(new Vec3(0,rise,0));
    CollisionResult across=resolve(world,raised,new Vec3(desired.x(),0,desired.z()),true);
    double fall=clip(world,raised.move(across.resolved()),-rise,1);
    Vec3 stepped=new Vec3(across.resolved().x(),rise+fall,across.resolved().z());
    return stepped.horizontalDistanceSquared(Vec3.ZERO)>direct.resolved().horizontalDistanceSquared(Vec3.ZERO)
      ? new CollisionResult(desired,stepped,across.collidedX(),Double.compare(stepped.y(),desired.y())!=0,across.collidedZ(),true)
      : direct;
  }
  private static double clip(Snapshot w,Aabb b,double amount,int axis) { Aabb expanded=axis==0?b.move(new Vec3(amount,0,0)):axis==1?b.move(new Vec3(0,amount,0)):b.move(new Vec3(0,0,amount)); for(Aabb c:w.collisions(expanded)) { if(axis==0 && b.maxY()>c.minY()&&b.minY()<c.maxY()&&b.maxZ()>c.minZ()&&b.minZ()<c.maxZ()) amount=amount>0?Math.min(amount,c.minX()-b.maxX()):Math.max(amount,c.maxX()-b.minX()); if(axis==1 && b.maxX()>c.minX()&&b.minX()<c.maxX()&&b.maxZ()>c.minZ()&&b.minZ()<c.maxZ()) amount=amount>0?Math.min(amount,c.minY()-b.maxY()):Math.max(amount,c.maxY()-b.minY()); if(axis==2 && b.maxX()>c.minX()&&b.minX()<c.maxX()&&b.maxY()>c.minY()&&b.minY()<c.maxY()) amount=amount>0?Math.min(amount,c.minZ()-b.maxZ()):Math.max(amount,c.maxZ()-b.minZ()); } return amount; }
}
