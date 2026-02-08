package utils;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory session manager (single-thread friendly).
 *
 * - Stores sessions in a map keyed by SID
 * - Each session expires after ttlMs since last activity (sliding expiration)
 * - cleanup(nowMs) removes expired sessions
 */
public final class SessionManager {

    public static final String COOKIE_NAME = "SID";

    private final SecureRandom rng = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final long ttlMs;

    public SessionManager(long ttlMs) {
        if (ttlMs <= 0) throw new IllegalArgumentException("ttlMs must be > 0");
        this.ttlMs = ttlMs;
    }

    /**
     * Returns an existing non-expired session if cookie has SID; otherwise creates a new one.
     *
     * @param cookies parsed cookies from "Cookie:" header
     * @param nowMs current time in milliseconds (System.currentTimeMillis())
     */
    public Session getOrCreate(Map<String, String> cookies, long nowMs) {
        String sid = (cookies == null) ? null : cookies.get(COOKIE_NAME);

        if (sid != null && !sid.isBlank()) {
            Session existing = sessions.get(sid);
            if (existing != null && !existing.isExpired(nowMs)) {
                existing.touch(nowMs); // sliding expiration
                return existing;
            }
            // If session exists but expired, remove it (optional but cleaner)
            if (existing != null) sessions.remove(sid);
        }

        // Create new session
        Session created = new Session(newId(), ttlMs, nowMs);
        sessions.put(created.id, created);
        return created;
    }

    /** Remove expired sessions. Call periodically (e.g. every few seconds in your server tick). */
    public void cleanup(long nowMs) {
        for (Iterator<Map.Entry<String, Session>> it = sessions.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, Session> e = it.next();
            if (e.getValue().isExpired(nowMs)) it.remove();
        }
    }

    /** Optional helper: returns current number of active sessions. */
    public int size() {
        return sessions.size();
    }

    private String newId() {
        // 32 bytes -> 64 hex chars
        byte[] b = new byte[32];
        rng.nextBytes(b);
        return toHex(b);
    }

    private static String toHex(byte[] b) {
        final char[] hex = "0123456789abcdef".toCharArray();
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            int v = b[i] & 0xFF;
            out[i * 2] = hex[v >>> 4];
            out[i * 2 + 1] = hex[v & 0x0F];
        }
        return new String(out);
    }

    // ---------------- Session object ----------------

    public static final class Session {
        public final String id;

        private final long ttlMs;
        private volatile long lastSeenMs;
        private volatile long expiresAtMs;

        // attributes
        private final Map<String, String> data = new ConcurrentHashMap<>();

        Session(String id, long ttlMs, long nowMs) {
            this.id = id;
            this.ttlMs = ttlMs;
            touch(nowMs); // sets lastSeen and expiresAt
        }

        /** Sliding expiration: whenever used, refresh expiry to now + ttl. */
        public void touch(long nowMs) {
            lastSeenMs = nowMs;
            expiresAtMs = nowMs + ttlMs;
        }

        public boolean isExpired(long nowMs) {
            return nowMs >= expiresAtMs;
        }

        public long lastSeenMs() {
            return lastSeenMs;
        }

        public long expiresAtMs() {
            return expiresAtMs;
        }

        // Attribute helpers
        public void put(String key, String value) {
            if (key == null) return;
            if (value == null) data.remove(key);
            else data.put(key, value);
        }

        public String get(String key) {
            return data.get(key);
        }

        public void remove(String key) {
            data.remove(key);
        }

        public Map<String, String> snapshot() {
            return new HashMap<>(data);
        }
    }
}
