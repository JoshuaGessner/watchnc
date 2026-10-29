package com.watchnc.core;

/** A single PMC R-byte mapping; returned flags use WatchNC's internal color masks. */
public final class SignalMapping {
    public final int address, red, yellow, green;
    public final boolean activeLow;
    public SignalMapping(int address, int red, int yellow, int green, boolean activeLow) {
        if (address < 0 || address > 65535 || red < 0 || red > 7 || yellow < 0 || yellow > 7 || green < 0 || green > 7
                || red == yellow || red == green || yellow == green) throw new IllegalArgumentException("Invalid signal mapping");
        this.address = address; this.red = red; this.yellow = yellow; this.green = green; this.activeLow = activeLow;
    }
    public int normalize(int raw) {
        if (raw < 0 || raw > 255) throw new IllegalArgumentException("Invalid PMC byte");
        int value = activeLow ? raw ^ 255 : raw;
        return ((value & (1 << red)) != 0 ? 0x80 : 0) | ((value & (1 << yellow)) != 0 ? 0x10 : 0)
                | ((value & (1 << green)) != 0 ? 0x40 : 0);
    }
}
