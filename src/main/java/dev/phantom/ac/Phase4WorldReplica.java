  public FluidState getFluidState(int x,int y,int z){return WorldQueries.fluidAt(snapshot(),x,y,z);}

  public synchronized List<Generation> generations(){return List.copyOf(history);}

  public synchronized Optional<Generation> generationAtSequence(long sequence){
    Generation answer=null;
    for(Generation generation:history){
      if(generation.sequence()<=sequence)answer=generation;
      else break;
    }
    return Optional.ofNullable(answer);
  }

  public synchronized Optional<Generation> generationAt(Order point){
    Generation answer=null;
    for(Generation generation:history){
      if(generation.order().compareTo(point)<=0)answer=generation;
      else break;
    }
    return Optional.ofNullable(answer);
  }

  public synchronized WorldSnapshot snapshotAtSequence(long sequence){
    return generationAtSequence(sequence).map(Generation::world).orElse(null);
  }

  /** Returns the latest acknowledged immutable generation at or before the query sequence. */
  public synchronized WorldSnapshot snapshotAtOrBefore(long sequence){
    if(sequence<0)return null;
    return generationAtSequence(sequence).map(Generation::world).orElse(null);
  }

  public synchronized WorldSnapshot snapshotAtOrBeforeIncludingPending(long sequence){
    if(sequence<0)return null;
    WorldSnapshot base=generationAtSequence(sequence).map(Generation::world).orElse(null);
    if(base==null)return null;

    /*
     * Do not replay the complete journal on the movement hot path. The acknowledged
     * generation already contains every world mutation known to be visible at this
     * point; only the small set of still-pending block updates needs to be overlaid.
     *
     * Chunk/entity mutations remain pending just as they were before this method was
     * introduced. That keeps this helper focused on the client block-prediction race
     * without turning every movement packet into an O(journal-size) world rebuild.
     */
    List<Event> pendingEvents=new ArrayList<>();
    for(Event event:unassigned.values()){
      if(event.order().sequence()<=sequence
          && event instanceof BlockChange)pendingEvents.add(event);
    }
    for(List<Event> batch:pending.values()){
      for(Event event:batch){
        if(event.order().sequence()<=sequence
            && event instanceof BlockChange)pendingEvents.add(event);
      }
    }
    pendingEvents.sort(Comparator.comparing(Event::order));

    WorldSnapshot result=base;
    for(Event event:pendingEvents){
      BlockChange change=(BlockChange)event;
      result=result.withBlockOverride(
          change.position().x(),change.position().y(),change.position().z(),change.state());
    }
    return result.withCausalSequence(sequence);
  }

  public synchronized void accept(Event event){
    Objects.requireNonNull(event);
    acceptVisible(List.of(event),event.order().sequence());
  }

  /** Stages a clientbound mutation until the next world transaction barrier is acknowledged. */
  public synchronized void queue(Event event){
    Objects.requireNonNull(event);
    unassigned.put(event.order().sequence(),event);
  }

  public synchronized boolean hasUnassignedMutations(){return !unassigned.isEmpty();}

  public synchronized void openBarrier(short transactionId){
    openBarrier(transactionId,Long.MAX_VALUE);
  }

  /**