import java.util.*;

public final class Metrics {
    private static final Metrics INSTANCE = new Metrics();
    public static Metrics get() { return INSTANCE; }

    private final long startMs = System.currentTimeMillis();

    private long totalRequests = 0;
    private long totalResponses = 0;
    private long bytesOut = 0;

    private final Map<Integer, Long> statusCounts = new HashMap<>();

    private Metrics() {}

    public void onRequest() {
        totalRequests++;
    }

    public void onResponseStatus(int status) {
        totalResponses++;
        statusCounts.put(status, statusCounts.getOrDefault(status, 0L) + 1);
    }

    public void onBytesOut(long n) {
        if (n > 0) bytesOut += n;
    }

    public long uptimeMs() {
        return System.currentTimeMillis() - startMs;
    }

    public Snapshot snapshot(int activeConnections, int pendingWriteBytesTotal) {
        // copy map so it’s stable for rendering
        Map<Integer, Long> sc = new TreeMap<>(statusCounts);
        return new Snapshot(
                uptimeMs(),
                activeConnections,
                pendingWriteBytesTotal,
                totalRequests,
                totalResponses,
                bytesOut,
                sc
        );
    }

    public static final class Snapshot {
        public final long uptimeMs;
        public final int activeConnections;
        public final int pendingWriteBytesTotal;
        public final long totalRequests;
        public final long totalResponses;
        public final long bytesOut;
        public final Map<Integer, Long> statusCounts;

        Snapshot(long uptimeMs, int activeConnections, int pendingWriteBytesTotal,
                 long totalRequests, long totalResponses, long bytesOut,
                 Map<Integer, Long> statusCounts) {
            this.uptimeMs = uptimeMs;
            this.activeConnections = activeConnections;
            this.pendingWriteBytesTotal = pendingWriteBytesTotal;
            this.totalRequests = totalRequests;
            this.totalResponses = totalResponses;
            this.bytesOut = bytesOut;
            this.statusCounts = statusCounts;
        }
    }
}
