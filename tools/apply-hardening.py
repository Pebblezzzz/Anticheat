from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace(path: str, old: str, new: str, count: int | None = None) -> None:
    file = ROOT / path
    text = file.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"missing patch anchor in {path}: {old[:120]!r}")
    if count is None:
        text = text.replace(old, new)
    else:
        text = text.replace(old, new, count)
    file.write_text(text, encoding="utf-8", newline="\n")


# Phase 8: impossible evidence must alert on supporting evidence, not only a consecutive streak.
replace(
    "src/main/java/dev/phantom/ac/Phase8MovementValidation.java",
    "public static Config defaults() { return new Config(2, 20, true, true); }",
    "public static Config defaults() { return new Config(1, 20, true, true); }",
)
replace(
    "src/main/java/dev/phantom/ac/Phase8MovementValidation.java",
    "&& next.consecutiveImpossible() >= config.minimumImpossibleObservations()",
    "&& next.supportingImpossible() >= config.minimumImpossibleObservations()",
)

# Velocity packets are authoritative velocity state, not an additive acceleration impulse.
replace(
    "src/main/java/dev/phantom/ac/Phase5Mechanics.java",
    "return new State.Player(state.position(), state.velocity().add(new Maths.Vec3(impulse.x(), impulse.y(), impulse.z())), state.yaw(), state.pitch(), state.onGround(), state.gamemode(), state.effects(), state.awaitingTeleport(), state.uncertain());",
    "return new State.Player(state.position(), new Maths.Vec3(impulse.x(), impulse.y(), impulse.z()), state.yaw(), state.pitch(), state.onGround(), state.gamemode(), state.effects(), state.awaitingTeleport(), state.uncertain(), state.input(), state.attributes(), state.pose(), state.environment(), state.clientTickRange(), state.provenance(), state.uncertaintyReasons());",
)

# Make the rich physics convenience context explicitly conservative about untracked entities.
replace(
    "src/main/java/dev/phantom/ac/Vanilla12111RichPhysics.java",
    "public Context(long tick,Player state,Simulation.AdvancedInput input,WorldSnapshot world,Simulation.Environment environment,Simulation.Attributes attributes,Phase5Mechanics.MovementEffects effects,Phase5Mechanics.Pose pose,Phase5Mechanics.MovementEnvironment movementEnvironment,boolean sleeping){this(tick,state,input,world,environment,attributes,effects,pose,movementEnvironment,sleeping,EntityCollisions.of(List.of()));}",
    "public Context(long tick,Player state,Simulation.AdvancedInput input,WorldSnapshot world,Simulation.Environment environment,Simulation.Attributes attributes,Phase5Mechanics.MovementEffects effects,Phase5Mechanics.Pose pose,Phase5Mechanics.MovementEnvironment movementEnvironment,boolean sleeping){this(tick,state,input,world,environment,attributes,effects,pose,movementEnvironment,sleeping,EntityCollisions.NONE_TRACKED);}",
)

# Use actual block slipperiness for ground friction and fail closed if fluid height is unknowable.
replace(
    "src/main/java/dev/phantom/ac/Vanilla12111RichPhysics.java",
    "double horizontalFactor;if(context.movementEnvironment().fluid()==Phase5Mechanics.Fluid.WATER)horizontalFactor=context.movementEnvironment().fluidSpeedMultiplier()*WATER_DRAG;else if(context.movementEnvironment().fluid()==Phase5Mechanics.Fluid.LAVA)horizontalFactor=context.movementEnvironment().fluidSpeedMultiplier()*LAVA_DRAG;else if(climbing)horizontalFactor=s.onGround()?GROUND_FRICTION:AIR_HORIZONTAL_FRICTION;else if(gliding)horizontalFactor=AIR_DRAG;else horizontalFactor=s.onGround()?GROUND_FRICTION:AIR_HORIZONTAL_FRICTION;",
    "double horizontalFactor;if(context.movementEnvironment().fluid()==Phase5Mechanics.Fluid.WATER)horizontalFactor=context.movementEnvironment().fluidSpeedMultiplier()*WATER_DRAG;else if(context.movementEnvironment().fluid()==Phase5Mechanics.Fluid.LAVA)horizontalFactor=context.movementEnvironment().fluidSpeedMultiplier()*LAVA_DRAG;else if(climbing)horizontalFactor=s.onGround()?GROUND_FRICTION:AIR_HORIZONTAL_FRICTION;else if(gliding)horizontalFactor=AIR_DRAG;else if(s.onGround()){\n            BlockState support = context.world().blockAtOrNull((int)Math.floor(s.position().x()), (int)Math.floor(s.position().y()-GROUND_PROBE), (int)Math.floor(s.position().z()));\n            if(support == null) return uncertain(context,\"support block is unavailable for friction calculation\");\n            if(support.isUnsupported()) return uncertain(context,\"support block is unsupported for friction calculation\");\n            horizontalFactor=BlockCatalogue12111.slipperiness(support)*AIR_HORIZONTAL_FRICTION;\n        } else horizontalFactor=AIR_HORIZONTAL_FRICTION;",
)
replace(
    "src/main/java/dev/phantom/ac/Vanilla12111RichPhysics.java",
    "import dev.phantom.ac.world.EntityCollisions;\nimport dev.phantom.ac.world.WorldSnapshot;",
    "import dev.phantom.ac.world.EntityCollisions;\nimport dev.phantom.ac.world.WorldSnapshot;\nimport dev.phantom.ac.world.BlockState;\nimport dev.phantom.ac.world.v12111.BlockCatalogue12111;",
)

# Legacy compatibility: uncertainty is local to the movement-relevant volume, not any stale block in the snapshot.
replace(
    "src/main/java/dev/phantom/ac/Simulation.java",
    "    private static boolean legacyRequiresUncertainty(World.Snapshot world,Player state){\n      if(!world.visibleChunks().contains(World.Chunk.containing((int)Math.floor(state.position().x()),(int)Math.floor(state.position().z()))))return true;\n      return world.blocks().values().stream().anyMatch(b->b==World.Block.UNKNOWN||b==World.Block.UNSUPPORTED||b==World.Block.WATER||b==World.Block.LADDER);\n    }",
    "    private static boolean legacyRequiresUncertainty(World.Snapshot world,Player state){\n      Aabb box=Aabb.playerAt(state.position(),state.pose());\n      int minX=(int)Math.floor(box.minX())-1,maxX=(int)Math.ceil(box.maxX())+1,minY=(int)Math.floor(box.minY())-1,maxY=(int)Math.ceil(box.maxY())+1,minZ=(int)Math.floor(box.minZ())-1,maxZ=(int)Math.ceil(box.maxZ())+1;\n      int minChunkX=Math.floorDiv(minX,16),maxChunkX=Math.floorDiv(maxX,16),minChunkZ=Math.floorDiv(minZ,16),maxChunkZ=Math.floorDiv(maxZ,16);\n      for(int cx=minChunkX;cx<=maxChunkX;cx++)for(int cz=minChunkZ;cz<=maxChunkZ;cz++)if(!world.visibleChunks().contains(new World.Chunk(cx,cz)))return true;\n      return world.blocks().entrySet().stream().anyMatch(e->{var p=e.getKey();var b=e.getValue();return p.x()>=minX&&p.x()<=maxX&&p.y()>=minY&&p.y()<=maxY&&p.z()>=minZ&&p.z()<=maxZ&&(b==World.Block.UNKNOWN||b==World.Block.UNSUPPORTED);});\n    }",
)

