package dev.smpmanager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** A connection owns its queue presence until that exact connection is removed. */
final class QueueConnections<C> {
    private final Map<UUID,C> current=new ConcurrentHashMap<>();
    private boolean closed;

    synchronized C replace(UUID id,C connection,Runnable registerPresence) {
        if(closed) throw new IllegalStateException("The queue is stopping");
        registerPresence.run();
        return current.put(id,connection);
    }
    synchronized boolean remove(UUID id,C connection,Runnable removePresence) {
        if(current.get(id)!=connection) return false;
        current.remove(id); removePresence.run(); return true;
    }
    C get(UUID id) { return current.get(id); }
    boolean contains(UUID id) { return current.containsKey(id); }
    boolean isCurrent(UUID id,C connection) { return current.get(id)==connection; }
    List<C> snapshot() { return List.copyOf(current.values()); }
    synchronized List<C> close() { closed=true; return snapshot(); }
}
