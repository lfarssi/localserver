import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class HttpParser {

    public enum Status { OK, NEED_MORE, ERROR }
    public enum Stage { START, HEADERS, BODY, CHUNK_SIZE, CHUNK_DATA, CHUNK_CRLF, CHUNK_TRAILERS }
    public static final String ATTR_IGNORE_BODY = "ignoreBody";
    public static final String ATTR_IGNORE_BODY_LEN = "ignoreBodyLength";

    public interface BodyConsumer {
        void accept(byte[] buf, int off, int len) throws IOException;
        void close() throws IOException;
        default void abort() {}
    }

    public interface BodyConsumerFactory {
        BodyConsumer create(HttpModels.Request req, long contentLength, boolean chunked) throws IOException;
    }

    public static final class ParseResult {
        public final Status status;
        public final HttpModels.Request request;
        public final int errorCode; // 0 if not error; else 400/411/413/500

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
    private long contentLength = 0;
    private long bodyRead = 0;
    private byte[] fixedBodyBuf = null;

    // chunked state
    private int chunkRemaining = 0;
    private boolean chunked = false;

    // body accumulation (yes, still buffers; streaming comes later)
    private final ByteArrayOutput bodyAcc = new ByteArrayOutput(16 * 1024);
    private long bodyLimit = 0;

    private final BodyConsumerFactory bodyConsumerFactory;
    private BodyConsumer bodyConsumer;
    private final byte[] streamBuf = new byte[64 * 1024];

    public HttpParser() {
        this(null);
    }

    public HttpParser(BodyConsumerFactory bodyConsumerFactory) {
        this.bodyConsumerFactory = bodyConsumerFactory;
    }

    public ParseResult parse(ByteBuffer in, long bodyLimitBytes) {

        try {
            this.bodyLimit = bodyLimitBytes;

            if (stage == Stage.START) {
                current = new HttpModels.Request();
                contentLength = 0;
                bodyRead = 0;
                fixedBodyBuf = null;
                bodyConsumer = null;
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
                boolean ignoreBody = isBodyIgnorable(current.method);
                String te = current.headers.getOrDefault("transfer-encoding", "");
                if (ignoreBody && containsChunked(te)) {
                    current.attrs.put(ATTR_IGNORE_BODY, Boolean.TRUE);
                    HttpModels.Request done = current;
                    resetToStart();
                    return ParseResult.ok(done);
                }
                if (containsChunked(te)) {
                    chunked = true;
                    stage = Stage.CHUNK_SIZE;
                    stageStartMs = System.currentTimeMillis();
                    // HTTP/1.1: ignore Content-Length if chunked present
                    contentLength = 0;
                    bodyRead = 0;
                    bodyConsumer = maybeCreateBodyConsumer(current, -1, true);
                } else {
                    chunked = false;
                    String cl = current.headers.get("content-length");

                    // If no body delimiter for POST/PUT, require length (411)
                    if (cl == null && isLengthRequired(current.method)) {
                        resetToStart();
                        return ParseResult.error(411);
                    }

                    contentLength = (cl == null) ? 0 : parseLongSafe(cl, -1);
                    if (contentLength < 0) {
                        resetToStart();
                        return ParseResult.error(400);
                    }
                    if (ignoreBody && contentLength > 0) {
                        current.attrs.put(ATTR_IGNORE_BODY, Boolean.TRUE);
                        current.attrs.put(ATTR_IGNORE_BODY_LEN, contentLength);
                        HttpModels.Request done = current;
                        resetToStart();
                        return ParseResult.ok(done);
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
                    bodyRead = 0;
                    bodyConsumer = maybeCreateBodyConsumer(current, contentLength, false);
                    if (bodyConsumer == null) {
                        if (contentLength > Integer.MAX_VALUE) {
                            resetToStart();
                            return ParseResult.error(413);
                        }
                        fixedBodyBuf = new byte[(int) contentLength];
                    }
                    stage = Stage.BODY;
                    stageStartMs = System.currentTimeMillis();
                }
            }

            // -------- FIXED BODY (Content-Length) --------
            if (stage == Stage.BODY) {
                long remaining = contentLength - bodyRead;
                if (remaining > 0 && in.remaining() > 0) {
                    int toRead = (int) Math.min((long) in.remaining(), remaining);
                    if (bodyConsumer != null) {
                        consumeToBodyConsumer(in, toRead);
                    } else {
                        in.get(fixedBodyBuf, (int) bodyRead, toRead);
                    }
                    bodyRead += toRead;
                }
                if (bodyRead < contentLength) return ParseResult.needMore();

                if (bodyConsumer != null) {
                    bodyConsumer.close();
                    bodyConsumer = null;
                    current.body = new byte[0];
                } else {
                    current.body = fixedBodyBuf;
                }

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

                    long size;
                    try {
                        size = Long.parseLong(hex, 16);
                    } catch (Exception e) {
                        resetToStart();
                        return ParseResult.error(400);
                    }

                    if (size < 0 || size > Integer.MAX_VALUE) {

                        resetToStart();
                        return ParseResult.error(413);
                    }

                    chunkRemaining = (int) size;

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
                    if (chunkRemaining > 0) {
                        if (in.remaining() == 0) return ParseResult.needMore();
                        int toRead = Math.min(chunkRemaining, in.remaining());
                        if (bodyRead + toRead > bodyLimit) {
                            resetToStart();

                            return ParseResult.error(413);
                        }
                        if (bodyConsumer != null) {
                            consumeToBodyConsumer(in, toRead);
                        } else {
                            if ((long) bodyAcc.size() + toRead > Integer.MAX_VALUE) {
                                resetToStart();

                                return ParseResult.error(413);
                            }
                            byte[] data = new byte[toRead];
                            in.get(data);
                            bodyAcc.write(data);
                        }
                        bodyRead += toRead;
                        chunkRemaining -= toRead;
                        if (chunkRemaining > 0) return ParseResult.needMore();
                    }
                    stage = Stage.CHUNK_CRLF;
                    stageStartMs = System.currentTimeMillis();
                    continue;
                }

                if (stage == Stage.CHUNK_CRLF) {
                    if (in.remaining() < 2) return ParseResult.needMore();
                    byte c1 = in.get();
                    byte c2 = in.get();
                    if (c1 != '\r' || c2 != '\n') {
                        resetToStart();
                        return ParseResult.error(400);
                    }
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
                        if (bodyConsumer != null) {
                            bodyConsumer.close();
                            bodyConsumer = null;
                            current.body = new byte[0];
                        } else {
                            current.body = bodyAcc.toByteArray();
                        }
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

        } catch (IOException e) {
            resetToStart();
            return ParseResult.error(500);
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
        bodyRead = 0;
        fixedBodyBuf = null;
        if (bodyConsumer != null) {
            try {
                bodyConsumer.abort();
            } catch (Exception ignored) {
            }
        }
        bodyConsumer = null;
        chunkRemaining = 0;
        chunked = false;
        bodyAcc.reset();
    }

    private BodyConsumer maybeCreateBodyConsumer(HttpModels.Request req, long contentLength, boolean chunked)
            throws IOException {
        if (bodyConsumerFactory == null) return null;
        return bodyConsumerFactory.create(req, contentLength, chunked);
    }

    private void consumeToBodyConsumer(ByteBuffer in, int bytes) throws IOException {
        int remaining = bytes;
        while (remaining > 0) {
            int n = Math.min(remaining, streamBuf.length);
            in.get(streamBuf, 0, n);
            bodyConsumer.accept(streamBuf, 0, n);
            remaining -= n;
        }
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

    private static boolean isLengthRequired(String method) {
        if (method == null) return false;
        return "POST".equals(method) || "PUT".equals(method);
    }

    private static boolean isBodyIgnorable(String method) {
        if (method == null) return false;
        return "GET".equals(method) || "HEAD".equals(method);
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

    private static long parseLongSafe(String s, long def) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return def; }
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
