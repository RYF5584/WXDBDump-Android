package io.github.ryf5584.wxdbdump.payload.proto;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Frame codec for the loopback TCP channel.
 *
 * <p>Format: [4-byte big-endian length][1-byte type][payload]
 * <ul>
 *   <li>0x01: UTF-8 text, the JSON control plane</li>
 *   <li>0x02: raw bytes, the data plane carrying database contents</li>
 * </ul>
 * Frames above {@link #MAX_FRAME} abort the connection to bound memory use.
 */
public final class Wire {

    public static final byte TYPE_TEXT = 0x01;
    public static final byte TYPE_BYTES = 0x02;

    public static final int MAX_FRAME = 8 * 1024 * 1024;

    private Wire() {
    }

    public static final class Frame {
        public final byte type;
        public final byte[] payload;

        public Frame(byte type, byte[] payload) {
            this.type = type;
            this.payload = payload == null ? new byte[0] : payload;
        }

        public String text() {
            return new String(payload, StandardCharsets.UTF_8);
        }
    }

    public static void writeText(OutputStream out, String text) throws IOException {
        write(out, TYPE_TEXT, text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8));
    }

    public static void writeBytes(OutputStream out, byte[] data) throws IOException {
        write(out, TYPE_BYTES, data);
    }

    public static void write(OutputStream out, byte type, byte[] payload) throws IOException {
        byte[] p = payload == null ? new byte[0] : payload;
        synchronized (out) {
            out.write((p.length >>> 24) & 0xFF);
            out.write((p.length >>> 16) & 0xFF);
            out.write((p.length >>> 8) & 0xFF);
            out.write(p.length & 0xFF);
            out.write(type);
            out.write(p);
            out.flush();
        }
    }

    public static Frame read(InputStream in) throws IOException {
        DataInputStream din = new DataInputStream(in);
        int len = din.readInt();
        if (len < 0 || len > MAX_FRAME) throw new IOException("invalid frame length " + len);
        byte type = din.readByte();
        byte[] payload = new byte[len];
        din.readFully(payload);
        return new Frame(type, payload);
    }
}
