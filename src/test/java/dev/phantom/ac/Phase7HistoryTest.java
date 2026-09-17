package dev.phantom.ac;

import static dev.phantom.ac.Maths.Vec3;
import static dev.phantom.ac.Packets.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class Phase7HistoryTest {
  private static Timeline.Snapshot timeline(){
    return Timeline.assign(new Normalizer().normalize(List.of(
        new RawPacket(1,0,new Move(Vec3.ZERO,0f,0f,true,0L)),
        new RawPacket(2,50_000_000L,new Velocity(new Vec3(.2,.3,0))),
        new RawPacket(3,100_000_000L,new Move(new Vec3(.2,0,0),0f,0f,true,null)))),0,50_000_000L);
  }

  @Test void historyJoinsTimingPlayerStateWorldReferenceAndSynchronization(){
    State.Seed seed=State.Seed.serverAnchor(State.Player.initial(Vec3.ZERO));
    Phase7History.Reconstruction history=Phase7History.reconstruct(timeline(),seed,Phase7Timing.Config.defaultConfig());
    assertEquals(3,history.frames().size());
    assertEquals(3,history.playerState().frames().size());
    assertEquals(3,history.timing().frames().size());
    assertEquals(history.frames().get(1).event().packet().sequence(),history.frames().get(1).worldReference().triggeringSequence());
    assertEquals(history.frames().get(1).timing().simulationClientTicks(),history.frames().get(1).worldReference().possibleClientTicks());
    assertEquals(history.frames().get(1).synchronizationAfter(),history.timing().frames().get(1).after());
    assertTrue(history.canonicalText().contains("phase7-history-v1"));
  }

  @Test void historyIsDeterministicForIdenticalCapture(){
    State.Seed seed=State.Seed.serverAnchor(State.Player.initial(Vec3.ZERO));
    var a=Phase7History.reconstruct(timeline(),seed,Phase7Timing.Config.defaultConfig());
    var b=Phase7History.reconstruct(timeline(),seed,Phase7Timing.Config.defaultConfig());
    assertEquals(a.canonicalText(),b.canonicalText());
  }
}
