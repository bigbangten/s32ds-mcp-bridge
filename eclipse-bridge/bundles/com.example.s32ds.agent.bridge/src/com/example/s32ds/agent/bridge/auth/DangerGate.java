package com.example.s32ds.agent.bridge.auth;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory gate that controls whether mutating debug operations are allowed.
 *
 * <p>Default: OFF. Bridge restart = OFF. TTL-based auto-expiry so the gate
 * doesn't stay open forever — typical session is 30 minutes.
 *
 * <p>Why in-memory and not persisted: a gate that survives restart is a footgun.
 * If the user crashes mid-session and forgets they enabled it, the next session
 * inherits write access. Forcing re-enable on every bridge boot is the right
 * default for "you can flash the chip" privileges.
 */
public final class DangerGate {

    /** Default TTL when caller doesn't specify. */
    public static final long DEFAULT_TTL_MS = 30L * 60L * 1000L;
    /** Hard ceiling to prevent runaway "always on". */
    public static final long MAX_TTL_MS = 4L * 60L * 60L * 1000L;

    /** Epoch millis at which the gate expires. 0 = disabled. */
    private static final AtomicLong enabledUntil = new AtomicLong(0L);

    private DangerGate() {}

    /** Returns true iff the gate is currently open. Cheap, lock-free. */
    public static boolean isOn() {
        long until = enabledUntil.get();
        return until > 0L && System.currentTimeMillis() < until;
    }

    /**
     * Open the gate for the requested duration (clamped to {@link #MAX_TTL_MS}).
     * Passing ttl &lt;= 0 falls back to {@link #DEFAULT_TTL_MS}.
     *
     * @return absolute epoch millis at which the gate will close
     */
    public static long enable(long ttlMs) {
        long clamped = ttlMs <= 0 ? DEFAULT_TTL_MS : Math.min(ttlMs, MAX_TTL_MS);
        long until = System.currentTimeMillis() + clamped;
        enabledUntil.set(until);
        return until;
    }

    public static void disable() {
        enabledUntil.set(0L);
    }

    /** Milliseconds remaining; 0 if already off/expired. */
    public static long remainingMs() {
        long until = enabledUntil.get();
        long now = System.currentTimeMillis();
        return until > now ? until - now : 0L;
    }

    /** Snapshot for /danger/state endpoint. */
    public static Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        long until = enabledUntil.get();
        long now = System.currentTimeMillis();
        boolean on = until > now;
        m.put("enabled", on);
        m.put("remainingMs", on ? until - now : 0L);
        m.put("expiresAtEpochMs", on ? until : 0L);
        m.put("defaultTtlMs", DEFAULT_TTL_MS);
        m.put("maxTtlMs", MAX_TTL_MS);
        return m;
    }
}
