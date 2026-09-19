package dev.phantom.ac;

import dev.phantom.ac.geometry.BlockBox;
import dev.phantom.ac.geometry.VoxelShape;
import dev.phantom.ac.world.BlockState;
import dev.phantom.ac.world.Chunk;
import dev.phantom.ac.world.Coverage;
import dev.phantom.ac.world.EntityCollisions;
import dev.phantom.ac.world.FluidState;
import dev.phantom.ac.world.Pos;
import dev.phantom.ac.world.WorldQueries;
import dev.phantom.ac.world.WorldSnapshot;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Authoritative Phase 4 per-player client-visible world replica.
 *
 * <p>The live representation follows the useful part of Grim's architecture:
 * packet-driven, per-player, palette/bit-packed chunk sections, causal world
 * visibility and immutable published generations. The implementation is
 * clean-room and uses Phantom's own world model/collision catalogue.</p>
 */
public final class Phase4WorldReplica implements Serializable {

  public record Order(long serverTick,long receivedNanos,long sequence,long ordinal)
      implements Comparable<Order>,Serializable {
    public Order {
      if(serverTick<0||receivedNanos<0||sequence<0||ordinal<0)
        throw new IllegalArgumentException("invalid order");
    }
    @Override public int compareTo(Order o) {
      int c=Long.compare(serverTick,o.serverTick); if(c!=0)return c;
      c=Long.compare(receivedNanos,o.receivedNanos); if(c!=0)return c;
      c=Long.compare(sequence,o.sequence); return c!=0?c:Long.compare(ordinal,o.ordinal);
    }
  }

  public record Provenance(
      String sourceId,String packetType,long sequence,long serverTick,Long clientTick,
      boolean clientVisible,String detail) implements Serializable {
    public Provenance {
      Objects.requireNonNull(sourceId);
      Objects.requireNonNull(packetType);
      Objects.requireNonNull(detail);
    }
  }

  public sealed interface Event extends Serializable
      permits ChunkLoad,ChunkData,ChunkSections,PackedChunkData,ChunkUnload,BlockChange,
              MultiBlockChange,DimensionChange,WorldMetadata,EntitySpawn,EntityMove,EntityDespawn {
    Order order();
    Provenance provenance();
  }

  public record ChunkLoad(Order order,Provenance provenance,Chunk chunk) implements Event {}

  /** Legacy/replay sparse representation; live packet paths should use PackedChunkData. */
  public record ChunkData(
      Order order,Provenance provenance,Chunk chunk,Map<Pos,BlockState> states) implements Event {
    public ChunkData {
      Objects.requireNonNull(chunk);
      states=Map.copyOf(states);
    }
  }

  /** Legacy/replay section representation; live packet paths should use PackedChunkData. */
  public record ChunkSections(
      Order order,Provenance provenance,Chunk chunk,
      Map<Integer,Map<Pos,BlockState>> sections) implements Event {
    public ChunkSections {
      Objects.requireNonNull(chunk);
      if(sections.isEmpty())throw new IllegalArgumentException("sections must not be empty");
      TreeMap<Integer,Map<Pos,BlockState>> copy=new TreeMap<>();
      for(var e:sections.entrySet()){
        if(e.getKey()<0)throw new IllegalArgumentException("section index must be non-negative");
        copy.put(e.getKey(),Map.copyOf(e.getValue()));
      }
      sections=Map.copyOf(copy);
    }
  }

  /**
   * Compact live packet representation. Sections remain bit-packed and share a
   * palette of decoded Phantom block states instead of one object per block cell.
   */
  public record PackedChunkData(
      Order order,Provenance provenance,Chunk chunk,
      Map<Integer,PackedSection> sections,boolean fullChunk) implements Event {
    public PackedChunkData {
      Objects.requireNonNull(chunk);
      TreeMap<Integer,PackedSection> copy=new TreeMap<>();
      for(var e:Objects.requireNonNull(sections).entrySet()){
        if(e.getKey()<0)throw new IllegalArgumentException("section index must be non-negative");
        copy.put(e.getKey(),Objects.requireNonNull(e.getValue()));
      }
      sections=Map.copyOf(copy);
    }
  }

  public record ChunkUnload(Order order,Provenance provenance,Chunk chunk) implements Event {}
  public record BlockChange(Order order,Provenance provenance,Pos position,BlockState state) implements Event {}
  public record MultiBlockChange(
      Order order,Provenance provenance,Map<Pos,BlockState> states) implements Event {
    public MultiBlockChange { states=Map.copyOf(states); }
  }
  public record DimensionChange(
      Order order,Provenance provenance,String worldId,int minY,int maxY) implements Event {
    public DimensionChange {
      Objects.requireNonNull(worldId);
      if(worldId.isBlank()||minY>maxY)throw new IllegalArgumentException("invalid dimension");
    }
  }
  public record WorldMetadata(
      Order order,Provenance provenance,Map<String,String> values) implements Event {
    public WorldMetadata { values=Map.copyOf(values); }
  }
  public record EntitySpawn(
      Order order,Provenance provenance,EntityCollisions.EntityBox entity) implements Event {}
  public record EntityMove(
      Order order,Provenance provenance,EntityCollisions.EntityBox entity) implements Event {}
  public record EntityDespawn(
      Order order,Provenance provenance,int entityId) implements Event {}

