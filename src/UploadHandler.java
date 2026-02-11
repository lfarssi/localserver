import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

public class UploadHandler {
    private static final SecureRandom RNG = new SecureRandom();
    static final String ATTR_BODY_FILE = "bodyFile";
    static final String ATTR_BODY_FILE_SIZE = "bodyFileSize";

    public static Response handle(ConfigLoader.Config cfg, ConfigLoader.Route route, HttpModels.Request req) {
        try {
            if (req.body == null) req.body = new byte[0];

            Path bodyFile = null;
            Object bf = req.attrs.get(ATTR_BODY_FILE);
            if (bf instanceof Path) bodyFile = (Path) bf;

            long bodySize = req.body.length;
            if (bodyFile != null) {
                Object bs = req.attrs.get(ATTR_BODY_FILE_SIZE);
                if (bs instanceof Number) bodySize = ((Number) bs).longValue();
                else {
                    try { bodySize = Files.size(bodyFile); } catch (Exception ignored) {}
                }
            }

            String ct = req.headers.getOrDefault("content-type", "");
            Path uploadRoot = Path.of(route.root).toAbsolutePath().normalize();
            Files.createDirectories(uploadRoot);

            if ("GET".equals(req.method)) {
                return listUploads(uploadRoot);
            }

            bodySize = (bodyFile != null) ? bodySize : req.body.length;

            // Reject empty uploads explicitly
            if (bodySize == 0) {
                return ErrorPages.response(cfg, 400);
            }

            // Enforce body limit (parser already does; but double-check here too)
            if (bodySize > cfg.clientBodyLimitBytes) {

                return ErrorPages.response(cfg, 413);
            }

            if (ct.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
                if (bodyFile != null) {
                    return Response.text(400, "Bad Request", "text/plain",
                            "Multipart streaming is not supported for large uploads.\n");
                }
                String boundary = extractBoundary(ct);
                if (boundary == null || boundary.isEmpty()) return ErrorPages.response(cfg, 400);

                List<SavedFile> saved = saveMultipart(req.body, boundary, uploadRoot);
                if (saved.isEmpty()) {
                    return Response.text(400, "Bad Request", "text/plain",
                            "No file parts found in multipart body.\n");
                }

                return Response.text(200, "OK", "text/plain", renderSaved(saved));
            } else {
                // Raw upload
                Path out;
                if (bodyFile != null) {
                    out = bodyFile;
                } else {
                    String name = "upload_" + Instant.now().toEpochMilli() + "_" + (RNG.nextInt() & 0x7fffffff) + ".bin";
                    out = safeResolveFile(uploadRoot, name);
                    Files.write(out, req.body, StandardOpenOption.CREATE_NEW);
                }

                return Response.text(200, "OK", "text/plain",
                        "Saved raw upload: " + out.getFileName() + " (" + bodySize + " bytes)\n");
            }

        } catch (SecurityException se) {
            return ErrorPages.response(cfg, 403);
        } catch (Exception e) {
            e.printStackTrace();
            return ErrorPages.response(cfg, 500);
        }
    }

