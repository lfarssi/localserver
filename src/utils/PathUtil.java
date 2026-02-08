package utils;

public class PathUtil {
    // Normalize URL path: remove "..", ".", duplicate slashes (minimal safe
    // approach)
    public static String normalizeUrlPath(String path) {
        if (path == null || path.isEmpty())
            return "/";
        // ensure leading slash
        if (path.charAt(0) != '/')
            path = "/" + path;

        // collapse multiple slashes
        path = path.replaceAll("/{2,}", "/");

        // remove /./
        path = path.replace("/./", "/");

        // naive ".." removal (good enough for routing; real security is FS normalize +
        // startsWith(root))
        while (path.contains("/../")) {
            int idx = path.indexOf("/../");
            if (idx == 0) {
                path = path.substring(3);
                continue;
            }
            int prev = path.lastIndexOf('/', idx - 1);
            if (prev < 0)
                break;
            path = path.substring(0, prev) + path.substring(idx + 3);
        }
        return path;
    }
}