  public record Generation(
      long id,String worldId,long serverTick,long sequence,Order order,
      WorldSnapshot world,EntityCollisions entities,Map<String,String> metadata,
      List<Event> events) implements Serializable {
    public Generation {
      metadata=Map.copyOf(metadata);
      events=List.copyOf(events);
    }
  }

  /**
   * One compact section. The palette contains decoded Phantom states; data holds
   * palette indexes. Small block changes live in an immutable overlay so a single
   * block change does not force a 4096-cell rewrite.
   */
  public record PackedSection(
      int sectionY,BlockState[] palette,long[] data,int bitsPerEntry,
      Map<Integer,BlockState> overrides) implements Serializable {

    public PackedSection {
      if(sectionY<Integer.MIN_VALUE||sectionY>Integer.MAX_VALUE)
        throw new IllegalArgumentException("invalid sectionY");
      palette=Objects.requireNonNull(palette).clone();
      if(palette.length==0)throw new IllegalArgumentException("palette must not be empty");
      for(BlockState state:palette)Objects.requireNonNull(state);
      data=Objects.requireNonNull(data).clone();
      if(bitsPerEntry<0||bitsPerEntry>32)
        throw new IllegalArgumentException("bitsPerEntry must be 0..32");
      overrides=Map.copyOf(Objects.requireNonNull(overrides));
      for(int index:overrides.keySet())
        if(index<0||index>=4096)throw new IllegalArgumentException("override index out of range: "+index);
    }

    @Override public BlockState[] palette(){return palette.clone();}
    @Override public long[] data(){return data.clone();}

    public static PackedSection empty(int sectionY) {
      return new PackedSection(sectionY,new BlockState[]{BlockState.air()},new long[0],0,Map.of());
    }

    public static PackedSection uniform(int sectionY,BlockState state) {
      return new PackedSection(sectionY,new BlockState[]{Objects.requireNonNull(state)},new long[0],0,Map.of());
    }

    /** Builds a deterministic palette/packed section from exactly 4096 local cells. */
    public static PackedSection fromStates(int sectionY,BlockState[] states) {
      Objects.requireNonNull(states);
      if(states.length!=4096)throw new IllegalArgumentException("section must contain 4096 states");

      LinkedHashMap<BlockState,Integer> paletteIds=new LinkedHashMap<>();
      int[] indexes=new int[4096];
      for(int i=0;i<4096;i++){
        BlockState state=Objects.requireNonNull(states[i]);
        Integer id=paletteIds.get(state);
        if(id==null){id=paletteIds.size();paletteIds.put(state,id);}
        indexes[i]=id;
      }
      BlockState[] palette=paletteIds.keySet().toArray(BlockState[]::new);
      int bits=bitsForPalette(palette.length);
      long[] packed=pack(indexes,bits);
      return new PackedSection(sectionY,palette,packed,bits,Map.of());
    }

    /** Uses a packet-style palette plus a packet-style packed index array. */
    public static PackedSection fromPaletteStorage(
        int sectionY,BlockState[] palette,long[] packetData,int bitsPerEntry) {
      Objects.requireNonNull(palette);
      if(palette.length==0)throw new IllegalArgumentException("palette must not be empty");
      for(BlockState state:palette)Objects.requireNonNull(state);
      if(bitsPerEntry==0)return uniform(sectionY,palette[0]);

      if(packetData.length < ((4096*bitsPerEntry+63)/64))
        throw new IllegalArgumentException("packed data is shorter than the 4096-entry section requires");
      return new PackedSection(sectionY,palette,packetData,bitsPerEntry,Map.of());
    }

    public PackedSection withOverride(int localIndex,BlockState state) {
      if(localIndex<0||localIndex>=4096)throw new IllegalArgumentException("localIndex out of range");
      Objects.requireNonNull(state);
      Map<Integer,BlockState> copy=new HashMap<>(overrides);
      copy.put(localIndex,state);
      return new PackedSection(sectionY,palette,data,bitsPerEntry,copy);
    }

    public BlockState stateAt(int localIndex) {
      if(localIndex<0||localIndex>=4096)throw new IllegalArgumentException("localIndex out of range");
      BlockState overridden=overrides.get(localIndex);
      if(overridden!=null)return overridden;
      int paletteIndex=bitsPerEntry==0?0:readPacked(data,bitsPerEntry,localIndex);
      if(paletteIndex<0||paletteIndex>=palette.length)
        return BlockState.unsupported("packed-palette-index-"+paletteIndex);
      return palette[paletteIndex];
    }

    public int storedEntryCount() {
      return palette.length+overrides.size();
    }

    private static int bitsForPalette(int paletteSize) {
      if(paletteSize<=1)return 0;
      return Math.max(1,32-Integer.numberOfLeadingZeros(paletteSize-1));
    }

    private static long[] pack(int[] indexes,int bits) {
      if(bits==0)return new long[0];
      int totalBits=4096*bits;
      long[] result=new long[(totalBits+63)/64];
      long mask=(1L<<bits)-1L;
      for(int i=0;i<indexes.length;i++){
        long value=indexes[i]&mask;
        int bit=i*bits;
        int word=bit>>>6;
        int offset=bit&63;
        result[word]|=value<<offset;
        if(offset+bits>64){
          result[word+1]|=value>>>(64-offset);
        }
      }
      return result;
    }

    private static int readPacked(long[] data,int bits,int index) {
      if(bits==0)return 0;
      int bit=index*bits;
      int word=bit>>>6;
      int offset=bit&63;
      long mask=(1L<<bits)-1L;
      long value=data[word]>>>offset;
      if(offset+bits>64)value|=data[word+1]<<(64-offset);
      return (int)(value&mask);
    }
  }

