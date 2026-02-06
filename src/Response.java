import java.nio.charset.StandardCharsets;
import java.util.*;

public class Response {
    public int status;
    public String reason;
    public Map<String, String> headers= new LinkedHashMap<>();
    public byte[] body = new byte[0];
    public boolean closeAfterWrite=false;
    
    public static Response text(int status, String reason, String contentType, String text){
        Response r= new Response();
        r.status=status;
        r.reason=reason;
        r.body=text.getBytes(StandardCharsets.UTF_8);
        r.headers.put("Content-Type",contentType+"; charset=utf-8");
        return r;
    }
   
}
