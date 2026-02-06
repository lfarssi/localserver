import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;


public class ConfigLoader {
    public static final class Config {
        public String host;
        public List<Integer> ports = new ArrayList<>();
        public int defaultServerPort;
        public int clientBodyLimitBytes;
        public String errorPagesDir;
        public List<Route> routes = new ArrayList<>();
    }

    public static final class Route {
        public String pathPrefix;
        public String root;
        public String index;
        public Set<String> methods = new HashSet<>();
        public boolean dirLilsting;

        public String redirectTo;
        public int redirectCode = 302;
        public boolean upload;
        public String cgiExt;
    }

    public static Config load(Path path) throws Exception {
        String json = Files.readString(path, StandardCharsets.UTF_8);
        Object v = new MiniJson(json).parseValue();
        if (!(v instanceof Map))
            throw new IllegalArgumentException("Config must be a JSON obejct");

        @SuppressWarnings("unchecked")
        Map<String, Object> o = (Map<String, Object>) v;
        Config cfg = new Config();
        cfg.host = str(o, "host", "0.0.0.0");
        cfg.errorPagesDir = str(o, "errorPagesDir", "err");
        cfg.clientBodyLimitBytes = num(o, "clientBodyLimitBytes", 1024 * 1024);
        cfg.defaultServerPort = num(o, "defaultServerPort", 8080);

        List<Object> ports = arr(o, "ports");
        for (Object p : ports)
            cfg.ports.add(((Number) p).intValue());
        if (cfg.ports.isEmpty())
            throw new IllegalArgumentException("ports must not be empty");
        List<Object> routes = arr(o, "routes");
        for (Object r0 : routes) {
            if (!(r0 instanceof Map))
                continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> r = (Map<String, Object>) r0;

            Route rt = new Route();
            rt.pathPrefix = str(r, "pathPrefix", "/");
            rt.root = str(r, "root", "www");
            rt.index = str(r, "index", "index.html");
            rt.dirLilsting = bool(r, "dirListing", false);

            if (r.containsKey("redirectTo")) {
                rt.redirectTo = str(r, "redirectTo", null);
                rt.redirectCode = num(r, "redirectCode", 302);
            }

            rt.upload = bool(r, "upload", false);
            if (r.containsKey("cgiExt"))
                rt.cgiExt = str(r, "cgiExt", null);

            List<Object> ms = r.containsKey("methods") ? (List<Object>) r.get("methods") : List.of();
            for (Object m : ms)
                rt.methods.add(String.valueOf(m).toUpperCase(Locale.ROOT));
            cfg.routes.add(rt);
        }
        validate(cfg);
        return cfg;
    }

    private static void validate(Config cfg) {
        if (cfg.host == null || cfg.host.isBlank())
            throw new IllegalArgumentException("host missing");
        if (cfg.clientBodyLimitBytes <= 0)
            throw new IllegalArgumentException("clientBodyLimitBytes must be >0");
        for (Route r : cfg.routes) {
            if (r.pathPrefix == null || !r.pathPrefix.startsWith("/"))
                throw new IllegalArgumentException("route.pathPrefix must start with /");
        }
    }

    private static String str(Map<String, Object> o, String k, String def) {
        Object v = o.get(k);
        return (v == null) ? def : String.valueOf(v);
    }

    private static int num(Map<String, Object> o, String k, int def) {
        Object v = o.get(k);
        return (v instanceof Number) ? ((Number) v).intValue() : def;
    }

    private static boolean bool(Map<String, Object> o, String k, boolean def) {
        Object v = o.get(k);
        return (v instanceof Boolean) ? (Boolean) v : def;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> arr(Map<String, Object> o, String k) {
        Object v = o.get(k);
        return (v instanceof List) ? (List<Object>) v : List.of();
    }

    static final class MiniJson {
        private final String s;
        private int i = 0;

        MiniJson(String s) {
            this.s = s;
        }

        Object parseValue() {
            skipWs();
            if (i >= s.length())
                throw err("Unexpected EOF");

            char c = s.charAt(i);
            if (c == '{')
                return parseObject();
            if (c == '[')
                return parseArray();
            if (c == '"')
                return parseString();
            if (c == 't' || c == 'f')
                return parsBoolean();
            if (c == 'n')
                return parseNull();
            if (c == '-' || Character.isDigit(c))
                return parsNumber();
            throw err("Unexpected char: " + c);
        }

        Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> m = new LinkedHashMap<>();
            skipWs();
            if (peek('}')) {
                i++;
                return m;
            }
            while (true) {
                skipWs();
                String k = parseString();
                skipWs();
                expect(':');
                Object v = parseValue();
                m.put(k, v);
                skipWs();
                if (peek('}')) {
                    i++;
                    return m;
                }
                expect(',');
            }
        }

        List<Object> parseArray() {
            expect('[');
            List<Object> a = new ArrayList<>();
            skipWs();
            if (peek(']')) {
                i++;
                return a;
            }
            while (true) {
                Object v = parseValue();
                a.add(v);
                skipWs();
                if (peek(']')) {
                    i++;
                    return a;
                }
                expect(',');
            }
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"')
                    break;
                if (c == '\\') {
                    if (i >= s.length())
                        throw err("Bad escape");
                    char e = s.charAt(i++);
                    sb.append(switch (e) {
                        case '"', '\\', '/' -> e;
                        case 'b' -> '\b';
                        case 'f' -> '\f';
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        default -> throw err("Bad escape: \\" + e);
                    });
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Boolean parsBoolean() {
            if (s.startsWith("true", i)) {
                i += 4;
                return true;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return false;
            }
            throw err("Bad boolean");
        }

        Object parseNull() {
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            throw err("Bad null");
        }

        Number parsNumber() {
            int start = i;
            if (s.charAt(i) == '-')
                i++;
            while (i < s.length() && Character.isDigit(s.charAt(i)))
                i++;
            if (i < s.length() && s.charAt(i) == '.') {
                i++;
                while (i < s.length() && Character.isDigit(s.charAt(i)))
                    i++;
                return Double.parseDouble(s.substring(start, i));
            }
            return Long.parseLong(s.substring(start, i));
        }

        void skipWs() {
            while (i < s.length() && Character.isWhitespace((s.charAt(i))))
                i++;
        }

        boolean peek(char c) {
            return i < s.length() && s.charAt(i) == c;
        }

        void expect(char c) {
            skipWs();
            if (i > s.length() || s.charAt(i) != c)
                throw err("Expect '" + c + "'");
            i++;
        }

        RuntimeException err(String msg) {
            return new IllegalArgumentException(msg + " at pos " + i);
        }
    }
}
