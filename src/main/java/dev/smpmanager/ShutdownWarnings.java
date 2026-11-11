package dev.smpmanager;

import java.util.*;

public final class ShutdownWarnings {
    private static final int[] MARKS={600,300,60,10,9,8,7,6,5,4,3,2,1};
    private UUID session;
    private long deadline,previous=-1;
    private final Set<Integer> sent=new HashSet<>();
    public Optional<String> update(ManagerSnapshot current,long now) {
        if(!current.confirmed() || now-current.updated()>=8000) return Optional.empty();
        if(current.session()==null || !Set.of(ManagerEngine.State.COUNTDOWN,ManagerEngine.State.JOINING).contains(current.state())) {
            session=null; sent.clear(); previous=-1; return Optional.empty();
        }
        long left=Math.max(0,(current.deadline()-now+999)/1000);
        if(!Objects.equals(session,current.session()) || deadline!=current.deadline()) {
            session=current.session(); deadline=current.deadline(); sent.clear(); previous=left+1;
            if(current.state()==ManagerEngine.State.COUNTDOWN && left>=current.graceSeconds()-2) previous=Math.max(previous,current.graceSeconds()+1L);
        }
        if(current.online()>=current.minimumPlayers() || left==0) { previous=left; return Optional.empty(); }
        int urgent=-1;
        for(int mark:MARKS) if(previous>mark && left<=mark && sent.add(mark)) urgent=mark;
        previous=left;
        if(urgent<0) return Optional.empty();
        long n=urgent>=60 && urgent-left<=2?urgent:left;
        String duration=n>=60 && n%60==0?n/60+" minute"+(n==60?"":"s"):n+" second"+(n==1?"":"s");
        return Optional.of("Player threshold not met. Closing world in "+duration+".");
    }
}