# Fence/wall/pane decoding must reject incomplete state instead of silently inventing false properties.
replace(
    "src/main/java/dev/phantom/ac/world/v12111/BlockCatalogue12111.java",
    "  private static BlockState fence(String name, Map<String, String> p) {\n    return new BlockState(name, Variant.FENCE, Direction.NORTH, Half.BOTTOM, BlockState.StairShape.STRAIGHT,",
    "  private static BlockState fence(String name, Map<String, String> p) {\n    if (!hasAll(p, \"waterlogged\", \"north\", \"south\", \"west\", \"east\")) return incomplete(name);\n    return new BlockState(name, Variant.FENCE, Direction.NORTH, Half.BOTTOM, BlockState.StairShape.STRAIGHT,",
)
replace(
    "src/main/java/dev/phantom/ac/world/v12111/BlockCatalogue12111.java",
    "  private static BlockState wall(String name, Map<String, String> p) {\n    return new BlockState(name, Variant.WALL, Direction.NORTH, Half.BOTTOM, BlockState.StairShape.STRAIGHT,",
    "  private static BlockState wall(String name, Map<String, String> p) {\n    if (!hasAll(p, \"waterlogged\", \"up\", \"north\", \"south\", \"west\", \"east\")) return incomplete(name);\n    return new BlockState(name, Variant.WALL, Direction.NORTH, Half.BOTTOM, BlockState.StairShape.STRAIGHT,",
)
replace(
    "src/main/java/dev/phantom/ac/world/v12111/BlockCatalogue12111.java",
    "  private static BlockState pane(String name, Map<String, String> p) {\n    return new BlockState(name, Variant.PANE, Direction.NORTH, Half.BOTTOM, BlockState.StairShape.STRAIGHT,",
    "  private static BlockState pane(String name, Map<String, String> p) {\n    if (!hasAll(p, \"waterlogged\", \"north\", \"south\", \"west\", \"east\")) return incomplete(name);\n    return new BlockState(name, Variant.PANE, Direction.NORTH, Half.BOTTOM, BlockState.StairShape.STRAIGHT,",
)
replace(
    "src/main/java/dev/phantom/ac/world/v12111/BlockCatalogue12111.java",
    "  private static boolean bool(Map<String, String> p, String key) {\n    return \"true\".equals(p.get(key));\n  }",
    "  private static boolean hasAll(Map<String, String> p, String... keys) {\n    for (String key : keys) if (!p.containsKey(key)) return false;\n    return true;\n  }\n\n  private static boolean bool(Map<String, String> p, String key) {\n    return \"true\".equals(p.get(key));\n  }",
)

# Add a rich, server-observed state snapshot packet so live validation does not replay default attributes/effects/pose.
replace(
    "src/main/java/dev/phantom/ac/Packets.java",
    "  public sealed interface Packet extends Serializable permits Move, ClientInput, Teleport, TeleportConfirm,\n      Velocity, Effect, Gamemode, ChunkData, ChunkUnload, BlockChange, ChunkStates, BlockStateChange, UnsupportedBlockStateChange {",
    "  public sealed interface Packet extends Serializable permits Move, ClientInput, Teleport, TeleportConfirm,\n      Velocity, Effect, Gamemode, PlayerSnapshot, ChunkData, ChunkUnload, BlockChange, ChunkStates, BlockStateChange, UnsupportedBlockStateChange {",
)
replace(
    "src/main/java/dev/phantom/ac/Packets.java",
    "  public record Gamemode(String value) implements Packet { public Gamemode { if(value==null||value.isBlank()) throw new IllegalArgumentException(\"gamemode is required\"); } }",
    "  public record Gamemode(String value) implements Packet { public Gamemode { if(value==null||value.isBlank()) throw new IllegalArgumentException(\"gamemode is required\"); } }\n  public record PlayerSnapshot(String gamemode, Simulation.Attributes attributes, Phase5Mechanics.MovementEffects movementEffects, Map<String,Integer> effects, Phase5Mechanics.Pose pose, Phase5Mechanics.MovementEnvironment movementEnvironment, boolean sleeping, List<dev.phantom.ac.world.EntityCollisions.EntityBox> entityBoxes) implements Packet { public PlayerSnapshot { if(gamemode==null||gamemode.isBlank()) throw new IllegalArgumentException(\"gamemode is required\"); Objects.requireNonNull(attributes); Objects.requireNonNull(movementEffects); effects=Map.copyOf(effects); Objects.requireNonNull(pose); Objects.requireNonNull(movementEnvironment); entityBoxes=List.copyOf(entityBoxes); } }",
)
replace(
    "src/main/java/dev/phantom/ac/State.java",
    "    if(packet instanceof Gamemode g){return new Player(state.position(),state.velocity(),state.yaw(),state.pitch(),state.onGround(),g.value(),state.effects(),state.awaitingTeleport(),uncertain,state.input(),state.attributes(),state.pose(),state.environment(),state.clientTickRange(),new Provenance(event.sequence(),0,\"Gamemode\"),reasons);}",
    "    if(packet instanceof Gamemode g){return new Player(state.position(),state.velocity(),state.yaw(),state.pitch(),state.onGround(),g.value(),state.effects(),state.awaitingTeleport(),uncertain,state.input(),state.attributes(),state.pose(),state.environment(),state.clientTickRange(),new Provenance(event.sequence(),0,\"Gamemode\"),reasons);}\n    if(packet instanceof PlayerSnapshot s){State.Environment environment=s.movementEnvironment().fluid()==Phase5Mechanics.Fluid.WATER?Environment.WATER:s.movementEnvironment().fluid()==Phase5Mechanics.Fluid.LAVA?Environment.LAVA:s.movementEnvironment().climbable()?Environment.CLIMBABLE:Environment.DRY;return new Player(state.position(),state.velocity(),state.yaw(),state.pitch(),state.onGround(),s.gamemode(),s.effects(),state.awaitingTeleport(),uncertain,state.input(),s.attributes(),s.pose(),environment,state.clientTickRange(),new Provenance(event.sequence(),0,\"PlayerSnapshot\"),reasons);}",
)
replace(
    "src/main/java/dev/phantom/ac/State.java",
    "else if(packet instanceof Gamemode)facts.add(Fact.GAMEMODE);return facts;",
    "else if(packet instanceof Gamemode)facts.add(Fact.GAMEMODE);else if(packet instanceof PlayerSnapshot)facts.addAll(EnumSet.of(Fact.GAMEMODE,Fact.ATTRIBUTES,Fact.EFFECTS,Fact.POSE,Fact.ENVIRONMENT));return facts;",
)

