package com.watchnc.core;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class FocasTest {
    private static byte[] frame(int type, byte[] body) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); DataOutputStream d = new DataOutputStream(b);
        d.writeInt(0xa0a0a0a0); d.writeShort(2); d.writeShort(type); d.writeShort(body.length); d.write(body); return b.toByteArray();
    }
    private static byte[] result(int device, int command, byte[] payload) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); DataOutputStream d = new DataOutputStream(b);
        d.writeShort(1); d.writeShort(16 + payload.length); d.writeShort(device); d.writeShort(1); d.writeShort(command);
        d.writeShort(0); d.writeInt(0); d.writeShort(payload.length); d.write(payload); return frame(0x2102, b.toByteArray());
    }
    private static byte[] readPacket(DataInputStream in) throws IOException {
        byte[] head = new byte[10]; in.readFully(head);
        int len = ((head[8] & 255) << 8) | (head[9] & 255); byte[] body = new byte[len]; in.readFully(body);
        ByteArrayOutputStream b = new ByteArrayOutputStream(); b.write(head); b.write(body); return b.toByteArray();
    }
    private static String hex(byte[] bytes) { StringBuilder b = new StringBuilder(); for (byte x : bytes) b.append(String.format("%02x", x & 255)); return b.toString(); }
    @Test public void identityAndFixedRelayReadHandleFragmentedFrames() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            ExecutorService exec = Executors.newSingleThreadExecutor();
            Future<?> future = exec.submit(() -> {
                try (Socket s = server.accept()) {
                    DataInputStream in = new DataInputStream(s.getInputStream()); OutputStream out = s.getOutputStream();
                    assertEquals("a0a0a0a00001010100020002", hex(readPacket(in)));
                    out.write(frame(0x0102, new byte[]{0,8}));
                    readPacket(in);
                    ByteArrayOutputStream info = new ByteArrayOutputStream(); DataOutputStream d = new DataOutputStream(info);
                    d.writeShort(0); d.writeShort(8); d.writeBytes("0 M D4G313.003");
                    byte[] identity = result(1, 0x18, info.toByteArray());
                    for (byte c : identity) { out.write(c); out.flush(); }
                    assertEquals("a0a0a0a000012101001e0001001c0002000180010000002000000020000000050000000000000000", hex(readPacket(in)));
                    out.write(result(2, 0x8001, new byte[]{(byte)0xd0}));
                    readPacket(in); out.write(frame(0x0202, new byte[0]));
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            try (Socket s = new Socket("127.0.0.1", server.getLocalPort()); Focas cnc = new Focas(s)) {
                cnc.verifyController(); assertEquals(0xd0, cnc.readRelay());
            }
            future.get(5, TimeUnit.SECONDS); exec.shutdownNow();
        }
    }
    @Test public void rejectsUnsupportedControllerBeforeRelayRead() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            ExecutorService exec = Executors.newSingleThreadExecutor();
            try {
                Future<?> future = exec.submit(() -> {
                    try (Socket s = server.accept()) {
                        DataInputStream in = new DataInputStream(s.getInputStream()); OutputStream out = s.getOutputStream();
                        readPacket(in); out.write(frame(0x0102, new byte[]{0,8}));
                        readPacket(in);
                        ByteArrayOutputStream info = new ByteArrayOutputStream(); DataOutputStream d = new DataOutputStream(info);
                        d.writeShort(0); d.writeShort(8); d.writeBytes("0 M D4G399.003");
                        out.write(result(1, 0x18, info.toByteArray()));
                        assertEquals("a0a0a0a0000102010000", hex(readPacket(in)));
                        out.write(frame(0x0202, new byte[0]));
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                try (Socket s = new Socket("127.0.0.1", server.getLocalPort()); Focas cnc = new Focas(s)) {
                    try { cnc.verifyController(); fail("Unexpectedly accepted unsupported controller"); }
                    catch (IOException expected) { assertTrue(expected.getMessage().contains("identity")); }
                }
                future.get(5, TimeUnit.SECONDS);
            } finally { exec.shutdownNow(); }
        }
    }
}