  private static final class PackedChunk implements Serializable {
    private final Map<Integer,PackedSection> sections;
    private final Set<Integer> knownSections;

    private PackedChunk(Map<Integer,PackedSection> sections,Set<Integer> knownSections) {
      this.sections=Map.copyOf(sections);
      this.knownSections=Set.copyOf(knownSections);
    }

    static PackedChunk unknown() { return new PackedChunk(Map.of(),Set.of()); }

    PackedChunk withSections(Map<Integer,PackedSection> incoming,Set<Integer> known) {
      Map<Integer,PackedSection> next=new HashMap<>(sections);
      next.putAll(incoming);
      Set<Integer> nextKnown=new HashSet<>(knownSections);
      nextKnown.addAll(known);
      return new PackedChunk(next,nextKnown);
    }

    PackedChunk replaceFully(Map<Integer,PackedSection> incoming,int sectionCount) {
      Set<Integer> known=new HashSet<>();
      for(int i=0;i<sectionCount;i++)known.add(i);
      return new PackedChunk(incoming,known);
    }

    PackedChunk withOverride(int sectionIndex,int localIndex,BlockState state) {
      PackedSection section=sections.get(sectionIndex);
      if(section==null)return this;
      Map<Integer,PackedSection> next=new HashMap<>(sections);
      next.put(sectionIndex,section.withOverride(localIndex,state));
      return new PackedChunk(next,knownSections);
    }

    boolean sectionKnown(int index){return knownSections.contains(index);}
    boolean fullyKnown(int sectionCount){return knownSections.size()>=sectionCount;}

    PackedSection section(int index){return sections.get(index);}
    int storedEntryCount(){
      int n=0;for(PackedSection section:sections.values())n+=section.storedEntryCount();return n;
    }
  }

  private record PackedWorldData(
      String worldId,int minY,int maxY,Map<Chunk,PackedChunk> chunks) implements Serializable {
    PackedWorldData {
      Objects.requireNonNull(worldId);
      if(minY>maxY)throw new IllegalArgumentException("invalid world bounds");
      chunks=Map.copyOf(chunks);
    }

    static PackedWorldData empty(String worldId,int minY,int maxY){
      return new PackedWorldData(worldId,minY,maxY,Map.of());
    }

    int sectionMinY(){return Math.floorDiv(minY,16);}
    int sectionMaxY(){return Math.floorDiv(maxY,16);}
    int sectionCount(){return sectionMaxY()-sectionMinY()+1;}

    PackedWorldData loadUnknown(Chunk chunk) {
      Map<Chunk,PackedChunk> next=new HashMap<>(chunks);
      next.put(chunk,PackedChunk.unknown());
      return new PackedWorldData(worldId,minY,maxY,next);
    }

    PackedWorldData unload(Chunk chunk) {
      if(!chunks.containsKey(chunk))return this;
      Map<Chunk,PackedChunk> next=new HashMap<>(chunks);
      next.remove(chunk);
      return new PackedWorldData(worldId,minY,maxY,next);
    }

    PackedWorldData putFullChunk(Chunk chunk,Map<Integer,PackedSection> sections) {
      Map<Chunk,PackedChunk> next=new HashMap<>(chunks);
      next.put(chunk,PackedChunk.unknown().replaceFully(sections,sectionCount()));
      return new PackedWorldData(worldId,minY,maxY,next);
    }

    PackedWorldData putSections(Chunk chunk,Map<Integer,PackedSection> sections) {
      PackedChunk prior=chunks.getOrDefault(chunk,PackedChunk.unknown());
      Map<Chunk,PackedChunk> next=new HashMap<>(chunks);
      next.put(chunk,prior.withSections(sections,new HashSet<>(sections.keySet())));
      return new PackedWorldData(worldId,minY,maxY,next);
    }

    PackedWorldData putSparseChunk(Chunk chunk,Map<Pos,BlockState> sparse) {
      BlockState[] defaultStates=new BlockState[4096];
      Arrays.fill(defaultStates,BlockState.air());
      Map<Integer,BlockState[]> sectionStates=new TreeMap<>();
      for(var entry:sparse.entrySet()){
        Pos p=entry.getKey();
        int sectionIndex=Math.floorDiv(p.y(),16)-sectionMinY();
        if(sectionIndex<0||sectionIndex>=sectionCount())continue;
        BlockState[] states=sectionStates.computeIfAbsent(sectionIndex,k->{
          BlockState[] copy=defaultStates.clone();return copy;
        });
        int ly=Math.floorMod(p.y(),16);
        int localIndex=(ly<<8)|((p.z()&15)<<4)|(p.x()&15);
        states[localIndex]=entry.getValue();
      }
      Map<Integer,PackedSection> sections=new TreeMap<>();
      for(int i=0;i<sectionCount();i++){
        BlockState[] states=sectionStates.get(i);
        sections.put(i,states==null?PackedSection.empty(sectionMinY()+i):PackedSection.fromStates(sectionMinY()+i,states));
      }
      return putFullChunk(chunk,sections);
    }

    PackedWorldData blockChange(Pos position,BlockState state) {
      Chunk chunk=position.chunk();
      PackedChunk packed=chunks.get(chunk);
      if(packed==null)return this;
      int sectionIndex=Math.floorDiv(position.y(),16)-sectionMinY();
      if(sectionIndex<0||sectionIndex>=sectionCount()||!packed.sectionKnown(sectionIndex))return this;
      int ly=Math.floorMod(position.y(),16);
      int localIndex=(ly<<8)|((position.z()&15)<<4)|(position.x()&15);
      PackedChunk nextChunk=packed.withOverride(sectionIndex,localIndex,state);
      if(nextChunk==packed)return this;
      Map<Chunk,PackedChunk> next=new HashMap<>(chunks);
      next.put(chunk,nextChunk);
      return new PackedWorldData(worldId,minY,maxY,next);
    }
  }

