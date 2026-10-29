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
    private static byte[] errorResult(int device, int command, int code) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); DataOutputStream d = new DataOutputStream(b);
        d.writeShort(1); d.writeShort(10); d.writeShort(device); d.writeShort(1); d.writeShort(command); d.writeShort(code);
        return frame(0x2102, b.toByteArray());
    }
    private static byte[] ints(int... values) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); DataOutputStream d = new DataOutputStream(b);
        for (int value : values) d.writeInt(value);
        return b.toByteArray();
    }
    private static byte[] timerParam(int id, int value) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); DataOutputStream d = new DataOutputStream(b);
        d.writeInt(id); d.writeShort(0); d.writeShort(3); d.writeInt(value); return b.toByteArray();
    }
    @Test public void readsSpindleToolCandidateAndRejectsMalformedResponse() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            ExecutorService exec = Executors.newSingleThreadExecutor();
            try {
                Future<?> future = exec.submit(() -> {
                    try (Socket s = server.accept()) {
                        DataInputStream in = new DataInputStream(s.getInputStream()); OutputStream out = s.getOutputStream();
                        readPacket(in); out.write(frame(0x0102, new byte[]{0,8}));
                        for (byte[] response : new byte[][]{{10}, {6}, {6, 0}}) {
                            java.nio.ByteBuffer packet = java.nio.ByteBuffer.wrap(readPacket(in));
                            assertEquals(2, packet.getShort(14) & 0xffff);
                            assertEquals(0x8001, packet.getShort(18) & 0xffff);
                            assertEquals(450, packet.getInt(20));
                            assertEquals(450, packet.getInt(24));
                            assertEquals(9, packet.getInt(28));
                            out.write(result(2, 0x8001, response));
                        }
                        readPacket(in); out.write(frame(0x0202, new byte[0]));
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                try (Socket s = new Socket("127.0.0.1", server.getLocalPort()); Focas cnc = new Focas(s)) {
                    assertEquals(10, cnc.readSpindleTool());
                    assertEquals(6, cnc.readSpindleTool());
                    try { cnc.readSpindleTool(); fail("Expected invalid response"); }
                    catch (Focas.InvalidTelemetryException expected) { }
                }
                future.get(5, TimeUnit.SECONDS);
            } finally { exec.shutdownNow(); }
        }
    }
    @Test public void timerFallsBackToLongParameterReadAndRemembersCommand() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            ExecutorService exec = Executors.newSingleThreadExecutor();
            try {
                Future<?> future = exec.submit(() -> {
                    try (Socket s = server.accept()) {
                        DataInputStream in = new DataInputStream(s.getInputStream()); OutputStream out = s.getOutputStream();
                        readPacket(in); out.write(frame(0x0102, new byte[]{0,8}));
                        byte[] request = readPacket(in);
                        assertEquals(0x0e, java.nio.ByteBuffer.wrap(request).getShort(18) & 0xffff);
                        out.write(errorResult(1, 0x0e, 1));
                        for (int[] item : new int[][]{{6758,3}, {6757,38352}, {6758,3}, {6758,3}, {6757,39000}, {6758,3}}) {
                            request = readPacket(in);
                            java.nio.ByteBuffer packet = java.nio.ByteBuffer.wrap(request);
                            assertEquals(0x8d, packet.getShort(18) & 0xffff);
                            assertEquals(item[0], packet.getInt(20));
                            assertEquals(item[0], packet.getInt(24));
                            out.write(result(1, 0x8d, java.util.Arrays.copyOf(timerParam(item[0], item[1]), 264)));
                        }
                        readPacket(in); out.write(frame(0x0202, new byte[0]));
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                try (Socket s = new Socket("127.0.0.1", server.getLocalPort()); Focas cnc = new Focas(s)) {
                    assertEquals(218352L, cnc.readCycleTimeMillis());
                    assertEquals(219000L, cnc.readCycleTimeMillis());
                }
                future.get(5, TimeUnit.SECONDS);
            } finally { exec.shutdownNow(); }
        }
    }
    @Test public void readsTimerComponentsAndRejectsMinuteRollover() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            ExecutorService exec = Executors.newSingleThreadExecutor();
            try {
                Future<?> future = exec.submit(() -> {
                    try (Socket s = server.accept()) {
                        DataInputStream in = new DataInputStream(s.getInputStream()); OutputStream out = s.getOutputStream();
                        readPacket(in); out.write(frame(0x0102, new byte[]{0,8}));
                        for (int[] item : new int[][]{{6758,2},{6757,3456},{6758,2},{6758,2},{6757,59999},{6758,3}}) {
                            byte[] request = readPacket(in);
                            java.nio.ByteBuffer packet = java.nio.ByteBuffer.wrap(request);
                            assertEquals(0x0e, packet.getShort(18) & 0xffff);
                            assertEquals(item[0], packet.getInt(20));
                            assertEquals(item[0], packet.getInt(24));
                            assertEquals(0, packet.getInt(28));
                            out.write(result(1, 0x0e, timerParam(item[0], item[1])));
                        }
                        readPacket(in); out.write(frame(0x0202, new byte[0]));
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                try (Socket socket = new Socket("127.0.0.1", server.getLocalPort()); Focas cnc = new Focas(socket)) {
                    assertEquals(123456L, cnc.readCycleTimeMillis());
                    try { cnc.readCycleTimeMillis(); fail("Expected inconsistent timer"); } catch (Focas.InvalidTelemetryException expected) { }
                }
                future.get(5, TimeUnit.SECONDS);
            } finally { exec.shutdownNow(); }
        }
    }
    private static void expectCncRead(DataInputStream in, int command, int firstArg) throws IOException {
        String expected = "a0a0a0a000012101001e0001001c00010001" + String.format("%04x%08x", command, firstArg) + "00000000000000000000000000000000";
        assertEquals(expected, hex(readPacket(in)));
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
                assertTrue(cnc.verifyController()); assertEquals(0xd0, cnc.readRelay());
            }
            future.get(5, TimeUnit.SECONDS); exec.shutdownNow();
        }
    }
    @Test public void acceptsOtherControllerAndReadsConfiguredRelayOnly() throws Exception {
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
                        assertEquals("a0a0a0a000012101001e0001001c000200018001000004d2000004d2000000050000000000000000", hex(readPacket(in)));
                        out.write(result(2, 0x8001, new byte[]{0x41}));
                        assertEquals("a0a0a0a0000102010000", hex(readPacket(in)));
                        out.write(frame(0x0202, new byte[0]));
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                try (Socket s = new Socket("127.0.0.1", server.getLocalPort()); Focas cnc = new Focas(s)) {
                    assertFalse(cnc.verifyController());
                    assertEquals(0x41, cnc.readRelay(1234));
                    try { cnc.readRelay(-1); fail("Expected invalid relay address"); }
                    catch (IllegalArgumentException expected) { }
                    try { cnc.readRelay(65536); fail("Expected invalid relay address"); }
                    catch (IllegalArgumentException expected) { }
                }
                future.get(5, TimeUnit.SECONDS);
            } finally { exec.shutdownNow(); }
        }
    }
    @Test public void rejectsMalformedControllerIdentityBeforeRelayRead() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            ExecutorService exec = Executors.newSingleThreadExecutor();
            try {
                Future<?> future = exec.submit(() -> {
                    try (Socket s = server.accept()) {
                        DataInputStream in = new DataInputStream(s.getInputStream()); OutputStream out = s.getOutputStream();
                        readPacket(in); out.write(frame(0x0102, new byte[]{0,8}));
                        readPacket(in);
                        out.write(result(1, 0x18, new byte[18]));
                        assertEquals("a0a0a0a0000102010000", hex(readPacket(in)));
                        out.write(frame(0x0202, new byte[0]));
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                try (Socket s = new Socket("127.0.0.1", server.getLocalPort()); Focas cnc = new Focas(s)) {
                    try { cnc.verifyController(); fail("Expected malformed identity"); }
                    catch (IOException expected) { assertTrue(expected.getMessage().contains("identity")); }
                }
                future.get(5, TimeUnit.SECONDS);
            } finally { exec.shutdownNow(); }
        }
    }
    @Test public void readsTelemetryAndKeepsSessionAfterControllerErrors() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            ExecutorService exec = Executors.newSingleThreadExecutor();
            try {
                Future<?> future = exec.submit(() -> {
                    try (Socket s = server.accept()) {
                        DataInputStream in = new DataInputStream(s.getInputStream()); OutputStream out = s.getOutputStream();
                        readPacket(in); out.write(frame(0x0102, new byte[]{0,8}));
                        expectCncRead(in, 0x1c, 0); out.write(result(1, 0x1c, ints(1501, 1502)));
                        expectCncRead(in, 0x25, 0); out.write(result(1, 0x25, new byte[]{0,0,12,(byte)0xaf,0,10,0,0}));
                        expectCncRead(in, 0x24, 0); out.write(result(1, 0x24, new byte[]{0,0,9,(byte)0x88,0,10,0,2}));
                        expectCncRead(in, 0x19, 0); out.write(result(1, 0x19, new byte[]{0,1,0,3,0,1,0,0,0,0,0,0,0,23}));
                        expectCncRead(in, 0x1a, 0); out.write(result(1, 0x1a, ints(0x80000010)));
                        expectCncRead(in, 0x1d, 0); out.write(result(1, 0x1d, ints(1234)));
                        expectCncRead(in, 0x1d, 0); out.write(result(1, 0x1d, ints(-1)));
                        expectCncRead(in, 0x1d, 0); out.write(errorResult(1, 0x1d, 6));
                        expectCncRead(in, 0x25, 0); out.write(errorResult(1, 0x25, 6));
                        expectCncRead(in, 0x24, 0); out.write(errorResult(1, 0x24, -3));
                        expectCncRead(in, 0x1c, 0); out.write(result(1, 0x1c, ints(1234, 1234)));
                        readPacket(in); out.write(frame(0x0202, new byte[0]));
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                try (Socket s = new Socket("127.0.0.1", server.getLocalPort()); Focas cnc = new Focas(s)) {
                    assertArrayEquals(new int[]{1501,1502}, cnc.readProgram());
                    assertEquals(3247.0, cnc.readSpindle(), 0.0001);
                    assertEquals(24.4, cnc.readFeed(), 0.0001);
                    assertArrayEquals(new int[]{1,3,1,0,0,0,23}, cnc.readStatus());
                    assertEquals(0x80000010L, cnc.readAlarm());
                    assertEquals(1234L, cnc.readSequence());
                    assertEquals(0xffffffffL, cnc.readSequence());
                    try { cnc.readSequence(); fail("Expected unsupported sequence read"); }
                    catch (Focas.UnsupportedReadException expected) { assertEquals(6, expected.code); }
                    try { cnc.readSpindle(); fail("Expected unsupported"); }
                    catch (Focas.UnsupportedReadException expected) { assertEquals(6, expected.code); }
                    try { cnc.readFeed(); fail("Expected controller error"); }
                    catch (Focas.ControllerException expected) { assertEquals(-3, expected.code); }
                    assertArrayEquals(new int[]{1234,1234}, cnc.readProgram());
                }
                future.get(5, TimeUnit.SECONDS);
            } finally { exec.shutdownNow(); }
        }
    }
    @Test public void rejectsMalformedReadings() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            ExecutorService exec = Executors.newSingleThreadExecutor();
            try {
                Future<?> future = exec.submit(() -> {
                    try (Socket s = server.accept()) {
                        DataInputStream in = new DataInputStream(s.getInputStream()); OutputStream out = s.getOutputStream();
                        readPacket(in); out.write(frame(0x0102, new byte[]{0,8}));
                        expectCncRead(in, 0x24, 0); out.write(result(1, 0x24, new byte[]{0,0,0,1,0,3,0,2}));
                        expectCncRead(in, 0x25, 0); out.write(result(1, 0x25, new byte[]{0,0,0,1,0,10,(byte)0xff,(byte)0xff}));
                        expectCncRead(in, 0x1d, 0); out.write(result(1, 0x1d, new byte[]{0,0,0}));
                        expectCncRead(in, 0x1d, 0); out.write(result(1, 0x1d, new byte[8]));
                        expectCncRead(in, 0x1c, 0); out.write(result(1, 0x1c, new byte[7]));
                        assertEquals("a0a0a0a000012101001e0001001c0002000180010000002000000020000000050000000000000000", hex(readPacket(in)));
                        out.write(result(2, 0x8001, new byte[]{0x40}));
                        readPacket(in); out.write(frame(0x0202, new byte[0]));
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                try (Socket s = new Socket("127.0.0.1", server.getLocalPort()); Focas cnc = new Focas(s)) {
                    try { cnc.readFeed(); fail("Expected invalid base"); } catch (Focas.InvalidTelemetryException expected) { }
                    try { cnc.readSpindle(); fail("Expected sentinel rejection"); } catch (Focas.InvalidTelemetryException expected) { }
                    try { cnc.readSequence(); fail("Expected short sequence"); } catch (Focas.InvalidTelemetryException expected) { }
                    try { cnc.readSequence(); fail("Expected long sequence"); } catch (Focas.InvalidTelemetryException expected) { }
                    try { cnc.readProgram(); fail("Expected invalid program"); } catch (Focas.InvalidTelemetryException expected) { }
                    assertEquals(0x40, cnc.readRelay());
                }
                future.get(5, TimeUnit.SECONDS);
            } finally { exec.shutdownNow(); }
        }
    }
}
