import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;

public class Server {
    private final ConfigLoader.Config cfg;
    private final Router router;

    private Selector selector;
    private final Map<SocketChannel, ConnectionContext> contexts = new HashMap<>();

    // Metrics (single-threaded, safe)
    private final Metrics metrics = Metrics.get();

    // Timeouts (tune later)
    private static final long IDLE_TIMEOUT_MS = 15_000;   // connection idle
    private static final long HEADER_TIMEOUT_MS = 10_000; // header not finished
    private static final long BODY_TIMEOUT_MS = 20_000;   // body not finished

    // Backpressure: max queued bytes per connection (prevents slow clients OOM-ing you)
    private static final int MAX_PENDING_WRITE_BYTES = 2 * 1024 * 1024; // 2MB

    public Server(ConfigLoader.Config cfg, Router router) {
        this.cfg = cfg;
        this.router = router;
    }

    /** Called by Router/Admin to display server stats */
    public Metrics.Snapshot metricsSnapshot() {
        int pendingTotal = 0;
        for (ConnectionContext ctx : contexts.values()) pendingTotal += ctx.pendingWriteBytes;
        return metrics.snapshot(contexts.size(), pendingTotal);
    }


}
