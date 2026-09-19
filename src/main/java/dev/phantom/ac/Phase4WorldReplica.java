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

/** Authoritative Phase 4 per-player client-visible world reconstruction. */
public final class Phase4WorldReplica implements Serializable {
  public record Order(long serverTick,long receivedNanos,long sequence,long ordinal) implements Comparable<Order>,Serializable {
    public Order { if(serverTick<0||receivedNanos<0||sequence<0||ordinal<0) throw new IllegalArgumentException("invalid order"); }
    public int compareTo(Order o) { int c=Long.compare(serverTick,o.serverTick);if(c!=0)return c;c=Long.compare(receivedNanos,o.receivedNanos);if(c!=0)return c;c=Long.compare(sequence,o.sequence);return c!=0?c:Long.compare(ordinal,o.ordinal); }
  }
  public record Provenance(String sourceId,String packetType,long sequence,long serverTick,Long clientTick,boolean clientVisible,String detail) implements Serializable {
    public Provenance { Objects.requireNonNull(sourceId);Objects.requireNonNull(packetType);Objects.requireNonNull(detail); }
  }
  public sealed interface Event extends Serializable permits ChunkLoad,ChunkData,ChunkSections,ChunkUnload,BlockChange,MultiBlockChange,DimensionChange,WorldMetadata,EntitySpawn,EntityMove,EntityDespawn {
    Order order(); Provenance provenance();
  }
  public record ChunkLoad(Order order,Provenance provenance,Chunk chunk) implements Event {}
  public record ChunkData(Order order,Provenance provenance,Chunk chunk,Map<Pos,BlockState> states) implements Event { public ChunkData { Objects.requireNonNull(chunk);states=Map.copyOf(states); } }
  /** Replaces complete protocol sections without assuming omitted blocks are unchanged. */
  public record ChunkSections(Order order,Provenance provenance,Chunk chunk,Map<Integer,Map<Pos,BlockState>> sections) implements Event {
    public ChunkSections { Objects.requireNonNull(chunk); if(sections.isEmpty()) throw new IllegalArgumentException("sections must not be empty");
      TreeMap<Integer,Map<Pos,BlockState>> copy=new TreeMap<>();
      for(var e:sections.entrySet()){ if(e.getKey()<0) throw new IllegalArgumentException("section index must be non-negative"); copy.put(e.getKey(),Map.copyOf(e.getValue())); }
      sections=Map.copyOf(copy); }
  }
  public record ChunkUnload(Order order,Provenance provenance,Chunk chunk) implements Event {}
  public record BlockChange(Order order,Provenance provenance,Pos position,BlockState state) implements Event {}
  public record MultiBlockChange(Order order,Provenance provenance,Map<Pos,BlockState> states) implements Event { public MultiBlockChange { states=Map.copyOf(states); } }
  public record DimensionChange(Order order,Provenance provenance,String worldId,int minY,int maxY) implements Event { public DimensionChange { Objects.requireNonNull(worldId);if(worldId.isBlank()||minY>maxY)throw new IllegalArgumentException("invalid dimension"); } }
  public record WorldMetadata(Order order,Provenance provenance,Map<String,String> values) implements Event { public WorldMetadata { values=Map.copyOf(values); } }
  public record EntitySpawn(Order order,Provenance provenance,EntityCollisions.EntityBox entity) implements Event {}
  public record EntityMove(Order order,Provenance provenance,EntityCollisions.EntityBox entity) implements Event {}
  public record EntityDespawn(Order order,Provenance provenance,int entityId) implements Event {}
  public record Generation(long id,String worldId,long serverTick,long sequence,Order order,WorldSnapshot world,EntityCollisions entities,Map<String,String> metadata,List<Event> events) implements Serializable {
    public Generation { metadata=Map.copyOf(metadata);events=List.copyOf(events); }
  }

