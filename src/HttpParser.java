import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class HttpParser {

    enum Status {
        OK, NEED_MORE, ERROR
    }

    enum Stage {
        START, HEADERS, BODY, CHUNKED
    }

    Stage stage = Stage.START;
    long stageStartMs = System.currentTimeMillis();

    private HttpModels.Request current;
    private int contentLength = 0;

    // for chunked
    private int chunkRemaining = -1;
    private final ByteArrayOutput bodyAcc = new ByteArrayOutput(1024 * 16);

    public static final class ParseResult {
        public final Status status;
        public final HttpModels.Request request;

        ParseResult(Status status, HttpModels.Request request) {
            this.status = status;
            this.request = request;
        }

        static ParseResult needMore() {
            return new ParseResult(Status.NEED_MORE, null);
        }

        static ParseResult error() {
            return new ParseResult(Status.ERROR, null);
        }

        static ParseResult ok(HttpModels.Request r) {
            return new ParseResult(Status.OK, r);
        }
    }

    public ParseResult parse(ByteBuffer in, int bodyLimitBytes) {
        try {
            if (stage == Stage.START) {
                current = new HttpModels.Request();
                stage = Stage.HEADERS;
                stageStartMs = System.currentTimeMillis();
                contentLength = 0;
                chunkRemaining = -1;
                bodyAcc.reset();
            }

            if (stage == Stage.HEADERS) {
                String headersBlock = readUntilDoubleCRLF(in);
                if (headersBlock == null)
                    return ParseResult.needMore();

                if (!parseStartLineAndHeaders(headersBlock, current))
                    return ParseResult.error();

                // Determine body mode
                String te = current.headers.getOrDefault("transfer-encoding", "");
                if (te.toLowerCase(Locale.ROOT).contains("chunked")) {
                    stage = Stage.CHUNKED;
                    stageStartMs = System.currentTimeMillis();
                } else {
                    String cl = current.headers.get("content-length");
                    contentLength = (cl == null) ? 0 : parseIntSafe(cl, -1);
                    if (contentLength < 0)
                        return ParseResult.error();
                    if (contentLength > bodyLimitBytes)
                        return ParseResult.error(); // map to 413 later if you prefer
                    if (contentLength == 0) {
                        HttpModels.Request done = current;
                        stage = Stage.START;
                        return ParseResult.ok(done);
                    }
                    stage = Stage.BODY;
                    stageStartMs = System.currentTimeMillis();
                }
            }

            if (stage == Stage.BODY) {
                if (in.remaining() < contentLength)
                    return ParseResult.needMore();
                byte[] body = new byte[contentLength];
                in.get(body);
                current.body = body;

                HttpModels.Request done = current;
                stage = Stage.START;
                return ParseResult.ok(done);
            }

            if (stage == Stage.CHUNKED) {
                // chunked format: <hex>\r\n<data>\r\n ... 0\r\n\r\n
                while (true) {
                    if (chunkRemaining < 0) {
                        String line = readLineCRLF(in);
                        if (line == null)
                            return ParseResult.needMore();
                        int semi = line.indexOf(';');
                        String hex = (semi >= 0) ? line.substring(0, semi) : line;
                        chunkRemaining = Integer.parseInt(hex.trim(), 16);
                        if (chunkRemaining == 0) {
                            // read trailing CRLF line after 0 chunk and optional trailers (minimal: require
                            // \r\n)
                            String end = readUntilDoubleCRLF(in);
                            if (end == null)
                                return ParseResult.needMore();
                            current.body = bodyAcc.toByteArray();
                            HttpModels.Request done = current;
                            stage = Stage.START;
                            return ParseResult.ok(done);
                        }
                    }

                    if (in.remaining() < chunkRemaining + 2)
                        return ParseResult.needMore(); // +CRLF
                    byte[] data = new byte[chunkRemaining];
                    in.get(data);
                    bodyAcc.write(data);

                    // consume CRLF
                    byte c1 = in.get();
                    byte c2 = in.get();
                    if (c1 != '\r' || c2 != '\n')
                        return ParseResult.error();

                    chunkRemaining = -1;
                }
            }

            return ParseResult.needMore();
        } catch (Exception e) {
            return ParseResult.error();
        }
    }

    private static boolean parseStartLineAndHeaders(String block, HttpModels.Request req) {
        String[] lines = block.split("\r\n");
        if (lines.length < 1)
            return false;

        String[] parts = lines[0].split(" ");
        if (parts.length != 3)
            return false;

        req.method = parts[0].trim();
        req.target = parts[1].trim();
        req.version = parts[2].trim();

        // Basic HTTP/1.1 requirement
        if (!req.version.equals("HTTP/1.1"))
            return false;

        // headers
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty())
                continue;
            int idx = line.indexOf(':');
            if (idx <= 0)
                return false;
            String k = line.substring(0, idx).trim().toLowerCase(Locale.ROOT);
            String v = line.substring(idx + 1).trim();
            req.headers.put(k, v);
        }

        // parse path/query
        String t = req.target;
        int q = t.indexOf('?');
        req.path = (q >= 0) ? t.substring(0, q) : t;
        req.query = (q >= 0) ? t.substring(q + 1) : "";
        req.path = utils.PathUtil.normalizeUrlPath(req.path);

        return true;
    }

    private static int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    // Reads until \r\n\r\n. Returns the text excluding final delimiter. Null if
    // incomplete.
    private static String readUntilDoubleCRLF(ByteBuffer in) {
        int startPos = in.position();
        for (int i = startPos; i + 3 < in.limit(); i++) {
            if (in.get(i) == '\r' && in.get(i + 1) == '\n' && in.get(i + 2) == '\r' && in.get(i + 3) == '\n') {
                int len = i - startPos;
                byte[] bytes = new byte[len];
                in.get(bytes);
                in.position(in.position() + 4); // skip \r\n\r\n
                return new String(bytes, StandardCharsets.ISO_8859_1);
            }
        }
        return null;
    }

    // Reads one line ending with CRLF, returns string without CRLF. Null if
    // incomplete.
    private static String readLineCRLF(ByteBuffer in) {
        int startPos = in.position();
        for (int i = startPos; i + 1 < in.limit(); i++) {
            if (in.get(i) == '\r' && in.get(i + 1) == '\n') {
                int len = i - startPos;
                byte[] bytes = new byte[len];
                in.get(bytes);
                in.position(in.position() + 2); // skip CRLF
                return new String(bytes, StandardCharsets.ISO_8859_1);
            }
        }
        return null;
    }

    // Small helper to avoid repeated allocations
    static final class ByteArrayOutput {
        private byte[] buf;
        private int size;

        ByteArrayOutput(int cap) {
            buf = new byte[cap];
        }

        void reset() {
            size = 0;
        }

        void write(byte[] b) {
            ensure(size + b.length);
            System.arraycopy(b, 0, buf, size, b.length);
            size += b.length;
        }

        void ensure(int cap) {
            if (cap <= buf.length)
                return;
            int n = buf.length;
            while (n < cap)
                n *= 2;
            buf = Arrays.copyOf(buf, n);
        }

        byte[] toByteArray() {
            return Arrays.copyOf(buf, size);
        }
    }
}
