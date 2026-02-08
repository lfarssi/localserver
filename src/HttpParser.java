import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class HttpParser {

    public enum Status { OK, NEED_MORE, ERROR }
    public enum Stage { START, HEADERS, BODY, CHUNK_SIZE, CHUNK_DATA, CHUNK_TRAILERS }

    public static final class ParseResult {
        public final Status status;
        public final HttpModels.Request request;
        public final int errorCode; // 0 if not error; else 400/413

        private ParseResult(Status status, HttpModels.Request request, int errorCode) {
            this.status = status;
            this.request = request;
            this.errorCode = errorCode;
        }

        public static ParseResult needMore() { return new ParseResult(Status.NEED_MORE, null, 0); }
        public static ParseResult ok(HttpModels.Request r) { return new ParseResult(Status.OK, r, 0); }
        public static ParseResult error(int code) { return new ParseResult(Status.ERROR, null, code); }
    }

    public Stage stage = Stage.START;
    public long stageStartMs = System.currentTimeMillis();

    private HttpModels.Request current;
    private int contentLength = 0;

    // chunked state
    private int chunkRemaining = 0;
    private boolean chunked = false;

    // body accumulation (yes, still buffers; streaming comes later)
    private final ByteArrayOutput bodyAcc = new ByteArrayOutput(16 * 1024);
    private int bodyLimit = 0;

    public ParseResult parse(ByteBuffer in, int bodyLimitBytes) {
        try {
            this.bodyLimit = bodyLimitBytes;

            if (stage == Stage.START) {
                current = new HttpModels.Request();
                contentLength = 0;
                chunkRemaining = 0;
                chunked = false;
                bodyAcc.reset();
                stage = Stage.HEADERS;
                stageStartMs = System.currentTimeMillis();
            }

            // -------- HEADERS --------
            if (stage == Stage.HEADERS) {
                String headersBlock = readUntilDoubleCRLF(in);
                if (headersBlock == null) return ParseResult.needMore();

                if (!parseStartLineAndHeaders(headersBlock, current)) {
                    resetToStart();
                    return ParseResult.error(400);
                }

                // Decide body mode
                String te = current.headers.getOrDefault("transfer-encoding", "");
                if (containsChunked(te)) {
                    chunked = true;
                    stage = Stage.CHUNK_SIZE;
                    stageStartMs = System.currentTimeMillis();
                    // HTTP/1.1: ignore Content-Length if chunked present
                    contentLength = 0;
                } else {
                    chunked = false;
                    String cl = current.headers.get("content-length");
                    contentLength = (cl == null) ? 0 : parseIntSafe(cl, -1);
                    if (contentLength < 0) {
                        resetToStart();
                        return ParseResult.error(400);
                    }
                    if (contentLength > bodyLimit) {
                        resetToStart();
                        return ParseResult.error(413);
                    }
                    if (contentLength == 0) {
                        HttpModels.Request done = current;
                        resetToStart();
                        return ParseResult.ok(done);
                    }
                    stage = Stage.BODY;
                    stageStartMs = System.currentTimeMillis();
                }
            }

            // -------- FIXED BODY (Content-Length) --------
            if (stage == Stage.BODY) {
                if (in.remaining() < contentLength) return ParseResult.needMore();
                byte[] body = new byte[contentLength];
                in.get(body);
                current.body = body;

                HttpModels.Request done = current;
                resetToStart();
                return ParseResult.ok(done);
            }

            // -------- CHUNKED --------
            while (chunked) {

                if (stage == Stage.CHUNK_SIZE) {
                    String line = readLineCRLF(in);
                    if (line == null) return ParseResult.needMore();

                    // chunk-size can include extensions: "4;ext=1"
                    int semi = line.indexOf(';');
                    String hex = (semi >= 0) ? line.substring(0, semi) : line;
                    hex = hex.trim();

                    if (hex.isEmpty()) {
                        resetToStart();
                        return ParseResult.error(400);
                    }

                    int size;
                    try {
                        size = Integer.parseInt(hex, 16);
                    } catch (Exception e) {
                        resetToStart();
                        return ParseResult.error(400);
                    }

                    chunkRemaining = size;

                    if (chunkRemaining == 0) {
                        // Next: trailers terminated by blank line
                        stage = Stage.CHUNK_TRAILERS;
                        stageStartMs = System.currentTimeMillis();
                        continue;
                    } else {
                        stage = Stage.CHUNK_DATA;
                        stageStartMs = System.currentTimeMillis();
                        continue;
                    }
                }

                if (stage == Stage.CHUNK_DATA) {
                    // Need chunkRemaining bytes + CRLF after data
                    if (in.remaining() < chunkRemaining + 2) return ParseResult.needMore();

                    // Enforce body limit while accumulating
                    if (bodyAcc.size() + chunkRemaining > bodyLimit) {
                        resetToStart();
                        return ParseResult.error(413);
                    }

                    byte[] data = new byte[chunkRemaining];
                    in.get(data);
                    bodyAcc.write(data);

                    byte c1 = in.get();
                    byte c2 = in.get();
                    if (c1 != '\r' || c2 != '\n') {
                        resetToStart();
                        return ParseResult.error(400);
                    }

                    // Next chunk size line
                    stage = Stage.CHUNK_SIZE;
                    stageStartMs = System.currentTimeMillis();
                    continue;
                }

                if (stage == Stage.CHUNK_TRAILERS) {
                    // Trailer section: 0 or more header lines, terminated by empty line (CRLF)
                    // We ignore contents but must consume correctly.
                    String line = readLineCRLF(in);
                    if (line == null) return ParseResult.needMore();

                    if (line.isEmpty()) {
                        // Done: finalize request
                        current.body = bodyAcc.toByteArray();
                        HttpModels.Request done = current;
                        resetToStart();
                        return ParseResult.ok(done);
                    }

                    // Non-empty trailer line -> ignore and continue
                    continue;
                }

                // Should never hit here
                break;
            }

            return ParseResult.needMore();

        } catch (Exception e) {
            resetToStart();
            return ParseResult.error(400);
        }
    }

    private void resetToStart() {
        stage = Stage.START;
        stageStartMs = System.currentTimeMillis();
        current = null;
        contentLength = 0;
        chunkRemaining = 0;
        chunked = false;
        bodyAcc.reset();
    }

    private static boolean containsChunked(String te) {
        if (te == null) return false;
        String x = te.toLowerCase(Locale.ROOT);
        // handle "gzip, chunked" etc
        for (String part : x.split(",")) {
            if (part.trim().equals("chunked")) return true;
        }
        return false;
    }

    private static boolean parseStartLineAndHeaders(String block, HttpModels.Request req) {
        String[] lines = block.split("\r\n");
        if (lines.length < 1) return false;

        String[] parts = lines[0].split(" ");
        if (parts.length != 3) return false;

        req.method = parts[0].trim();
        req.target = parts[1].trim();
        req.version = parts[2].trim();

        if (!"HTTP/1.1".equals(req.version)) return false;

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) continue;
            int idx = line.indexOf(':');
            if (idx <= 0) return false;
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
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    // Reads until \r\n\r\n. Returns text excluding delimiter. Null if incomplete.
    private static String readUntilDoubleCRLF(ByteBuffer in) {
        int startPos = in.position();
        for (int i = startPos; i + 3 < in.limit(); i++) {
            if (in.get(i) == '\r' && in.get(i + 1) == '\n' && in.get(i + 2) == '\r' && in.get(i + 3) == '\n') {
                int len = i - startPos;
                byte[] bytes = new byte[len];
                in.get(bytes);
                in.position(in.position() + 4);
                return new String(bytes, StandardCharsets.ISO_8859_1);
            }
        }
        return null;
    }

    // Reads one line ending with CRLF. Returns line without CRLF. Null if incomplete.
    private static String readLineCRLF(ByteBuffer in) {
        int startPos = in.position();
        for (int i = startPos; i + 1 < in.limit(); i++) {
            if (in.get(i) == '\r' && in.get(i + 1) == '\n') {
                int len = i - startPos;
                byte[] bytes = new byte[len];
                in.get(bytes);
                in.position(in.position() + 2);
                return new String(bytes, StandardCharsets.ISO_8859_1);
            }
        }
        return null;
    }

    // small accumulator
    static final class ByteArrayOutput {
        private byte[] buf;
        private int size;

        ByteArrayOutput(int cap) { buf = new byte[cap]; }

        void reset() { size = 0; }

        int size() { return size; }

        void write(byte[] b) {
            ensure(size + b.length);
            System.arraycopy(b, 0, buf, size, b.length);
            size += b.length;
        }

        void ensure(int cap) {
            if (cap <= buf.length) return;
            int n = buf.length;
            while (n < cap) n *= 2;
            buf = Arrays.copyOf(buf, n);
        }

        byte[] toByteArray() { return Arrays.copyOf(buf, size); }
    }
}
