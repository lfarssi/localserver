import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class CGIHandler {

    private static final int DEFAULT_TIMEOUT_MS = 3000;
    private static final int DEFAULT_MAX_STDOUT = 2_000_000;
    private static final int DEFAULT_MAX_STDERR = 256_000;

    public static Response handle(ConfigLoader.Config cfg, ConfigLoader.Route route, HttpModels.Request req) {
        try {
            Path root = Path.of(route.root).toAbsolutePath().normalize();

            // URL -> script under root (allow extra path after script extension)
            String after = req.path.substring(route.pathPrefix.length());
            if (after.isEmpty()) after = "/";

            int extIdx = (route.cgiExt == null) ? -1 : after.indexOf(route.cgiExt);
            if (extIdx < 0) return ErrorPages.response(cfg, 404);

            String scriptRel = after.substring(0, extIdx + route.cgiExt.length());
            String extraPathInfo = after.substring(extIdx + route.cgiExt.length());
            if (scriptRel.isEmpty()) return ErrorPages.response(cfg, 404);
            if (!scriptRel.startsWith("/")) scriptRel = "/" + scriptRel;

            Path script = root.resolve(scriptRel.substring(1)).normalize();

            if (!script.startsWith(root)) return ErrorPages.response(cfg, 403);
            if (!Files.exists(script) || Files.isDirectory(script)) return ErrorPages.response(cfg, 404);
            if (route.cgiExt != null && !script.getFileName().toString().endsWith(route.cgiExt)) {
                return ErrorPages.response(cfg, 404);
            }

            String interpreter = (route.cgiInterpreter == null || route.cgiInterpreter.isBlank())
                    ? "python3"
                    : route.cgiInterpreter;

            ProcessBuilder pb = new ProcessBuilder(interpreter, script.toString());
            Map<String, String> env = pb.environment();

            // ---- CGI env parity (practical minimum) ----
            env.put("GATEWAY_INTERFACE", "CGI/1.1");
            env.put("SERVER_PROTOCOL", "HTTP/1.1");
            env.put("SERVER_SOFTWARE", "LocalServer/1.0");

            env.put("REQUEST_METHOD", req.method);
            env.put("QUERY_STRING", req.query == null ? "" : req.query);
            env.put("REQUEST_URI", req.target == null ? req.path : req.target);

            // If you stored these in attrs from Server (recommended below)
            Object sp = req.attrs.get("serverPort");
            Object ra = req.attrs.get("remoteAddr");
            Object rp = req.attrs.get("remotePort");

            String hostHeader = req.headers.getOrDefault("host", cfg.host);
            String serverName = stripPort(hostHeader);
            String serverPort = (sp != null) ? String.valueOf(sp) : String.valueOf(parsePort(hostHeader, 80));

            env.put("SERVER_NAME", serverName);
            env.put("SERVER_PORT", serverPort);

            if (ra != null) env.put("REMOTE_ADDR", String.valueOf(ra));
            if (rp != null) env.put("REMOTE_PORT", String.valueOf(rp));

            // Script/path info
            // Your spec: PATH_INFO contains full paths
            env.put("PATH_INFO", script.toString());
            env.put("SCRIPT_FILENAME", script.toString());
            String scriptName = "/".equals(route.pathPrefix) ? scriptRel : route.pathPrefix + scriptRel;
            env.put("SCRIPT_NAME", scriptName);
            env.put("PATH_TRANSLATED", script.toString());
            env.put("EXTRA_PATH_INFO", extraPathInfo);

            // Content
            env.put("CONTENT_TYPE", req.headers.getOrDefault("content-type", ""));
            env.put("CONTENT_LENGTH", String.valueOf(req.body == null ? 0 : req.body.length));

            // Pass headers as HTTP_*
            for (var e : req.headers.entrySet()) {
                String k = e.getKey().toUpperCase(Locale.ROOT).replace('-', '_');
                if (k.equals("CONTENT_TYPE") || k.equals("CONTENT_LENGTH")) continue;
                env.put("HTTP_" + k, e.getValue());
            }

            pb.redirectErrorStream(false);
            Process p = pb.start();

            // stdin
            if (req.body != null && req.body.length > 0) {
                try (OutputStream os = p.getOutputStream()) {
                    os.write(req.body);
                    os.flush();
                }
            } else {
                p.getOutputStream().close();
            }

            int timeoutMs = route.cgiTimeoutMs > 0 ? route.cgiTimeoutMs : DEFAULT_TIMEOUT_MS;
            int maxOut = route.cgiMaxOutputBytes > 0 ? route.cgiMaxOutputBytes : DEFAULT_MAX_STDOUT;

            CappedRead stdout = readCapped(p.getInputStream(), maxOut);
            CappedRead stderr = readCapped(p.getErrorStream(), DEFAULT_MAX_STDERR);

            boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                return Response.text(500, "Internal Server Error", "text/plain", "CGI timeout\n");
            }

            if (p.exitValue() != 0) {
                return Response.text(500, "Internal Server Error", "text/plain",
                        "CGI failed (exit=" + p.exitValue() + ")\n" + snippet(stderr.data));
            }

            Response resp = parseCgiStdout(stdout.data);
            if (stdout.truncated) {
                resp.chunked = true;
                resp.setHeader("Transfer-Encoding", "chunked");
                resp.setHeader("X-CGI-Output-Truncated", "true");
            }
            return resp;

        } catch (Exception e) {
            e.printStackTrace();
            return ErrorPages.response(cfg, 500);
        }
    }

    private static Response parseCgiStdout(byte[] stdout) {
        int sep = indexOf(stdout, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), 0);
        int sepLen = 4;
        if (sep < 0) {
            sep = indexOf(stdout, "\n\n".getBytes(StandardCharsets.ISO_8859_1), 0);
            sepLen = 2;
        }

        Response resp = new Response();
        resp.status = 200;
        resp.reason = "OK";

        if (sep < 0) {
            resp.body = stdout;
            resp.setHeader("Content-Type", "text/plain; charset=utf-8");
            return resp;
        }

        String head = new String(stdout, 0, sep, StandardCharsets.ISO_8859_1);
        resp.body = Arrays.copyOfRange(stdout, sep + sepLen, stdout.length);

        boolean hasStatus = false;

        for (String line : head.split("\\r?\\n")) {
            if (line.isBlank()) continue;

            if (line.regionMatches(true, 0, "Status:", 0, 7)) {
                hasStatus = true;
                String v = line.substring(7).trim();
                int sp = v.indexOf(' ');
                if (sp > 0) {
                    resp.status = parseIntSafe(v.substring(0, sp).trim(), 200);
                    resp.reason = v.substring(sp + 1).trim();
                } else {
                    resp.status = parseIntSafe(v, 200);
                    resp.reason = defaultReason(resp.status);
                }
                continue;
            }

            int idx = line.indexOf(':');
            if (idx <= 0) continue;

            String k = line.substring(0, idx).trim();
            String v = line.substring(idx + 1).trim();

            if (k.equalsIgnoreCase("Set-Cookie")) resp.addHeader("Set-Cookie", v);
            else resp.setHeader(k, v);
        }

        // Location without Status => treat as redirect
        if (!hasStatus && resp.getHeader("Location") != null) {
            resp.status = 302;
            resp.reason = "Found";
        }

        // If CGI didn't provide length or transfer-encoding, use chunked
        if (resp.getHeader("Content-Length") == null && resp.getHeader("Transfer-Encoding") == null) {
            resp.chunked = true;
            resp.setHeader("Transfer-Encoding", "chunked");
        }

        if (resp.getHeader("Content-Type") == null) {
            resp.setHeader("Content-Type", "text/plain; charset=utf-8");
        }

        return resp;
    }

    private static CappedRead readCapped(InputStream is, int cap) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.min(32_768, cap));
        byte[] buf = new byte[8192];
        int total = 0;
        boolean truncated = false;
        while (true) {
            int n = is.read(buf);
            if (n == -1) break;
            total += n;
            if (!truncated) {
                if (total <= cap) {
                    bos.write(buf, 0, n);
                } else {
                    int keep = n - (total - cap);
                    if (keep > 0) bos.write(buf, 0, keep);
                    truncated = true;
                }
            }
            // if truncated, discard remaining bytes but keep draining to avoid deadlock
        }
        return new CappedRead(bos.toByteArray(), truncated);
    }

    private static final class CappedRead {
        final byte[] data;
        final boolean truncated;
        CappedRead(byte[] data, boolean truncated) {
            this.data = data;
            this.truncated = truncated;
        }
    }

    private static String snippet(byte[] b) {
        if (b == null || b.length == 0) return "";
        String s = new String(b, StandardCharsets.UTF_8);
        if (s.length() > 2000) s = s.substring(0, 2000) + "\n...[truncated]\n";
        return s;
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

    private static int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
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

    private static String stripPort(String host) {
        int idx = host.indexOf(':');
        return (idx >= 0) ? host.substring(0, idx) : host;
    }

    private static int parsePort(String host, int def) {
        int idx = host.indexOf(':');
        if (idx < 0) return def;
        try { return Integer.parseInt(host.substring(idx + 1)); } catch (Exception e) { return def; }
    }
}
