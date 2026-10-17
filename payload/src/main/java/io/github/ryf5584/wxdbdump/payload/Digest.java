package io.github.ryf5584.wxdbdump.payload;

import java.security.MessageDigest;

/**
 * Digest and encoding helpers (pure Java).
 *
 * <p>{@link #md5Hex(byte[])} is equivalent to WeChat's internal {@code ak.k.g(byte[])}:
 * 32 lowercase hex characters. Account directory names and database keys both depend on
 * it, so its correctness decides whether decryption works at all.
 *
 * <p>One of the invariants that ignore the WeChat version entirely: as long as old data
 * must stay readable, this derivation cannot change.
 */
public final class Digest {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Digest() {
    }

    /** 32-character lowercase hex MD5. */
    public static String md5Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(data == null ? new byte[0] : data);
            byte[] d = md.digest();
            char[] out = new char[d.length * 2];
            for (int i = 0; i < d.length; i++) {
                int v = d[i] & 0xFF;
                out[i * 2] = HEX[v >>> 4];
                out[i * 2 + 1] = HEX[v & 0x0F];
            }
            return new String(out);
        } catch (Throwable t) {
            throw new IllegalStateException("MD5 not available", t);
        }
    }

    public static String md5Hex(String s) {
        try {
            return md5Hex(s.getBytes("UTF-8"));
        } catch (Throwable t) {
            return md5Hex(s.getBytes());
        }
    }

    /** Hex string to byte array. */
    public static byte[] hexToBytes(String hex) {
        if (hex == null) return new byte[0];
        int n = hex.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public static String bytesToHex(byte[] b) {
        if (b == null) return "";
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            int v = b[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
