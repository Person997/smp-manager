package dev.smpmanager;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class QueueInteractionTest {
    @Test void obsoleteConnectionCannotRemoveItsReplacementOrQueuePresence() {
        var connections=new QueueConnections<Object>(); UUID id=UUID.randomUUID();
        Object old=new Object(),replacement=new Object(); Map<UUID,Object> presence=new HashMap<>();
        connections.replace(id,old,()->presence.put(id,old));
        assertSame(old,connections.replace(id,replacement,()->presence.put(id,replacement)));
        assertFalse(connections.remove(id,old,()->presence.remove(id)));
        assertSame(replacement,connections.get(id)); assertSame(replacement,presence.get(id));
        assertTrue(connections.remove(id,replacement,()->presence.remove(id)));
        assertFalse(connections.contains(id)); assertTrue(presence.isEmpty());
    }
    @Test void failedAdmissionKeepsTheExistingConnection() {
        var connections=new QueueConnections<Object>(); UUID id=UUID.randomUUID(); Object old=new Object();
        connections.replace(id,old,()->{});
        assertThrows(IllegalArgumentException.class,()->connections.replace(id,new Object(),()->{ throw new IllegalArgumentException("Not whitelisted"); }));
        assertSame(old,connections.get(id));
    }
    @Test void closingTheQueueRejectsAdmissionsBeforeTheyChangePresence() {
        var connections=new QueueConnections<Object>(); UUID id=UUID.randomUUID(); Object current=new Object();
        connections.replace(id,current,()->{});
        assertEquals(List.of(current),connections.close());
        assertThrows(IllegalStateException.class,()->connections.replace(UUID.randomUUID(),new Object(),()->fail("Admission ran during shutdown")));
        assertTrue(connections.remove(id,current,()->{})); assertTrue(connections.snapshot().isEmpty());
    }
    @Test void replacingAndRemovingPresenceAreAtomicAcrossThreads() throws Exception {
        var connections=new QueueConnections<Object>(); UUID id=UUID.randomUUID(); Object old=new Object(),replacement=new Object();
        Map<UUID,Object> presence=new ConcurrentHashMap<>(); connections.replace(id,old,()->presence.put(id,old));
        CountDownLatch updating=new CountDownLatch(1),continueUpdate=new CountDownLatch(1),cleanupStarted=new CountDownLatch(1);
        try(var threads=Executors.newFixedThreadPool(2)) {
            var replace=threads.submit(()->connections.replace(id,replacement,()->{
                presence.put(id,replacement); updating.countDown();
                try { assertTrue(continueUpdate.await(3,TimeUnit.SECONDS)); } catch(InterruptedException interrupted) { throw new AssertionError(interrupted); }
            }));
            try {
                assertTrue(updating.await(3,TimeUnit.SECONDS));
                var remove=threads.submit(()->{ cleanupStarted.countDown(); return connections.remove(id,old,()->presence.remove(id)); });
                assertTrue(cleanupStarted.await(3,TimeUnit.SECONDS)); continueUpdate.countDown();
                assertSame(old,replace.get(3,TimeUnit.SECONDS)); assertFalse(remove.get(3,TimeUnit.SECONDS));
                assertSame(replacement,presence.get(id)); assertSame(replacement,connections.get(id));
            } finally { continueUpdate.countDown(); }
        }
    }
    @Test void runRequiresTheCommandsViewAndRejectsDoubleClicks() {
        var menu=new QueueMenuState(); assertNull(menu.begin());
        menu.showCommands(); var request=menu.begin(); assertNotNull(request); assertNull(menu.begin());
        assertTrue(menu.complete(request)); assertNotNull(menu.begin());
    }
    @Test void backPreventsLateRepliesFromReopeningOrOverwritingAnotherMenu() {
        var menu=new QueueMenuState(); menu.showCommands(); var request=menu.begin();
        menu.back(); menu.showCommands(); assertFalse(menu.complete(request)); assertTrue(menu.commands());
        var next=menu.begin(); assertNotNull(next); assertTrue(menu.complete(next));
        var third=menu.begin(); menu.back(); assertFalse(menu.complete(third)); assertFalse(menu.commands());
    }
    @Test void disconnectOrWorldEntryInvalidatesPendingRepliesAndFutureActions() {
        var menu=new QueueMenuState(); menu.showCommands(); var request=menu.begin();
        assertTrue(menu.finish()); assertFalse(menu.finish()); assertFalse(menu.running(request)); assertFalse(menu.complete(request));
        menu.showCommands(); assertFalse(menu.commands()); assertNull(menu.begin());
    }
    @Test void duplicateOldCompletionDoesNotUnlockANewerCommand() {
        var menu=new QueueMenuState(); menu.showCommands(); var first=menu.begin(); assertTrue(menu.complete(first));
        var next=menu.begin(); assertFalse(menu.complete(first)); assertTrue(menu.running(next)); assertNull(menu.begin()); assertTrue(menu.complete(next));
    }
}
