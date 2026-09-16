package dev.phantom.ac;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.phantom.ac.Maths.*;
import static dev.phantom.ac.Simulation.*;
import static dev.phantom.ac.State.*;

class SimulationTickTest {
  @Test void explicitTickContextIsDeterministic() {
    var world=World.Snapshot.emptyVisibleChunks(java.util.List.of(new World.Chunk(-1,-1),new World.Chunk(-1,0),new World.Chunk(0,-1),new World.Chunk(0,0)));
    var state=Player.initial(Vec3.ZERO);
    var context=new TickContext(17,state,new Input(1,0,false),world);
    var physics=new Vanilla12111Physics();
    var first=physics.step(context,17);
    var second=physics.step(context,17);
    assertEquals(first,second);
    assertEquals(17,first.simulationTick());
    assertFalse(first.state().uncertain());
  }
  @Test void unsupportedSweepIsExplicitlyUncertain() {
    var world=World.Snapshot.emptyVisibleChunks(java.util.List.of());
    var context=new TickContext(1,Player.initial(Vec3.ZERO),new Input(0,0,false),world);
    var result=new Vanilla12111Physics().step(context,1);
    assertTrue(result.state().uncertain());
    assertTrue(result.diagnostic().contains("known"));
  }
  @Test void collisionClipsVelocityRatherThanUsingDisplacementAsVelocity() {
    var blocks=new java.util.HashMap<World.Pos,World.Block>();
    blocks.put(new World.Pos(0,-1,0),World.Block.FULL);
    var world=new World.Snapshot(blocks,java.util.Set.of(new World.Chunk(-1,-1),new World.Chunk(-1,0),new World.Chunk(0,-1),new World.Chunk(0,0)));
    var context=new TickContext(4,Player.initial(new Vec3(.5,0,.5)),new Input(1,0,false),world);
    var result=new Vanilla12111Physics().step(context,4);
    assertEquals(4,result.simulationTick());
    assertEquals(0.0,result.state().position().x()-.5,.000001);
    assertEquals(0.0,result.state().velocity().x(),.000001);
  }
}