    private static Response listUploads(Path uploadRoot) {
        try {
            if (!Files.exists(uploadRoot)) {
                return Response.text(200, "OK", "text/html", renderUploadsHtml(List.of(), 0));
            }
            List<Path> files = new ArrayList<>();
            try (var stream = Files.list(uploadRoot)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> !p.getFileName().toString().endsWith(".part"))
                        .forEach(files::add);
            }
            files.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
            if (files.isEmpty()) {
                return Response.text(200, "OK", "text/html", renderUploadsHtml(List.of(), 0));
            }
            return Response.text(200, "OK", "text/html", renderUploadsHtml(files, -1));
        } catch (Exception e) {
            return Response.text(500, "Internal Server Error", "text/html",
                    "<html><body><h1>Failed to list uploads.</h1></body></html>");
        }
    }

    private static String renderUploadsHtml(List<Path> files, long totalBytesHint) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\">")
                .append("<title>Uploads</title>")
                .append("<style>")
                .append("body{font-family:Arial, sans-serif; margin:32px; color:#222;}")
                .append("h1{margin-bottom:8px;} .meta{color:#666; margin-bottom:16px;}")
                .append("table{border-collapse:collapse; width:100%; max-width:900px;}")
                .append("th,td{padding:8px 10px; border-bottom:1px solid #eee; text-align:left;}")
                .append("tr:hover{background:#fafafa;} .size{white-space:nowrap;}")
                .append("</style></head><body>");
        sb.append("<h1>Uploaded Files</h1>");
        if (files.isEmpty()) {
            sb.append("<div class=\"meta\">No uploaded files.</div>");
        } else {
            long totalBytes = (totalBytesHint >= 0) ? totalBytesHint : 0;
            sb.append("<table><thead><tr><th>File</th><th class=\"size\">Size (bytes)</th></tr></thead><tbody>");
            for (Path p : files) {
                String name = p.getFileName().toString();
                long size = 0;
                try { size = Files.size(p); } catch (Exception ignored) {}
                totalBytes += size;
                sb.append("<tr><td>").append(escapeHtml(name)).append("</td>")
                        .append("<td class=\"size\">").append(size).append("</td></tr>");
            }
            sb.append("</tbody></table>");
            sb.append("<div class=\"meta\">Count: ").append(files.size())
                    .append(" &middot; Total size: ").append(totalBytes).append(" bytes</div>");
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static String extractBoundary(String contentType) {
        // content-type: multipart/form-data; boundary=----WebKitFormBoundary...
        for (String p : splitHeaderParams(contentType)) {
            String t = p.trim();
            if (t.toLowerCase(Locale.ROOT).startsWith("boundary=")) {
                String b = t.substring("boundary=".length()).trim();
                b = stripQuotes(b);
                // Be forgiving if someone includes the leading "--"
                if (b.startsWith("--")) b = b.substring(2);
                return b;
            }
        }
        return null;
    }

    private static List<SavedFile> saveMultipart(byte[] body, String boundary, Path uploadRoot) throws Exception {
        // We parse multipart using byte-level boundary splitting.
        // boundary format in body is: "--" + boundary
        byte[] boundaryBytes = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] boundaryEndBytes = ("--" + boundary + "--").getBytes(StandardCharsets.ISO_8859_1);

        List<SavedFile> saved = new ArrayList<>();

        int i = 0;

        // Must start with boundary
        int first = indexOf(body, boundaryBytes, 0);
        if (first < 0) return saved;
        i = first;

        while (true) {
            // Check end boundary
            if (startsWith(body, i, boundaryEndBytes)) break;

            // Consume boundary line: "--boundary\r\n"
            i += boundaryBytes.length;
            if (!consumeCRLF(body, i)) {
                // could be "--" end marker or malformed
                if (startsWith(body, i, "--".getBytes(StandardCharsets.ISO_8859_1))) break;
                throw new IllegalArgumentException("Malformed multipart boundary");
            }
            i += 2; // \r\n

            // Read part headers until \r\n\r\n
            int headersEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), i);
            if (headersEnd < 0) throw new IllegalArgumentException("Multipart headers incomplete");

            String headersBlock = new String(body, i, headersEnd - i, StandardCharsets.ISO_8859_1);
            Map<String, String> headers = parseHeaders(headersBlock);

            i = headersEnd + 4; // skip \r\n\r\n

            // Part data goes until "\r\n--boundary"
            int nextBoundary = indexOf(body, ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1), i);
            if (nextBoundary < 0) throw new IllegalArgumentException("Multipart boundary not found after part");

            int dataStart = i;
            int dataEnd = nextBoundary; // exclude preceding \r\n

            // Move i to the start of "--boundary"
            i = nextBoundary + 2; // skip leading \r\n

            // Determine if this part is a file part
            String cd = headers.getOrDefault("content-disposition", "");
            ContentDisposition disp = parseContentDisposition(cd);

            if (disp.filename != null && !disp.filename.isBlank()) {
                byte[] partData = Arrays.copyOfRange(body, dataStart, dataEnd);
                String safeName = sanitizeFilename(disp.filename);
                if (safeName.isEmpty()) safeName = "upload_" + Instant.now().toEpochMilli() + ".bin";

                Path out = uniquePath(uploadRoot, safeName);
                Files.write(out, partData, StandardOpenOption.CREATE_NEW);

                saved.add(new SavedFile(out.getFileName().toString(), partData.length));
            }

            // Continue loop; i is currently at "--boundary" (without leading CRLF)
            // Next iteration consumes boundaryBytes again.
        }

        return saved;
    }

    private static Map<String, String> parseHeaders(String block) {
        Map<String, String> m = new HashMap<>();
        String[] lines = block.split("\r\n");
        for (String line : lines) {
            int idx = line.indexOf(':');
            if (idx <= 0) continue;
            String k = line.substring(0, idx).trim().toLowerCase(Locale.ROOT);
            String v = line.substring(idx + 1).trim();
            m.put(k, v);
        }
        return m;
    }

    private static final class ContentDisposition {
        final String name;
        final String filename;
        ContentDisposition(String name, String filename) {
            this.name = name;
            this.filename = filename;
        }
    }

    private static ContentDisposition parseContentDisposition(String cd) {
        // Example: form-data; name="file"; filename="a.txt"
        String name = null;
        String filename = null;
        String filenameStar = null;

        for (String p : splitHeaderParams(cd)) {
            String t = p.trim();
            int eq = t.indexOf('=');
            if (eq < 0) continue;
            String k = t.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String v = t.substring(eq + 1).trim();
            v = stripQuotes(v);

            if (k.equals("name")) name = v;
            if (k.equals("filename")) filename = v;
            if (k.equals("filename*")) filenameStar = v;
        }
        String chosen = filename;
        if (filenameStar != null && !filenameStar.isBlank()) {
            String decoded = decodeRfc5987(filenameStar);
            if (decoded != null && !decoded.isBlank()) chosen = decoded;
        }
        return new ContentDisposition(name, chosen);
    }

    private static String sanitizeFilename(String s) {
        // remove path separators and control chars
        String x = s.replace("\\", "/");
        int slash = x.lastIndexOf('/');
        if (slash >= 0) x = x.substring(slash + 1);
        x = x.replaceAll("[\\x00-\\x1F\\x7F]", "");
        x = x.replaceAll("[^a-zA-Z0-9._-]", "_");
        // avoid "." or ".."
        if (x.equals(".") || x.equals("..")) return "";
        return x;
    }

    private static Path safeResolveFile(Path root, String filename) {
        Path p = root.resolve(filename).normalize();
        if (!p.startsWith(root)) throw new SecurityException("Traversal blocked");
        return p;
    }

    private static Path uniquePath(Path root, String filename) throws Exception {
        String base = filename;
        String ext = "";
        int dot = filename.lastIndexOf('.');
        if (dot > 0) { base = filename.substring(0, dot); ext = filename.substring(dot); }

        for (int n = 0; n < 10_000; n++) {
            String name = (n == 0) ? (base + ext) : (base + "_" + n + ext);
            Path p = safeResolveFile(root, name);
            if (!Files.exists(p)) return p;
        }
        throw new IllegalStateException("Could not allocate unique filename");
    }

    private static boolean consumeCRLF(byte[] a, int pos) {
        return pos + 1 < a.length && a[pos] == '\r' && a[pos + 1] == '\n';
    }

    private static boolean startsWith(byte[] a, int pos, byte[] prefix) {
        if (pos < 0 || pos + prefix.length > a.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (a[pos + i] != prefix[i]) return false;
        }
        return true;
    }

    private static int indexOf(byte[] a, byte[] needle, int from) {
        if (needle.length == 0) return from;
        outer:
        for (int i = Math.max(0, from); i + needle.length <= a.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (a[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static List<String> splitHeaderParams(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                cur.append(c);
                continue;
            }
            if (c == ';' && !inQuotes) {
                out.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    private static String stripQuotes(String v) {
        if (v == null) return null;
        String t = v.trim();
        if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    private static String decodeRfc5987(String v) {
        // filename*=charset'lang'%xx%yy
        int first = v.indexOf('\'');
        if (first < 0) return percentDecodeToString(v, StandardCharsets.UTF_8);
        int second = v.indexOf('\'', first + 1);
        if (second < 0) return percentDecodeToString(v, StandardCharsets.UTF_8);

        String charset = v.substring(0, first);
        String encoded = v.substring(second + 1);
        Charset cs;
        try {
            cs = Charset.forName(charset);
        } catch (Exception e) {
            cs = StandardCharsets.UTF_8;
        }
        return percentDecodeToString(encoded, cs);
    }

    private static String percentDecodeToString(String s, Charset cs) {
        if (s == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int h1 = hexVal(s.charAt(i + 1));
                int h2 = hexVal(s.charAt(i + 2));
                if (h1 >= 0 && h2 >= 0) {
                    out.write((h1 << 4) | h2);
                    i += 2;
                    continue;
                }
            }
            out.write((byte) c);
        }
        return new String(out.toByteArray(), cs);
    }

    private static int hexVal(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return 10 + (c - 'a');
        if (c >= 'A' && c <= 'F') return 10 + (c - 'A');
        return -1;
    }

    private static String renderSaved(List<SavedFile> saved) {
        StringBuilder sb = new StringBuilder();
        for (SavedFile f : saved) {
            sb.append("Saved: ").append(f.name).append(" (").append(f.bytes).append(" bytes)\n");
        }
        return sb.toString();
    }

    private static final class SavedFile {
        final String name;
        final int bytes;
        SavedFile(String name, int bytes) {
            this.name = name;
            this.bytes = bytes;
        }
    }
}