  private record VisibleState(
      String worldId,int minY,int maxY,PackedWorldData world,
      Map<Integer,EntityCollisions.EntityBox> entities,Map<String,String> metadata)
      implements Serializable {

    static VisibleState empty(String worldId,int minY,int maxY){
      return new VisibleState(worldId,minY,maxY,PackedWorldData.empty(worldId,minY,maxY),Map.of(),Map.of());
    }

    VisibleState withWorld(PackedWorldData next){return new VisibleState(worldId,next.minY(),next.maxY(),next,entities,metadata);}
    VisibleState withWorldIdentity(String id,int minY,int maxY){
      return new VisibleState(id,minY,maxY,PackedWorldData.empty(id,minY,maxY),Map.of(),Map.of());
    }

    VisibleState withEntities(Map<Integer,EntityCollisions.EntityBox> next){
      return new VisibleState(worldId,minY,maxY,world,Map.copyOf(next),metadata);
    }

    VisibleState withMetadata(Map<String,String> next){
      return new VisibleState(worldId,minY,maxY,world,entities,Map.copyOf(next));
    }
  }

  private static final int MAX_GENERATIONS=512;

  private final String version;
  private final AtomicReference<Generation> current;
  private final NavigableMap<Order,Event> journal=new TreeMap<>();
  private final Set<Long> seenSequences=new HashSet<>();
  private final List<Generation> history=new ArrayList<>();
  private final NavigableMap<Long,Event> unassigned=new TreeMap<>();
  private final Map<Short,List<Event>> pending=new LinkedHashMap<>();
  private transient volatile WorldSnapshot.CollisionResolver collisionResolver;
  private volatile boolean entityTrackingComplete=false;
  private final Deque<Short> sentOrder=new ArrayDeque<>();

  private long generationId;
  private long ordinal;
  private long lastVisibleSequence=-1L;
  private Order lastVisibleOrder;
  private VisibleState visible;

  public Phase4WorldReplica(String version,String worldId,int minY,int maxY) {
    this.version=Objects.requireNonNull(version);
    this.visible=VisibleState.empty(worldId,minY,maxY);
    WorldSnapshot worldSnapshot=snapshotFor(visible,lastVisibleSequence);
    Generation initial=new Generation(
        0,worldId,0,-1L,new Order(0,0,0,0),
        worldSnapshot,new TrackedEntities(Map.of(),true),Map.of(),List.of());
    current=new AtomicReference<>(initial);
    history.add(initial);
  }

  public Phase4WorldReplica(String version){
    this(version,"unknown",WorldSnapshot.OVERWORLD_MIN_Y,WorldSnapshot.OVERWORLD_MAX_Y);
  }

  public String version(){return version;}

  /** Installs an optional platform-native collision resolver for live snapshots. */
  public synchronized void setCollisionResolver(WorldSnapshot.CollisionResolver resolver){
    this.collisionResolver=resolver;
    VisibleState state=visible;
    if (current.get().world()!=null) publish(state,lastVisibleSequence,List.of());
  }

