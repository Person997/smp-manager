package dev.smpmanager;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DialogDisconnectTest {
    @Test void clearingIsAcknowledgedBeforeTheConnectionEnds() {
        List<String> events=new ArrayList<>(); var acknowledgment=new CompletableFuture<byte[]>();
        var done=DialogDisconnect.start(()->events.add("clear"),()->{ events.add("request"); return acknowledgment; },()->events.add("disconnect"));
        assertEquals(List.of("clear","request"),events); assertFalse(done.isDone());
        acknowledgment.complete(null); done.join(); assertEquals(List.of("clear","request","disconnect"),events);
    }
    @Test void lateClientAcknowledgmentCannotCauseRepeatedDisconnects() {
        AtomicInteger ended=new AtomicInteger(); var acknowledgment=new CompletableFuture<byte[]>();
        var done=DialogDisconnect.start(()->{},()->acknowledgment,ended::incrementAndGet);
        acknowledgment.completeExceptionally(new TimeoutException()); done.join();
        acknowledgment.complete(new byte[0]); assertEquals(1,ended.get());
    }
    @Test void failureToSendClearStillEndsTheConnectionWithoutRequestingACookie() {
        AtomicInteger ended=new AtomicInteger();
        DialogDisconnect.start(()->{ throw new IllegalStateException("connection closed"); },()->{ fail("Requested cookie after clear failed"); return null; },ended::incrementAndGet).join();
        assertEquals(1,ended.get());
    }
    @Test void failureToRequestAcknowledgmentDoesNotLeaveConfigurationWaitingForever() {
        AtomicInteger ended=new AtomicInteger();
        DialogDisconnect.start(()->{},()->{ throw new IllegalStateException("connection closed"); },ended::incrementAndGet).join();
        assertEquals(1,ended.get());
    }
}
