package dev.phantom.ac;

import java.io.*;
import java.util.*;
import static dev.phantom.ac.Packets.*;
import static dev.phantom.ac.Simulation.Input;

/** Deterministic projection of capture-order packets onto server ticks. */
public final class Timeline {
  private Timeline() {}
  private static final int MAGIC=0x50484143; // PHAC
  private static final short FORMAT_VERSION=1;

  public record Metadata(String modelVersion,long captureEpochNanos,long serverTickNanos) implements Serializable {
    public Metadata { Contracts.requireTargetVersion(modelVersion); if(captureEpochNanos<0||serverTickNanos<=0) throw new IllegalArgumentException("invalid timeline clock metadata"); }
  }
  public record Event(long serverTick,NormalizedPacket packet) implements Serializable { public Event { if(serverTick<0) throw new IllegalArgumentException("serverTick must be non-negative"); Objects.requireNonNull(packet,"packet"); } }
  public record Snapshot(Metadata metadata,List<Event> events) implements Serializable {
    public Snapshot { Objects.requireNonNull(metadata,"metadata"); events=List.copyOf(events); Event prior=null; for(Event event:events){if(prior!=null&&EVENT_ORDER.compare(prior,event)>0)throw new IllegalArgumentException("events must be canonical timeline ordered");prior=event;} }
    public Snapshot(List<Event> events) { this(new Metadata(Contracts.TARGET_VERSION,0,50_000_000L),events); }
  }
  private static final Comparator<Event> EVENT_ORDER=Comparator.comparingLong(Event::serverTick).thenComparing(event->event.packet().receivedNanos()).thenComparing(event->event.packet().sequence());
  public static final class Reconstructor implements Contracts.TimelineReconstructor { @Override public Snapshot reconstruct(Collection<NormalizedPacket> packets,long epochNanos,long serverTickNanos){return assign(packets,epochNanos,serverTickNanos);} }

  public static Snapshot assign(Collection<NormalizedPacket> packets,long epochNanos,long tickNanos) {
    if(epochNanos<0||tickNanos<=0) throw new IllegalArgumentException("capture epoch must be non-negative and tickNanos positive");
    List<Event> events=new ArrayList<>();
    for(NormalizedPacket packet:packets){EnumSet<PacketFlag> flags=packet.flags();long tick;if(packet.receivedNanos()<epochNanos){flags.add(PacketFlag.BEFORE_CAPTURE_EPOCH);tick=0;}else tick=(packet.receivedNanos()-epochNanos)/tickNanos;events.add(new Event(tick,new NormalizedPacket(packet.sequence(),packet.receivedNanos(),packet.packet(),flags)));}
    events.sort(EVENT_ORDER);
    return new Snapshot(new Metadata(Contracts.TARGET_VERSION,epochNanos,tickNanos),events);
  }
  /** Evidence summary for packet/timeline health; it is never a cheat verdict. */
  public record Integrity(int events,int duplicatePackets,int outOfOrderPackets,int sequenceGaps,int preEpochPackets,int movementPackets,int inputPackets,int teleportPackets,int velocityPackets) {}
  public static Integrity inspect(Snapshot timeline){
    int duplicates=0,outOfOrder=0,gaps=0,preEpoch=0,moves=0,inputs=0,teleports=0,velocities=0;
    for(Event event:timeline.events()){EnumSet<PacketFlag> flags=event.packet().flags();if(flags.contains(PacketFlag.DUPLICATE))duplicates++;if(flags.contains(PacketFlag.OUT_OF_ORDER))outOfOrder++;if(flags.contains(PacketFlag.SEQUENCE_GAP))gaps++;if(flags.contains(PacketFlag.BEFORE_CAPTURE_EPOCH))preEpoch++;Packet packet=event.packet().packet();if(packet instanceof Move)moves++;else if(packet instanceof ClientInput)inputs++;else if(packet instanceof Teleport||packet instanceof TeleportConfirm)teleports++;else if(packet instanceof Velocity)velocities++;}
    return new Integrity(timeline.events().size(),duplicates,outOfOrder,gaps,preEpoch,moves,inputs,teleports,velocities);
  }

  /** Counts of the client-visible world mutation packets in a timeline. */
  public record WorldIntegrity(int chunkPayloads,int chunkUnloads,int blockChanges,int worldStatePayloads) {}

