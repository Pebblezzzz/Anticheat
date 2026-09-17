package dev.phantom.ac;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** One deterministic replay artifact for the complete internal Phase 1-7 pipeline. */
public record PipelineReplay(String schemaVersion,Timeline.Snapshot timeline,State.Seed seed,Phase7Timing.Config timingConfig,int maximumCandidates) implements Serializable {
  public static final String SCHEMA_VERSION="pipeline-replay-v1";
  private static final int MAGIC=0x50315250;
  private static final short FORMAT_VERSION=1;

  public PipelineReplay {
    if(!SCHEMA_VERSION.equals(schemaVersion))throw new IllegalArgumentException("unsupported pipeline replay schema");
    Objects.requireNonNull(timeline);Objects.requireNonNull(seed);Objects.requireNonNull(timingConfig);
    if(maximumCandidates<1)throw new IllegalArgumentException("maximumCandidates must be positive");
    timeline=canonicalTimeline(timeline);
  }

  public static PipelineReplay of(Timeline.Snapshot timeline,State.Seed seed,Phase7Timing.Config timingConfig,int maximumCandidates){return new PipelineReplay(SCHEMA_VERSION,timeline,seed,timingConfig,maximumCandidates);}

  /** Reconstructs timeline -> PlayerState -> client-visible world -> timing/synchronization -> validation. */
  public Result replay(){
    State.Reconstruction playerHistory=State.reconstruct(seed,timeline);
    World.VisibilityHistory worldHistory=World.fromTimeline(timeline);
    Phase7Timing.Reconstruction timing=Phase7Timing.reconstruct(timeline,timingConfig);
    LiveValidation.Report validation=LiveValidation.analyze(timeline,maximumCandidates,timingConfig);
    return new Result(timeline,playerHistory,worldHistory,timing,validation,Timeline.projectInputs(timeline));
  }

