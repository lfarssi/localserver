#!/usr/bin/env bash
set -u

HOST="${HOST:-127.0.0.1}"
PORT="${PORT:-8080}"
PORT2="${PORT2:-8081}"
BASE="http://${HOST}:${PORT}"
TMP_DIR="${TMP_DIR:-/tmp/localserver_audit}"
mkdir -p "$TMP_DIR"

pass() { printf "PASS: %s\n" "$*"; }
fail() { printf "FAIL: %s\n" "$*"; }
warn() { printf "WARN: %s\n" "$*"; }
skip() { printf "SKIP: %s\n" "$*"; }
info() { printf "INFO: %s\n" "$*"; }

need_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    skip "Missing command: $1"
    return 1
  fi
  return 0
}

have_rg=0
if command -v rg >/dev/null 2>&1; then
  have_rg=1
fi

grep_q() {
  local pattern="$1"
  local file="$2"
  if [ "$have_rg" -eq 1 ]; then
    rg -q "$pattern" "$file"
  else
    grep -Eq "$pattern" "$file"
  fi
}

grep_qi() {
  local pattern="$1"
  local file="$2"
  if [ "$have_rg" -eq 1 ]; then
    rg -qi "$pattern" "$file"
  else
    grep -Eqi "$pattern" "$file"
  fi
}

if ! need_cmd curl; then
  fail "curl is required"
  exit 1
fi

# Quick server check
if ! curl -sS --max-time 2 "$BASE/" >/dev/null 2>&1; then
  fail "Server not reachable at $BASE"
  info "Start the server and re-run: java -cp out Main config.json"
  exit 1
fi

info "Using BASE=$BASE"

# ---- Functional / I/O Multiplexing (static checks) ----
if [ "$have_rg" -eq 1 ]; then
  if rg -n "Selector|OP_READ|OP_WRITE|select" src/Server.java >/dev/null; then
    pass "Selector-based I/O multiplexing present"
  else
    fail "Selector-based I/O multiplexing not found in src/Server.java"
  fi
else
  if grep -En "Selector|OP_READ|OP_WRITE|select" src/Server.java >/dev/null; then
    pass "Selector-based I/O multiplexing present (grep)"
  else
    warn "Could not confirm selector usage (no rg)"
  fi
fi

# ---- Configuration checks ----
if curl -sS --max-time 2 "http://${HOST}:${PORT2}/" >/dev/null 2>&1; then
  pass "Multiple ports: ${PORT2} reachable"
else
  warn "Port ${PORT2} not reachable (check config.json ports)"
fi

# Custom error page 404
resp_404_body="$TMP_DIR/resp_404_body.txt"
resp_404_head="$TMP_DIR/resp_404_head.txt"
curl -sS -D "$resp_404_head" -o "$resp_404_body" "$BASE/nope" || true
if grep_qi "404 Not Found" "$resp_404_body"; then
  pass "Custom 404 page served"
else
  fail "Custom 404 page missing or body empty"
fi

# Body limit
limit=$(grep -Eo '"clientBodyLimitBytes"[[:space:]]*:[[:space:]]*[0-9]+' config.json 2>/dev/null | grep -Eo '[0-9]+' | head -n 1)
if [ -z "${limit:-}" ]; then
  limit=10485760
  warn "Could not parse clientBodyLimitBytes; using $limit"
fi
big_file="$TMP_DIR/big.bin"
count=$((limit + 1))
head -c "$count" /dev/zero > "$big_file"
resp_413_head="$TMP_DIR/resp_413_head.txt"
curl -sS -D "$resp_413_head" -o /dev/null -X POST --data-binary "@$big_file" "$BASE/upload" || true
if grep_q "^HTTP/.* 413" "$resp_413_head"; then
  pass "Body limit enforced (413)"
else
  fail "Body limit not enforced or wrong status"
fi

# Routes / index
resp_index_head="$TMP_DIR/resp_index_head.txt"
curl -sS -D "$resp_index_head" -o /dev/null "$BASE/" || true
if grep_q "^HTTP/.* 200" "$resp_index_head"; then
  pass "Index route responds 200"
else
  fail "Index route not 200"
fi

