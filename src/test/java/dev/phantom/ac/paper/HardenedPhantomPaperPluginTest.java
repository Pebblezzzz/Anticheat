package dev.phantom.ac.paper;

import dev.phantom.ac.Packets;
import dev.phantom.ac.world.BlockState;
import dev.phantom.ac.world.Pos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HardenedPhantomPaperPluginTest {
  @Test
  void invalidBreakSpeedIsDowngradedToUnknown() {
    assertEquals(0.25, HardenedPhantomPaperPlugin.sanitizeBreakSpeed(0.25));
    assertNull(HardenedPhantomPaperPlugin.sanitizeBreakSpeed(-1.0));
    assertNull(HardenedPhantomPaperPlugin.sanitizeBreakSpeed(Double.NaN));
    assertNull(HardenedPhantomPaperPlugin.sanitizeBreakSpeed(Double.POSITIVE_INFINITY));
  }

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

  @Test
  void excludesVanillaSprintModifierFromPredictionAttribute() {
    java.util.UUID sprintUuid =
        java.util.UUID.fromString("662a6b8d-da3e-4c1c-8813-96ea6097278d");
    org.bukkit.attribute.AttributeModifier sprint = new org.bukkit.attribute.AttributeModifier(
        sprintUuid, "Sprinting speed boost", 0.30000001192092896D,
        org.bukkit.attribute.AttributeModifier.Operation.MULTIPLY_SCALAR_1);
    org.bukkit.attribute.AttributeModifier custom = new org.bukkit.attribute.AttributeModifier(
        java.util.UUID.fromString("9d4f8b9b-8bd8-4a59-9f6c-67d2b1a71f44"),
        "custom-speed", 0.2D,
        org.bukkit.attribute.AttributeModifier.Operation.ADD_SCALAR);

    dev.phantom.ac.Simulation.Attributes attributes =
        HardenedPhantomPaperPlugin.predictionAttributes(0.1D, java.util.List.of(sprint, custom));

    assertEquals(0.1D, attributes.movementSpeed(), 1.0e-12);
    assertEquals(1, attributes.modifiers().size());
    var retained = attributes.modifiers().getFirst();
    assertEquals("9d4f8b9b-8bd8-4a59-9f6c-67d2b1a71f44", retained.id());
    assertEquals(0.2D, retained.amount(), 1.0e-12);
    assertEquals(dev.phantom.ac.Phase5Mechanics.ModifierOperation.ADD_MULTIPLIED_BASE, retained.operation());
  }

  @Test
  void invalidMovementSpeedBaseFallsBackToVanillaBaseSpeed() {
    dev.phantom.ac.Simulation.Attributes attributes =
        HardenedPhantomPaperPlugin.predictionAttributes(Double.NaN, java.util.List.of());
    assertEquals(0.1D, attributes.movementSpeed(), 1.0e-12);
    assertTrue(attributes.modifiers().isEmpty());
  }
}
