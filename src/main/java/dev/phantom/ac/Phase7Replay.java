package dev.phantom.ac;

import java.io.*;
import java.util.*;

/** Self-contained Phase 7 replay artifact: canonical packet timeline + explicit timing configuration. */
public record Phase7Replay(
    String schemaVersion,
    Timeline.Snapshot timeline,
    Phase7Timing.Config config) implements Serializable {

  public static final String SCHEMA_VERSION = "phase7-replay-v2";
  private static final int MAGIC = 0x50375250;
  private static final short FORMAT_VERSION = 2;

  public record Verification(
      boolean identical,
      Optional<Phase7Timing.FirstDivergence> firstDivergence,
      String expectedCanonical,
      String actualCanonical) implements Serializable {
    public Verification {
      Objects.requireNonNull(firstDivergence);
      Objects.requireNonNull(expectedCanonical);
      Objects.requireNonNull(actualCanonical);
    }
  }

  public Phase7Replay {
    if (!SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException("unsupported Phase 7 replay schema: " + schemaVersion);
    }
    Objects.requireNonNull(timeline);
    Objects.requireNonNull(config);
  }

  public static Phase7Replay of(
      Timeline.Snapshot timeline,
      Phase7Timing.Config config) {
    return new Phase7Replay(SCHEMA_VERSION, timeline, config);
  }

  public Phase7Timing.Reconstruction reconstruct() {
    return Phase7Timing.reconstruct(timeline, config);
  }

  public Phase7History.Reconstruction reconstructHistory(State.Seed seed) {
    return Phase7History.reconstruct(timeline, seed, config);
  }

  public Verification verifyAgainst(Phase7Timing.Reconstruction expected) {
    Objects.requireNonNull(expected);
    Phase7Timing.Reconstruction actual = reconstruct();
    Optional<Phase7Timing.FirstDivergence> divergence =
        Phase7Timing.firstDivergence(expected, actual);
    return new Verification(
        divergence.isEmpty(),
        divergence,
        expected.canonicalText(),
        actual.canonicalText());
  }

  public byte[] encode() {
    byte[] timelineBytes = new Timeline.Codec().encode(timeline);
    try (var bytes = new ByteArrayOutputStream();
         var out = new DataOutputStream(bytes)) {
      out.writeInt(MAGIC);
      out.writeShort(FORMAT_VERSION);
      out.writeInt(timelineBytes.length);
      out.write(timelineBytes);
      writeConfig(out, config, FORMAT_VERSION);
      out.flush();
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new IllegalStateException(
          "in-memory Phase 7 replay encoding failed", impossible);
    }
  }

  public static Phase7Replay decode(byte[] bytes) {
    if (bytes == null) {
      throw new IllegalArgumentException("Phase 7 replay bytes are required");
    }
    try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
      if (in.readInt() != MAGIC) {
        throw new IllegalArgumentException("unsupported Phase 7 replay format");
      }
      int version = in.readUnsignedShort();
      if (version < 1 || version > FORMAT_VERSION) {
        throw new IllegalArgumentException("unsupported Phase 7 replay format");
      }
      int length = in.readInt();
      if (length < 0 || length > 64 * 1024 * 1024) {
        throw new IllegalArgumentException("invalid timeline payload length");
      }
      byte[] timelineBytes = in.readNBytes(length);
      if (timelineBytes.length != length) {
        throw new EOFException("truncated timeline payload");
      }
      Timeline.Snapshot timeline = new Timeline.Codec().decode(timelineBytes);
      Phase7Timing.Config config = readConfig(in, version);
      if (in.available() != 0) {
        throw new IllegalArgumentException("trailing Phase 7 replay data");
      }
      return new Phase7Replay(SCHEMA_VERSION, timeline, config);
    } catch (EOFException e) {
      throw new IllegalArgumentException("truncated Phase 7 replay", e);
    } catch (IOException | IllegalArgumentException e) {
      throw new IllegalArgumentException("invalid Phase 7 replay", e);
    }
  }

  public String canonicalText() {
    return SCHEMA_VERSION + "\n"
        + "timelineBytes="
        + Base64.getEncoder().encodeToString(new Timeline.Codec().encode(timeline))
        + "\n"
        + "config=" + config + "\n"
        + "reconstruction=" + reconstruct().canonicalText();
  }

  private static void writeConfig(
      DataOutputStream out,
      Phase7Timing.Config c,
      int version) throws IOException {
    out.writeLong(c.serverTickNanos());
    out.writeLong(c.clientTickMinNanos());
    out.writeLong(c.clientTickMaxNanos());
    writeLatency(out, c.upstreamLatency());
    writeLatency(out, c.downstreamLatency());
    writeDelay(out, c.inputToSimulation());
    writeDelay(out, c.simulationToPacket());
    out.writeLong(c.packetGapThresholdNanos());
    out.writeInt(c.recoveryStableEvents());
    out.writeInt(c.maxTimingCandidates());
    if (version >= 2) {
      out.writeLong(c.maxTimingHistories());
    }
  }

  private static Phase7Timing.Config readConfig(
      DataInputStream in,
      int version) throws IOException {
    long serverTickNanos = in.readLong();
    long clientTickMinNanos = in.readLong();
    long clientTickMaxNanos = in.readLong();
    Phase7Timing.LatencyBounds upstream = readLatency(in);
    Phase7Timing.LatencyBounds downstream = readLatency(in);
    Phase7Timing.TickDelayBounds inputDelay = readDelay(in);
    Phase7Timing.TickDelayBounds simulationDelay = readDelay(in);
    long packetGap = in.readLong();
    int recoveryStable = in.readInt();
    int maxTimingCandidates = in.readInt();
    long maxTimingHistories = version >= 2 ? in.readLong() : 65_536L;
    return new Phase7Timing.Config(
        serverTickNanos,
        clientTickMinNanos,
        clientTickMaxNanos,
        upstream,
        downstream,
        inputDelay,
        simulationDelay,
        packetGap,
        recoveryStable,
        maxTimingCandidates,
        maxTimingHistories);
  }

  private static void writeLatency(
      DataOutputStream out,
      Phase7Timing.LatencyBounds b) throws IOException {
    out.writeLong(b.minNanos());
    out.writeLong(b.maxNanos());
  }

  private static Phase7Timing.LatencyBounds readLatency(
      DataInputStream in) throws IOException {
    return new Phase7Timing.LatencyBounds(in.readLong(), in.readLong());
  }

  private static void writeDelay(
      DataOutputStream out,
      Phase7Timing.TickDelayBounds d) throws IOException {
    out.writeLong(d.minTicks());
    out.writeLong(d.maxTicks());
  }

  private static Phase7Timing.TickDelayBounds readDelay(
      DataInputStream in) throws IOException {
    return new Phase7Timing.TickDelayBounds(in.readLong(), in.readLong());
  }
}
