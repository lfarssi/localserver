import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class AdminView {
    private AdminView() {}

    public static Response metricsJson(Metrics.Snapshot s) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"uptime_ms\":").append(s.uptimeMs).append(",");
        sb.append("\"active_connections\":").append(s.activeConnections).append(",");
        sb.append("\"pending_write_bytes_total\":").append(s.pendingWriteBytesTotal).append(",");
        sb.append("\"total_requests\":").append(s.totalRequests).append(",");
        sb.append("\"total_responses\":").append(s.totalResponses).append(",");
        sb.append("\"bytes_out\":").append(s.bytesOut).append(",");
        sb.append("\"status_counts\":{");
        boolean first = true;
        for (Map.Entry<Integer, Long> e : s.statusCounts.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(e.getKey()).append("\":").append(e.getValue());
        }
        sb.append("}}");

        Response r = new Response();
        r.status = 200;
        r.reason = "OK";
        r.body = sb.toString().getBytes(StandardCharsets.UTF_8);
        r.setHeader("Content-Type", "application/json; charset=utf-8");
        return r;
    }

    public static Response adminHtml(Metrics.Snapshot s) {
        StringBuilder rows = new StringBuilder();
        for (var e : s.statusCounts.entrySet()) {
            rows.append("<tr><td>").append(e.getKey()).append("</td><td>").append(e.getValue()).append("</td></tr>");
        }

        String html =
                "<!doctype html><html><head><meta charset='utf-8'>" +
                "<title>LocalServer Admin</title>" +
                "<style>body{font-family:Arial;margin:24px} .k{color:#555} table{border-collapse:collapse} td,th{border:1px solid #ccc;padding:6px 10px}</style>" +
                "</head><body>" +
                "<h1>LocalServer Admin</h1>" +
                "<p class='k'>Refresh the page to update.</p>" +
                "<ul>" +
                "<li><b>Uptime:</b> " + (s.uptimeMs / 1000) + "s</li>" +
                "<li><b>Active connections:</b> " + s.activeConnections + "</li>" +
                "<li><b>Pending write bytes (total):</b> " + s.pendingWriteBytesTotal + "</li>" +
                "<li><b>Total requests:</b> " + s.totalRequests + "</li>" +
                "<li><b>Total responses:</b> " + s.totalResponses + "</li>" +
                "<li><b>Bytes out:</b> " + s.bytesOut + "</li>" +
                "</ul>" +
                "<h2>Status codes</h2>" +
                "<table><tr><th>Status</th><th>Count</th></tr>" + rows + "</table>" +
                "<p><a href='/metrics'>/metrics (JSON)</a></p>" +
                "</body></html>";

        return Response.text(200, "OK", "text/html", html);
    }
}
