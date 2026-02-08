package utils;

import java.util.*;

public final class CookieUtil {
    private CookieUtil() {}

    /** Parse Cookie header into a map. */
    public static Map<String, String> parseCookieHeader(String cookieHeader) {
        Map<String, String> out = new HashMap<>();
        if (cookieHeader == null || cookieHeader.isBlank()) return out;

        // Cookie: a=b; c=d; SID=...
        String[] parts = cookieHeader.split(";");
        for (String part : parts) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int eq = p.indexOf('=');
            if (eq <= 0) continue;
            String k = p.substring(0, eq).trim();
            String v = p.substring(eq + 1).trim();
            // No RFC6265 decoding here; keep raw. (Good enough for SID.)
            out.put(k, v);
        }
        return out;
    }

    /** Build a Set-Cookie header value. */
    public static String buildSetCookie(
            String name,
            String value,
            String path,
            boolean httpOnly,
            boolean secure,
            String sameSite,   // "Lax", "Strict", "None" or null
            Integer maxAgeSec  // null for session cookie
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append("=").append(value);

        if (path != null && !path.isBlank()) sb.append("; Path=").append(path);
        if (maxAgeSec != null) sb.append("; Max-Age=").append(maxAgeSec);

        // Default Lax is a sane choice for internal apps
        if (sameSite != null && !sameSite.isBlank()) sb.append("; SameSite=").append(sameSite);

        if (secure) sb.append("; Secure");
        if (httpOnly) sb.append("; HttpOnly");

        return sb.toString();
    }
}
