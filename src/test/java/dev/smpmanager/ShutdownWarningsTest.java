package dev.smpmanager;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ShutdownWarningsTest {
    final UUID session=UUID.randomUUID(); final long end=10_000_000;
    ManagerSnapshot status(long now,int online,ManagerEngine.State state,int grace) {
        return new ManagerSnapshot(state,session,0,5,4,online,List.of(),end,now,true,grace==3600,grace,false,"");
    }
    @Test void overrideOnlyWarnsAtRequestedMilestonesAndNeverDuplicates() {
        ShutdownWarnings warnings=new ShutdownWarnings(); List<String> messages=new ArrayList<>();
        for(int left=3600;left>=1;left--) {
            long now=end-left*1000L; ManagerSnapshot current=status(now,1,ManagerEngine.State.COUNTDOWN,3600);
            warnings.update(current,now).ifPresent(messages::add);
            assertTrue(warnings.update(current,now+10).isEmpty());
        }
        assertEquals(13,messages.size());
        assertEquals("Player threshold not met. Closing world in 10 minutes.",messages.get(0));
        assertEquals("Player threshold not met. Closing world in 5 minutes.",messages.get(1));
        assertEquals("Player threshold not met. Closing world in 1 minute.",messages.get(2));
        for(int n=10;n>=1;n--) assertEquals("Player threshold not met. Closing world in "+n+" second"+(n==1?"":"s")+".",messages.get(13-n));
    }
    @Test void normalGraceAnnouncesFiveMinutesAtItsBeginning() {
        long now=end-299_000; ShutdownWarnings warnings=new ShutdownWarnings();
        assertEquals("Player threshold not met. Closing world in 5 minutes.",warnings.update(status(now,3,ManagerEngine.State.COUNTDOWN,300),now).orElseThrow());
    }
    @Test void metThresholdStaleSamplesAndClosedWorldsSuppressWarnings() {
        long now=end-60_000; ShutdownWarnings warnings=new ShutdownWarnings();
        assertTrue(warnings.update(status(now,4,ManagerEngine.State.COUNTDOWN,3600),now).isEmpty());
        assertTrue(warnings.update(status(now-8000,1,ManagerEngine.State.COUNTDOWN,3600),now).isEmpty());
        assertTrue(warnings.update(status(now,1,ManagerEngine.State.CLOSED,300),now).isEmpty());
    }
    @Test void lagProducesOnlyTheCurrentUrgentWarning() {
        ShutdownWarnings warnings=new ShutdownWarnings(); long first=end-601_000,last=end-7000;
        assertTrue(warnings.update(status(first,1,ManagerEngine.State.COUNTDOWN,3600),first).isEmpty());
        assertEquals("Player threshold not met. Closing world in 7 seconds.",warnings.update(status(last,1,ManagerEngine.State.COUNTDOWN,3600),last).orElseThrow());
    }
}