  public WorldSnapshot.CollisionResolver collisionResolver(){return collisionResolver;}

  /** Marks live entity reconstruction incomplete until a fresh capture is established. */
  public synchronized void markEntityTrackingIncomplete(){
    if(entityTrackingComplete){
      entityTrackingComplete=false;
      publish(visible,lastVisibleSequence,List.of());
    }
  }

  public synchronized void markEntityTrackingComplete(){
    if(!entityTrackingComplete){
      entityTrackingComplete=true;
      publish(visible,lastVisibleSequence,List.of());
    }
  }
  public Generation getWorldGeneration(){return current.get();}
  public WorldSnapshot getWorldState(){return current.get().world();}
  public WorldSnapshot snapshot(){return getWorldState();}

  public boolean isKnown(int x,int y,int z){return snapshot().coverageAt(x,y,z)==Coverage.KNOWN;}
  public boolean isLoaded(int x,int y,int z){return snapshot().coverageAt(x,y,z)!=Coverage.UNLOADED;}
  public BlockState getBlockState(int x,int y,int z){return snapshot().blockAtOrNull(x,y,z);}
  public VoxelShape getCollisionShape(int x,int y,int z){return snapshot().collisionShapeAt(x,y,z);}
  public WorldQueries.CollisionResult getCollisionShapes(BlockBox box){return WorldQueries.collisions(snapshot(),box);}
  public FluidState getFluidState(int x,int y,int z){return WorldQueries.fluidAt(snapshot(),x,y,z);}

  public synchronized List<Generation> generations(){return List.copyOf(history);}

  public synchronized Optional<Generation> generationAtSequence(long sequence){
    Generation answer=null;
    for(Generation generation:history){
      if(generation.sequence()<=sequence)answer=generation;
      else break;
    }
    return Optional.ofNullable(answer);
  }

  public synchronized Optional<Generation> generationAt(Order point){
    Generation answer=null;
    for(Generation generation:history){
      if(generation.order().compareTo(point)<=0)answer=generation;
      else break;
    }
    return Optional.ofNullable(answer);
  }

  public synchronized WorldSnapshot snapshotAtSequence(long sequence){
    return generationAtSequence(sequence).map(Generation::world).orElse(null);
  }

  /** Returns the latest acknowledged immutable generation at or before the query sequence. */
  public synchronized WorldSnapshot snapshotAtOrBefore(long sequence){
    if(sequence<0)return null;
    return generationAtSequence(sequence).map(Generation::world).orElse(null);
  }

  public synchronized void accept(Event event){
    Objects.requireNonNull(event);
    acceptVisible(List.of(event),event.order().sequence());
  }

  /** Stages a clientbound mutation until the next world transaction barrier is acknowledged. */
  public synchronized void queue(Event event){
    Objects.requireNonNull(event);
    unassigned.put(event.order().sequence(),event);
  }

  public synchronized boolean hasUnassignedMutations(){return !unassigned.isEmpty();}

  public synchronized void openBarrier(short transactionId){
    openBarrier(transactionId,Long.MAX_VALUE);
  }

  /**
   * Opens a barrier over all currently staged events. The explicit sequence bound
   * is recorded for diagnostics and future integrations; event ordering remains
   * canonical even when async chunk decoders finish out of order.
   */
  public synchronized void openBarrier(short transactionId,long sequenceBoundary){
    if(pending.containsKey(transactionId))
      throw new IllegalStateException("transaction barrier already open: "+transactionId);
    if(unassigned.isEmpty())return;
    List<Event> batch=new ArrayList<>(unassigned.values());
    unassigned.clear();
    pending.put(transactionId,List.copyOf(batch));
    sentOrder.addLast(transactionId);
  }

  public synchronized boolean acknowledge(short transactionId,long acknowledgementSequence){
    if(acknowledgementSequence<0)throw new IllegalArgumentException("acknowledgementSequence must be non-negative");
    if(!pending.containsKey(transactionId))return false;

    List<Event> visibleEvents=new ArrayList<>();
    while(!sentOrder.isEmpty()){
      short head=sentOrder.removeFirst();
      List<Event> batch=pending.remove(head);
      if(batch!=null)visibleEvents.addAll(batch);
      if(head==transactionId)break;
    }
    acceptVisible(visibleEvents,acknowledgementSequence);
    return true;
  }

  public synchronized void abortBarrier(short transactionId){
    List<Event> batch=pending.remove(transactionId);
    sentOrder.remove(transactionId);
    if(batch!=null)for(Event event:batch)unassigned.put(event.order().sequence(),event);
  }

  public synchronized int pendingBarrierCount(){return pending.size();}
  public synchronized long causalSequence(){return lastVisibleSequence;}
  public synchronized int visibleChunkCount(){return getWorldState().loadedChunks().size();}

  /** Number of compact palette/overlay entries, not a one-object-per-block count. */
  public synchronized int compactStateEntryCount(){
    if(current.get().world().loadedChunks().isEmpty())return 0;
    int total=0;
    for(Chunk chunk:current.get().world().loadedChunks()){
      PackedChunk packed=visible.world.chunks().get(chunk);
      if(packed!=null)total+=packed.storedEntryCount();
    }
    return total;
  }

