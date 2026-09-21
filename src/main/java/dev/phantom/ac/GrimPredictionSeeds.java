package dev.phantom.ac;

import dev.phantom.ac.Maths.Vec3;
import dev.phantom.ac.Phase5Mechanics.MovementEnvironment;
import dev.phantom.ac.Phase6Reachability.Context;
import dev.phantom.ac.State.Player;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Start-of-tick alternative vectors patterned after Grim's prediction-seed expansion. */
public final class GrimPredictionSeeds {
  private GrimPredictionSeeds() {}

  public record Seed(Player player, String cause, List<String> assumptions) {
    public Seed {
      Objects.requireNonNull(player);
      Objects.requireNonNull(cause);
      assumptions = List.copyOf(assumptions);
    }
  }

  public static List<Seed> expand(Context context) {
    Objects.requireNonNull(context);
    Player player = context.player();
    MovementEnvironment env = context.movementEnvironment();
    List<Seed> result = new ArrayList<>();

    result.add(new Seed(
        player,
        "NORMAL_CLIENT_VELOCITY",
        List.of("existing client velocity is retained as the primary prediction vector")));

    if (env.climbable() && !player.onGround()) {
      result.add(new Seed(
          withVelocity(player, new Vec3(player.velocity().x(), 0.2, player.velocity().z())),
          "CLIMBABLE_ENTRY",
          List.of("climbable entry can seed the ladder-climb vector before regular input")));

    }

    if (env.fluid() == Phase5Mechanics.Fluid.WATER
        && !player.onGround()
        && !env.vehicle().active()) {
      result.add(new Seed(
          withVelocity(player, player.velocity().add(new Vec3(0.0, 0.04, 0.0))),
          "SWIM_HOP",
          List.of("water movement retains the small upward swim-hop possibility")));
    }

    return List.copyOf(result);
  }

  private static Player withVelocity(Player p, Vec3 v) {
    return new Player(
        p.position(), v, p.yaw(), p.pitch(), p.onGround(), p.gamemode(), p.effects(),
        p.awaitingTeleport(), p.uncertain(), p.input(), p.attributes(), p.pose(),
        p.environment(), p.clientTickRange(), p.provenance(), p.uncertaintyReasons());
  }
}
