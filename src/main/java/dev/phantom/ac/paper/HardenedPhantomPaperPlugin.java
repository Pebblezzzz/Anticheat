  }

  private static boolean isNearBlock(Capture capture,dev.phantom.ac.world.Pos position){
    return Math.abs(capture.lastServerX-position.x())<16.0
        &&Math.abs(capture.lastServerY-position.y())<16.0
        &&Math.abs(capture.lastServerZ-position.z())<16.0;
  }

  private void captureLiveContext(){
    for(Capture capture:captures.values()){
      Player player=getServer().getPlayer(capture.playerId);
      if(player==null)continue;
      long authoritativeTick=capture.authoritativeServerTick.incrementAndGet();
      Long authoritativeClientTick=capture.clientTickTracker.hasObservedBoundary()
          ?capture.clientTickTracker.clientTickForMovement():null;
      capture.updateServerPosition(player);
      if(capture.clientWorld.hasUnassignedMutations())requestWorldBarrier(player,capture);
      capture.lastAuthoritativePosition=new Vec3(player.getLocation().getX(),player.getLocation().getY(),player.getLocation().getZ());
      org.bukkit.util.Vector authoritativeVelocity=player.getVelocity();
      capture.lastAuthoritativeVelocity=new Vec3(authoritativeVelocity.getX(),authoritativeVelocity.getY(),authoritativeVelocity.getZ());
      capture.lastAuthoritativeOnGround=player.isOnGround();
      capture.lastAuthoritativeCanFly=player.getAllowFlight();
      capture.lastAuthoritativeFlying=player.isFlying();
      capture.minY=player.getWorld().getMinHeight();
      capture.maxY=player.getWorld().getMaxHeight();

      AttributeInstance movement=player.getAttribute(Attribute.MOVEMENT_SPEED);
      double movementSpeed=movement==null?0.1:movement.getValue();
      Map<String,Integer> effects=new LinkedHashMap<>();
      for(PotionEffect effect:player.getActivePotionEffects())
        if(effect.getType().getKey()!=null)
          effects.put(effect.getType().getKey().toString(),effect.getAmplifier());

      Phase5Mechanics.Pose pose=
          player.isSleeping()?Phase5Mechanics.Pose.SLEEPING:
          player.isGliding()?Phase5Mechanics.Pose.FALL_FLYING:
          player.isSwimming()?Phase5Mechanics.Pose.SWIMMING:
          player.isSneaking()?Phase5Mechanics.Pose.CROUCHING:
          Phase5Mechanics.Pose.STANDING;

      boolean water=false,lava=false,climb=false;
      org.bukkit.util.BoundingBox box=player.getBoundingBox();
      int minX=(int)Math.floor(box.getMinX()),maxX=(int)Math.floor(Math.nextDown(box.getMaxX()));
      int minY=(int)Math.floor(box.getMinY()),maxY=(int)Math.floor(Math.nextDown(box.getMaxY()));
      int minZ=(int)Math.floor(box.getMinZ()),maxZ=(int)Math.floor(Math.nextDown(box.getMaxZ()));

      for(int y=minY;y<=maxY;y++)for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++){
        Material material=player.getWorld().getBlockAt(x,y,z).getType();
        if(material==Material.WATER||material==Material.BUBBLE_COLUMN)water=true;
        if(material==Material.LAVA)lava=true;
        if(material==Material.LADDER||material==Material.VINE||material==Material.SCAFFOLDING)climb=true;
      }

      boolean sprint=player.isSprinting(),sneak=player.isSneaking();
      Phase5Mechanics.MovementEnvironment env=
          water?Phase5Mechanics.MovementEnvironment.vanillaWater(player.isOnGround(),sprint,sneak,player.isSwimming()):
          lava?Phase5Mechanics.MovementEnvironment.vanillaLava(player.isOnGround(),sprint,sneak):
          climb?Phase5Mechanics.MovementEnvironment.vanillaClimbable(player.isOnGround(),sprint,sneak):
          Phase5Mechanics.MovementEnvironment.dry(player.isOnGround(),sprint,sneak);

      /*
       * Bukkit's nearby-entity query is an optimization, not a proof that the
       * returned set is complete. Enumerate the world entity set and retain every
       * entity whose box intersects a generous six-block validation region.
       * Normal Phase 5 movement and collision probes stay well inside this region,
       * so the resulting fixed provider can truthfully advertise completeness.
       */
      org.bukkit.util.BoundingBox relevantEntityRegion = box.expand(6.0,6.0,6.0);
      List<EntityCollisions.EntityBox> entityBoxes=new ArrayList<>();
      for(Entity entity:player.getWorld().getEntities()){
        if(entity.getEntityId()==player.getEntityId())continue;
        org.bukkit.util.BoundingBox eb=entity.getBoundingBox();
        if(eb.getMaxX()<relevantEntityRegion.getMinX()
            ||eb.getMinX()>relevantEntityRegion.getMaxX()
            ||eb.getMaxY()<relevantEntityRegion.getMinY()
            ||eb.getMinY()>relevantEntityRegion.getMaxY()
            ||eb.getMaxZ()<relevantEntityRegion.getMinZ()
            ||eb.getMinZ()>relevantEntityRegion.getMaxZ())continue;
        entityBoxes.add(new EntityCollisions.EntityBox(entity.getEntityId(),
            new dev.phantom.ac.geometry.BlockBox(eb.getMinX(),eb.getMinY(),eb.getMinZ(),eb.getMaxX(),eb.getMaxY(),eb.getMaxZ())));
      }
      entityBoxes.sort(Comparator.comparingInt(EntityCollisions.EntityBox::entityId));

      Packets.PlayerContext context=new Packets.PlayerContext(
          player.getGameMode().name().toLowerCase(Locale.ROOT),
          new dev.phantom.ac.Simulation.Attributes(movementSpeed),
          effects,pose,env,
          new Vec3(player.getLocation().getX(),player.getLocation().getY(),player.getLocation().getZ()),
          new Vec3(authoritativeVelocity.getX(),authoritativeVelocity.getY(),authoritativeVelocity.getZ()),
          player.getAllowFlight(),player.isFlying(),player.isSleeping(),entityBoxes);

      if(capture.initialState==null){
        long anchorReceivedNanos=System.nanoTime();
        State.Environment stateEnvironment=switch(env.fluid()){
          case WATER -> State.Environment.WATER;
          case LAVA -> State.Environment.LAVA;
          case NONE -> env.climbable()?State.Environment.CLIMBABLE:State.Environment.DRY;
        };
        capture.initialState=new State.Player(
            vector(player.getLocation().getX(),player.getLocation().getY(),player.getLocation().getZ()),
            vector(authoritativeVelocity.getX(),authoritativeVelocity.getY(),authoritativeVelocity.getZ()),player.getLocation().getYaw(),player.getLocation().getPitch(),player.isOnGround(),
            player.getGameMode().name().toLowerCase(Locale.ROOT),effects,java.util.OptionalInt.empty(),false,
            java.util.Optional.empty(),new dev.phantom.ac.Simulation.Attributes(movementSpeed),pose,stateEnvironment,
            State.TickRange.unknown(),State.Provenance.UNKNOWN,Set.of());
        capture.initialStateReceivedNanos=anchorReceivedNanos;
      }

      long contextReceivedNanos=System.nanoTime();
      appendPacket(capture,new RawPacket(capture.sequence.incrementAndGet(),contextReceivedNanos,context,
          Packets.CaptureProvenance.fromAdapter("paper-live",context,authoritativeTick,authoritativeClientTick)));
    }
  }

  private WorldSnapshot validationSnapshot(Capture capture,double centerX,double centerZ,
                                             double observedX,double observedZ){
    double anchorX=centerX;
    double anchorZ=centerZ;
    State.Player anchor=capture.initialState;
    if(anchor!=null&&!anchor.uncertain()){
      anchorX=anchor.position().x();
      anchorZ=anchor.position().z();
    }

    double spanX=Math.abs(observedX-centerX);
    double spanZ=Math.abs(observedZ-centerZ);
    int observedRadius=Math.max(
        LOCAL_SNAPSHOT_RADIUS_CHUNKS,
        Math.min(8,(int)Math.ceil(Math.max(spanX,spanZ)/16.0)+LOCAL_SNAPSHOT_RADIUS_CHUNKS));

    WorldSnapshot snapshot=capture.clientWorld.snapshotAround(
        centerX,centerZ,LOCAL_SNAPSHOT_RADIUS_CHUNKS);

    if(Math.abs(centerX-anchorX)>16.0*LOCAL_SNAPSHOT_RADIUS_CHUNKS
        ||Math.abs(centerZ-anchorZ)>16.0*LOCAL_SNAPSHOT_RADIUS_CHUNKS){
      snapshot=WorldSnapshot.merge(snapshot,
          capture.clientWorld.snapshotAround(anchorX,anchorZ,LOCAL_SNAPSHOT_RADIUS_CHUNKS));
    }

    // The server may deliberately refuse to move a player whose client-reported
    // position is invalid. Validate against the observed destination as well as
    // the server position, otherwise a long rejected movement can fall outside
    // the acknowledged collision window and become UNKNOWN instead of impossible.
    if(Math.abs(observedX-centerX)>1.0 || Math.abs(observedZ-centerZ)>1.0){
      snapshot=WorldSnapshot.merge(snapshot,
          capture.clientWorld.snapshotAround(observedX,observedZ,observedRadius));
    }

    return snapshot;
  }

  /**
   * The expensive prediction path runs on the dedicated validation executor.
   * Packet capture stays lightweight and all Bukkit/Paper actions remain on the
   * server's main thread.
   */
  private void schedulePredictionValidation(Capture capture){
    if(capture==null)return;
    ExecutorService executor=validationExecutor;
    if(executor==null)return;

    /*
     * Validation is CPU-heavy but stateful; never execute it
     * on the packet connection's Netty EventLoop: doing so turns anti-cheat work
     * into client-visible packet/movement latency.
     *
     * predictionValidationQueued is deliberately a coalescing gate. While one batch
     * is running, additional movement/world packets only cause a single follow-up
     * batch, rather than one expensive prediction pass per packet.
     */
    if(!capture.predictionValidationQueued.compareAndSet(false,true))return;
    try{
      executor.execute(()->{
        try{
          runPredictionValidation(capture);
        }finally{