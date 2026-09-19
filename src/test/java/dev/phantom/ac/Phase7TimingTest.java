package dev.phantom.ac;

import static dev.phantom.ac.Maths.Vec3;
import static dev.phantom.ac.Packets.*;
import static dev.phantom.ac.Phase7Timing.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class Phase7TimingTest {
  private static Timeline.Snapshot timeline(RawPacket... packets) {
    return Timeline.assign(
        new Normalizer().normalize(List.of(packets)), 0, 50_000_000L);
  }

  private static Config exactConfig() {
    return new Config(
        50_000_000L, 50_000_000L, 50_000_000L,
        new LatencyBounds(0, 0),
        new LatencyBounds(0, 0),
        new TickDelayBounds(0, 0),
        new TickDelayBounds(0, 0),
        250_000_000L, 3, 128);
  }

  @Test
  void serverTickAndClientTickAreSeparateCoordinates() {
    Reconstruction r = reconstruct(timeline(
        new RawPacket(1, 0, new Move(Vec3.ZERO, 0f, 0f, true, 0L)),
        new RawPacket(2, 120_000_000L,
            new Move(new Vec3(.1, 0, 0), 0f, 0f, true, 3L))),
        exactConfig());
    assertEquals(0L, r.frames().getFirst().timing().serverTick());
    assertEquals(2L, r.frames().getLast().timing().serverTick());
    assertEquals(Range.exact(3), r.frames().getLast().timing().simulationClientTicks());
    assertNotEquals(r.frames().getLast().timing().serverTick(),
        r.frames().getLast().timing().simulationClientTicks().min());
  }

  @Test
  void firstUnwatermarkedClientPacketAnchorsRelativeTickZero() {
    Config config = new Config(
        50_000_000L, 50_000_000L, 50_000_000L,
        new LatencyBounds(0, 100_000_000L),
        new LatencyBounds(0, 100_000_000L),
        new TickDelayBounds(0, 1),
        new TickDelayBounds(0, 1),
        250_000_000L, 3, 128);
    Reconstruction r = reconstruct(timeline(
        new RawPacket(1, 0, new Move(Vec3.ZERO, 0f, 0f, true, null))),
        config);
    EventTiming timing = r.frames().getFirst().timing();
    assertEquals(Range.exact(0), timing.packetGenerationClientTicks());
    assertEquals(TimingSource.RELATIVE_CLIENT_ANCHOR, timing.source());
  }

  @Test
  void explicitClientTickIsCaptureMetadataNotAUniversalNetworkTimestamp() {
    Reconstruction r = reconstruct(timeline(
        new RawPacket(1, 0, new Move(Vec3.ZERO, 0f, 0f, true, 42L))),
        exactConfig());
    EventTiming timing = r.frames().getFirst().timing();
    assertEquals(Range.exact(42), timing.packetGenerationClientTicks());
    assertEquals(TimingSource.EXPLICIT_CLIENT_TICK, timing.source());
    assertFalse(timing.uncertain());
  }

  @Test
  void boundaryPacketsDoNotAssignTheirArrivalIntervalToLaterMovement() {
    Reconstruction r = reconstruct(timeline(
        new RawPacket(1, 0, new ClientTickEnd()),
        new RawPacket(2, 50_000_000L,
            new Move(Vec3.ZERO, 0f, 0f, true, null)),
        new RawPacket(3, 100_000_000L, new ClientTickEnd()),
        new RawPacket(4, 150_000_000L,
            new Move(new Vec3(.1, 0, 0), 0f, 0f, true, null))),
        exactConfig());
    EventTiming firstMove = r.timingFor(2).orElseThrow();
    EventTiming secondMove = r.timingFor(4).orElseThrow();
    assertEquals(Range.exact(1), firstMove.packetGenerationClientTicks());
    assertEquals(Range.exact(2), secondMove.packetGenerationClientTicks());
    assertTrue(firstMove.possiblePacketGenerationClientTicks().contains(1L));
    assertTrue(secondMove.possiblePacketGenerationClientTicks().contains(2L));
  }

  @Test
  void boundaryAndLatencyConflictWidensInsteadOfSelectingOneSide() {
    Config config = new Config(
        50_000_000L, 50_000_000L, 50_000_000L,
        new LatencyBounds(0, 40_000_000L),
        new LatencyBounds(0, 0),
        new TickDelayBounds(0, 0),
        new TickDelayBounds(0, 0),
        250_000_000L, 3, 128);
    Reconstruction r = reconstruct(timeline(
        new RawPacket(1, 0, new ClientTickEnd()),
        new RawPacket(2, 60_000_000L,
            new Move(new Vec3(.1, 0, 0), 0f, 0f, true, null)),
        new RawPacket(3, 100_000_000L, new ClientTickEnd())),
        config);
    EventTiming timing = r.timingFor(2).orElseThrow();
    assertTrue(timing.simulationClientTicks().width() >= 1);
    assertTrue(timing.uncertain());
  }

  @Test
  void jitterWidensClientTickEnvelope() {
    Config config = new Config(
        50_000_000L, 50_000_000L, 50_000_000L,
        new LatencyBounds(0, 30_000_000L),
        new LatencyBounds(0, 30_000_000L),
        new TickDelayBounds(0, 0),
        new TickDelayBounds(0, 0),
        250_000_000L, 3, 128);
    Reconstruction r = reconstruct(timeline(
        new RawPacket(1, 0, new Move(Vec3.ZERO, 0f, 0f, true, 0L)),
        new RawPacket(2, 100_000_000L,
            new Move(new Vec3(.1, 0, 0), 0f, 0f, true, null))),
        config);
    EventTiming timing = r.timingFor(2).orElseThrow();
    assertTrue(timing.packetGenerationClientTicks().width() >= 1);
    assertTrue(timing.simulationClientTicks().width() >= 1);
    assertFalse(timing.possibleSimulationClientTicks().isEmpty());
    assertTrue(timing.simulationCandidatesExhaustive());
    assertTrue(timing.uncertain());
  }

  @Test
  void multiplePacketsCanShareAClientTickWithoutResortingArrivalHistory() {
    Reconstruction r = reconstruct(timeline(
        new RawPacket(1, 0, new Move(Vec3.ZERO, 0f, 0f, true, 0L)),
        new RawPacket(2, 10_000_000L,
            new Move(new Vec3(.1, 0, 0), 0f, 0f, true, 0L)),
        new RawPacket(3, 20_000_000L,
            new Move(new Vec3(.2, 0, 0), 0f, 0f, true, 0L))),
        exactConfig());
    assertEquals(List.of(1L, 2L, 3L),
        r.frames().stream().map(f -> f.timing().sequence()).toList());
    assertEquals(Range.exact(0), r.timingFor(2).orElseThrow().packetGenerationClientTicks());
    assertEquals(Range.exact(0), r.timingFor(3).orElseThrow().packetGenerationClientTicks());
  }

  @Test
  void sequenceGapsProduceUncertaintyWithoutInventingMissingPackets() {
    Reconstruction r = reconstruct(timeline(
        new RawPacket(1, 0, new Move(Vec3.ZERO, 0f, 0f, true, 0L)),
        new RawPacket(3, 50_000_000L,
            new Move(new Vec3(.1, 0, 0), 0f, 0f, true, null))),
        exactConfig());
    EventTiming timing = r.timingFor(3).orElseThrow();
    assertTrue(timing.uncertain());
    assertTrue(timing.reasons().stream().anyMatch(s -> s.contains("sequence gap")));
  }

  @Test
  void delayedPacketDoesNotBecomeExactClientTick() {
    Config config = new Config(
        50_000_000L, 50_000_000L, 50_000_000L,
        new LatencyBounds(0, 100_000_000L),
        new LatencyBounds(0, 100_000_000L),
        new TickDelayBounds(0, 1),
        new TickDelayBounds(0, 1),
        250_000_000L, 3, 128);
    Reconstruction r = reconstruct(timeline(
        new RawPacket(1, 0, new Move(Vec3.ZERO, 0f, 0f, true, 0L)),
        new RawPacket(2, 200_000_000L,
            new Move(new Vec3(.1, 0, 0), 0f, 0f, true, null))),
        config);
    EventTiming timing = r.timingFor(2).orElseThrow();
    assertTrue(timing.simulationClientTicks().width() >= 1);
    assertTrue(timing.uncertain());
  }

  @Test
  void reorderedPacketsPreserveCaptureOrderAndFlagReordering() {
    Timeline.Snapshot timeline = timeline(
        new RawPacket(1, 0, new Move(Vec3.ZERO, 0f, 0f, true, 0L)),
        new RawPacket(3, 50_000_000L,
            new Move(new Vec3(.3, 0, 0), 0f, 0f, true, 3L)),
        new RawPacket(2, 60_000_000L,
            new Move(new Vec3(.2, 0, 0), 0f, 0f, true, 2L)));
    assertEquals(List.of(1L, 3L, 2L),
        timeline.events().stream().map(e -> e.packet().sequence()).toList());
    Reconstruction r = reconstruct(timeline, exactConfig());
    assertTrue(r.timingFor(2).orElseThrow().windows().stream()
        .anyMatch(w -> w.kind() == WindowKind.REORDERING));
  }

  @Test
  void asymmetricalLatencyKeepsDirectionSpecificNetworkIntervals() {
    Config config = new Config(
        50_000_000L, 50_000_000L, 50_000_000L,
        new LatencyBounds(40_000_000L, 60_000_000L),
        new LatencyBounds(10_000_000L, 20_000_000L),
        new TickDelayBounds(0, 0),
        new TickDelayBounds(0, 0),
        250_000_000L, 3, 128);
    Reconstruction r = reconstruct(timeline(
        new RawPacket(1, 100_000_000L,
            new Move(Vec3.ZERO, 0f, 0f, true, 0L)),
        new RawPacket(2, 100_000_000L,
            new Velocity(Vec3.ZERO))),
        config);
    assertEquals(new TimeRange(40_000_000L, 60_000_000L),
        r.timingFor(1).orElseThrow().packetGenerationNanos());
    assertEquals(new TimeRange(110_000_000L, 120_000_000L),
        r.timingFor(2).orElseThrow().clientProcessingNanos());
  }

  @Test
  void velocityGetsAClientProcessingEnvelope() {
    Config config = new Config(
        50_000_000L, 50_000_000L, 50_000_000L,
        new LatencyBounds(0, 30_000_000L),
        new LatencyBounds(0, 30_000_000L),
        new TickDelayBounds(0, 1),
        new TickDelayBounds(0, 1),
        250_000_000L, 3, 128);
    Reconstruction r = reconstruct(timeline(
        new RawPacket(1, 0, new Move(Vec3.ZERO, 0f, 0f, true, 0L)),
        new RawPacket(2, 50_000_000L,
            new Velocity(new Vec3(.4, .2, 0)))),
        config);
    EventTiming timing = r.timingFor(2).orElseThrow();
    assertEquals(EventKind.VELOCITY, timing.kind());
    assertEquals(timing.clientProcessingClientTickEnvelope().range(),
        timing.simulationClientTicks());
    assertTrue(timing.possibleSimulationClientTicks().size() >= 1);
    assertTrue(timing.uncertain());
    assertTrue(timing.windows().stream().anyMatch(w -> w.kind() == WindowKind.VELOCITY));
  }

  @Test
  void teleportCorrectionAndAckHaveDistinctRecoveryLifecycle() {
    Reconstruction r = reconstruct(timeline(
        new RawPacket(1, 0, new Move(Vec3.ZERO, 0f, 0f, true, 0L)),
        new RawPacket(2, 50_000_000L,
            new Teleport(7, Vec3.ZERO, 0f, 0f)),
        new RawPacket(3, 100_000_000L, new TeleportConfirm(7)),
        new RawPacket(4, 150_000_000L,
            new Move(new Vec3(.1, 0, 0), 0f, 0f, true, 3L)),
        new RawPacket(5, 200_000_000L,
            new Move(new Vec3(.2, 0, 0), 0f, 0f, true, 4L)),
        new RawPacket(6, 250_000_000L,
            new Move(new Vec3(.3, 0, 0), 0f, 0f, true, 5L))),
        exactConfig());
    assertEquals(SyncStatus.RECOVERING, r.frames().get(1).after().status());
    assertEquals(SyncStatus.RECOVERING, r.frames().get(2).after().status());
    assertTrue(r.timingFor(4).orElseThrow().uncertain());
    assertEquals(SyncStatus.SYNCHRONIZED, r.finalState().status());
    assertTrue(r.finalState().pendingTeleportId().isEmpty());
  }

  @Test
  void wrongTeleportAckBecomesAmbiguous() {
    Reconstruction r = reconstruct(timeline(
        new RawPacket(1, 0, new Move(Vec3.ZERO, 0f, 0f, true, 0L)),
        new RawPacket(2, 50_000_000L,
            new Teleport(7, Vec3.ZERO, 0f, 0f)),
        new RawPacket(3, 100_000_000L, new TeleportConfirm(8))),
        exactConfig());
    assertEquals(SyncStatus.AMBIGUOUS, r.finalState().status());
    assertTrue(r.timingFor(3).orElseThrow().reasons().stream()
        .anyMatch(s -> s.contains("did not match")));
  }

  @Test
  void worldUpdateTimingIsSeparateFromClockSynchronization() {
    Config config = new Config(
        50_000_000L, 50_000_000L, 50_000_000L,
        new LatencyBounds(0, 0),
        new LatencyBounds(0, 25_000_000L),
        new TickDelayBounds(0, 0),
        new TickDelayBounds(0, 0),
        250_000_000L, 3, 128);
    Reconstruction r = reconstruct(timeline(
        new RawPacket(1, 0,
            new Move(Vec3.ZERO, 0f, 0f, true, 0L)),
        new RawPacket(2, 50_000_000L,
            new ChunkData(new World.Chunk(0, 0), Map.of())),
        new RawPacket(3, 100_000_000L,
            new Move(new Vec3(.1, 0, 0), 0f, 0f, true, 2L))),
        config);
    assertTrue(r.timingFor(2).orElseThrow().uncertain());
    assertTrue(r.timingFor(2).orElseThrow().windows().stream()
        .anyMatch(w -> w.kind() == WindowKind.WORLD_UPDATE));
    assertEquals(SyncStatus.SYNCHRONIZED, r.finalState().status());
    assertFalse(r.timingFor(3).orElseThrow().uncertain());
  }

  @Test
  void worldTransactionSendAndAckHaveExplicitDirections() {
    Reconstruction r = reconstruct(timeline(
        new RawPacket(1, 0,
            new Move(Vec3.ZERO, 0f, 0f, true, 0L)),
        new RawPacket(2, 20_000_000L, new WorldTransactionSend((short) 5)),
        new RawPacket(3, 40_000_000L, new WorldTransactionAck((short) 5))),
        exactConfig());
    assertEquals(Direction.SERVER_TO_CLIENT, r.timingFor(2).orElseThrow().direction());
    assertEquals(EventKind.WORLD_TRANSACTION_SEND, r.timingFor(2).orElseThrow().kind());
    assertEquals(Direction.CLIENT_TO_SERVER, r.timingFor(3).orElseThrow().direction());
    assertEquals(EventKind.WORLD_TRANSACTION_ACK, r.timingFor(3).orElseThrow().kind());
  }

  @Test
  void flightToggleHasClientDirection() {
    Reconstruction r = reconstruct(timeline(
        new RawPacket(1, 0, new Move(Vec3.ZERO, 0f, 0f, true, 0L)),
        new RawPacket(2, 50_000_000L, new FlightToggle(true, false))),
        exactConfig());
    assertEquals(Direction.CLIENT_TO_SERVER,
        r.timingFor(2).orElseThrow().direction());
    assertEquals(EventKind.FLIGHT_TOGGLE, r.timingFor(2).orElseThrow().kind());
  }

  @Test
  void oversizedEnvelopeIsRetainedButDiscreteEnumerationBecomesIncomplete() {
    Config config = new Config(
        50_000_000L, 1_000_000L, 1_000_000L,
        new LatencyBounds(0, 500_000_000L),
        new LatencyBounds(0, 0),
        new TickDelayBounds(0, 1),
        new TickDelayBounds(0, 1),
        250_000_000L, 3, 4, 16);
    Reconstruction r = reconstruct(timeline(
        new RawPacket(1, 0, new Move(Vec3.ZERO, 0f, 0f, true, 0L)),
        new RawPacket(2, 500_000_000L,
            new Move(new Vec3(.1, 0, 0), 0f, 0f, true, null))),
        config);
    EventTiming timing = r.timingFor(2).orElseThrow();
    assertTrue(timing.simulationClientTicks().width() > 4);
    assertTrue(timing.possibleSimulationClientTicks().isEmpty());
    assertFalse(timing.simulationCandidatesExhaustive());
    assertTrue(timing.uncertain());
    assertTrue(r.metrics().budgetReached());
  }

  @Test
  void timingHistoryBudgetMakesReconstructionIncomplete() {
    Config config = new Config(
        50_000_000L, 50_000_000L, 50_000_000L,
        new LatencyBounds(0, 30_000_000L),
        new LatencyBounds(0, 0),
        new TickDelayBounds(0, 0),
        new TickDelayBounds(0, 0),
        250_000_000L, 3, 128, 2);
    Reconstruction r = reconstruct(timeline(
        new RawPacket(1, 0, new Move(Vec3.ZERO, 0f, 0f, true, 0L)),
        new RawPacket(2, 100_000_000L,
            new Move(new Vec3(.1, 0, 0), 0f, 0f, true, null)),
        new RawPacket(3, 200_000_000L,
            new Move(new Vec3(.2, 0, 0), 0f, 0f, true, null))),
        config);
    assertTrue(r.metrics().budgetReached());
    assertTrue(r.frames().stream().anyMatch(f -> f.timing().windows().stream()
        .anyMatch(w -> w.kind() == WindowKind.TIMING_BUDGET)));
    assertEquals(Consistency.UNCERTAIN, r.consistency());
  }

  @Test
  void insufficientServerToClientEvidenceRemainsUnknown() {
    Reconstruction r = reconstruct(timeline(
        new RawPacket(1, 0, new Velocity(new Vec3(.1, 0, 0)))),
        exactConfig());
    EventTiming timing = r.frames().getFirst().timing();
    assertEquals(TimingSource.SERVER_CAPTURE_ONLY, timing.source());
    assertFalse(timing.packetGenerationClientTickEnvelope().known());
    assertFalse(timing.simulationClientTickEnvelope().known());
    assertTrue(timing.uncertain());
  }

  @Test
  void authoritativeServerTickProvenanceIsRetained() {
    NormalizedPacket packet = new NormalizedPacket(
        1, 0, new Move(Vec3.ZERO, 0f, 0f, true, 0L),
        EnumSet.of(PacketFlag.NORMAL),
        new CaptureProvenance("test", "CLIENT_TO_SERVER", "Move", 77L, 0L));
    Reconstruction r = reconstruct(new Timeline.Snapshot(List.of(new Timeline.Event(3, packet))),
        exactConfig());
    EventTiming timing = r.frames().getFirst().timing();
    assertEquals(OptionalLong.of(77L), timing.authoritativeServerTick());
    assertEquals(77L, timing.serverTick());
    assertEquals(packet.provenance(), timing.provenance());
  }

  @Test
  void replayFirstDivergenceIdentifiesFirstDifferentTimingEvent() {
    Reconstruction expected = reconstruct(timeline(
        new RawPacket(1, 0, new Move(Vec3.ZERO, 0f, 0f, true, 0L)),
        new RawPacket(2, 50_000_000L,
            new Move(new Vec3(.1, 0, 0), 0f, 0f, true, null))),
        exactConfig());
    Reconstruction actual = reconstruct(timeline(
        new RawPacket(1, 0, new Move(Vec3.ZERO, 0f, 0f, true, 0L)),
        new RawPacket(2, 100_000_000L,
            new Move(new Vec3(.1, 0, 0), 0f, 0f, true, null))),
        exactConfig());
    Optional<FirstDivergence> divergence = Phase7Timing.firstDivergence(expected, actual);
    assertTrue(divergence.isPresent());
    assertEquals(1, divergence.get().eventIndex());
    assertEquals(2L, divergence.get().sequence());
    assertTrue(divergence.get().differingFields().contains("serverTick"));
  }

  @Test
  void replayArtifactVerifiesExactlyAndReportsNoDivergence() {
    Timeline.Snapshot capture = timeline(
        new RawPacket(1, 0, new Move(Vec3.ZERO, 0f, 0f, true, 0L)),
        new RawPacket(2, 50_000_000L,
            new Move(new Vec3(.1, 0, 0), 0f, 0f, true, 1L)));
    Phase7Replay replay = Phase7Replay.of(capture, exactConfig());
    Phase7Timing.Reconstruction expected = replay.reconstruct();
    Phase7Replay decoded = Phase7Replay.decode(replay.encode());
    Phase7Replay.Verification verification = decoded.verifyAgainst(expected);
    assertTrue(verification.identical());
    assertTrue(verification.firstDivergence().isEmpty());
  }

  @Test
  void replayArtifactIsDeterministicAcrossRoundTrip() {
    Timeline.Snapshot capture = timeline(
        new RawPacket(1, 0, new Move(Vec3.ZERO, 0f, 0f, true, 0L)),
        new RawPacket(2, 50_000_000L,
            new Velocity(new Vec3(.3, .2, 0))));
    Phase7Timing.Config config = new Config(
        50_000_000L, 50_000_000L, 50_000_000L,
        new LatencyBounds(0, 25_000_000L),
        new LatencyBounds(0, 25_000_000L),
        new TickDelayBounds(0, 1),
        new TickDelayBounds(0, 1),
        250_000_000L, 3, 128, 65_536L);
    Phase7Replay artifact = Phase7Replay.of(capture, config);
    Phase7Replay decoded = Phase7Replay.decode(artifact.encode());
    assertArrayEquals(artifact.encode(), decoded.encode());
    assertEquals(artifact.canonicalText(), decoded.canonicalText());
  }

  @Test
  void timingEnvelopeMatchesAreBoundedAndDeterministic() {
    Reconstruction a = reconstruct(timeline(
        new RawPacket(1, 0, new Move(Vec3.ZERO, 0f, 0f, true, 0L)),
        new RawPacket(2, 70_000_000L,
            new Move(new Vec3(.1, 0, 0), 0f, 0f, true, null))),
        new Config(
            50_000_000L, 50_000_000L, 50_000_000L,
            new LatencyBounds(0, 25_000_000L),
            new LatencyBounds(0, 25_000_000L),
            new TickDelayBounds(0, 1),
            new TickDelayBounds(0, 1),
            250_000_000L, 3, 128));
    Reconstruction b = reconstruct(timeline(
        new RawPacket(1, 0, new Move(Vec3.ZERO, 0f, 0f, true, 0L)),
        new RawPacket(2, 70_000_000L,
            new Move(new Vec3(.1, 0, 0), 0f, 0f, true, null))),
        new Config(
            50_000_000L, 50_000_000L, 50_000_000L,
            new LatencyBounds(0, 25_000_000L),
            new LatencyBounds(0, 25_000_000L),
            new TickDelayBounds(0, 1),
            new TickDelayBounds(0, 1),
            250_000_000L, 3, 128));
    assertEquals(a.canonicalText(), b.canonicalText());
    assertEquals(a.metrics(), b.metrics());
  }
}
