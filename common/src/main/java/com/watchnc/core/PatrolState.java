package com.watchnc.core;

public final class PatrolState {
    private PatrolState() {}
    public static boolean fresh(long sample, long now) {
        long age = now - sample;
        return sample > 0 && age >= -5000 && age <= 15000;
    }
    public static int normalize(int relay) { return relay < 0 ? -1 : relay & 0xd0; }
    public static boolean acceptSample(long sample, long previous, long now) {
        return fresh(sample, now) && (!fresh(previous, now) || sample > previous);
    }
    public static boolean changedKnown(int previous, int current) {
        return previous >= 0 && current >= 0 && normalize(previous) != normalize(current);
    }
    public static String shortLabel(int relay) {
        relay = normalize(relay);
        if (relay < 0) return "?";
        if (relay == 0) return "OFF";
        return ((relay & 0x80) != 0 ? "R" : "") + ((relay & 0x10) != 0 ? "Y" : "") + ((relay & 0x40) != 0 ? "G" : "");
    }
}
