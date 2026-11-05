package kr.magi.mirror.common;

/** Prevent a queued old Surface frame from uncovering the display after reconnect. */
public final class PresentationGate {
    private long minimum=Long.MAX_VALUE;
    private long last=-1;
    public void begin(long presentationUs) {
        if(presentationUs<0 || presentationUs>Long.MAX_VALUE/1000) throw new IllegalArgumentException("Invalid timestamp");
        minimum=presentationUs*1000;
        last=-1;
    }
    public void clear() {minimum=Long.MAX_VALUE;last=-1;}
    public boolean accept(long timestampNanos) {
        if(timestampNanos<minimum || timestampNanos<=last) return false;
        last=timestampNanos;
        return true;
    }
}
