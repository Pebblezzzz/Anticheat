package dev.phantom.ac.paper;

import dev.phantom.ac.Packets;
import dev.phantom.ac.world.BlockState;
import dev.phantom.ac.world.Pos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HardenedPhantomPaperPluginTest {
  @Test
  void unqualifiedPacketEventsBlockIdsUseTheMinecraftNamespace() {
    assertEquals("minecraft:stone", HardenedPhantomPaperPlugin.normalizeBlockId("stone"));
    assertEquals("minecraft:cobblestone", HardenedPhantomPaperPlugin.normalizeBlockId("cobblestone"));
    assertEquals("minecraft:air", HardenedPhantomPaperPlugin.normalizeBlockId("minecraft:air"));
    assertEquals("custom:block", HardenedPhantomPaperPlugin.normalizeBlockId("custom:block"));
  }

  @Test
  void flagModeMapsToImpossibleOnlyAndSuppressesUncertain() {
    assertEquals(HardenedPhantomPaperPlugin.DebugLevel.IMPOSSIBLE,
        HardenedPhantomPaperPlugin.parseDebugMode("flag"));
    assertEquals(HardenedPhantomPaperPlugin.DebugLevel.IMPOSSIBLE,
        HardenedPhantomPaperPlugin.parseDebugMode("impossible"));
    assertTrue(HardenedPhantomPaperPlugin.DebugLevel.IMPOSSIBLE.impossibleOnly());
    assertFalse(HardenedPhantomPaperPlugin.DebugLevel.FOCUS.impossibleOnly());
    assertFalse(HardenedPhantomPaperPlugin.DebugLevel.TRACE.impossibleOnly());
    assertNull(HardenedPhantomPaperPlugin.parseDebugMode("not-a-mode"));
  }

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
