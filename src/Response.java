import java.nio.ByteBuffer;
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
   public List<ByteBuffer> toByteBuffers(){
    if(!headers.containsKey("Content-Length")){
        headers.put("Content-Length",String.valueOf(body.length));
    }
    if(!headers.containsKey("Connection")){
        headers.put("Connection",closeAfterWrite?"close":"keep-alive");
    }
    if(!headers.containsKey("Server")){
        headers.put("Server","LocalServer/1.0");
    }
    StringBuilder sb=new StringBuilder();
    sb.append("HTTP/1.1").append(status).append(" ").append(reason).append("\r\n");
    for(var e:headers.entrySet()){
        sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
    }
    sb.append("\r\n");
    byte[] head=sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    return List.of(ByteBuffer.wrap(head),ByteBuffer.wrap(body));
   }
}
