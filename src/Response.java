import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Response {
    public int status;
    public String reason;
    public byte[] body = new byte[0];
    public boolean closeAfterWrite = false;

    // HTTP allows repeated headers (Set-Cookie). A Map is wrong.
    private final List<Map.Entry<String, String>> headers = new ArrayList<>();

    public static Response text(int status, String reason, String contentType, String text) {
        Response r = new Response();
        r.status = status;
        r.reason = reason;
        r.body = text.getBytes(StandardCharsets.UTF_8);
        r.setHeader("Content-Type", contentType + "; charset=utf-8");
        return r;
    }

    /** Replace any existing header with the same name (case-insensitive). */
    public void setHeader(String key, String value) {
        headers.removeIf(e -> e.getKey().equalsIgnoreCase(key));
        headers.add(Map.entry(key, value));
    }

    /** Add a header line even if the same name already exists. */
    public void addHeader(String key, String value) {
        headers.add(Map.entry(key, value));
    }

    public String getHeader(String key) {
        for (var e : headers) {
            if (e.getKey().equalsIgnoreCase(key)) return e.getValue();
        }
        return null;
    }

    public List<ByteBuffer> toByteBuffers() {
        byte[] b = (body == null) ? new byte[0] : body;
        String r = (reason == null || reason.isBlank()) ? "OK" : reason;

        if (getHeader("Content-Length") == null) setHeader("Content-Length", String.valueOf(b.length));
        if (getHeader("Connection") == null) setHeader("Connection", closeAfterWrite ? "close" : "keep-alive");
        if (getHeader("Server") == null) setHeader("Server", "LocalServer/1.0");

        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(status).append(" ").append(r).append("\r\n");
        for (var e : headers) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        sb.append("\r\n");

        byte[] head = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        return List.of(ByteBuffer.wrap(head), ByteBuffer.wrap(b));
    }
}
