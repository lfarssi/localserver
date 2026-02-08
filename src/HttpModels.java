import java.util.*;

public class HttpModels {
    public static final class Request {
        public String method;
        public String target; // raw target
        public String path; // normalized path
        public String query; // raw query (optional)
        public String version; // HTTP/1.1

        public Map<String, String> headers = new HashMap<>();
        public byte[] body = new byte[0];
    }
}
