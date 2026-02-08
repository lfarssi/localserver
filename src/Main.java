import java.nio.file.Path;

public class Main {
    public static void main(String[] args) {
        try {
            String configPath = (args.length > 0) ? args[0] : "config.json";
            ConfigLoader.Config cfg = ConfigLoader.load(Path.of(configPath));

            Router router = new Router(cfg);
            Server server = new Server(cfg, router);
            server.run(); // blocks, single-thread loop
        } catch (Exception e) {
            // fail-fast at startup with clear message (but never crash at runtime loop)
            System.err.println("Startup failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
