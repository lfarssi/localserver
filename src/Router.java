import java.nio.file.*;
import java.util.*;

import utils.CookieUtil;
import utils.SessionManager;

public class Router {
    private final ConfigLoader.Config cfg;
    private final SessionManager sessionManager = new SessionManager(30 * 60 * 1000L); // 30 min TTL

    public Router(ConfigLoader.Config cfg) {
        this.cfg = cfg;
    }

    // Server will call this periodically
    public void cleanupSessions(long nowMs) {
        sessionManager.cleanup(nowMs);
    }

    public Response handle(HttpModels.Request req) {

        long now = System.currentTimeMillis();

        // ---- SESSION: parse cookies ----
        Map<String, String> cookies = CookieUtil.parseCookieHeader(req.headers.get("cookie"));

        // ---- SESSION: get or create ----
        SessionManager.Session session = sessionManager.getOrCreate(cookies, now);

        // If client didn't send SID (or it was expired), we must set it
        boolean needsSetCookie = !session.id.equals(cookies.get(SessionManager.COOKIE_NAME));

        // Find best matching route by longest pathPrefix
        ConfigLoader.Route route = null;
        int best = -1;
        for (ConfigLoader.Route r : cfg.routes) {
            if (req.path.startsWith(r.pathPrefix) && r.pathPrefix.length() > best) {
                route = r;
                best = r.pathPrefix.length();
            }
        }

        Response resp;

        if (route == null) {
            resp = ErrorPages.response(cfg, 404);
            return attachSessionCookie(resp, needsSetCookie, session);
        }

        if (!route.methods.isEmpty() && !route.methods.contains(req.method)) {
            resp = ErrorPages.response(cfg, 405);
            resp.setHeader("Allow", String.join(", ", route.methods));
            return attachSessionCookie(resp, needsSetCookie, session);
        }

        // upload handler
        if (route.upload) {
            if (!("POST".equals(req.method) || "GET".equals(req.method))) {
                resp = ErrorPages.response(cfg, 405);
                return attachSessionCookie(resp, needsSetCookie, session);
            }
            resp = UploadHandler.handle(cfg, route, req);
            return attachSessionCookie(resp, needsSetCookie, session);
        }

        // Redirect
        if (route.redirectTo != null) {
            resp = Response.text(route.redirectCode, "Moved", "text/plain", "");
            resp.setHeader("Location", route.redirectTo);
            return attachSessionCookie(resp, needsSetCookie, session);
        }

        // CGI
        if (route.cgiExt != null) {
            int extIdx = req.path.indexOf(route.cgiExt, route.pathPrefix.length());
            if (extIdx >= 0) {
                // If you haven't added CGIHandler yet, keep your 501, but sessions still apply
                // resp = Response.text(501, "Not Implemented", "text/plain",
                // "CGI route matched but CGI handler not wired yet.\n");

                resp = CGIHandler.handle(cfg, route, req); // <-- requires the CGIHandler class I gave you
                return attachSessionCookie(resp, needsSetCookie, session);
            }
        }
        if ("GET".equals(req.method) && "/metrics".equals(req.path)) {
            return AdminView.metricsJson(ServerRef.get().metricsSnapshot());
        }
        if ("GET".equals(req.method) && "/admin".equals(req.path)) {
            return AdminView.adminHtml(ServerRef.get().metricsSnapshot());
        }

        // Static file serving
        resp = serveStatic(route, req);
        return attachSessionCookie(resp, needsSetCookie, session);
    }

    private Response attachSessionCookie(Response resp, boolean needsSetCookie, SessionManager.Session session) {
        if (needsSetCookie) {
            resp.addHeader("Set-Cookie", CookieUtil.buildSetCookie(
                    SessionManager.COOKIE_NAME,
                    session.id,
                    "/",
                    true,
                    false,
                    "Lax",
                    null));
        }
        return resp;
    }

    private Response serveStatic(ConfigLoader.Route route, HttpModels.Request req) {
        try {
            Path root = Path.of(route.root).toAbsolutePath().normalize();

            String rel = req.path.substring(route.pathPrefix.length());
            if (rel.isEmpty())
                rel = "/";

            // If rel is "/" then substring(1) is "", root.resolve("") == root
            Path resolved = root.resolve(rel.substring(1)).normalize();

            // prevent traversal
            if (!resolved.startsWith(root))
                return ErrorPages.response(cfg, 403);

            if (Files.isDirectory(resolved)) {
                Path idx = resolved.resolve(route.index != null ? route.index : "index.html");
                if (Files.exists(idx)) {
                    return fileResponse(idx);
                }
                if (route.dirListing) {
                    return Response.text(200, "OK", "text/plain", "Directory listing not implemented yet.\n");
                }
                return ErrorPages.response(cfg, 403);
            }

            if (!Files.exists(resolved))
                return ErrorPages.response(cfg, 404);

            // DELETE support (file delete)
            if ("DELETE".equals(req.method)) {
                try {
                    Files.delete(resolved);
                    return Response.text(200, "OK", "text/plain", "Deleted\n");
                } catch (Exception e) {
                    return ErrorPages.response(cfg, 403);
                }
            }

            return fileResponse(resolved);

        } catch (Exception e) {
            return ErrorPages.response(cfg, 500);
        }
    }

    private Response fileResponse(Path p) throws Exception {
        Response r = new Response();
        r.status = 200;
        r.reason = "OK";
        r.body = Files.readAllBytes(p);
        r.setHeader("Content-Type", guessContentType(p));
        return r;
    }

    private static String guessContentType(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".html") || name.endsWith(".htm"))
            return "text/html; charset=utf-8";
        if (name.endsWith(".css"))
            return "text/css; charset=utf-8";
        if (name.endsWith(".js"))
            return "application/javascript; charset=utf-8";
        if (name.endsWith(".json"))
            return "application/json; charset=utf-8";
        if (name.endsWith(".png"))
            return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg"))
            return "image/jpeg";
        if (name.endsWith(".gif"))
            return "image/gif";
        return "application/octet-stream";
    }
}