# Expose entity collision providers in Phase 6, while keeping the historical convenience Context as an explicitly known-empty provider.
replace(
    "src/main/java/dev/phantom/ac/Phase6Reachability.java",
    "import dev.phantom.ac.world.WorldSnapshot;",
    "import dev.phantom.ac.world.WorldSnapshot;\nimport dev.phantom.ac.world.EntityCollisions;",
)
replace(
    "src/main/java/dev/phantom/ac/Phase6Reachability.java",
    "                        MovementEffects effects,Pose pose,MovementEnvironment movementEnvironment,boolean sleeping,\n                        Set<UncertainDimension> uncertainty) implements Serializable {\n    public Context(long tick,Player player,Simulation.Environment env,Simulation.Attributes attributes,MovementEffects effects,Pose pose,MovementEnvironment movementEnvironment,boolean sleeping){this(tick,player,env,attributes,effects,pose,movementEnvironment,sleeping,Set.of());}\n    public Context {",
    "                        MovementEffects effects,Pose pose,MovementEnvironment movementEnvironment,boolean sleeping,EntityCollisions entityCollisions,\n                        Set<UncertainDimension> uncertainty) implements Serializable {\n    public Context(long tick,Player player,Simulation.Environment env,Simulation.Attributes attributes,MovementEffects effects,Pose pose,MovementEnvironment movementEnvironment,boolean sleeping){this(tick,player,env,attributes,effects,pose,movementEnvironment,sleeping,EntityCollisions.of(List.of()),Set.of());}\n    public Context(long tick,Player player,Simulation.Environment env,Simulation.Attributes attributes,MovementEffects effects,Pose pose,MovementEnvironment movementEnvironment,boolean sleeping,EntityCollisions entityCollisions){this(tick,player,env,attributes,effects,pose,movementEnvironment,sleeping,entityCollisions,Set.of());}\n    public Context {",
)
replace(
    "src/main/java/dev/phantom/ac/Phase6Reachability.java",
    "Objects.requireNonNull(movementEnvironment);uncertainty=Set.copyOf(uncertainty);}",
    "Objects.requireNonNull(movementEnvironment);Objects.requireNonNull(entityCollisions);uncertainty=Set.copyOf(uncertainty);}",
)
replace(
    "src/main/java/dev/phantom/ac/Phase6Reachability.java",
    "    public Context withTick(long tick){return new Context(tick,player,environment,attributes,effects,pose,movementEnvironment,sleeping,uncertainty);}\n    public Context withUncertainty(UncertainDimension... dimensions){EnumSet<UncertainDimension> u=EnumSet.noneOf(UncertainDimension.class);u.addAll(uncertainty);u.addAll(List.of(dimensions));return new Context(simulationTick,player,environment,attributes,effects,pose,movementEnvironment,sleeping,u);}",
    "    public Context withTick(long tick){return new Context(tick,player,environment,attributes,effects,pose,movementEnvironment,sleeping,entityCollisions,uncertainty);}\n    public Context withUncertainty(UncertainDimension... dimensions){EnumSet<UncertainDimension> u=EnumSet.noneOf(UncertainDimension.class);u.addAll(uncertainty);u.addAll(List.of(dimensions));return new Context(simulationTick,player,environment,attributes,effects,pose,movementEnvironment,sleeping,entityCollisions,u);}",
)
replace(
    "src/main/java/dev/phantom/ac/Phase6Reachability.java",
    "Maths.Aabb pb=Maths.Aabb.playerAt(parent.context().player().position(),parent.context().pose());if(!branch.world().fullyKnown(new dev.phantom.ac.geometry.BlockBox(pb.minX(),pb.minY(),pb.minZ(),pb.maxX(),pb.maxY(),pb.maxZ()))){uncertainTransitions++;continue;}\n        for(ExternalTransition event:external){Context pre=applyExternal(parent.context(),event,tick);if(pre.player().uncertain()){uncertainTransitions++;continue;}\n          for(AdvancedInput input:allowed){MovementEnvironment env=adjustEnvironment(pre.movementEnvironment(),pre.player(),input);Vanilla12111RichPhysics.Context rc=new Vanilla12111RichPhysics.Context(tick,pre.player(),input,branch.world(),pre.environment(),pre.attributes(),pre.effects(),pre.pose(),env,pre.sleeping());",
    "Maths.Aabb pb=Maths.Aabb.playerAt(parent.context().player().position(),parent.context().pose());if(!branch.world().fullyKnown(new dev.phantom.ac.geometry.BlockBox(pb.minX(),pb.minY(),pb.minZ(),pb.maxX(),pb.maxY(),pb.maxZ()))){uncertainTransitions++;continue;}\n        for(ExternalTransition event:external){Context pre=applyExternal(parent.context(),event,tick);if(pre.player().uncertain()){uncertainTransitions++;continue;}\n          for(AdvancedInput input:allowed){MovementEnvironment env=adjustEnvironment(pre.movementEnvironment(),pre.player(),input);Vanilla12111RichPhysics.Context rc=new Vanilla12111RichPhysics.Context(tick,pre.player(),input,branch.world(),pre.environment(),pre.attributes(),pre.effects(),pre.pose(),env,pre.sleeping(),pre.entityCollisions());",
)
replace(
    "src/main/java/dev/phantom/ac/Phase6Reachability.java",
    "Context after=new Context(tick+1,stepped.state(),pre.environment(),pre.attributes(),pre.effects(),nextPose,adjustEnvironment(env,stepped.state(),input),pre.sleeping());",
    "Context after=new Context(tick+1,stepped.state(),pre.environment(),pre.attributes(),pre.effects(),nextPose,adjustEnvironment(env,stepped.state(),input),pre.sleeping(),pre.entityCollisions());",
)
replace(
    "src/main/java/dev/phantom/ac/Phase6Reachability.java",
    "Player n=Phase5Mechanics.applyVelocityImpulse(s,new Phase5Mechanics.Vec3Like(v.impulse().x(),v.impulse().y(),v.impulse().z()));return new Context(tick,n,c.environment(),c.attributes(),c.effects(),c.pose(),c.movementEnvironment(),c.sleeping(),c.uncertainty());}",
    "Player n=Phase5Mechanics.applyVelocityImpulse(s,new Phase5Mechanics.Vec3Like(v.impulse().x(),v.impulse().y(),v.impulse().z()));return new Context(tick,n,c.environment(),c.attributes(),c.effects(),c.pose(),c.movementEnvironment(),c.sleeping(),c.entityCollisions(),c.uncertainty());}",
)
replace(
    "src/main/java/dev/phantom/ac/Phase6Reachability.java",
    "Player n=new Player(t.position(),t.velocity(),s.yaw(),s.pitch(),false,s.gamemode(),s.effects(),p,false);return new Context(tick,n,c.environment(),c.attributes(),c.effects(),t.pose(),c.movementEnvironment(),c.sleeping(),c.uncertainty());}",
    "Player n=new Player(t.position(),t.velocity(),s.yaw(),s.pitch(),false,s.gamemode(),s.effects(),p,false);return new Context(tick,n,c.environment(),c.attributes(),c.effects(),t.pose(),c.movementEnvironment(),c.sleeping(),c.entityCollisions(),c.uncertainty());}",
)
replace(
    "src/main/java/dev/phantom/ac/Phase6Reachability.java",
    "Player n=new Player(s.position(),s.velocity(),s.yaw(),s.pitch(),s.onGround(),s.gamemode(),s.effects(),ok?OptionalInt.empty():s.awaitingTeleport(),s.uncertain()||!ok);return new Context(tick,n,c.environment(),c.attributes(),c.effects(),c.pose(),c.movementEnvironment(),c.sleeping(),c.uncertainty());}",
    "Player n=new Player(s.position(),s.velocity(),s.yaw(),s.pitch(),s.onGround(),s.gamemode(),s.effects(),ok?OptionalInt.empty():s.awaitingTeleport(),s.uncertain()||!ok);return new Context(tick,n,c.environment(),c.attributes(),c.effects(),c.pose(),c.movementEnvironment(),c.sleeping(),c.entityCollisions(),c.uncertainty());}",
)

