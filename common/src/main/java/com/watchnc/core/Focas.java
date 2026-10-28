package com.watchnc.core;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Fixed read-only subset of the seenc FOCAS wire protocol. */
public final class Focas implements Closeable {
    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private boolean session;
    public Focas(Socket socket) throws IOException {
        this.socket = socket;
        in = new DataInputStream(socket.getInputStream());
        out = new DataOutputStream(socket.getOutputStream());
        exchange(0x0101, new byte[]{0,2}, 0x0102);
        session = true;
    }
    private byte[] exchange(int kind, byte[] body, int expected) throws IOException {
        out.writeInt(0xa0a0a0a0); out.writeShort(1); out.writeShort(kind); out.writeShort(body.length); out.write(body); out.flush();
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
        int code = r.readShort(); if (code != 0) throw new IOException("Controller error " + code);
        if (response.length < 18 || r.readInt() != 0) throw new IOException("Invalid response metadata");
        int size = r.readUnsignedShort(); if (size != r.available()) throw new IOException("Invalid response length");
        byte[] payload = new byte[size]; r.readFully(payload); return payload;
    }
    public void verifyController() throws IOException {
        byte[] data = read(1, 0x18, 0, 0, 0);
        if (data.length != 18) throw new IOException("Invalid controller identity");
        String type = new String(data, 4, 2, StandardCharsets.US_ASCII).trim();
        String machine = new String(data, 6, 2, StandardCharsets.US_ASCII).trim();
        String series = new String(data, 8, 4, StandardCharsets.US_ASCII).trim();
        String version = new String(data, 12, 4, StandardCharsets.US_ASCII).trim();
        if (!type.equals("0") || !machine.equals("M") || !series.equals("D4G3") || !version.equals("13.0")) throw new IOException("Controller identity does not match Doosan D4G3/13.0");
    }
    public int readRelay() throws IOException {
        byte[] data = read(2, 0x8001, 32, 32, 5);
        if (data.length != 1) throw new IOException("Invalid R0032 response");
        return data[0] & 255;
    }
    public void close() throws IOException {
        try { if (session && !socket.isClosed()) exchange(0x0201, new byte[0], 0x0202); }
        finally { session = false; socket.close(); }
    }
}
