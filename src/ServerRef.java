public final class ServerRef {
    private static ServerRef INSTANCE;
    private final Server server;

    private ServerRef(Server server) { this.server = server; }

    public static void init(Server server) { INSTANCE = new ServerRef(server); }
    public static ServerRef get() { return INSTANCE; }

    public Metrics.Snapshot metricsSnapshot() {
        return server.metricsSnapshot();
    }
}
