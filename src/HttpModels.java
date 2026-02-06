import java.util.*;

public class HttpModels {
    public static final class Request{
        public String method;
        public String target;
        public String path;
        public String query;
        public String version;

        public Map<String, String> headers=new HashMap<>();
        public byte[] body= new byte[0];
    }
}
 