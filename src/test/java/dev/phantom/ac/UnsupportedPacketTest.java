package dev.phantom.ac;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

import dev.phantom.ac.Packets.Normalizer;
import dev.phantom.ac.Packets.RawPacket;
import dev.phantom.ac.Packets.UnsupportedBlockStateChange;

class UnsupportedPacketTest {
  @Test void unsupportedBlockStateIsRetainedWithoutThrowing() {
    var position=new dev.phantom.ac.world.Pos(1,64,1);
    var state=dev.phantom.ac.world.BlockState.unsupported("minecraft:unknown_future_block");
    var packet=new UnsupportedBlockStateChange(position,state);
    var normalized=new Normalizer().normalize(List.of(new RawPacket(1,0,packet)));
    var timeline=Timeline.assign(normalized,0,50_000_000L);
    assertEquals(packet,timeline.events().getFirst().packet().packet());
    var decoded=new Timeline.Codec().decode(new Timeline.Codec().encode(timeline));
    assertEquals(packet,decoded.events().getFirst().packet().packet());
    var world=World.fromTimeline(decoded).statesAt(0);
    assertEquals(dev.phantom.ac.world.Coverage.UNLOADED,world.coverageAt(1,64,1));
  }
}
