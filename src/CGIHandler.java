import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class CGIHandler {

    public static Response handle(ConfigLoader.Config cfg, ConfigLoader.Route route, HttpModels.Request req) {
        try {
            // Map URL to script path inside route.root
            Path root = Path.of(route.root).toAbsolutePath().normalize();

            // For /cgi/hello.py -> rel "/hello.py"
            String relUrl = req.path.substring(route.pathPrefix.length());
            if (relUrl.isEmpty())
                relUrl = "/";
            if (relUrl.startsWith("/"))
                relUrl = relUrl.substring(1);

            Path script = root.resolve(relUrl).normalize();

            // Prevent traversal
            if (!script.startsWith(root))
                return ErrorPages.response(cfg, 403);

            // Must exist and be file
            if (!Files.exists(script) || Files.isDirectory(script))
                return ErrorPages.response(cfg, 404);

            // Extension gate
            if (route.cgiExt != null && !script.getFileName().toString().endsWith(route.cgiExt)) {
                return ErrorPages.response(cfg, 405); // or 404; your choice
            }

            String interpreter = (route.cgiInterpreter != null && !route.cgiInterpreter.isBlank())
                    ? route.cgiInterpreter
                    : defaultInterpreterFor(route.cgiExt);

            if (interpreter == null) {
                return Response.text(500, "Internal Server Error", "text/plain",
                        "No CGI interpreter configured for " + route.cgiExt + "\n");
            }

            ProcessBuilder pb = new ProcessBuilder(interpreter, script.toString());
            Map<String, String> env = pb.environment();

            // CGI-ish env (minimal, but useful)
            env.put("REQUEST_METHOD", req.method);
            env.put("QUERY_STRING", req.query == null ? "" : req.query);
            env.put("CONTENT_LENGTH", String.valueOf(req.body == null ? 0 : req.body.length));
            env.put("CONTENT_TYPE", req.headers.getOrDefault("content-type", ""));
            env.put("PATH_INFO", script.toString()); // per your spec: full paths in PATH_INFO
            env.put("SERVER_PROTOCOL", "HTTP/1.1");

            // For debugging, you can also pass headers as HTTP_* vars
            for (var e : req.headers.entrySet()) {
                String k = e.getKey().toUpperCase(Locale.ROOT).replace('-', '_');
                if (k.equals("CONTENT_TYPE") || k.equals("CONTENT_LENGTH"))
                    continue;
                env.put("HTTP_" + k, e.getValue());
            }

            pb.redirectErrorStream(false); // keep stderr separate

            Process p = pb.start();

            // Write request body to stdin
            if (req.body != null && req.body.length > 0) {
                try (OutputStream os = p.getOutputStream()) {
                    os.write(req.body);
                    os.flush();
                }
            } else {
                // Must close to signal EOF to scripts expecting it
                p.getOutputStream().close();
            }

            // Read stdout/stderr with caps
            byte[] stdout = readCapped(p.getInputStream(), route.cgiMaxOutputBytes);
            byte[] stderr = readCapped(p.getErrorStream(), 512_000);

            boolean finished = p.waitFor(route.cgiTimeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                Response r = ErrorPages.response(cfg, 500);
                // Optional: include small debug; avoid leaking in prod
                return r;
            }

            int exit = p.exitValue();
            if (exit != 0) {
                // CGI script failed; return 500 (include stderr in body for internal usage if
                // desired)
                return Response.text(500, "Internal Server Error", "text/plain",
                        "CGI exited with " + exit + "\n" + safeSnippet(stderr));
            }

            return parseCgiResponse(cfg, stdout);

        } catch (Exception e) {
            e.printStackTrace();
            return ErrorPages.response(cfg, 500);
        }
    }

    private static String defaultInterpreterFor(String ext) {
        if (ext == null)
            return null;
        return switch (ext) {
            case ".py" -> "python3";
            case ".pl" -> "perl";
            case ".php" -> "php";
            default -> null;
        };
    }

    private static byte[] readCapped(InputStream is, int max) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.min(32_768, max));
        byte[] buf = new byte[8192];
        int total = 0;
        while (true) {
            int n = is.read(buf);
            if (n == -1)
                break;
            total += n;
            if (total > max)
                throw new IOException("CGI output exceeded cap: " + max);
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static String safeSnippet(byte[] b) {
        if (b == null || b.length == 0)
            return "";
        String s = new String(b, StandardCharsets.UTF_8);
        if (s.length() > 2000)
            s = s.substring(0, 2000) + "\n...[truncated]\n";
        return s;
    }

    /**
     * Parse CGI stdout into HTTP response. Supports Status: and normal headers,
     * then body.
     */
    private static Response parseCgiResponse(ConfigLoader.Config cfg, byte[] stdout) {
        // CGI often returns headers + blank line + body.
        // Find header/body separator: \r\n\r\n preferred, else \n\n
        int sep = indexOf(stdout, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), 0);
        int sepLen = 4;
        if (sep < 0) {
            sep = indexOf(stdout, "\n\n".getBytes(StandardCharsets.ISO_8859_1), 0);
            sepLen = 2;
        }
        if (sep < 0) {
            // No headers; treat all as body 200
            Response r = new Response();
            r.status = 200;
            r.reason = "OK";
            r.setHeader("Content-Type", "text/plain; charset=utf-8");
            r.body = stdout;
            return r;
        }

        byte[] headBytes = Arrays.copyOfRange(stdout, 0, sep);
        byte[] bodyBytes = Arrays.copyOfRange(stdout, sep + sepLen, stdout.length);

        String head = new String(headBytes, StandardCharsets.ISO_8859_1);
        String[] lines = head.split("\\r?\\n");

        int status = 200;
        String reason = "OK";
        Response r = new Response();

        for (String line : lines) {
            if (line.isBlank())
                continue;

            // Status: 302 Found
            if (line.regionMatches(true, 0, "Status:", 0, 7)) {
                String v = line.substring(7).trim();
                // parse "### reason"
                int sp = v.indexOf(' ');
                if (sp > 0) {
                    status = parseIntSafe(v.substring(0, sp).trim(), 200);
                    reason = v.substring(sp + 1).trim();
                } else {
                    status = parseIntSafe(v, 200);
                    reason = defaultReason(status);
                }
                continue;
            }

            int idx = line.indexOf(':');
            if (idx <= 0)
                continue;
            String k = line.substring(0, idx).trim();
            String v = line.substring(idx + 1).trim();

            // Multiple Set-Cookie allowed
            if (k.equalsIgnoreCase("Set-Cookie")) {
                r.addHeader("Set-Cookie", v);
            } else {
                r.setHeader(k, v);
            }
        }

        r.status = status;
        r.reason = (reason == null || reason.isBlank()) ? defaultReason(status) : reason;
        r.body = bodyBytes;

        // If CGI didn't set content-type, default it
        if (r.getHeader("Content-Type") == null) {
            r.setHeader("Content-Type", "application/octet-stream");
        }

        // If CGI provides Location but no Status, some CGIs expect redirect:
        // We'll keep status as parsed/default. You can enforce 302 if Location exists.
        return r;
    }

    private static int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }

    private static String defaultReason(int code) {
        return switch (code) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 303 -> "See Other";
            case 307 -> "Temporary Redirect";
            case 308 -> "Permanent Redirect";
            case 400 -> "Bad Request";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 413 -> "Payload Too Large";
            default -> (code >= 500 ? "Internal Server Error" : "OK");
        };
    }

    private static int indexOf(byte[] a, byte[] needle, int from) {
        if (needle.length == 0)
            return from;
        outer: for (int i = Math.max(0, from); i + needle.length <= a.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (a[i + j] != needle[j])
                    continue outer;
            }
            return i;
        }
        return -1;
    }
}