# Make Phase 8 use the rich state already reconstructed and carry entity history into Phase 6.
replace(
    "src/main/java/dev/phantom/ac/Phase8LiveValidation.java",
    "import dev.phantom.ac.world.BlockState;\nimport dev.phantom.ac.world.WorldSnapshot;",
    "import dev.phantom.ac.world.BlockState;\nimport dev.phantom.ac.world.WorldSnapshot;\nimport dev.phantom.ac.world.EntityCollisions;",
)
replace(
    "    InputConstraint currentInput = InputConstraint.any();\n    List<Phase8MovementValidation.Result> results = new ArrayList<>();",
    "    InputConstraint currentInput = InputConstraint.any();\n    long lastInputServerTick = -1;\n    EntityCollisions currentEntityCollisions = EntityCollisions.of(List.of());\n    List<Phase8MovementValidation.Result> results = new ArrayList<>();",
)
replace(
    "      if (packet instanceof Packets.ClientInput input) {\n        if (!normalized.flags().contains(Packets.PacketFlag.DUPLICATE)) currentInput = InputConstraint.fromClientInput(input);\n        continue;\n      }",
    "      if (packet instanceof Packets.ClientInput input) {\n        if (!normalized.flags().contains(Packets.PacketFlag.DUPLICATE)) { currentInput = InputConstraint.fromClientInput(input); lastInputServerTick = event.serverTick(); }\n        continue;\n      }\n      if (packet instanceof Packets.PlayerSnapshot snapshot) {\n        if (!normalized.flags().contains(Packets.PacketFlag.DUPLICATE)) currentEntityCollisions = EntityCollisions.of(snapshot.entityBoxes());\n        continue;\n      }",
)
# Teleports become a new authoritative anchor instead of suppressing every movement until the ACK arrives.
replace(
    "      if (packet instanceof Packets.Teleport teleport) {\n        pendingTeleportId = teleport.id(); candidates = Set.of(); continuation = Continuation.WAITING_FOR_TELEPORT_CONFIRM; continue;\n      }",
    "      if (packet instanceof Packets.Teleport teleport) {\n        pendingTeleportId = teleport.id();\n        State.StateFrame teleportFrame = observedBySequence.get(normalized.sequence());\n        if (teleportFrame != null) {\n          Player anchored = teleportAnchorState(teleportFrame.after());\n          WorldSnapshot teleportWorld = history.statesAt(event.serverTick());\n          Phase7Timing.EventTiming teleportTiming = timing.timingFor(normalized.sequence()).orElse(null);\n          if (teleportTiming != null && !anchored.uncertain()) {\n            long anchorTick = Math.max(0, Phase7Timing.toPhase6Window(teleportTiming).earliestClientTick());\n            candidates = Set.of(new Candidate(0, anchorContext(anchored, teleportWorld, currentInput, anchorTick, currentEntityCollisions),\n                new Phase6Reachability.Provenance(0, -1, event.serverTick(), \"TELEPORT\", \"ROOT\", \"TeleportCorrection\", List.of(\"authoritative server correction anchor\"), 1, List.of())));\n            continuation = Continuation.ACTIVE;\n          } else { candidates = Set.of(); continuation = Continuation.UNANCHORED; }\n        } else { candidates = Set.of(); continuation = Continuation.UNANCHORED; }\n        continue;\n      }",
)
replace(
    "      if (packet instanceof Packets.TeleportConfirm confirm) {\n        if (pendingTeleportId != null && pendingTeleportId == confirm.id()) {\n          pendingTeleportId = null; candidates = Set.of(); continuation = Continuation.UNANCHORED;\n        }\n        continue;\n      }",
    "      if (packet instanceof Packets.TeleportConfirm confirm) {\n        if (pendingTeleportId != null && pendingTeleportId == confirm.id()) pendingTeleportId = null;\n        continue;\n      }",
)
# An old input is no longer reused forever; once it goes stale, validate against the declared unknown input envelope.
replace(
    "      Player prior = stateFrame.before(), observed = stateFrame.after();\n      Validation.SyncWindow sync = Phase7Timing.toPhase6Window(eventTiming);\n      String replayReference = \"live:phase8:\" + playerId + \":\" + normalized.sequence();\n      List<String> inputAssumptions = List.of(\"client-input=\" + currentInput,\n          \"input-constraint-candidates=\" + currentInput.enumerate().size(),",
    "      Player prior = stateFrame.before(), observed = stateFrame.after();\n      Validation.SyncWindow sync = Phase7Timing.toPhase6Window(eventTiming);\n      String replayReference = \"live:phase8:\" + playerId + \":\" + normalized.sequence();\n      if (lastInputServerTick < 0 || event.serverTick() - lastInputServerTick > 1) currentInput = InputConstraint.any();\n      List<String> inputAssumptions = List.of(\"client-input=\" + currentInput,\n          \"input-fresh=\" + (lastInputServerTick >= 0 && event.serverTick() - lastInputServerTick <= 1),\n          \"input-constraint-candidates=\" + currentInput.enumerate().size(),",
)
# Use the authoritative entity provider throughout anchor/context construction.
replace(
    "anchorContext(safeObserved, world, currentInput, Math.max(0, sync.earliestClientTick()))",
    "anchorContext(safeObserved, world, currentInput, Math.max(0, sync.earliestClientTick()), currentEntityCollisions)",
)
replace(
    "Phase6Reachability.Context prepared = withObservedEnvironment(parent.context(), world, currentInput, earliest);",
    "Phase6Reachability.Context prepared = withObservedEnvironment(parent.context(), world, currentInput, earliest, currentEntityCollisions);",
)
replace(
    "  private static Phase6Reachability.Context anchorContext(Player player, WorldSnapshot world, InputConstraint input, long tick) {\n    MovementEnvironment env = inferEnvironment(world, player, input);\n    return new Phase6Reachability.Context(tick, player, environmentFor(env), Attributes.DEFAULT, MovementEffects.NONE, Pose.STANDING, env, false);\n  }",
    "  private static Phase6Reachability.Context anchorContext(Player player, WorldSnapshot world, InputConstraint input, long tick, EntityCollisions entityCollisions) {\n    MovementEnvironment env = inferEnvironment(world, player, input);\n    return new Phase6Reachability.Context(tick, player, environmentFor(env), player.attributes(), movementEffects(player), player.pose(), env, player.pose() == Pose.SLEEPING, entityCollisions);\n  }",
)
replace(
    "  private static Phase6Reachability.Context withObservedEnvironment(Phase6Reachability.Context context, WorldSnapshot world,\n                                                                     InputConstraint input, long tick) {\n    MovementEnvironment env = inferEnvironment(world, context.player(), input);\n    return new Phase6Reachability.Context(tick, context.player(), environmentFor(env), context.attributes(), context.effects(),\n        context.pose(), env, context.sleeping(), context.uncertainty());\n  }",
    "  private static Phase6Reachability.Context withObservedEnvironment(Phase6Reachability.Context context, WorldSnapshot world,\n                                                                     InputConstraint input, long tick, EntityCollisions entityCollisions) {\n    MovementEnvironment env = inferEnvironment(world, context.player(), input);\n    Phase6Reachability.Context next = new Phase6Reachability.Context(tick, context.player(), environmentFor(env), context.player().attributes(), movementEffects(context.player()), context.player().pose(), env, context.player().pose() == Pose.SLEEPING, entityCollisions, context.uncertainty());\n    return next;\n  }",
)
replace(
    "  private static MovementEnvironment inferEnvironment(WorldSnapshot world, Player player, InputConstraint input) {\n    int x = (int) Math.floor(player.position().x()), y = (int) Math.floor(player.position().y()), z = (int) Math.floor(player.position().z());\n    boolean water = false, lava = false, climb = false;\n    for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {\n      BlockState state = world.blockAtOrNull(x + dx, y + dy, z + dz);\n      if (state == null) continue;\n      if (state.variant() == BlockState.Variant.FLUID && state.blockId().equals(\"minecraft:water\")) water = true;\n      if (state.variant() == BlockState.Variant.FLUID && state.blockId().equals(\"minecraft:lava\")) lava = true;\n      if (state.variant() == BlockState.Variant.LADDER) climb = true;\n    }\n    boolean sprint = input.sprint().orElse(false), sneak = input.sneak().orElse(false), swim = input.jump().orElse(false);\n    if (water) return MovementEnvironment.vanillaWater(player.onGround(), sprint, sneak, swim);\n    if (lava) return MovementEnvironment.vanillaLava(player.onGround(), sprint, sneak);\n    if (climb) return MovementEnvironment.vanillaClimbable(player.onGround(), sprint, sneak);\n    return MovementEnvironment.dry(player.onGround(), sprint, sneak);\n  }",
    "  private static MovementEnvironment inferEnvironment(WorldSnapshot world, Player player, InputConstraint input) {\n    Maths.Aabb box = Maths.Aabb.playerAt(player.position(), player.pose());\n    int minX=(int)Math.floor(box.minX()), maxX=(int)Math.floor(Math.nextDown(box.maxX()));\n    int minY=(int)Math.floor(box.minY()), maxY=(int)Math.floor(Math.nextDown(box.maxY()));\n    int minZ=(int)Math.floor(box.minZ()), maxZ=(int)Math.floor(Math.nextDown(box.maxZ()));\n    boolean water=false,lava=false,climb=false;\n    for(int y=minY;y<=maxY;y++) for(int x=minX;x<=maxX;x++) for(int z=minZ;z<=maxZ;z++){\n      BlockState state=world.blockAtOrNull(x,y,z);\n      if(state==null) continue;\n      if(state.variant()==BlockState.Variant.LADDER) climb=true;\n      var fluid=dev.phantom.ac.world.WorldQueries.fluidAt(world,x,y,z);\n      if(fluid.type()==dev.phantom.ac.world.FluidState.Type.WATER && fluid.isFluid()) water=true;\n      if(fluid.type()==dev.phantom.ac.world.FluidState.Type.LAVA && fluid.isFluid()) lava=true;\n    }\n    boolean sprint=input.sprint().orElse(false), sneak=input.sneak().orElse(false), swim=input.sneak().orElse(false)||player.pose()==Pose.SWIMMING;\n    MovementEnvironment env;if(water) env=MovementEnvironment.vanillaWater(player.onGround(),sprint,sneak,swim);else if(lava) env=MovementEnvironment.vanillaLava(player.onGround(),sprint,sneak);else if(climb) env=MovementEnvironment.vanillaClimbable(player.onGround(),sprint,sneak);else env=MovementEnvironment.dry(player.onGround(),sprint,sneak);\n    return new MovementEnvironment(env.fluid(),env.submerged(),env.climbable(),player.onGround(),sprint,sneak,env.swimmingInput(),player.pose()==Pose.FALL_FLYING,env.fluidSpeedMultiplier(),env.fluidDrag(),env.gravityMultiplier());\n  }\n\n  private static MovementEffects movementEffects(Player player) {\n    return new MovementEffects(amplifier(player.effects(),\"speed\",\"minecraft:speed\"), amplifier(player.effects(),\"slowness\",\"minecraft:slowness\"), amplifier(player.effects(),\"jump_boost\",\"minecraft:jump_boost\"), amplifier(player.effects(),\"levitation\",\"minecraft:levitation\"), player.effects().keySet().stream().anyMatch(id -> id.equals(\"slow_falling\") || id.equals(\"minecraft:slow_falling\")));\n  }\n  private static int amplifier(Map<String,Integer> effects,String... ids){for(String id:ids){Integer value=effects.get(id);if(value!=null)return value;}return -1;}\n\n  private static Player teleportAnchorState(Player player) {\n    EnumSet<State.UncertaintyReason> reasons=EnumSet.noneOf(State.UncertaintyReason.class);reasons.addAll(player.uncertaintyReasons());reasons.remove(State.UncertaintyReason.TELEPORT_CORRECTION);\n    return new Player(player.position(),player.velocity(),player.yaw(),player.pitch(),player.onGround(),player.gamemode(),player.effects(),OptionalInt.empty(),!reasons.isEmpty(),player.input(),player.attributes(),player.pose(),player.environment(),player.clientTickRange(),player.provenance(),reasons);\n  }",
)
# Remove obsolete waiting branch so post-correction moves are actually validated.
replace(
    "      if (continuation == Continuation.WAITING_FOR_TELEPORT_CONFIRM) {\n        SearchResult uncertain = new SearchResult(Phase6Reachability.Verdict.UNCERTAIN, Set.of(), 0,\n            candidates.size(), 0, 0, 1, 0,\n            List.of(\"movement observed before the explicit teleport correction was confirmed\"));\n        results.add(Phase8MovementValidation.validate(playerId, event.serverTick(), prior, observed,\n            world, worldReference, sync, inputAssumptions, uncertain, replayReference));\n        continue;\n      }\n",
    "",
)

