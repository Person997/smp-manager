package dev.smpmanager;

/** Keeps duplicate clicks and late replies from changing a newer menu. */
final class QueueMenuState {
    record Request(long sequence,long revision) { }
    private boolean finished,commands;
    private long revision,sequence;
    private Request pending;

    synchronized boolean finished() { return finished; }
    synchronized boolean commands() { return commands; }
    synchronized void showCommands() { if(!finished) { commands=true; revision++; } }
    synchronized void back() { if(!finished) { commands=false; revision++; } }
    synchronized Request begin() {
        if(finished || !commands || pending!=null) return null;
        return pending=new Request(++sequence,revision);
    }
    synchronized boolean running(Request request) { return !finished && pending==request; }
    synchronized boolean complete(Request request) {
        if(pending!=request) return false;
        pending=null;
        return !finished && commands && request.revision()==revision;
    }
    synchronized boolean finish() {
        if(finished) return false;
        finished=true; commands=false; pending=null; revision++; return true;
    }
}
