package com.watchnc.core;

import java.util.List;
import java.util.ArrayList;
import java.util.Objects;

/** Sends changed data immediately and repeats unchanged data as a freshness heartbeat. */
public final class UpdateGate {
    private final long heartbeatMillis;
    private List<?> last;
    private long lastSent;
    private long generation;
    public UpdateGate(long heartbeatMillis) {
        if (heartbeatMillis <= 0) throw new IllegalArgumentException("heartbeat must be positive");
        this.heartbeatMillis = heartbeatMillis;
    }
    public synchronized long begin(List<?> data, long now) {
        if (last != null && Objects.equals(last, data) && now >= lastSent && now - lastSent < heartbeatMillis) return 0;
        last = new ArrayList<>(data);
        lastSent = now;
        return ++generation;
    }
    public synchronized void failed(long attempt) {
        if (attempt == generation) last = null;
    }
}