# Timeline replay format v3 supports PlayerSnapshot without dropping rich state on disk replay.
replace(
    "src/main/java/dev/phantom/ac/Timeline.java",
    "  private static final short FORMAT_VERSION=2;",
    "  private static final short FORMAT_VERSION=3;",
)
replace(
    "      else if(p instanceof Gamemode v){out.writeByte(7);writeString(out,v.value());}\n      else if(p instanceof ChunkData v){",
    "      else if(p instanceof Gamemode v){out.writeByte(7);writeString(out,v.value());}\n      else if(p instanceof PlayerSnapshot v){out.writeByte(14);writeString(out,v.gamemode());writeAttributes(out,v.attributes());writeMovementEffects(out,v.movementEffects());List<Map.Entry<String,Integer>> effectEntries=new ArrayList<>(v.effects().entrySet());effectEntries.sort(Map.Entry.comparingByKey());out.writeInt(effectEntries.size());for(var entry:effectEntries){writeString(out,entry.getKey());out.writeInt(entry.getValue());}out.writeByte(v.pose().ordinal());writeMovementEnvironment(out,v.movementEnvironment());out.writeBoolean(v.sleeping());out.writeInt(v.entityBoxes().size());for(var box:v.entityBoxes()){out.writeInt(box.entityId());writeBlockBox(out,box.box());}}\n      else if(p instanceof ChunkData v){",
)
replace(
    "      case 4->new TeleportConfirm(in.readInt());case 5->new Velocity(readVec(in));case 6->new Effect(readString(in),in.readInt(),in.readBoolean());case 7->new Gamemode(readString(in));\n      case 8->{",
    "      case 4->new TeleportConfirm(in.readInt());case 5->new Velocity(readVec(in));case 6->new Effect(readString(in),in.readInt(),in.readBoolean());case 7->new Gamemode(readString(in));\n      case 14->{String gm=readString(in);var attributes=readAttributes(in);var movementEffects=readMovementEffects(in);int effectCount=readCount(in,\"player effect\");Map<String,Integer> effects=new HashMap<>();for(int i=0;i<effectCount;i++)if(effects.put(readString(in),in.readInt())!=null)throw new IllegalArgumentException(\"duplicate player effect\");var pose=ordinal(Phase5Mechanics.Pose.values(),in.readUnsignedByte(),\"player pose\");var env=readMovementEnvironment(in);boolean sleeping=in.readBoolean();int boxes=readCount(in,\"entity box\");List<dev.phantom.ac.world.EntityCollisions.EntityBox> entityBoxes=new ArrayList<>();for(int i=0;i<boxes;i++)entityBoxes.add(new dev.phantom.ac.world.EntityCollisions.EntityBox(in.readInt(),readBlockBox(in)));yield new PlayerSnapshot(gm,attributes,movementEffects,effects,pose,env,sleeping,entityBoxes);}\n      case 8->{",
)
replace(
    "    private static void writeBlockState(DataOutputStream out,dev.phantom.ac.world.BlockState state)throws IOException{",
    "    private static void writeAttributes(DataOutputStream out,Simulation.Attributes attributes)throws IOException{out.writeDouble(attributes.movementSpeed());out.writeInt(attributes.modifiers().size());for(var m:attributes.modifiers()){writeString(out,m.id());out.writeDouble(m.amount());out.writeByte(m.operation().ordinal());}}\n    private static Simulation.Attributes readAttributes(DataInputStream in)throws IOException{double base=in.readDouble();int count=readCount(in,\"attribute modifier\");List<Phase5Mechanics.AttributeModifier> modifiers=new ArrayList<>();for(int i=0;i<count;i++)modifiers.add(new Phase5Mechanics.AttributeModifier(readString(in),in.readDouble(),ordinal(Phase5Mechanics.ModifierOperation.values(),in.readUnsignedByte(),\"attribute operation\")));return new Simulation.Attributes(base,modifiers);}\n    private static void writeMovementEffects(DataOutputStream out,Phase5Mechanics.MovementEffects effects)throws IOException{out.writeByte(effects.speedAmplifier()+1);out.writeByte(effects.slownessAmplifier()+1);out.writeByte(effects.jumpBoostAmplifier()+1);out.writeByte(effects.levitationAmplifier()+1);out.writeBoolean(effects.slowFalling());}\n    private static Phase5Mechanics.MovementEffects readMovementEffects(DataInputStream in)throws IOException{return new Phase5Mechanics.MovementEffects(in.readUnsignedByte()-1,in.readUnsignedByte()-1,in.readUnsignedByte()-1,in.readUnsignedByte()-1,in.readBoolean());}\n    private static void writeMovementEnvironment(DataOutputStream out,Phase5Mechanics.MovementEnvironment e)throws IOException{out.writeByte(e.fluid().ordinal());out.writeBoolean(e.submerged());out.writeBoolean(e.climbable());out.writeBoolean(e.onGround());out.writeBoolean(e.sprinting());out.writeBoolean(e.sneaking());out.writeBoolean(e.swimmingInput());out.writeBoolean(e.gliding());out.writeDouble(e.fluidSpeedMultiplier());out.writeDouble(e.fluidDrag());out.writeDouble(e.gravityMultiplier());}\n    private static Phase5Mechanics.MovementEnvironment readMovementEnvironment(DataInputStream in)throws IOException{return new Phase5Mechanics.MovementEnvironment(Phase5Mechanics.Fluid.values()[in.readUnsignedByte()],in.readBoolean(),in.readBoolean(),in.readBoolean(),in.readBoolean(),in.readBoolean(),in.readBoolean(),in.readBoolean(),in.readDouble(),in.readDouble(),in.readDouble());}\n    private static void writeBlockBox(DataOutputStream out,dev.phantom.ac.geometry.BlockBox box)throws IOException{out.writeDouble(box.minX());out.writeDouble(box.minY());out.writeDouble(box.minZ());out.writeDouble(box.maxX());out.writeDouble(box.maxY());out.writeDouble(box.maxZ());}\n    private static dev.phantom.ac.geometry.BlockBox readBlockBox(DataInputStream in)throws IOException{return new dev.phantom.ac.geometry.BlockBox(in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble());}\n    private static void writeBlockState(DataOutputStream out,dev.phantom.ac.world.BlockState state)throws IOException{",
)