  private final String version;
  private final AtomicReference<Generation> current;
  private final NavigableMap<Order,Event> journal=new TreeMap<>();
  private final Set<Long> seenSequences=new HashSet<>();
  private final List<Generation> history=new ArrayList<>();
  private final List<Event> unassigned=new ArrayList<>();
  private final Map<Short,List<Event>> pending=new LinkedHashMap<>();
  private final Deque<Short> sentOrder=new ArrayDeque<>();
  private long generationId,ordinal,lastVisibleSequence=-1L;

  public Phase4WorldReplica(String version,String worldId,int minY,int maxY) {
    this.version=Objects.requireNonNull(version);
    WorldSnapshot world=WorldSnapshot.empty(version,minY,maxY);
    Generation initial=new Generation(0,worldId,0,-1,new Order(0,0,0,0),world,EntityCollisions.of(List.of()),Map.of(),List.of());
    current=new AtomicReference<>(initial);history.add(initial);
  }
  public Phase4WorldReplica(String version){this(version,"unknown",WorldSnapshot.OVERWORLD_MIN_Y,WorldSnapshot.OVERWORLD_MAX_Y);}
  public String version(){return version;}
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
  public synchronized Optional<Generation> generationAtSequence(long sequence){Generation a=null;for(Generation g:history)if(g.sequence()<=sequence)a=g;return Optional.ofNullable(a);}
  public synchronized Optional<Generation> generationAt(Order point){Generation a=null;for(Generation g:history)if(g.order().compareTo(point)<=0)a=g;return Optional.ofNullable(a);}
  public synchronized WorldSnapshot snapshotAtSequence(long sequence){return generationAtSequence(sequence).map(Generation::world).orElse(null);}
  /** Returns the latest acknowledged client-visible snapshot whose causal sequence is at or before the query sequence. */
  public synchronized WorldSnapshot snapshotAtOrBefore(long sequence){
    if(sequence<0)return null;
    return generationAtSequence(sequence).map(Generation::world).orElse(null);
  }

  /** Adds an already-visible event; canonical ordering is tick, receive time, capture sequence, ordinal. */
  public synchronized void accept(Event event){Objects.requireNonNull(event);acceptVisible(List.of(event),event.order().sequence());}

  /** Stages a clientbound event until a transaction barrier proves client visibility. */
  public synchronized void queue(Event event){Objects.requireNonNull(event);unassigned.add(event);}
  public synchronized boolean hasUnassignedMutations(){return !unassigned.isEmpty();}
  public synchronized void openBarrier(short transactionId){
    if(pending.containsKey(transactionId))throw new IllegalStateException("transaction barrier already open: "+transactionId);
    if(unassigned.isEmpty())return;
    pending.put(transactionId,List.copyOf(unassigned)); unassigned.clear(); sentOrder.addLast(transactionId);
  }
  public synchronized boolean acknowledge(short transactionId,long acknowledgementSequence){
    if(acknowledgementSequence<0)throw new IllegalArgumentException("acknowledgementSequence must be non-negative");
    if(!pending.containsKey(transactionId))return false;
    List<Event> visible=new ArrayList<>();
    while(!sentOrder.isEmpty()){
      short head=sentOrder.removeFirst(); List<Event> batch=pending.remove(head); if(batch!=null)visible.addAll(batch);
      if(head==transactionId)break;
    }
    acceptVisible(visible,acknowledgementSequence); return true;
  }
  public synchronized void abortBarrier(short transactionId){
    List<Event> batch=pending.remove(transactionId); sentOrder.remove(transactionId); if(batch!=null&&!batch.isEmpty())unassigned.addAll(0,batch);
  }
  public synchronized int pendingBarrierCount(){return pending.size();}
  public synchronized long causalSequence(){return lastVisibleSequence;}
  public synchronized int visibleChunkCount(){return snapshot().loadedChunks().size();}
  public synchronized int decodedStateCacheSize(){int n=0;for(var states:snapshot().chunks().values())n+=states.size();return n;}
  public synchronized WorldSnapshot snapshotAround(double centerX,double centerZ,int radiusChunks){
    if(radiusChunks<0)throw new IllegalArgumentException("radiusChunks must be non-negative");
    WorldSnapshot source=snapshot(); int cx=Math.floorDiv((int)Math.floor(centerX),16),cz=Math.floorDiv((int)Math.floor(centerZ),16);
    WorldSnapshot.Builder b=WorldSnapshot.builder(version,source.minY(),source.maxY());
    for(Chunk chunk:source.loadedChunks()) if(Math.abs(chunk.x()-cx)<=radiusChunks&&Math.abs(chunk.z()-cz)<=radiusChunks){
      b.loadChunk(chunk); for(var e:source.chunkStates(chunk).entrySet()){Pos p=e.getKey(); BlockState s=e.getValue(); if(s.isUnsupported())b.setUnsupportedBlock(p.x(),p.y(),p.z(),s.blockId()); else b.setBlock(p.x(),p.y(),p.z(),s);}
    }
    for(Chunk chunk:source.unknownChunkSet()) if(Math.abs(chunk.x()-cx)<=radiusChunks&&Math.abs(chunk.z()-cz)<=radiusChunks)b.loadUnknownChunk(chunk);
    return b.build();
  }