# Methods / Allow header
resp_405_head="$TMP_DIR/resp_405_head.txt"
curl -sS -D "$resp_405_head" -o /dev/null -X DELETE "$BASE/" || true
if grep_q "^HTTP/.* 405" "$resp_405_head"; then
  pass "DELETE on / returns 405"
else
  fail "DELETE on / did not return 405"
fi
if grep_qi "^Allow:" "$resp_405_head"; then
  pass "Allow header present on 405"
else
  warn "Allow header missing on 405"
fi

# Sessions / cookies
resp_cookie_head="$TMP_DIR/resp_cookie_head.txt"
curl -sS -D "$resp_cookie_head" -o /dev/null "$BASE/" || true
if grep_qi "^Set-Cookie: SID=" "$resp_cookie_head"; then
  pass "Session cookie SID set"
else
  warn "Session cookie SID not set"
fi

# Upload raw file
small_file="$TMP_DIR/small.bin"
printf "hello" > "$small_file"
resp_upload_body="$TMP_DIR/resp_upload_body.txt"
resp_upload_head="$TMP_DIR/resp_upload_head.txt"
curl -sS -D "$resp_upload_head" -o "$resp_upload_body" -X POST --data-binary "@$small_file" "$BASE/upload" || true
if grep_q "^HTTP/.* 200" "$resp_upload_head"; then
  pass "Raw upload returns 200"
else
  fail "Raw upload did not return 200"
fi

# Extract saved filename and verify file exists on disk
saved_name=$(grep -Eo "Saved raw upload: [^ ]+" "$resp_upload_body" | sed 's/Saved raw upload: //')
if [ -n "${saved_name:-}" ] && [ -f "uploads/$saved_name" ]; then
  pass "Uploaded file saved to uploads/$saved_name"
else
  warn "Could not verify uploaded file on disk (no name or file missing)"
fi

# Wrong request (requires nc)
if need_cmd nc; then
  printf "GARBAGE\r\n\r\n" | nc -w 2 "$HOST" "$PORT" >/dev/null 2>&1 && pass "Server handled malformed request (connection responded)" || warn "nc request failed or no response"
else
  skip "Malformed request check (nc missing)"
fi

# Redirect
resp_redir_head="$TMP_DIR/resp_redir_head.txt"
curl -sS -D "$resp_redir_head" -o /dev/null "$BASE/old" || true
if grep_q "^HTTP/.* 301" "$resp_redir_head" && grep_qi "^Location: /new" "$resp_redir_head"; then
  pass "Redirect /old -> /new (301)"
else
  fail "Redirect not correct"
fi

# CGI test (first .py in cgi-bin)
if [ "$have_rg" -eq 1 ]; then
  cgi_script=$(rg --files -g "*.py" cgi-bin 2>/dev/null | head -n 1)
else
  cgi_script=$(find cgi-bin -type f -name "*.py" 2>/dev/null | head -n 1)
fi
if [ -n "${cgi_script:-}" ]; then
  cgi_url="$BASE/cgi/$(basename "$cgi_script")"
  resp_cgi_head="$TMP_DIR/resp_cgi_head.txt"
  curl -sS -D "$resp_cgi_head" -o /dev/null "$cgi_url" || true
  if grep_q "^HTTP/.* 200" "$resp_cgi_head"; then
    pass "CGI GET works (${cgi_url})"
  else
    warn "CGI GET did not return 200 (${cgi_url})"
  fi
else
  skip "No CGI .py scripts found in cgi-bin"
fi

# Hostname routing check (virtual hosts)
resp_host_head="$TMP_DIR/resp_host_head.txt"
curl -sS -D "$resp_host_head" -o /dev/null -H "Host: test.com" "$BASE/" || true
if grep_q "^HTTP/.* 200" "$resp_host_head"; then
  warn "Host header accepted but no vhost routing test implemented"
else
  warn "Host header request failed"
fi

# Siege (optional)
if need_cmd siege; then
  info "Running short siege test (5s)"
  siege -b -t5S "$BASE/" >/dev/null 2>&1 && pass "Siege completed" || warn "Siege had errors"
else
  skip "Siege not installed"
fi

info "Audit check complete"
