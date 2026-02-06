import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public class Router {
    private final ConfigLoader.Config cfg;

    public Router(ConfigLoader.Config cfg) {
        this.cfg = cfg;
    }

    public Response handle(HttpModels.Request req) {
        ConfigLoader.Route route = null;
        int best = -1;
        for (ConfigLoader.Route r : cfg.routes) {
            if (req.path.startsWith(r.pathPrefix) && r.pathPrefix.length() > best) {
                route = r;
                best = r.pathPrefix.length();
            }

        }
        if (route == null)
            return ErrorPages.response(cfg, 404);
        if (!route.methods.isEmpty() && !route.methods.contains(req.method)) {
            Response r = ErrorPages.response(cfg, 405);
            r.headers.put("Allow", String.join(", ", route.methods));
            return r;
        }
        // :redirect
        if (route.redirectTo != null) {
            Response r = Response.text(route.redirectCode, "Moved", "text/plain", "");
            r.headers.put("Location", route.redirectTo);
            return r;
        }
        if (route.cgiExt != null && req.path.endsWith(route.cgiExt)) {
            return Response.text(501, "Not Implemented", "text/plain",
                    "CGI route matched but CGI handler not wired yet.\n");
        }
        // serve static file
        return serverStatic(route, req);
    }

    private Response serverStatic(ConfigLoader.Route route, HttpModels.Request req) {
        try {
            Path root = Path.of(route.root).toAbsolutePath().normalize();
            String rel = req.path.substring(route.pathPrefix.length());
            if (rel.isEmpty())
                rel = "/";
            Path resolved = root.resolve(rel.substring(1)).normalize();

            // prevent traversal
            if (!resolved.startsWith(root))
                return ErrorPages.response(cfg, 403);

            if (Files.isDirectory(resolved)) {
                Path idx = resolved.resolve(route.index != null ? route.index : "index.html");
                if (Files.exists(idx)) {
                    return filResponse(idx);
                }
                if (route.dirLilsting) {
                    return Response.text(200, "OK", "text/plain", "Directory listing not implemented yet.\n");
                }
                return ErrorPages.response(cfg, 403);
            }
            if (!Files.exists(resolved))
                return ErrorPages.response(cfg, 404);
            if ("DELETE".equals(req.method)) {
                try {
                    Files.delete(resolved);
                    return Response.text(200, "OK", "text/plain", "Deleted\n");
                } catch (Exception e) {
                    return ErrorPages.response(cfg, 403);
                }
            }
            return filResponse(resolved);
        } catch (Exception e) {
            return ErrorPages.response(cfg, 500);
        }
    }

    private Response filResponse(Path p) throws Exception {
        Response r = new Response();
        r.status = 200;
        r.reason = "OK";
        r.body = Files.readAllBytes(p);
        r.headers.put("Content-Type", guessContentType(p));
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