# Plugin: capture server-authoritative rich state on the main thread, bound history/queue sizes, and keep Bukkit calls synchronous.
replace(
    "import java.util.concurrent.atomic.AtomicLong;",
    "import java.util.concurrent.atomic.AtomicLong;\nimport java.util.Comparator;\nimport org.bukkit.attribute.Attribute;\nimport org.bukkit.block.Block;\nimport org.bukkit.entity.Entity;\nimport org.bukkit.potion.PotionEffect;",
)
replace(
    "  private org.bukkit.scheduler.BukkitTask chunkTask;",
    "  private org.bukkit.scheduler.BukkitTask chunkTask;\n  private org.bukkit.scheduler.BukkitTask liveStateTask;",
)
replace(
    "  private volatile int validationBudget;",
    "  private volatile int validationBudget;\n  private static final int MAX_CAPTURE_PACKETS = 12_000;\n  private static final int MAX_VALIDATION_PACKETS = 6_000;\n  private static final int MAX_CHUNK_QUEUE = 512;",
)
replace(
    "        capture.chunkPackets.incrementAndGet();\n        capture.chunkQueue.add(new PendingChunk(sequence, receivedNanos, column, minY, maxY, clientVersion));",
    "        capture.chunkPackets.incrementAndGet();\n        while (capture.chunkQueue.size() >= MAX_CHUNK_QUEUE) { capture.chunkQueue.poll(); capture.droppedChunks.incrementAndGet(); }\n        capture.chunkQueue.add(new PendingChunk(sequence, receivedNanos, column, minY, maxY, clientVersion));",
)
replace(
    "    chunkTask = getServer().getScheduler().runTaskTimer(this, this::drainChunkQueue, 1L, 1L);\n    int validationInterval = Math.max(1, getConfig().getInt(\"validation.interval-ticks\", 10));\n    validationTask = getServer().getScheduler().runTaskTimerAsynchronously(this, this::evaluateCapturesAsync, validationInterval, validationInterval);",
    "    chunkTask = getServer().getScheduler().runTaskTimer(this, this::drainChunkQueue, 1L, 1L);\n    liveStateTask = getServer().getScheduler().runTaskTimer(this, this::captureLiveStates, 1L, 1L);\n    int validationInterval = Math.max(1, getConfig().getInt(\"validation.interval-ticks\", 10));\n    validationTask = getServer().getScheduler().runTaskTimer(this, this::scheduleEvaluations, validationInterval, validationInterval);",
)
replace(
    "    if (chunkTask != null) chunkTask.cancel();\n    if (validationTask != null) validationTask.cancel();",
    "    if (chunkTask != null) chunkTask.cancel();\n    if (liveStateTask != null) liveStateTask.cancel();\n    if (validationTask != null) validationTask.cancel();",
)
# Replace async method with sync snapshot + async pure-core evaluation.
old = """  private void evaluateCapturesAsync() {\n    for (Map.Entry<UUID, Capture> entry : captures.entrySet()) {\n      Capture capture = entry.getValue();\n      if (!capture.validationRunning.compareAndSet(false, true)) continue;\n      List<RawPacket> raw = capture.copy();\n      if (raw.isEmpty()) { capture.validationRunning.set(false); continue; }\n      UUID playerId = entry.getKey(); Player player = getServer().getPlayer(playerId);\n      if (player == null) { capture.validationRunning.set(false); continue; }\n      String playerName = player.getName();\n      try {\n        Timeline.Snapshot timeline = Timeline.assign(new Packets.Normalizer().normalize(raw), capture.epochNanos, 50_000_000L);\n        Phase8LiveValidation.Report report = Phase8LiveValidation.analyze(playerName, timeline, validationBudget, Phase7Timing.Config.defaultConfig());\n        if (debugFor(playerId)) logPhase8Run(playerName, capture, raw, timeline, report, \"scheduled\");\n        getServer().getScheduler().runTask(this, () -> applyPhase8Result(playerId, capture, report));\n      } catch (RuntimeException failure) {\n        capture.validationRunning.set(false);\n        getLogger().log(java.util.logging.Level.WARNING, \"[PhantomAC][PHASE8] validation failed for \" + playerId, failure);\n      }\n    }\n  }"""
new = """  private void scheduleEvaluations() {\n    for (Map.Entry<UUID, Capture> entry : captures.entrySet()) {\n      Capture capture = entry.getValue();\n      if (!capture.validationRunning.compareAndSet(false, true)) continue;\n      List<RawPacket> raw = capture.copy();\n      if (raw.isEmpty()) { capture.validationRunning.set(false); continue; }\n      UUID playerId = entry.getKey();\n      Player player = getServer().getPlayer(playerId);\n      if (player == null) { capture.validationRunning.set(false); continue; }\n      String playerName = player.getName();\n      long captureEpoch = capture.epochNanos;\n      getServer().getScheduler().runTaskAsynchronously(this, () -> {\n        try {\n          Timeline.Snapshot timeline = Timeline.assign(new Packets.Normalizer().normalize(raw), captureEpoch, 50_000_000L);\n          Phase8LiveValidation.Report report = Phase8LiveValidation.analyze(playerName, timeline, validationBudget, Phase7Timing.Config.defaultConfig());\n          if (debugFor(playerId)) logPhase8Run(playerName, capture, raw, timeline, report, \"scheduled\");\n          getServer().getScheduler().runTask(this, () -> applyPhase8Result(playerId, capture, report));\n        } catch (RuntimeException failure) {\n          capture.validationRunning.set(false);\n          getLogger().log(java.util.logging.Level.WARNING, \"[PhantomAC][PHASE8] validation failed for \" + playerId, failure);\n        }\n      });\n    }\n  }"""
replace("src/main/java/dev/phantom/ac/paper/PhantomPaperPlugin.java", old, new)
replace(
    "new Phase8MovementValidation.Config(2, 20, alertsEnabled, true)",
    "new Phase8MovementValidation.Config(1, 20, alertsEnabled, true)",
)
# Bounded packet append helper and use it for both network and chunk-decoder paths.
replace(
    "      capture.packets.add(new RawPacket(pending.sequence(), pending.receivedNanos(), new Packets.ChunkStates(new dev.phantom.ac.world.Chunk(pending.column.getX(), pending.column.getZ()), states)));",
    "      appendPacket(capture, new RawPacket(pending.sequence(), pending.receivedNanos(), new Packets.ChunkStates(new dev.phantom.ac.world.Chunk(pending.column.getX(), pending.column.getZ()), states)));",
)
replace(
    "  private void record(Capture capture, Packets.Packet packet) { capture.packets.add(new RawPacket(capture.sequence.incrementAndGet(), System.nanoTime(), packet)); }",
    "  private void record(Capture capture, Packets.Packet packet) { appendPacket(capture, new RawPacket(capture.sequence.incrementAndGet(), System.nanoTime(), packet)); }\n  private void appendPacket(Capture capture, RawPacket packet) { synchronized (capture.packets) { while (capture.packets.size() >= MAX_CAPTURE_PACKETS) { capture.packets.remove(0); capture.droppedPackets.incrementAndGet(); } capture.packets.add(packet); } }",
)
replace(
    "    List<RawPacket> copy() { synchronized (packets) { return List.copyOf(packets); } }",
    "    final AtomicLong droppedPackets = new AtomicLong(); final AtomicLong droppedChunks = new AtomicLong();\n    List<RawPacket> copy() { synchronized (packets) { int start=Math.max(0,packets.size()-MAX_VALIDATION_PACKETS); return List.copyOf(packets.subList(start,packets.size())); } }",
)
# Add a main-thread state sampler with authoritative attributes/effects/pose/entity boxes.
needle = "  private void record(Player player, Packets.Packet packet) { record(captures.computeIfAbsent(player.getUniqueId(), ignored -> new Capture(player.getUniqueId(), System.nanoTime())), packet); }"
sampler = r'''  private void captureLiveStates() {
    for (Capture capture : captures.values()) {
      Player player = getServer().getPlayer(capture.playerId);
      if (player == null) continue;
      List<dev.phantom.ac.world.EntityCollisions.EntityBox> entities = new ArrayList<>();
      for (Entity entity : player.getWorld().getNearbyEntities(player.getLocation(), 3.0, 3.0, 3.0)) {
        if (entity.getEntityId() == player.getEntityId()) continue;
        org.bukkit.util.BoundingBox box = entity.getBoundingBox();
        entities.add(new dev.phantom.ac.world.EntityCollisions.EntityBox(entity.getEntityId(), new dev.phantom.ac.geometry.BlockBox(box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ())));
      }
      entities.sort(Comparator.comparingInt(dev.phantom.ac.world.EntityCollisions.EntityBox::entityId));
      org.bukkit.attribute.AttributeInstance attribute = player.getAttribute(Attribute.MOVEMENT_SPEED);
      double movementSpeed = attribute == null ? 0.1 : attribute.getValue();
      Map<String,Integer> effects = new LinkedHashMap<>();
      for (PotionEffect effect : player.getActivePotionEffects()) {
        if (effect.getType().getKey() != null) effects.put(effect.getType().getKey().toString(), effect.getAmplifier());
      }
      Phase5Mechanics.MovementEffects movementEffects = new Phase5Mechanics.MovementEffects(
          effectAmplifier(effects, "minecraft:speed"), effectAmplifier(effects, "minecraft:slowness"),
          effectAmplifier(effects, "minecraft:jump_boost"), effectAmplifier(effects, "minecraft:levitation"),
          effects.containsKey("minecraft:slow_falling"));
      Phase5Mechanics.Pose pose = player.isSleeping() ? Phase5Mechanics.Pose.SLEEPING : player.isGliding() ? Phase5Mechanics.Pose.FALL_FLYING : player.isSwimming() ? Phase5Mechanics.Pose.SWIMMING : player.isSneaking() ? Phase5Mechanics.Pose.CROUCHING : Phase5Mechanics.Pose.STANDING;
      Block feet = player.getLocation().getBlock();
      boolean water = feet.getType() == org.bukkit.Material.WATER || feet.getType() == org.bukkit.Material.BUBBLE_COLUMN;
      boolean lava = feet.getType() == org.bukkit.Material.LAVA;
      boolean climb = feet.getType() == org.bukkit.Material.LADDER || feet.getType() == org.bukkit.Material.VINE || feet.getType() == org.bukkit.Material.SCAFFOLDING;
      Phase5Mechanics.MovementEnvironment environment = water
          ? MovementEnvironment.vanillaWater(player.isOnGround(), player.isSprinting(), player.isSneaking(), player.isSwimming())
          : lava
          ? MovementEnvironment.vanillaLava(player.isOnGround(), player.isSprinting(), player.isSneaking())
          : climb
          ? MovementEnvironment.vanillaClimbable(player.isOnGround(), player.isSprinting(), player.isSneaking())
          : MovementEnvironment.dry(player.isOnGround(), player.isSprinting(), player.isSneaking());
      appendPacket(capture, new RawPacket(capture.sequence.incrementAndGet(), System.nanoTime(), new Packets.PlayerSnapshot(
          player.getGameMode().name().toLowerCase(Locale.ROOT), new dev.phantom.ac.Simulation.Attributes(movementSpeed), movementEffects,
          effects, pose, environment, player.isSleeping(), entities),
          Packets.CaptureProvenance.fromAdapter("paper-live", new Packets.PlayerSnapshot(
              player.getGameMode().name().toLowerCase(Locale.ROOT), new dev.phantom.ac.Simulation.Attributes(movementSpeed), movementEffects,
              effects, pose, environment, player.isSleeping(), entities), null), null)));
    }
  }

  private static int effectAmplifier(Map<String,Integer> effects, String id) { return effects.getOrDefault(id, -1); }

'''
replace("src/main/java/dev/phantom/ac/paper/PhantomPaperPlugin.java", needle, sampler + needle)