  /**
   * Returns a cheap compact backed view of the selected chunks. This never
   * materializes every block into a Java Map.
   */
  public synchronized WorldSnapshot snapshotAround(double centerX,double centerZ,int radiusChunks){
    if(radiusChunks<0)throw new IllegalArgumentException("radiusChunks must be non-negative");
    WorldSnapshot source=getWorldState();
    int cx=Math.floorDiv((int)Math.floor(centerX),16);
    int cz=Math.floorDiv((int)Math.floor(centerZ),16);

    Set<Chunk> selected=new TreeSet<>(Comparator.comparingInt(Chunk::x).thenComparingInt(Chunk::z));
    for(Chunk chunk:source.loadedChunks()){
      if(Math.abs(chunk.x()-cx)<=radiusChunks&&Math.abs(chunk.z()-cz)<=radiusChunks)selected.add(chunk);
    }

    final WorldSnapshot.CollisionResolver resolver=this.collisionResolver;
    WorldSnapshot.Backend backend=new WorldSnapshot.Backend(){
      @Override public String version(){return source.version();}
      @Override public int minY(){return source.minY();}
      @Override public int maxY(){return source.maxY();}
      @Override public Set<Chunk> loadedChunks(){return Set.copyOf(selected);}
      @Override public long causalSequence(){return source.causalSequence();}
      @Override public Set<Chunk> unknownChunks(){
        Set<Chunk> unknown=new HashSet<>();
        for(Chunk chunk:selected){
          Coverage coverage=source.coverageAt(chunk.x()*16,source.minY(),chunk.z()*16);
          if(coverage==Coverage.UNKNOWN)unknown.add(chunk);
        }
        return Set.copyOf(unknown);
      }
      @Override public boolean hasChunk(int chunkX,int chunkZ){return selected.contains(new Chunk(chunkX,chunkZ));}
      @Override public Coverage coverageAt(int x,int y,int z){
        Chunk chunk=Chunk.containing(x,z);
        return selected.contains(chunk)?source.coverageAt(x,y,z):Coverage.UNLOADED;
      }
      @Override public BlockState blockAtOrNull(int x,int y,int z){
        Chunk chunk=Chunk.containing(x,z);
        return selected.contains(chunk)?source.blockAtOrNull(x,y,z):null;
      }
      @Override public java.util.Optional<VoxelShape> resolveCollisionShape(WorldSnapshot snapshot,int x,int y,int z){
        if(resolver==null)return java.util.Optional.empty();
        BlockState state=source.blockAtOrNull(x,y,z);
        if(state==null)return java.util.Optional.of(VoxelShape.empty());
        return resolver.resolve(snapshot,state,x,y,z);
      }
    };
    return WorldSnapshot.backed(source.version(),source.minY(),source.maxY(),backend);
  }

  /** Adapts Phase 1-3 timeline events without consulting the server world. */
  public void accept(Timeline.Event event){
    Packets.Packet packet=event.packet().packet();
    Packets.CaptureProvenance cp=event.packet().provenance();
    Order order=new Order(event.serverTick(),event.packet().receivedNanos(),event.packet().sequence(),nextOrdinal());
    Provenance provenance=new Provenance(
        cp.sourceId(),cp.packetType(),event.packet().sequence(),event.serverTick(),
        cp.authoritativeClientTick(),true,"timeline");

    if(packet instanceof Packets.ChunkStates x){
      accept(new ChunkData(order,provenance,new Chunk(x.chunk().x(),x.chunk().z()),x.states()));
    } else if(packet instanceof Packets.ChunkUnload x){
      accept(new ChunkUnload(order,provenance,new Chunk(x.chunk().x(),x.chunk().z())));
    } else if(packet instanceof Packets.BlockStateChange x){
      accept(new BlockChange(order,provenance,x.position(),x.state()));
    } else if(packet instanceof Packets.UnsupportedBlockStateChange x){
      accept(new BlockChange(order,provenance,x.position(),x.state()));
    } else if(packet instanceof Packets.ChunkData x){
      Map<Pos,BlockState> states=new HashMap<>();
      for(var e:x.blocks().entrySet())
        if(e.getValue()!=World.Block.UNKNOWN)states.put(e.getKey().toWorldPos(),World.legacyBlockState(e.getValue()));
      accept(new ChunkData(order,provenance,x.chunk().toWorldChunk(),states));
    } else if(packet instanceof Packets.BlockChange x){
      accept(new BlockChange(order,provenance,x.position().toWorldPos(),World.legacyBlockState(x.block())));
    } else if(packet instanceof Packets.EntitySpawn x){
      accept(new EntitySpawn(order,provenance,new EntityCollisions.EntityBox(x.entityId(),x.box())));
    } else if(packet instanceof Packets.EntityMove x){
      accept(new EntityMove(order,provenance,new EntityCollisions.EntityBox(x.entityId(),x.box())));
    } else if(packet instanceof Packets.EntityDespawn x){
      accept(new EntityDespawn(order,provenance,x.entityId()));
    }
  }

