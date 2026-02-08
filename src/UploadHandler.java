import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

public class UploadHandler {
    private static final SecureRandom RNG = new SecureRandom();

    public static Response handle(ConfigLoader.Config cfg, ConfigLoader.Route route, HttpModels.Request req) {
        try {
            if (req.body == null) req.body = new byte[0];

            // Enforce body limit (parser already does; but double-check here too)
            if (req.body.length > cfg.clientBodyLimitBytes) {
                return ErrorPages.response(cfg, 413);
            }

            String ct = req.headers.getOrDefault("content-type", "");
            Path uploadRoot = Path.of(route.root).toAbsolutePath().normalize();
            Files.createDirectories(uploadRoot);

            if (ct.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
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
                String name = "upload_" + Instant.now().toEpochMilli() + "_" + (RNG.nextInt() & 0x7fffffff) + ".bin";
                Path out = safeResolveFile(uploadRoot, name);
                Files.write(out, req.body, StandardOpenOption.CREATE_NEW);

                return Response.text(200, "OK", "text/plain",
                        "Saved raw upload: " + out.getFileName() + " (" + req.body.length + " bytes)\n");
            }

        } catch (SecurityException se) {
            return ErrorPages.response(cfg, 403);
        } catch (Exception e) {
            e.printStackTrace();
            return ErrorPages.response(cfg, 500);
        }
    }

    private static String extractBoundary(String contentType) {
        // content-type: multipart/form-data; boundary=----WebKitFormBoundary...
        String[] parts = contentType.split(";");
        for (String p : parts) {
            String t = p.trim();
            if (t.toLowerCase(Locale.ROOT).startsWith("boundary=")) {
                String b = t.substring("boundary=".length()).trim();
                if (b.startsWith("\"") && b.endsWith("\"") && b.length() >= 2) {
                    b = b.substring(1, b.length() - 1);
                }
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
            byte[] partData = Arrays.copyOfRange(body, dataStart, dataEnd);

            // Move i to the start of "--boundary"
            i = nextBoundary + 2; // skip leading \r\n

            // Determine if this part is a file part
            String cd = headers.getOrDefault("content-disposition", "");
            ContentDisposition disp = parseContentDisposition(cd);

            if (disp.filename != null && !disp.filename.isBlank()) {
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

        String[] parts = cd.split(";");
        for (String p : parts) {
            String t = p.trim();
            int eq = t.indexOf('=');
            if (eq < 0) continue;
            String k = t.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String v = t.substring(eq + 1).trim();
            if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) v = v.substring(1, v.length() - 1);

            if (k.equals("name")) name = v;
            if (k.equals("filename")) filename = v;
        }
        return new ContentDisposition(name, filename);
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
