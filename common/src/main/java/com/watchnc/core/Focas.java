package com.watchnc.core;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Fixed read-only subset of the seenc FOCAS wire protocol. */
public final class Focas implements Closeable {
    /** A well-formed response containing a controller error code. */
    public static class ControllerException extends IOException {
        public final int code;
        public ControllerException(int code) { super("Controller error " + code); this.code = code; }
    }
    /** The controller reported that an optional CNC read is unavailable. */
    public static final class UnsupportedReadException extends ControllerException {
        public UnsupportedReadException() { super(6); }
    }
    /** A complete optional response had an unusable payload; the session can continue. */
    public static final class InvalidTelemetryException extends IOException {
        public InvalidTelemetryException(String message) { super(message); }
    }
    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private boolean session;
    private int timerParameterCommand = 0x0e;
    public Focas(Socket socket) throws IOException {
        this.socket = socket;
        in = new DataInputStream(socket.getInputStream());
        out = new DataOutputStream(socket.getOutputStream());
        exchange(0x0101, new byte[]{0,2}, 0x0102);
        session = true;
    }
    private byte[] exchange(int kind, byte[] body, int expected) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream(10 + body.length);
        DataOutputStream packet = new DataOutputStream(frame);
        packet.writeInt(0xa0a0a0a0); packet.writeShort(1); packet.writeShort(kind); packet.writeShort(body.length); packet.write(body);
        out.write(frame.toByteArray()); out.flush();
        if (in.readInt() != 0xa0a0a0a0) throw new IOException("Invalid FOCAS prefix");
        int origin = in.readUnsignedShort(), type = in.readUnsignedShort(), length = in.readUnsignedShort();
        if (origin <= 1 || type != expected || length > 16384) throw new IOException("Invalid FOCAS frame");
        byte[] result = new byte[length]; in.readFully(result); return result;
    }
    private byte[] read(int device, int command, int a, int b, int c) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream d = new DataOutputStream(bytes);
        d.writeShort(1); d.writeShort(28); d.writeShort(device); d.writeShort(1); d.writeShort(command);
        d.writeInt(a); d.writeInt(b); d.writeInt(c); d.writeInt(0); d.writeInt(0);
        byte[] response = exchange(0x2101, bytes.toByteArray(), 0x2102);
        DataInputStream r = new DataInputStream(new ByteArrayInputStream(response));
        if (response.length < 12 || r.readUnsignedShort() != 1 || r.readUnsignedShort() != response.length - 2 || r.readUnsignedShort() != device || r.readUnsignedShort() != 1 || r.readUnsignedShort() != command) throw new IOException("Invalid response");
        int code = r.readShort();
        if (device == 1 && code == 6) throw new UnsupportedReadException();
        if (code != 0) throw new ControllerException(code);
        if (response.length < 18 || r.readInt() != 0) throw new IOException("Invalid response metadata");
        int size = r.readUnsignedShort(); if (size != r.available()) throw new IOException("Invalid response length");
        byte[] payload = new byte[size]; r.readFully(payload); return payload;
    }
    /** Validate the identity response; return whether it matches the initially tested controller. */
    public boolean verifyController() throws IOException {
        byte[] data = read(1, 0x18, 0, 0, 0);
        if (data.length != 18) throw new IOException("Invalid controller identity");
        for (int i = 4; i < 16; i++)
            if ((data[i] & 255) < 0x20 || (data[i] & 255) > 0x7e) throw new IOException("Invalid controller identity");
        String type = new String(data, 4, 2, StandardCharsets.US_ASCII).trim();
        String machine = new String(data, 6, 2, StandardCharsets.US_ASCII).trim();
        String series = new String(data, 8, 4, StandardCharsets.US_ASCII).trim();
        String version = new String(data, 12, 4, StandardCharsets.US_ASCII).trim();
        if (!printableIdentity(type) || !printableIdentity(machine) || !printableIdentity(series) || !printableIdentity(version))
            throw new IOException("Invalid controller identity");
        return type.equals("0") && machine.equals("M") && series.equals("D4G3") && version.equals("13.0");
    }
    private static boolean printableIdentity(String value) {
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++)
            if (value.charAt(i) < 0x20 || value.charAt(i) > 0x7e) return false;
        return true;
    }
    public int readRelay() throws IOException {
        return readRelay(32);
    }
    /** Read one R-area byte; caller must verify its lamp mapping on each machine. */
    public int readRelay(int address) throws IOException {
        if (address < 0 || address > 65535) throw new IllegalArgumentException("R address must be 0–65535");
        byte[] data = read(2, 0x8001, address, address, 5);
        if (data.length != 1) throw new IOException("Invalid R relay response");
        return data[0] & 255;
    }
    /** Program numbers reported by the controller: running program, then main program. */
    public int[] readProgram() throws IOException {
        byte[] data = read(1, 0x1c, 0, 0, 0);
        if (data.length != 8) throw new InvalidTelemetryException("Invalid program response");
        DataInputStream r = new DataInputStream(new ByteArrayInputStream(data));
        return new int[]{r.readInt(), r.readInt()};
    }
    public double readSpindle() throws IOException { return readScaled(0x25); }
    public double readFeed() throws IOException { return readScaled(0x24); }
    /** D450 spindle-tool candidate, observed on one DNM 4000; requires further validation. */
    public long readSpindleTool() throws IOException {
        byte[] data = read(2, 0x8001, 450, 450, 9);
        if (data.length != 1) throw new InvalidTelemetryException("Invalid spindle-tool response length " + data.length);
        return data[0] & 255;
    }
    private double readScaled(int command) throws IOException {
        return decodeScaled(read(1, command, 0, 0, 0));
    }
    private double decodeScaled(byte[] data) throws IOException {
        if (data.length != 8) throw new InvalidTelemetryException("Invalid scaled reading length");
        int base = data[5] & 255, exponent = data[7] & 255;
        if ((base != 2 && base != 10) || ((data[6] & 255) == 255 && exponent == 255))
            throw new InvalidTelemetryException("Invalid scaled reading format");
        int raw = new DataInputStream(new ByteArrayInputStream(data)).readInt();
        double value = raw / Math.pow(base, exponent);
        if (!Double.isFinite(value)) throw new InvalidTelemetryException("Invalid scaled reading value");
        return value;
    }
    /** Seven raw controller status words; their meaning is controller-specific. */
    public int[] readStatus() throws IOException {
        byte[] data = read(1, 0x19, 0, 0, 0);
        if (data.length != 14) throw new InvalidTelemetryException("Invalid controller status length");
        DataInputStream r = new DataInputStream(new ByteArrayInputStream(data));
        int[] words = new int[7];
        for (int i = 0; i < words.length; i++) words[i] = r.readUnsignedShort();
        return words;
    }
    /** Raw alarm bitfield; a zero value does not prove the absence of alarms. */
    public long readAlarm() throws IOException {
        byte[] data = read(1, 0x1a, 0, 0, 0);
        if (data.length != 4) throw new InvalidTelemetryException("Invalid alarm bitfield length");
        return new DataInputStream(new ByteArrayInputStream(data)).readInt() & 0xffffffffL;
    }
    /** N sequence read matched the controller in an initial test; broader verification is needed. */
    public long readSequence() throws IOException {
        byte[] data = read(1, 0x1d, 0, 0, 0);
        if (data.length != 4) throw new InvalidTelemetryException("Invalid sequence response length: " + data.length);
        return new DataInputStream(new ByteArrayInputStream(data)).readInt() & 0xffffffffL;
    }
    /** Experimental non-axis timer parameters; controller semantics require verification. */
    private long readTimerParameter(int parameter) throws IOException {
        byte[] data;
        int command = timerParameterCommand;
        try {
            data = read(1, command, parameter, parameter, 0);
        } catch (ControllerException error) {
            if (command != 0x0e || error.code != 1) throw error;
            command = 0x8d;
            data = read(1, command, parameter, parameter, 0);
        }
        if (command == 0x8d ? data.length != 264 : data.length != 12 && data.length != 136)
            throw new InvalidTelemetryException("Invalid timer parameter length");
        DataInputStream r = new DataInputStream(new ByteArrayInputStream(data));
        int number = r.readInt(), axis = r.readShort(), type = r.readUnsignedShort();
        if (number != parameter || axis != 0 || type != 3) throw new InvalidTelemetryException("Unexpected timer parameter format");
        int value = r.readInt();
        if (value < 0) throw new InvalidTelemetryException("Invalid timer parameter value");
        timerParameterCommand = command;
        return value;
    }
    public long readCycleTimeMillis() throws IOException {
        long minutes = readTimerParameter(6758);
        long milliseconds = readTimerParameter(6757);
        long nextMinutes = readTimerParameter(6758);
        if (minutes != nextMinutes || milliseconds >= 60000) throw new InvalidTelemetryException("Timer changed while reading");
        return minutes * 60000L + milliseconds;
    }
    public void close() throws IOException {
        try { if (session && !socket.isClosed()) exchange(0x0201, new byte[0], 0x0202); }
        finally { session = false; socket.close(); }
    }
}
