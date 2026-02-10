# LocalServer

A minimal HTTP/1.1 server written in Java (single-threaded, NIO Selector). It supports static files, uploads, CGI, sessions, redirects, metrics, and custom error pages. Designed for learning, testing, and audit-style evaluations.

## Features
- HTTP/1.1 parsing with `Content-Length` and `Transfer-Encoding: chunked`
- 411 Length Required for POST/PUT without a body delimiter
- Static file serving with index files and path traversal protection
- Route-based method restrictions and redirects
- Raw and multipart file uploads
- CGI execution (Python `.py`)
- Sessions via `SID` cookie
- Admin dashboard and JSON metrics (`/admin`, `/metrics`)
- Custom error pages (`error_pages/*.html`)

## Quick Start
1. Build:

```bash
javac -d out $(find src -name "*.java")
```

2. Run:

```bash
java -cp out Main config.json
```

3. Test:

```bash
curl -i http://127.0.0.1:8080/
```

## Configuration (`config.json`)

Example:

```json
{
  "host": "0.0.0.0",
  "ports": [8080, 8081],
  "defaultServerPort": 8080,
  "clientBodyLimitBytes": 10485760,
  "errorPagesDir": "error_pages",
  "routes": [
    {
      "pathPrefix": "/",
      "root": "www",
      "index": "index.html",
      "methods": ["GET"],
      "dirListing": false
    },
    {
      "pathPrefix": "/upload",
      "root": "uploads",
      "methods": ["POST"],
      "upload": true
    },
    {
      "pathPrefix": "/cgi",
      "root": "cgi-bin",
      "methods": ["GET", "POST"],
      "cgiExt": ".py",
      "cgiInterpreter": "python3",
      "cgiTimeoutMs": 3000
    },
    {
      "pathPrefix": "/old",
      "redirectTo": "/new",
      "redirectCode": 301,
      "methods": ["GET"]
    }
  ]
}
```

### Route Fields
- `pathPrefix`: URL prefix to match
- `root`: filesystem root for static files or uploads
- `index`: default file for directories
- `methods`: allowed HTTP methods for this route
- `dirListing`: if true, returns a placeholder listing message
- `redirectTo` + `redirectCode`: redirect behavior
- `upload`: enables upload handling for this route
- `cgiExt`, `cgiInterpreter`, `cgiTimeoutMs`, `cgiMaxOutputBytes`: CGI settings

## Uploads

### Raw upload
```bash
curl -i -X POST --data-binary @file.bin http://127.0.0.1:8080/upload
```

### Chunked upload
```bash
curl -i -X POST -H "Transfer-Encoding: chunked" --data-binary @file.bin http://127.0.0.1:8080/upload
```

Empty bodies are rejected with **400 Bad Request**.

## Error Pages
Place HTML files in `error_pages/` named by status code (e.g., `404.html`).
If a file is missing, the server falls back to a simple default HTML response.

## Metrics & Admin
- `GET /metrics` returns JSON metrics
- `GET /admin` returns a simple HTML dashboard

## Audit Script
Run the local audit checks:

```bash
./audit_check.sh
```

## Notes
- Only one server configuration is supported (no virtual hosts).
- CGI currently supports Python `.py` scripts.
- Directory listing is not implemented beyond a placeholder message.

## License
MIT