  private void acceptVisible(List<Event> events,long causalSequence){
    boolean changed=false;
    for(Event event:events){if(!seenSequences.add(event.order().sequence()))continue;journal.put(event.order(),event);changed=true;}
    if(changed||causalSequence>lastVisibleSequence){lastVisibleSequence=Math.max(lastVisibleSequence,causalSequence);rebuild(lastVisibleSequence);}
  }

  /** Adapts the existing Phase 1-3 timeline without consulting the server world. */
  public void accept(Timeline.Event event){
    Packets.Packet p=event.packet().packet();Packets.CaptureProvenance cp=event.packet().provenance();
    Order o=new Order(event.serverTick(),event.packet().receivedNanos(),event.packet().sequence(),nextOrdinal());
    Provenance v=new Provenance(cp.sourceId(),cp.packetType(),event.packet().sequence(),event.serverTick(),cp.authoritativeClientTick(),true,"timeline");
    if(p instanceof Packets.ChunkStates x)accept(new ChunkData(o,v,new Chunk(x.chunk().x(),x.chunk().z()),x.states()));
    else if(p instanceof Packets.ChunkUnload x)accept(new ChunkUnload(o,v,new Chunk(x.chunk().x(),x.chunk().z())));
    else if(p instanceof Packets.BlockStateChange x)accept(new BlockChange(o,v,x.position(),x.state()));
    else if(p instanceof Packets.UnsupportedBlockStateChange x)accept(new BlockChange(o,v,x.position(),x.state()));
    else if(p instanceof Packets.ChunkData x){Map<Pos,BlockState>s=new HashMap<>();for(var e:x.blocks().entrySet())if(e.getValue()!=World.Block.UNKNOWN)s.put(e.getKey().toWorldPos(),World.legacyBlockState(e.getValue()));accept(new ChunkData(o,v,x.chunk().toWorldChunk(),s));}
    else if(p instanceof Packets.BlockChange x)accept(new BlockChange(o,v,x.position().toWorldPos(),World.legacyBlockState(x.block())));
  }
  private long nextOrdinal(){return ordinal++;}

  private void rebuild(){ rebuild(lastVisibleSequence); }