  /** Reports how much client-visible world information a timeline actually carries. */
  public static WorldIntegrity inspectWorld(Snapshot timeline){
    int chunks=0,unloads=0,blocks=0,states=0;
    for(Event event:timeline.events()){Packet packet=event.packet().packet();
      if(packet instanceof ChunkData)chunks++;
      else if(packet instanceof ChunkStates)states++;
      else if(packet instanceof ChunkUnload)unloads++;
      else if(packet instanceof BlockChange||packet instanceof BlockStateChange)blocks++;
    }
    return new WorldIntegrity(chunks,unloads,blocks,states);
  }

  /** Fixed binary replay format: byte-stable and independent of JVM object serialization. */
  public static final class Codec {
    public byte[] encode(Snapshot snapshot) {
      try(var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes)){out.writeInt(MAGIC);out.writeShort(FORMAT_VERSION);writeString(out,snapshot.metadata().modelVersion());out.writeLong(snapshot.metadata().captureEpochNanos());out.writeLong(snapshot.metadata().serverTickNanos());out.writeInt(snapshot.events().size());for(Event event:snapshot.events())writeEvent(out,event);out.flush();return bytes.toByteArray();}catch(IOException impossible){throw new IllegalStateException("in-memory replay encoding failed",impossible);}
    }
    public Snapshot decode(byte[] bytes) {
      if(bytes==null)throw new IllegalArgumentException("replay bytes are required");
      try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){if(in.readInt()!=MAGIC||in.readUnsignedShort()!=FORMAT_VERSION)throw new IllegalArgumentException("unsupported replay format");Metadata metadata=new Metadata(readString(in),in.readLong(),in.readLong());int count=readCount(in,"event");List<Event> events=new ArrayList<>(count);for(int i=0;i<count;i++)events.add(readEvent(in));if(in.available()!=0)throw new IllegalArgumentException("trailing replay data");return new Snapshot(metadata,events);}catch(EOFException e){throw new IllegalArgumentException("truncated replay",e);}catch(IOException|IllegalArgumentException e){throw new IllegalArgumentException("invalid replay",e);}
    }
    private static void writeEvent(DataOutputStream out,Event event)throws IOException{out.writeLong(event.serverTick());NormalizedPacket p=event.packet();out.writeLong(p.sequence());out.writeLong(p.receivedNanos());out.writeInt(flags(p.flags()));writePacket(out,p.packet());}
    private static Event readEvent(DataInputStream in)throws IOException{long tick=in.readLong(),seq=in.readLong(),nanos=in.readLong();EnumSet<PacketFlag> flags=readFlags(in.readInt());return new Event(tick,new NormalizedPacket(seq,nanos,readPacket(in),flags));}
    private static int flags(EnumSet<PacketFlag> flags){int bits=0;for(PacketFlag flag:flags)bits|=1<<flag.ordinal();return bits;}
    private static EnumSet<PacketFlag> readFlags(int bits){EnumSet<PacketFlag> flags=EnumSet.noneOf(PacketFlag.class);for(PacketFlag flag:PacketFlag.values())if((bits&(1<<flag.ordinal()))!=0)flags.add(flag);if((bits>>>PacketFlag.values().length)!=0)throw new IllegalArgumentException("unknown packet flag");return flags;}
    private static void writePacket(DataOutputStream out,Packet p)throws IOException{
      if(p instanceof Move v){out.writeByte(1);writeNullableVec(out,v.position());writeNullableFloat(out,v.yaw());writeNullableFloat(out,v.pitch());writeNullableBoolean(out,v.onGround());writeNullableLong(out,v.clientTick());}
      else if(p instanceof ClientInput v){out.writeByte(2);out.writeByte((v.forward()?1:0)|(v.backward()?2:0)|(v.left()?4:0)|(v.right()?8:0)|(v.jump()?16:0)|(v.sneak()?32:0)|(v.sprint()?64:0));}
      else if(p instanceof Teleport v){out.writeByte(3);out.writeInt(v.id());writeVec(out,v.position());out.writeFloat(v.yaw());out.writeFloat(v.pitch());out.writeByte((v.relativeX()?1:0)|(v.relativeY()?2:0)|(v.relativeZ()?4:0)|(v.relativeYaw()?8:0)|(v.relativePitch()?16:0));}
      else if(p instanceof TeleportConfirm v){out.writeByte(4);out.writeInt(v.id());}
      else if(p instanceof Velocity v){out.writeByte(5);writeVec(out,v.velocity());}
      else if(p instanceof Effect v){out.writeByte(6);writeString(out,v.id());out.writeInt(v.amplifier());out.writeBoolean(v.removed());}
      else if(p instanceof Gamemode v){out.writeByte(7);writeString(out,v.value());}
      else if(p instanceof ChunkData v){out.writeByte(8);writeChunk(out,v.chunk());List<Map.Entry<World.Pos,World.Block>> entries=new ArrayList<>(v.blocks().entrySet());entries.sort(Comparator.comparingInt((Map.Entry<World.Pos,World.Block> e)->e.getKey().x()).thenComparingInt(e->e.getKey().y()).thenComparingInt(e->e.getKey().z()));out.writeInt(entries.size());for(var entry:entries){writePos(out,entry.getKey());out.writeByte(entry.getValue().ordinal());}}
      else if(p instanceof ChunkUnload v){out.writeByte(9);writeChunk(out,v.chunk());}
      else if(p instanceof BlockChange v){out.writeByte(10);writePos(out,v.position());out.writeByte(v.block().ordinal());}
      else if(p instanceof BlockStateChange v){out.writeByte(11);writeWorldPos(out,v.position());writeBlockState(out,v.state());}
      else if(p instanceof UnsupportedBlockStateChange v){out.writeByte(13);writeWorldPos(out,v.position());writeBlockState(out,v.state());}
      else if(p instanceof ChunkStates v){out.writeByte(12);writeWorldChunk(out,v.chunk());List<Map.Entry<dev.phantom.ac.world.Pos,dev.phantom.ac.world.BlockState>> entries=new ArrayList<>(v.states().entrySet());entries.sort(Comparator.comparingInt((Map.Entry<dev.phantom.ac.world.Pos,dev.phantom.ac.world.BlockState> e)->e.getKey().x()).thenComparingInt(e->e.getKey().y()).thenComparingInt(e->e.getKey().z()));out.writeInt(entries.size());for(var entry:entries){writeWorldPos(out,entry.getKey());writeBlockState(out,entry.getValue());}}
      else throw new IllegalArgumentException("unsupported packet type: "+p.getClass());
    }
    private static Packet readPacket(DataInputStream in)throws IOException{return switch(in.readUnsignedByte()){
      case 1->new Move(readNullableVec(in),readNullableFloat(in),readNullableFloat(in),readNullableBoolean(in),readNullableLong(in));
      case 2->{int b=in.readUnsignedByte();if((b&~127)!=0)throw new IllegalArgumentException("unknown input bits");yield new ClientInput((b&1)!=0,(b&2)!=0,(b&4)!=0,(b&8)!=0,(b&16)!=0,(b&32)!=0,(b&64)!=0);}
      case 3->{int id=in.readInt();var pos=readVec(in);float yaw=in.readFloat(),pitch=in.readFloat();int b=in.readUnsignedByte();if((b&~31)!=0)throw new IllegalArgumentException("unknown teleport bits");yield new Teleport(id,pos,yaw,pitch,(b&1)!=0,(b&2)!=0,(b&4)!=0,(b&8)!=0,(b&16)!=0);}
      case 4->new TeleportConfirm(in.readInt());case 5->new Velocity(readVec(in));case 6->new Effect(readString(in),in.readInt(),in.readBoolean());case 7->new Gamemode(readString(in));
      case 8->{World.Chunk chunk=readChunk(in);int count=readCount(in,"chunk block");Map<World.Pos,World.Block> blocks=new HashMap<>();for(int i=0;i<count;i++){World.Pos pos=readPos(in);if(blocks.put(pos,readBlock(in))!=null)throw new IllegalArgumentException("duplicate chunk block");}yield new ChunkData(chunk,blocks);}
      case 9->new ChunkUnload(readChunk(in));case 10->new BlockChange(readPos(in),readBlock(in));
      case 11->new BlockStateChange(readWorldPos(in),readBlockState(in));
      case 13->new UnsupportedBlockStateChange(readWorldPos(in),readBlockState(in));
      case 12->{dev.phantom.ac.world.Chunk chunk=readWorldChunk(in);int count=readCount(in,"chunk state");Map<dev.phantom.ac.world.Pos,dev.phantom.ac.world.BlockState> states=new HashMap<>();for(int i=0;i<count;i++){dev.phantom.ac.world.Pos pos=readWorldPos(in);if(states.put(pos,readBlockState(in))!=null)throw new IllegalArgumentException("duplicate chunk state");}yield new ChunkStates(chunk,states);}
      default->throw new IllegalArgumentException("unknown packet tag");};}

    /**
     * Block states are written as their full property set rather than an ordinal,
     * so the replay format stays valid when the shape catalogue grows and never
     * silently reinterprets an old recording as a different block.
     */
    private static void writeBlockState(DataOutputStream out,dev.phantom.ac.world.BlockState state)throws IOException{
      writeString(out,state.blockId());
      out.writeByte(state.variant().ordinal());
      out.writeByte(state.facing().ordinal());
      out.writeByte(state.half().ordinal());
      out.writeByte(state.stairShape().ordinal());
      out.writeByte(state.layers());
      out.writeByte(state.level());
      int bits=(state.waterlogged()?1:0)|(state.open()?2:0)|(state.powered()?4:0)|(state.up()?8:0)|(state.north()?16:0)|(state.south()?32:0)|(state.west()?64:0)|(state.east()?128:0);
      out.writeByte(bits);
      int bits2=(state.northTall()?1:0)|(state.southTall()?2:0)|(state.westTall()?4:0)|(state.eastTall()?8:0)|(state.poweredState()?16:0);
      out.writeByte(bits2);
      out.writeByte(state.candles());
      out.writeByte(state.provenance().ordinal());
    }

    private static dev.phantom.ac.world.BlockState readBlockState(DataInputStream in)throws IOException{
      String blockId=readString(in);
      var variant=ordinal(dev.phantom.ac.world.BlockState.Variant.values(),in.readUnsignedByte(),"block variant");
      var facing=ordinal(dev.phantom.ac.geometry.Directions.Direction.values(),in.readUnsignedByte(),"facing");
      var half=ordinal(dev.phantom.ac.world.BlockState.Half.values(),in.readUnsignedByte(),"half");
      var shape=ordinal(dev.phantom.ac.world.BlockState.StairShape.values(),in.readUnsignedByte(),"stair shape");
      int layers=in.readUnsignedByte();int level=in.readUnsignedByte();int bits=in.readUnsignedByte();int bits2=in.readUnsignedByte();int candles=in.readUnsignedByte();
      var provenance=ordinal(dev.phantom.ac.world.BlockState.PropertySource.values(),in.readUnsignedByte(),"property source");
      return new dev.phantom.ac.world.BlockState(blockId,variant,facing,half,shape,layers,level,
        (bits&1)!=0,(bits&2)!=0,(bits&4)!=0,(bits&8)!=0,(bits&16)!=0,(bits&32)!=0,(bits&64)!=0,(bits&128)!=0,
        (bits2&1)!=0,(bits2&2)!=0,(bits2&4)!=0,(bits2&8)!=0,candles,(bits2&16)!=0,provenance);
    }

    private static <T> T ordinal(T[] values,int ordinal,String what)throws IOException{
      if(ordinal>=values.length)throw new IOException("unknown "+what+" ordinal "+ordinal);
      return values[ordinal];
    }
    private static void writeVec(DataOutputStream out,Maths.Vec3 v)throws IOException{out.writeDouble(v.x());out.writeDouble(v.y());out.writeDouble(v.z());}
    private static Maths.Vec3 readVec(DataInputStream in)throws IOException{return new Maths.Vec3(in.readDouble(),in.readDouble(),in.readDouble());}
    private static void writeNullableVec(DataOutputStream out,Maths.Vec3 v)throws IOException{out.writeBoolean(v!=null);if(v!=null)writeVec(out,v);}
    private static Maths.Vec3 readNullableVec(DataInputStream in)throws IOException{return in.readBoolean()?readVec(in):null;}
    private static void writeNullableFloat(DataOutputStream out,Float v)throws IOException{out.writeBoolean(v!=null);if(v!=null)out.writeFloat(v);}
    private static Float readNullableFloat(DataInputStream in)throws IOException{return in.readBoolean()?in.readFloat():null;}
    private static void writeNullableBoolean(DataOutputStream out,Boolean v)throws IOException{out.writeByte(v==null?0:v?1:2);}
    private static Boolean readNullableBoolean(DataInputStream in)throws IOException{return switch(in.readUnsignedByte()){case 0->null;case 1->true;case 2->false;default->throw new IllegalArgumentException("invalid nullable boolean");};}
    private static void writeNullableLong(DataOutputStream out,Long v)throws IOException{out.writeBoolean(v!=null);if(v!=null)out.writeLong(v);}
    private static Long readNullableLong(DataInputStream in)throws IOException{return in.readBoolean()?in.readLong():null;}
    private static void writeString(DataOutputStream out,String value)throws IOException{byte[] b=value.getBytes(java.nio.charset.StandardCharsets.UTF_8);if(b.length>1_000_000)throw new IllegalArgumentException("string too long");out.writeInt(b.length);out.write(b);}
    private static String readString(DataInputStream in)throws IOException{int n=readCount(in,"string byte");byte[] b=in.readNBytes(n);if(b.length!=n)throw new EOFException();return new String(b,java.nio.charset.StandardCharsets.UTF_8);}
    private static int readCount(DataInputStream in,String what)throws IOException{int n=in.readInt();if(n<0||n>1_000_000)throw new IllegalArgumentException("invalid "+what+" count");return n;}
    private static void writePos(DataOutputStream out,World.Pos p)throws IOException{out.writeInt(p.x());out.writeInt(p.y());out.writeInt(p.z());}
    private static World.Pos readPos(DataInputStream in)throws IOException{return new World.Pos(in.readInt(),in.readInt(),in.readInt());}
    private static void writeChunk(DataOutputStream out,World.Chunk c)throws IOException{out.writeInt(c.x());out.writeInt(c.z());}
    private static World.Chunk readChunk(DataInputStream in)throws IOException{return new World.Chunk(in.readInt(),in.readInt());}
    private static void writeWorldPos(DataOutputStream out,dev.phantom.ac.world.Pos p)throws IOException{out.writeInt(p.x());out.writeInt(p.y());out.writeInt(p.z());}
    private static dev.phantom.ac.world.Pos readWorldPos(DataInputStream in)throws IOException{return new dev.phantom.ac.world.Pos(in.readInt(),in.readInt(),in.readInt());}
    private static void writeWorldChunk(DataOutputStream out,dev.phantom.ac.world.Chunk c)throws IOException{out.writeInt(c.x());out.writeInt(c.z());}
    private static dev.phantom.ac.world.Chunk readWorldChunk(DataInputStream in)throws IOException{return new dev.phantom.ac.world.Chunk(in.readInt(),in.readInt());}
    private static World.Block readBlock(DataInputStream in)throws IOException{int ordinal=in.readUnsignedByte();World.Block[] values=World.Block.values();if(ordinal>=values.length)throw new IllegalArgumentException("unknown block");return values[ordinal];}
  }
  public static List<InputSample> projectInputs(Snapshot timeline){List<InputSample> out=new ArrayList<>();Input current=null;for(Event event:timeline.events()){if(event.packet().packet() instanceof ClientInput input)current=toSimulationInput(input);if(event.packet().packet() instanceof Move)out.add(new InputSample(event,Optional.ofNullable(current)));}return List.copyOf(out);}

  /**
   * The server ticks carrying a client-visible world change, ascending. Phase 5
   * uses this to know which ticks need a fresh world snapshot.
   */
  public static List<Long> worldChangeTicks(Snapshot timeline){
    List<Long> ticks=new ArrayList<>();
    for(Event event:timeline.events())if(event.packet().packet().mutatesWorld())ticks.add(event.serverTick());
    ticks.sort(java.util.Comparator.naturalOrder());
    return List.copyOf(ticks);
  }
  public record InputSample(Event movement,Optional<Input> input){public InputSample{Objects.requireNonNull(movement,"movement");input=Objects.requireNonNull(input,"input");}}
  private static Input toSimulationInput(ClientInput input){return new Input(axis(input.forward(),input.backward()),axis(input.right(),input.left()),input.jump());}
  private static int axis(boolean positive,boolean negative){return positive==negative?0:positive?1:-1;}
}