# Timeline/packet support requires the new import used by the sampler.
replace(
    "import dev.phantom.ac.World;",
    "import dev.phantom.ac.World;\nimport dev.phantom.ac.Phase5Mechanics.MovementEnvironment;",
)

# Add observability for dropped history/queue state.
replace(
    "    getLogger().info(\"[PhantomAC][PHASE8] source=\" + source + \" player=\" + playerName + \" tick=\" + (last == null ? -1 : last.evidence().serverTick())",
    "    getLogger().info(\"[PhantomAC][PHASE8] source=\" + source + \" player=\" + playerName + \" tick=\" + (last == null ? -1 : last.evidence().serverTick())",
)
replace(
    "        + \" chunks={seen=\" + capture.chunkPackets.get() + \",decoded=\" + capture.decodedChunks.get() + \",pending=\" + capture.chunkQueue.size() + \",fail=\" + capture.chunkDecodeFailures.get() + \"}\"",
    "        + \" chunks={seen=\" + capture.chunkPackets.get() + \",decoded=\" + capture.decodedChunks.get() + \",pending=\" + capture.chunkQueue.size() + \",dropped=\" + capture.droppedChunks.get() + \",fail=\" + capture.chunkDecodeFailures.get() + \"}\"",
)

# Regression tests for the highest-confidence under-detection and semantic fixes.
test = ROOT / "src/test/java/dev/phantom/ac/HardeningRegressionTest.java"
test.write_text(r'''package dev.phantom.ac;

import dev.phantom.ac.Phase8MovementValidation.Accumulated;
import dev.phantom.ac.Phase8MovementValidation.Config;
import dev.phantom.ac.Phase8MovementValidation.State;
import dev.phantom.ac.Phase8MovementValidation.Verdict;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.EntityCollisions;
import dev.phantom.ac.world.v12111.BlockCatalogue12111;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

class HardeningRegressionTest {
  @Test void oneImpossibleObservationAlertsWithDefaultConfig() {
    Phase8MovementValidation.Evidence evidence = evidence(10, Verdict.IMPOSSIBLE);
    Accumulated result = Phase8MovementValidation.Accumulator.empty().accept(evidence, Config.defaults());
    assertTrue(result.alert().isPresent());
  }

  @Test void alternatingImpossibleAndPossibleStillAccumulatesEvidence() {
    Config config = new Config(3, 0, true, true);
    var accumulator = Phase8MovementValidation.Accumulator.empty();
    for (int i = 0; i < 3; i++) {
      accumulator = accumulator.accept(evidence(i * 2, Verdict.IMPOSSIBLE), config).state();
      accumulator = accumulator.accept(evidence(i * 2 + 1, Verdict.POSSIBLE), config).state();
    }
    State state = accumulator.players().get("player/MOVEMENT_REACHABILITY");
    assertEquals(3, state.supportingImpossible());
  }

  @Test void velocityPacketReplacesVelocity() {
    Player base = new Player(Maths.Vec3.ZERO, new Maths.Vec3(0.9, 0.1, 0.2), 0, 0, true,
        "survival", Map.of(), OptionalInt.empty(), false);
    Player moved = Phase5Mechanics.applyVelocityImpulse(base, new Phase5Mechanics.Vec3Like(0.1, 0.4, -0.2));
    assertEquals(new Maths.Vec3(0.1, 0.4, -0.2), moved.velocity());
  }

  @Test void incompleteFenceStateIsUnsupported() {
    assertTrue(BlockCatalogue12111.decode("minecraft:oak_fence", Map.of("north", "true")).isUnsupported());
  }

  @Test void directRichPhysicsContextDoesNotPretendEntityHistoryIsComplete() {
    Vanilla12111RichPhysics.Context context = new Vanilla12111RichPhysics.Context(0, Player.initial(Maths.Vec3.ZERO),
        new Simulation.AdvancedInput(0, 0, false),
        WorldSnapshotHelper.empty(), Simulation.Environment.DRY, Simulation.Attributes.DEFAULT,
        Phase5Mechanics.MovementEffects.NONE, Phase5Mechanics.Pose.STANDING,
        Phase5Mechanics.MovementEnvironment.dry(true, false, false), false);
    assertFalse(context.entityCollisions().boxesIn(new dev.phantom.ac.geometry.BlockBox(-1, -1, -1, 1, 2, 1)).complete());
  }

  @Test void snapshotRoundTripsThroughTimelineCodec() {
    var snapshotPacket = new Packets.PlayerSnapshot("survival", new Simulation.Attributes(0.1),
        Phase5Mechanics.MovementEffects.NONE, Map.of("minecraft:speed", 0), Phase5Mechanics.Pose.STANDING,
        Phase5Mechanics.MovementEnvironment.dry(true, false, false), false,
        List.of(new EntityCollisions.EntityBox(5, new dev.phantom.ac.geometry.BlockBox(1, 2, 3, 2, 3, 4))));
    var raw = new Packets.RawPacket(0, 1, snapshotPacket);
    var timeline = Timeline.assign(new Packets.Normalizer().normalize(List.of(raw)), 0, 50_000_000L);
    var roundTripped = new Timeline.Codec().decode(new Timeline.Codec().encode(timeline));
    assertEquals(snapshotPacket, roundTripped.events().getFirst().packet().packet());
  }

  private static Phase8MovementValidation.Evidence evidence(long tick, Verdict verdict) {
    Player player = Player.initial(Maths.Vec3.ZERO);
    var world = WorldSnapshotHelper.empty();
    var timing = new Validation.SyncWindow(tick, tick, false, List.of());
    return new Phase8MovementValidation.Evidence(Phase8MovementValidation.VERSION, verdict, "player", tick, tick, tick,
        player, player, Contracts.TARGET_VERSION, "test", List.of(), List.of(), 1, 0, verdict == Verdict.IMPOSSIBLE ? 1 : 0,
        "test", verdict == Verdict.IMPOSSIBLE ? OptionalLong.of(tick) : OptionalLong.empty(), java.util.Optional.empty(), List.of(), List.of(),
        Phase8MovementValidation.PHASE5_VERSION, Phase8MovementValidation.PHASE6_VERSION,
        Phase8MovementValidation.PHASE7_VERSION, "test", "MOVEMENT_REACHABILITY");
  }

  private static final class WorldSnapshotHelper {
    static dev.phantom.ac.world.WorldSnapshot empty() { return dev.phantom.ac.world.WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0, 0).build(); }
  }
}
''', encoding="utf-8")
