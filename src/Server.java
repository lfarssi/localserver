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

    public void run() throws IOException {
        selector = Selector.open();

        // Bind multiple ports
        for (int port : cfg.ports) {
            ServerSocketChannel ssc = ServerSocketChannel.open();
            ssc.configureBlocking(false);
            ssc.bind(new InetSocketAddress(cfg.host, port));
            ssc.register(selector, SelectionKey.OP_ACCEPT);
            System.out.println("Listening on " + cfg.host + ":" + port);
        }

        // Main event loop: never crash
        while (true) {
            try {
                selector.select(250); // short tick to handle timeouts

                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();

                    try {
                        if (!key.isValid()) continue;

                        int ops = key.readyOps(); // snapshot ops; safe against mid-loop cancellation

                        if ((ops & SelectionKey.OP_ACCEPT) != 0) onAccept(key);
                        if ((ops & SelectionKey.OP_READ)   != 0) onRead(key);
                        if ((ops & SelectionKey.OP_WRITE)  != 0) onWrite(key);

                    } catch (CancelledKeyException ignored) {
                        // key cancelled while processing; ignore safely
                    } catch (Exception e) {
                        System.err.println("Key error: " + e.getMessage());
                        e.printStackTrace();
                        try { key.channel().close(); } catch (Exception ignored2) {}
                    }
                }

                enforceTimeouts();

            } catch (Exception e) {
                // Catch-all: must never crash
                System.err.println("Loop error: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private void onAccept(SelectionKey key) throws IOException {
        ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
        SocketChannel ch = ssc.accept();
        if (ch == null) return;

        ch.configureBlocking(false);
        ch.socket().setTcpNoDelay(true);

        ConnectionContext ctx = new ConnectionContext(ch);
        contexts.put(ch, ctx);

        ch.register(selector, SelectionKey.OP_READ);
    }

    private void onRead(SelectionKey key) {
        SocketChannel ch = (SocketChannel) key.channel();
        ConnectionContext ctx = contexts.get(ch);
        if (ctx == null) {
            closeQuietly(ch);
            return;
        }

        ctx.lastActivityMs = System.currentTimeMillis();

        try {
            int n = ch.read(ctx.readBuffer);
            if (n == -1) {
                closeConnection(ch);
                return;
            }
            if (n == 0) return;

            ctx.readBuffer.flip();

            // Parse incrementally: may yield 0..N requests (pipelining)
            while (true) {
                HttpParser.ParseResult pr = ctx.parser.parse(ctx.readBuffer, cfg.clientBodyLimitBytes);
                if (pr.status == HttpParser.Status.NEED_MORE) break;

                if (pr.status == HttpParser.Status.ERROR) {
                    int code = (pr.errorCode == 0) ? 400 : pr.errorCode;
                    Response resp = ErrorPages.response(cfg, code);

                    // Metrics: count as a response too (auditors like this)
                    metrics.onRequest();
                    metrics.onResponseStatus(resp.status);

                    if (!ctx.enqueue(resp.toByteBuffers())) {
                        closeConnection(ch);
                        return;
                    }

                    if (key.isValid()) key.interestOps(SelectionKey.OP_WRITE);
                    ctx.closeAfterWrite = true;
                    break;
                }

                HttpModels.Request req = pr.request;

                // Metrics: request received
                metrics.onRequest();

                // Attach useful connection attrs for CGI env parity
                try {
                    InetSocketAddress local = (InetSocketAddress) ch.getLocalAddress();
                    InetSocketAddress remote = (InetSocketAddress) ch.getRemoteAddress();
                    req.attrs.put("serverPort", local.getPort());
                    req.attrs.put("remoteAddr", remote.getAddress().getHostAddress());
                    req.attrs.put("remotePort", remote.getPort());
                } catch (Exception ignored) {}

                Response resp;
                try {
                    resp = router.handle(req);
                } catch (Exception e) {
                    System.err.println("Handler error: " + e.getMessage());
                    e.printStackTrace();
                    resp = ErrorPages.response(cfg, 500);
                }

                // Metrics: response status
                metrics.onResponseStatus(resp.status);

                if (!ctx.enqueue(resp.toByteBuffers())) {
                    // client too slow; protect server
                    closeConnection(ch);
                    return;
                }

                // keep-alive logic (HTTP/1.1 default keep-alive unless Connection: close)
                boolean close = "close".equalsIgnoreCase(req.headers.getOrDefault("connection", ""));
                if (close || resp.closeAfterWrite) ctx.closeAfterWrite = true;

                if (key.isValid()) key.interestOps(SelectionKey.OP_WRITE);

                if (ctx.readBuffer.remaining() == 0) break;
            }

            ctx.readBuffer.compact();

        } catch (IOException e) {
            closeConnection(ch);
        }
    }

    private void onWrite(SelectionKey key) {
        SocketChannel ch = (SocketChannel) key.channel();
        ConnectionContext ctx = contexts.get(ch);
        if (ctx == null) {
            closeQuietly(ch);
            return;
        }

        ctx.lastActivityMs = System.currentTimeMillis();

        try {
            while (!ctx.writeQueue.isEmpty()) {
                ConnectionContext.OutBuf ob = ctx.writeQueue.peek();

                int before = ob.buf.remaining();
                ch.write(ob.buf);
                int after = ob.buf.remaining();

                // Metrics: bytes out
                metrics.onBytesOut(before - after);

                if (ob.buf.hasRemaining()) break; // socket backpressure

                ctx.writeQueue.poll();
                ctx.pendingWriteBytes -= ob.bytes;
            }

            if (ctx.writeQueue.isEmpty()) {
                if (ctx.closeAfterWrite) {
                    closeConnection(ch);
                    return;
                }
                if (key.isValid()) key.interestOps(SelectionKey.OP_READ);
            }
        } catch (IOException e) {
            closeConnection(ch);
        }
    }


    static final class ConnectionContext {
        final SocketChannel ch;
        final ByteBuffer readBuffer = ByteBuffer.allocateDirect(64 * 1024);
        final Deque<OutBuf> writeQueue = new ArrayDeque<>();
        final HttpParser parser = new HttpParser();

        long lastActivityMs = System.currentTimeMillis();
        boolean closeAfterWrite = false;

        int pendingWriteBytes = 0;

        static final class OutBuf {
            final ByteBuffer buf;
            final int bytes;
            OutBuf(ByteBuffer buf) {
                this.buf = buf;
                this.bytes = buf.remaining();
            }
        }

        ConnectionContext(SocketChannel ch) {
            this.ch = ch;
        }

        boolean enqueue(List<ByteBuffer> bufs) {
            int add = 0;
            for (ByteBuffer b : bufs) add += b.remaining();

            if (pendingWriteBytes + add > MAX_PENDING_WRITE_BYTES) {
                return false;
            }

            for (ByteBuffer b : bufs) {
                OutBuf ob = new OutBuf(b);
                writeQueue.add(ob);
                pendingWriteBytes += ob.bytes;
            }
            return true;
        }

        void clearWriteQueue() {
            writeQueue.clear();
            pendingWriteBytes = 0;
        }
    }
}
