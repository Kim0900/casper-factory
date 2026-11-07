package kr.magi.mirror.common;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.List;
import java.util.Set;

/** Pure selection policy. Names come exclusively from OS Wi-Fi tethering callbacks. */
public final class ApSelection {
    private ApSelection() { }
    public static final class Candidate {
        public final String name;
        public final int index, prefix;
        public final InetAddress address;
        public final boolean up;
        public Candidate(String name, int index, boolean up, InetAddress address, int prefix) {
            this.name=name; this.index=index; this.up=up; this.address=address; this.prefix=prefix;
        }
        private boolean eligible(Set<String> tetheredWifi) {
            if (!tetheredWifi.contains(name) || index < 0 || !up || !(address instanceof Inet4Address)
                    || !address.isSiteLocalAddress() || prefix < 1 || prefix > 30) return false;
            int hostMask=(int)(0xffffffffL >>> prefix);
            int host=java.nio.ByteBuffer.wrap(address.getAddress()).getInt() & hostMask;
            return host != 0 && host != hostMask;
        }
        public boolean same(Candidate other) {
            return other != null && name.equals(other.name) && index==other.index && prefix==other.prefix
                    && up==other.up && address.equals(other.address);
        }
    }
    public static Candidate select(Set<String> tetheredWifi, List<Candidate> candidates) throws IOException {
        Candidate selected=null;
        for (Candidate candidate : candidates) if (candidate.eligible(tetheredWifi)) {
            if (selected != null) throw new IOException("Ambiguous Wi-Fi AP IPv4 addresses");
            selected=candidate;
        }
        if (selected==null) throw new IOException("No active Wi-Fi AP private IPv4 address");
        return selected;
    }
    public static boolean unchanged(Candidate selected, Set<String> names, List<Candidate> candidates) {
        try { return selected.same(select(names,candidates)); }
        catch (IOException exception) { return false; }
    }
    public static void checkLease(Candidate selected, Set<String> names, List<Candidate> candidates, RouteLease lease) {
        if (!unchanged(selected, names, candidates)) lease.invalidate();
    }
}