  private void rebuild(long causalSequence){
    Generation old=current.get();String worldId=old.worldId();int minY=old.world().minY(),maxY=old.world().maxY();
    Map<Chunk,Map<Pos,BlockState>> chunks=new TreeMap<>(Comparator.comparingInt(Chunk::x).thenComparingInt(Chunk::z));
    Set<Chunk> loaded=new TreeSet<>(Comparator.comparingInt(Chunk::x).thenComparingInt(Chunk::z));
    Set<Chunk> unknown=new TreeSet<>(Comparator.comparingInt(Chunk::x).thenComparingInt(Chunk::z));
    Map<Integer,EntityCollisions.EntityBox> entities=new TreeMap<>();Map<String,String> metadata=new TreeMap<>();List<Event> applied=new ArrayList<>();
    long tick=0,sequence=0;
    for(Event e:journal.values()){
      if(e instanceof DimensionChange d){worldId=d.worldId();minY=d.minY();maxY=d.maxY();chunks.clear();loaded.clear();unknown.clear();entities.clear();metadata.clear();}
      else if(e instanceof ChunkLoad c){loaded.add(c.chunk());unknown.add(c.chunk());chunks.remove(c.chunk());}
      else if(e instanceof ChunkData c){loaded.add(c.chunk());unknown.remove(c.chunk());TreeMap<Pos,BlockState>s=new TreeMap<>(WorldSnapshot.POS_ORDER);s.putAll(c.states());chunks.put(c.chunk(),s);}
      else if(e instanceof ChunkSections c){loaded.add(c.chunk());unknown.remove(c.chunk());Map<Pos,BlockState> states=chunks.computeIfAbsent(c.chunk(),k->new TreeMap<>(WorldSnapshot.POS_ORDER));for(var section:c.sections().entrySet()){int sectionIndex=section.getKey();int y0=minY+sectionIndex*16;states.keySet().removeIf(p->p.y()>=y0&&p.y()<y0+16);states.putAll(section.getValue());}}
      else if(e instanceof ChunkUnload c){loaded.remove(c.chunk());unknown.remove(c.chunk());chunks.remove(c.chunk());}
      else if(e instanceof BlockChange c)applyBlock(loaded,unknown,chunks,c.position(),c.state());
      else if(e instanceof MultiBlockChange c){List<Pos> ps=new ArrayList<>(c.states().keySet());ps.sort(WorldSnapshot.POS_ORDER);for(Pos p:ps)applyBlock(loaded,unknown,chunks,p,c.states().get(p));}
      else if(e instanceof WorldMetadata m)metadata.putAll(m.values());
      else if(e instanceof EntitySpawn x)entities.put(x.entity().entityId(),x.entity());
      else if(e instanceof EntityMove x)entities.put(x.entity().entityId(),x.entity());
      else if(e instanceof EntityDespawn x)entities.remove(x.entityId());
      applied.add(e);tick=e.order().serverTick();sequence=e.order().sequence();
    }
    WorldSnapshot.Builder b=WorldSnapshot.builder(version,minY,maxY);
    for(Chunk c:loaded){if(unknown.contains(c))b.loadUnknownChunk(c);else b.loadChunk(c);}
    for(Map<Pos,BlockState>s:chunks.values())for(var e:s.entrySet()){Pos p=e.getKey();BlockState state=e.getValue();if(state.isUnsupported())b.setUnsupportedBlock(p.x(),p.y(),p.z(),state.blockId());else b.setBlock(p.x(),p.y(),p.z(),state);}
    WorldSnapshot world=b.build();
    Generation next=new Generation(++generationId,worldId,tick,Math.max(sequence,causalSequence),applied.isEmpty()?old.order():applied.getLast().order(),world,EntityCollisions.of(new ArrayList<>(entities.values())),metadata,applied);
    history.add(next);current.set(next);
  }
  private static void applyBlock(Set<Chunk>loaded,Set<Chunk>unknown,Map<Chunk,Map<Pos,BlockState>>chunks,Pos p,BlockState state){Chunk c=p.chunk();if(!loaded.contains(c)||unknown.contains(c))return;Map<Pos,BlockState>s=chunks.computeIfAbsent(c,k->new TreeMap<>(WorldSnapshot.POS_ORDER));if(state.isAir())s.remove(p);else s.put(p,state);}
  public static Phase4WorldReplica replay(Timeline.Snapshot timeline){Phase4WorldReplica r=new Phase4WorldReplica(timeline.metadata().modelVersion());for(Timeline.Event e:timeline.events())r.accept(e);return r;}
}
