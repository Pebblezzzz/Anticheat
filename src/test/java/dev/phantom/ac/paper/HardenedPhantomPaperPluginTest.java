package dev.phantom.ac.paper;

import dev.phantom.ac.Packets;
import dev.phantom.ac.world.BlockState;
import dev.phantom.ac.world.Pos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HardenedPhantomPaperPluginTest {
  @Test
  void unsupportedBlockChangesAreRecordedAsUnsupportedPackets() {
    Pos position = new Pos(1, 64, 1);
    BlockState unsupported = BlockState.unsupported("minecraft:future_block");

    Packets.Packet packet = HardenedPhantomPaperPlugin.blockStatePacket(position, unsupported);

    assertInstanceOf(Packets.UnsupportedBlockStateChange.class, packet);
    assertEquals(position, ((Packets.UnsupportedBlockStateChange) packet).position());
    assertEquals(unsupported, ((Packets.UnsupportedBlockStateChange) packet).state());
  }

  @Test
  void supportedBlockChangesRemainKnownPackets() {
    Pos position = new Pos(1, 64, 1);
    BlockState stone = dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone", java.util.Map.of());

    Packets.Packet packet = HardenedPhantomPaperPlugin.blockStatePacket(position, stone);

    assertInstanceOf(Packets.BlockStateChange.class, packet);
    assertEquals(stone, ((Packets.BlockStateChange) packet).state());
  }
}