  private long nextOrdinal(){return ordinal++;}

  private void acceptVisible(List<Event> events,long causalSequence){
    boolean changed=false;
    boolean reorder=false;
    List<Event> appliedDelta=new ArrayList<>();

    for(Event event:events){
      if(!seenSequences.add(event.order().sequence()))continue;
      journal.put(event.order(),event);
      changed=true;
      appliedDelta.add(event);
      if(lastVisibleOrder!=null&&event.order().compareTo(lastVisibleOrder)<0)reorder=true;
    }

    if(!changed&&causalSequence<=lastVisibleSequence)return;

    if(reorder){
      visible=replayJournal();
      List<Event> canonical=new ArrayList<>(journal.values());
      lastVisibleOrder=canonical.isEmpty()?lastVisibleOrder:canonical.getLast().order();
      publish(visible,causalSequence,canonical);
      return;
    }

    for(Event event:appliedDelta)visible=applyEvent(visible,event);
    if(!appliedDelta.isEmpty())lastVisibleOrder=appliedDelta.getLast().order();
    lastVisibleSequence=Math.max(lastVisibleSequence,causalSequence);
    publish(visible,lastVisibleSequence,appliedDelta);
  }

  private VisibleState replayJournal(){
    VisibleState state=VisibleState.empty("unknown",WorldSnapshot.OVERWORLD_MIN_Y,WorldSnapshot.OVERWORLD_MAX_Y);
    for(Event event:journal.values())state=applyEvent(state,event);
    return state;
  }

  private static VisibleState applyEvent(VisibleState state,Event event){
    if(event instanceof DimensionChange change){
      return state.withWorldIdentity(change.worldId(),change.minY(),change.maxY());
    }
    if(event instanceof ChunkLoad load){
      return state.withWorld(state.world.loadUnknown(load.chunk()));
    }
    if(event instanceof PackedChunkData packed){
      PackedWorldData next=packed.fullChunk()
          ?state.world.putFullChunk(packed.chunk(),packed.sections())
          :state.world.putSections(packed.chunk(),packed.sections());
      return state.withWorld(next);
    }
    if(event instanceof ChunkData data){
      return state.withWorld(state.world.putSparseChunk(data.chunk(),data.states()));
    }
    if(event instanceof ChunkSections sections){
      Map<Integer,PackedSection> packed=new TreeMap<>();
      for(var entry:sections.sections().entrySet()){
        int sectionY=state.world.sectionMinY()+entry.getKey();
        BlockState[] cells=new BlockState[4096];
        Arrays.fill(cells,BlockState.air());
        for(var block:entry.getValue().entrySet()){
          Pos p=block.getKey();
          int ly=Math.floorMod(p.y(),16);
          int local=(ly<<8)|((p.z()&15)<<4)|(p.x()&15);
          if(local>=0&&local<4096)cells[local]=block.getValue();
        }
        packed.put(entry.getKey(),PackedSection.fromStates(sectionY,cells));
      }
      return state.withWorld(state.world.putSections(sections.chunk(),packed));
    }
    if(event instanceof ChunkUnload unload){
      return state.withWorld(state.world.unload(unload.chunk()));
    }
    if(event instanceof BlockChange change){
      return state.withWorld(state.world.blockChange(change.position(),change.state()));
    }
    if(event instanceof MultiBlockChange changes){
      VisibleState result=state;
      List<Pos> positions=new ArrayList<>(changes.states().keySet());
      positions.sort(WorldSnapshot.POS_ORDER);
      for(Pos position:positions)
        result=result.withWorld(result.world.blockChange(position,changes.states().get(position)));
      return result;
    }
    if(event instanceof WorldMetadata metadata){
      Map<String,String> next=new HashMap<>(state.metadata());
      next.putAll(metadata.values());
      return state.withMetadata(next);
    }
    if(event instanceof EntitySpawn spawn){
      Map<Integer,EntityCollisions.EntityBox> next=new HashMap<>(state.entities());
      next.put(spawn.entity().entityId(),spawn.entity());
      return state.withEntities(next);
    }
    if(event instanceof EntityMove move){
      Map<Integer,EntityCollisions.EntityBox> next=new HashMap<>(state.entities());
      next.put(move.entity().entityId(),move.entity());
      return state.withEntities(next);
    }
    if(event instanceof EntityDespawn despawn){
      Map<Integer,EntityCollisions.EntityBox> next=new HashMap<>(state.entities());
      next.remove(despawn.entityId());
      return state.withEntities(next);
    }
    return state;
  }

