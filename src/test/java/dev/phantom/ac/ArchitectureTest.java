package dev.phantom.ac;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Phase-0 regression tests: architectural contracts, not physics tests. */
class ArchitectureTest {
  @Test void productionLayersImplementTheirDeclaredContracts() {
    assertInstanceOf(Contracts.PacketNormalizer.class,new Packets.Normalizer());
    assertInstanceOf(Contracts.TimelineReconstructor.class,new Timeline.Reconstructor());
    assertInstanceOf(Contracts.PlayerStateReducer.class,new State.Reducer());
    assertInstanceOf(Contracts.PlayerStateReconstructor.class,new State.Reconstructor());
    assertInstanceOf(Contracts.CollisionResolver.class,new World.Resolver());
    assertInstanceOf(Contracts.PhysicsEngine.class,new Simulation.Vanilla12111Physics());
    assertInstanceOf(Contracts.ReachabilityEngine.class,new Validation.ReachableStates(new Simulation.Vanilla12111Physics()));
    assertInstanceOf(Contracts.Synchronizer.class,new Validation.DefaultSynchronizer());
    assertInstanceOf(Contracts.MovementValidator.class,new Validation.ReachabilityValidator());
  }

  @Test void contractsRejectUnsupportedVersionsAndInvalidCandidateBudgets() {
    assertDoesNotThrow(() -> Contracts.requireTargetVersion(Contracts.TARGET_VERSION));
    assertThrows(IllegalArgumentException.class,() -> Contracts.requireTargetVersion("Minecraft Java 1.20.4"));
    assertThrows(IllegalArgumentException.class,() -> Contracts.requireCandidateBudget(0));
  }

  @Test void immutableBoundaryValuesCannotBeMutatedByTheirCaller() {
    var blocks=new java.util.HashMap<World.Pos,World.Block>();
    blocks.put(new World.Pos(0,0,0),World.Block.FULL);
    var snapshot=new World.Snapshot(blocks);
    blocks.clear();
    assertEquals(World.Block.FULL,snapshot.blockAt(0,0,0));
    assertThrows(UnsupportedOperationException.class,() -> snapshot.blocks().put(new World.Pos(1,0,0),World.Block.AIR));
  }

  @Test void deterministicCoreSourcesDoNotReferencePlatformApis() throws Exception {
    Path root=Path.of("src","main","java","dev","phantom","ac");
    try(var paths=Files.walk(root)) {
      List<Path> core=paths.filter(path -> path.toString().endsWith(".java")).filter(path -> !path.toString().contains("paper")).toList();
      for(Path file:core) {
        String source=Files.readString(file);
        assertFalse(source.contains("org.bukkit"),file+" leaks Bukkit into deterministic core");
        assertFalse(source.contains("com.github.retrooper"),file+" leaks PacketEvents into deterministic core");
      }
    }
  }

  @Test void replayDecoderRejectsSerializedTypesOutsideTheReplayModel() throws Exception {
    byte[] hostile;
    try(var bytes=new ByteArrayOutputStream(); var output=new ObjectOutputStream(bytes)) {
      output.writeObject(new java.io.File("not-a-replay"));
      hostile=bytes.toByteArray();
    }
    assertThrows(IllegalArgumentException.class,() -> new Timeline.Codec().decode(hostile));
  }
}
