package dev.smpmanager;

import java.util.concurrent.*;
import java.util.function.Supplier;

/** Do not end the connection until the client has processed the preceding clear-dialog packet. */
final class DialogDisconnect {
    static CompletableFuture<Void> start(Runnable clearDialog,Supplier<CompletableFuture<byte[]>> acknowledge,Runnable endConnection) {
        CompletableFuture<byte[]> barrier;
        try {
            clearDialog.run();
            barrier=acknowledge.get().orTimeout(2,TimeUnit.SECONDS);
        } catch(RuntimeException unavailable) { barrier=CompletableFuture.failedFuture(unavailable); }
        return barrier.handle((ignored,failure)->{ endConnection.run(); return null; });
    }
}