  private void publish(VisibleState state,long causalSequence,List<Event> delta){
    lastVisibleSequence=Math.max(lastVisibleSequence,causalSequence);
    WorldSnapshot worldSnapshot=snapshotFor(state,lastVisibleSequence);
    EntityCollisions entities=new TrackedEntities(state.entities(),entityTrackingComplete);
    long tick=delta.isEmpty()?current.get().serverTick():delta.getLast().order().serverTick();
    Order order=delta.isEmpty()?current.get().order():delta.getLast().order();
    Generation generation=new Generation(
        ++generationId,state.worldId(),tick,lastVisibleSequence,order,
        worldSnapshot,entities,state.metadata(),delta);
    synchronized(history){
      history.add(generation);
      while(history.size()>MAX_GENERATIONS)history.removeFirst();
    }
    current.set(generation);
  }

  private WorldSnapshot snapshotFor(VisibleState state,long causalSequence){
    WorldSnapshot.Backend backend=new PackedBackend(state.world,causalSequence,collisionResolver);
    return WorldSnapshot.backed(version,state.minY,state.maxY,backend);
  }

  private final class PackedBackend implements WorldSnapshot.Backend {
    private final PackedWorldData data;
    private final long causalSequence;
    private final WorldSnapshot.CollisionResolver collisionResolver;

    PackedBackend(PackedWorldData data,long causalSequence,WorldSnapshot.CollisionResolver collisionResolver){
      this.data=data;this.causalSequence=causalSequence;this.collisionResolver=collisionResolver;
    }

    @Override public String version(){return version;}
    @Override public int minY(){return data.minY();}
    @Override public int maxY(){return data.maxY();}
    @Override public Set<Chunk> loadedChunks(){return Set.copyOf(data.chunks().keySet());}
    @Override public long causalSequence(){return causalSequence;}

    @Override public Set<Chunk> unknownChunks(){
      Set<Chunk> result=new HashSet<>();
      int sectionCount=data.sectionCount();
      for(var e:data.chunks().entrySet())
        if(!e.getValue().fullyKnown(sectionCount))result.add(e.getKey());
      return Set.copyOf(result);
    }

    @Override public boolean hasChunk(int chunkX,int chunkZ){
      return data.chunks().containsKey(new Chunk(chunkX,chunkZ));
    }

    @Override public Coverage coverageAt(int x,int y,int z){
      if(y<data.minY()||y>data.maxY())return Coverage.UNLOADED;
      PackedChunk chunk=data.chunks().get(Chunk.containing(x,z));
      if(chunk==null)return Coverage.UNLOADED;
      int sectionIndex=Math.floorDiv(y,16)-data.sectionMinY();
      if(!chunk.sectionKnown(sectionIndex))return Coverage.UNKNOWN;
      BlockState state=chunk.section(sectionIndex)==null
          ?BlockState.air()
          :chunk.section(sectionIndex).stateAt((Math.floorMod(y,16)<<8)|((z&15)<<4)|(x&15));
      return state.isUnsupported()?Coverage.UNSUPPORTED:Coverage.KNOWN;
    }

    @Override public java.util.Optional<VoxelShape> resolveCollisionShape(WorldSnapshot snapshot,int x,int y,int z){
      if(collisionResolver==null)return java.util.Optional.empty();
      BlockState state=blockAtOrNull(x,y,z);
      if(state==null)return java.util.Optional.of(VoxelShape.empty());
      return collisionResolver.resolve(snapshot,state,x,y,z);
    }

    @Override public BlockState blockAtOrNull(int x,int y,int z){
      if(y<data.minY()||y>data.maxY())return null;
      PackedChunk chunk=data.chunks().get(Chunk.containing(x,z));
      if(chunk==null)return null;
      int sectionIndex=Math.floorDiv(y,16)-data.sectionMinY();
      if(!chunk.sectionKnown(sectionIndex))return null;
      PackedSection section=chunk.section(sectionIndex);
      if(section==null)return null;
      BlockState state=section.stateAt((Math.floorMod(y,16)<<8)|((z&15)<<4)|(x&15));
      return state.isAir()||state.isUnsupported()?null:state;
    }
  }

  private static final class TrackedEntities implements EntityCollisions,Serializable {
    private final Map<Integer,EntityCollisions.EntityBox> entities;
    private final boolean complete;

    TrackedEntities(Map<Integer,EntityCollisions.EntityBox> entities,boolean complete){
      this.entities=Map.copyOf(entities);
      this.complete=complete;
    }

    @Override public boolean complete(){return complete;}

    @Override public EntityCollisionResult boxesIn(BlockBox query){
      List<EntityCollisions.EntityBox> result=new ArrayList<>();
      for(EntityCollisions.EntityBox entity:entities.values())
        if(entity.box().intersects(query))result.add(entity);
      result.sort(Comparator.comparingInt(EntityCollisions.EntityBox::entityId));
      return new EntityCollisionResult(result,true);
    }
  }

  public static Phase4WorldReplica replay(Timeline.Snapshot timeline){
    Phase4WorldReplica replica=new Phase4WorldReplica(
        timeline.metadata().modelVersion());
    for(Timeline.Event event:timeline.events())replica.accept(event);
    return replica;
  }
}
