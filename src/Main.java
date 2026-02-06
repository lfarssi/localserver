import java.nio.file.Path;

public class Main {
    public static void main(String[] args) {
        try {
            String configPath = (args.length > 0) ? args[0] : "config.json";
            // ConfigLoader.config cfg = ConfigLoader.load(Path.of(configPath));
            
        } catch (Exception ex) {
            System.err.println("Startup failed: " + ex.getMessage());
            ex.printStackTrace();
            System.exit(1);
        }
    }
}
