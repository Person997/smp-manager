package dev.smpmanager;

import java.util.*;

public record ManagerSnapshot(ManagerEngine.State state,UUID session,int queueSize,int startPlayers,int minimumPlayers,
                              int online,List<String> players,long deadline,long updated,boolean confirmed,
                              boolean autopass,int graceSeconds,boolean rearmRequired,String detail) {
    public boolean worldOpen() { return Set.of(ManagerEngine.State.JOINING,ManagerEngine.State.ACTIVE,ManagerEngine.State.COUNTDOWN).contains(state); }
    public String text(long now) {
        String population=worldOpen()?"World open — "+(confirmed?online+" eligible player(s)":"player count unconfirmed"):
                "World frozen — queue "+queueSize+"/"+startPlayers;
        String timer=deadline>now?" | "+(state==ManagerEngine.State.JOINING?"Join window":"Closing in")+" "+formatTime((deadline-now+999)/1000):"";
        return "SMP Manager: "+population+timer+(autopass?" | Autopass this session":"")+". "+detail;
    }
    public static String formatTime(long seconds) {
        seconds=Math.max(0,seconds);
        return seconds>=3600?"%d:%02d:%02d".formatted(seconds/3600,(seconds/60)%60,seconds%60):"%d:%02d".formatted(seconds/60,seconds%60);
    }
}
