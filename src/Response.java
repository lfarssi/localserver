import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Response {
    public int status;
    public String reason;
    public byte[] body = new byte[0];
    public boolean closeAfterWrite = false;
    public boolean chunked = false;

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

        String te = getHeader("Transfer-Encoding");
        boolean teChunked = te != null && "chunked".equalsIgnoreCase(te.trim());

        if (chunked) {
            headers.removeIf(e -> e.getKey().equalsIgnoreCase("Content-Length"));
            if (!teChunked) setHeader("Transfer-Encoding", "chunked");
            b = chunkedEncode(b);
        } else if (teChunked) {
            headers.removeIf(e -> e.getKey().equalsIgnoreCase("Content-Length"));
        } else {
            if (getHeader("Content-Length") == null) setHeader("Content-Length", String.valueOf(b.length));
        }
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

    private static byte[] chunkedEncode(byte[] b) {
        if (b == null || b.length == 0) {
            return "0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, b.length / 8));
        int i = 0;
        while (i < b.length) {
            int n = Math.min(8192, b.length - i);
            String hex = Integer.toHexString(n);
            try {
                out.write(hex.getBytes(StandardCharsets.ISO_8859_1));
                out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.write(b, i, n);
                out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
            } catch (IOException e) {
                break;
            }
            i += n;
        }
        try {
            out.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        } catch (IOException ignored) {}
        return out.toByteArray();
    }
}