  /** Strict binary artifact with a provenance sidecar so new packet metadata cannot be lost by legacy Timeline.Codec. */
  public byte[] encode(){
    byte[] timelineBytes=new Timeline.Codec().encode(timeline);
    try(var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes)){
      out.writeInt(MAGIC);out.writeShort(FORMAT_VERSION);out.writeInt(timelineBytes.length);out.write(timelineBytes);
      out.writeInt(timeline.events().size());
      for(Timeline.Event event:timeline.events()){
        Packets.CaptureProvenance p=event.packet().provenance();
        out.writeLong(event.packet().sequence());writeString(out,p.sourceId());writeString(out,p.direction());writeString(out,p.packetType());out.writeBoolean(p.authoritativeServerTick()!=null);if(p.authoritativeServerTick()!=null)out.writeLong(p.authoritativeServerTick());
      }
      writePlayer(out,seed.player());
      List<State.Fact> facts=new ArrayList<>(seed.known());facts.sort(Comparator.comparing(State.Fact::name));out.writeInt(facts.size());for(State.Fact fact:facts)writeString(out,fact.name());
      writeString(out,seed.environment().name());writeConfig(out,timingConfig);out.writeInt(maximumCandidates);out.flush();return bytes.toByteArray();
    }catch(IOException e){throw new IllegalStateException("pipeline replay encoding failed",e);}
  }

  public static PipelineReplay decode(byte[] bytes){
    Objects.requireNonNull(bytes);
    try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
      if(in.readInt()!=MAGIC||in.readUnsignedShort()!=FORMAT_VERSION)throw new IllegalArgumentException("unsupported pipeline replay format");
      int len=in.readInt();if(len<0||len>64*1024*1024)throw new IllegalArgumentException("invalid timeline payload length");byte[] timelineBytes=in.readNBytes(len);if(timelineBytes.length!=len)throw new EOFException("truncated timeline payload");
      Timeline.Snapshot base=new Timeline.Codec().decode(timelineBytes);
      int provenanceCount=in.readInt();if(provenanceCount<0||provenanceCount!=base.events().size())throw new IllegalArgumentException("provenance table length does not match timeline");
      Map<Long,Packets.CaptureProvenance> provenance=new HashMap<>();
      for(int i=0;i<provenanceCount;i++){long seq=in.readLong();String source=readString(in),direction=readString(in),packetType=readString(in);Long authoritative=in.readBoolean()?in.readLong():null;if(provenance.put(seq,new Packets.CaptureProvenance(source,direction,packetType,authoritative))!=null)throw new IllegalArgumentException("duplicate provenance sequence "+seq);}
      List<Timeline.Event> events=new ArrayList<>(base.events().size());
      for(Timeline.Event event:base.events()){
        Packets.CaptureProvenance p=provenance.get(event.packet().sequence());if(p==null)throw new IllegalArgumentException("missing provenance for sequence "+event.packet().sequence());
        long serverTick=p.authoritativeServerTick()!=null?p.authoritativeServerTick():event.serverTick();
        Packets.NormalizedPacket packet=new Packets.NormalizedPacket(event.packet().sequence(),event.packet().receivedNanos(),event.packet().packet(),event.packet().flags(),p);
        events.add(new Timeline.Event(serverTick,packet));
      }
      Timeline.Snapshot timeline=new Timeline.Snapshot(base.metadata(),events);
      State.Player player=readPlayer(in);
      int factsCount=in.readInt();if(factsCount<0||factsCount>64)throw new IllegalArgumentException("invalid fact count");EnumSet<State.Fact> facts=EnumSet.noneOf(State.Fact.class);for(int i=0;i<factsCount;i++)facts.add(State.Fact.valueOf(readString(in)));
      State.Environment environment=State.Environment.valueOf(readString(in));Phase7Timing.Config config=readConfig(in);int max=in.readInt();if(in.available()!=0)throw new IllegalArgumentException("trailing pipeline replay data");
      return of(timeline,new State.Seed(player,facts,environment),config,max);
    }catch(EOFException e){throw new IllegalArgumentException("truncated pipeline replay",e);}catch(IOException|IllegalArgumentException e){throw new IllegalArgumentException("invalid pipeline replay",e);}
  }

  public record Result(Timeline.Snapshot timeline,State.Reconstruction playerHistory,World.VisibilityHistory worldHistory,Phase7Timing.Reconstruction timing,LiveValidation.Report validation,List<Timeline.InputSample> simulationInputs) implements Serializable {public Result{Objects.requireNonNull(timeline);Objects.requireNonNull(playerHistory);Objects.requireNonNull(worldHistory);Objects.requireNonNull(timing);Objects.requireNonNull(validation);simulationInputs=List.copyOf(simulationInputs);}}

  private static Timeline.Snapshot canonicalTimeline(Timeline.Snapshot source){
    List<Timeline.Event> events=new ArrayList<>(source.events().size());
    for(Timeline.Event event:source.events()){
      Long authoritative=event.packet().provenance().authoritativeServerTick();
      long tick=authoritative==null?event.serverTick():authoritative;
      Packets.NormalizedPacket packet=new Packets.NormalizedPacket(event.packet().sequence(),event.packet().receivedNanos(),event.packet().packet(),event.packet().flags(),event.packet().provenance());
      events.add(new Timeline.Event(tick,packet));
    }
    events.sort(Comparator.comparingLong(Timeline.Event::serverTick).thenComparingLong(e->e.packet().receivedNanos()).thenComparingLong(e->e.packet().sequence()));
    return new Timeline.Snapshot(source.metadata(),events);
  }

  private static void writePlayer(DataOutputStream out,State.Player p)throws IOException{
    writeVec(out,p.position());writeVec(out,p.velocity());out.writeFloat(p.yaw());out.writeFloat(p.pitch());out.writeBoolean(p.onGround());writeString(out,p.gamemode());
    out.writeInt(p.effects().size());for(var e:new TreeMap<>(p.effects()).entrySet()){writeString(out,e.getKey());out.writeInt(e.getValue());}
    out.writeBoolean(p.awaitingTeleport().isPresent());if(p.awaitingTeleport().isPresent())out.writeInt(p.awaitingTeleport().getAsInt());out.writeBoolean(p.uncertain());
    out.writeBoolean(p.input().isPresent());if(p.input().isPresent()){var i=p.input().get();out.writeInt(i.forward());out.writeInt(i.strafe());out.writeBoolean(i.jump());out.writeBoolean(i.sprint());out.writeBoolean(i.sneak());}
    out.writeDouble(p.attributes().movementSpeed());out.writeInt(p.attributes().modifiers().size());for(var m:p.attributes().modifiers()){writeString(out,m.id());out.writeDouble(m.amount());writeString(out,m.operation().name());}
    writeString(out,p.pose().name());writeString(out,p.environment().name());out.writeLong(p.clientTickRange().min());out.writeLong(p.clientTickRange().max());out.writeBoolean(p.clientTickRange().exact());
    out.writeLong(p.provenance().sequence());out.writeLong(p.provenance().serverTick());writeString(out,p.provenance().packetType());
    List<State.UncertaintyReason> reasons=new ArrayList<>(p.uncertaintyReasons());reasons.sort(Comparator.comparing(State.UncertaintyReason::name));out.writeInt(reasons.size());for(var reason:reasons)writeString(out,reason.name());
  }

  private static State.Player readPlayer(DataInputStream in)throws IOException{
    Maths.Vec3 pos=readVec(in),vel=readVec(in);float yaw=in.readFloat(),pitch=in.readFloat();boolean ground=in.readBoolean();String gm=readString(in);int effectCount=in.readInt();if(effectCount<0||effectCount>10000)throw new IOException("invalid effect count");Map<String,Integer> effects=new HashMap<>();for(int i=0;i<effectCount;i++)effects.put(readString(in),in.readInt());OptionalInt tp=in.readBoolean()?OptionalInt.of(in.readInt()):OptionalInt.empty();boolean uncertain=in.readBoolean();Optional<Simulation.AdvancedInput> input=in.readBoolean()?Optional.of(new Simulation.AdvancedInput(in.readInt(),in.readInt(),in.readBoolean(),in.readBoolean(),in.readBoolean())):Optional.empty();double speed=in.readDouble();int modifierCount=in.readInt();if(modifierCount<0||modifierCount>10000)throw new IOException("invalid modifier count");List<Phase5Mechanics.AttributeModifier> modifiers=new ArrayList<>();for(int i=0;i<modifierCount;i++)modifiers.add(new Phase5Mechanics.AttributeModifier(readString(in),in.readDouble(),Phase5Mechanics.ModifierOperation.valueOf(readString(in))));Phase5Mechanics.Pose pose=Phase5Mechanics.Pose.valueOf(readString(in));State.Environment environment=State.Environment.valueOf(readString(in));State.TickRange ticks=new State.TickRange(in.readLong(),in.readLong(),in.readBoolean());State.Provenance provenance=new State.Provenance(in.readLong(),in.readLong(),readString(in));int reasonCount=in.readInt();if(reasonCount<0||reasonCount>100)throw new IOException("invalid uncertainty-reason count");EnumSet<State.UncertaintyReason> reasons=EnumSet.noneOf(State.UncertaintyReason.class);for(int i=0;i<reasonCount;i++)reasons.add(State.UncertaintyReason.valueOf(readString(in)));return new State.Player(pos,vel,yaw,pitch,ground,gm,effects,tp,uncertain,input,new Simulation.Attributes(speed,modifiers),pose,environment,ticks,provenance,reasons);
  }

  private static void writeConfig(DataOutputStream out,Phase7Timing.Config c)throws IOException{out.writeLong(c.serverTickNanos());out.writeLong(c.clientTickMinNanos());out.writeLong(c.clientTickMaxNanos());out.writeLong(c.upstreamLatency().minNanos());out.writeLong(c.upstreamLatency().maxNanos());out.writeLong(c.downstreamLatency().minNanos());out.writeLong(c.downstreamLatency().maxNanos());out.writeLong(c.inputToSimulation().minTicks());out.writeLong(c.inputToSimulation().maxTicks());out.writeLong(c.simulationToPacket().minTicks());out.writeLong(c.simulationToPacket().maxTicks());out.writeLong(c.packetGapThresholdNanos());out.writeInt(c.recoveryStableEvents());out.writeInt(c.maxTimingCandidates());}
  private static Phase7Timing.Config readConfig(DataInputStream in)throws IOException{return new Phase7Timing.Config(in.readLong(),in.readLong(),in.readLong(),new Phase7Timing.LatencyBounds(in.readLong(),in.readLong()),new Phase7Timing.LatencyBounds(in.readLong(),in.readLong()),new Phase7Timing.TickDelayBounds(in.readLong(),in.readLong()),new Phase7Timing.TickDelayBounds(in.readLong(),in.readLong()),in.readLong(),in.readInt(),in.readInt());}
  private static void writeVec(DataOutputStream out,Maths.Vec3 v)throws IOException{out.writeDouble(v.x());out.writeDouble(v.y());out.writeDouble(v.z());}
  private static Maths.Vec3 readVec(DataInputStream in)throws IOException{byte[] b=in.readNBytes(24);if(b.length!=24)throw new EOFException("truncated vector");try(var d=new DataInputStream(new ByteArrayInputStream(b))){return new Maths.Vec3(d.readDouble(),d.readDouble(),d.readDouble());}}
  private static void writeString(DataOutputStream out,String value)throws IOException{byte[] b=value.getBytes(StandardCharsets.UTF_8);if(b.length>1_000_000)throw new IllegalArgumentException("string too large");out.writeInt(b.length);out.write(b);}
  private static String readString(DataInputStream in)throws IOException{int n=in.readInt();if(n<0||n>1_000_000)throw new IOException("invalid string length");byte[] b=in.readNBytes(n);if(b.length!=n)throw new EOFException("truncated string");return new String(b,StandardCharsets.UTF_8);}
}
