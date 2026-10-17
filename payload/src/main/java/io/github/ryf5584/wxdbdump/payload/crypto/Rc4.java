package io.github.ryf5584.wxdbdump.payload.crypto;

/** RC4 stream cipher. KeyInfo.bin is encrypted with it under a fixed key. */
public final class Rc4 {

    private Rc4() {
    }

    public static byte[] apply(byte[] key, byte[] data) {
        if (key == null || key.length == 0 || data == null) return new byte[0];

        int[] s = new int[256];
        for (int i = 0; i < 256; i++) s[i] = i;
        for (int i = 0, j = 0; i < 256; i++) {
            j = (j + s[i] + (key[i % key.length] & 0xFF)) & 0xFF;
            int t = s[i];
            s[i] = s[j];
            s[j] = t;
        }

        byte[] out = new byte[data.length];
        for (int i = 0, j = 0, n = 0; n < data.length; n++) {
            i = (i + 1) & 0xFF;
            j = (j + s[i]) & 0xFF;
            int t = s[i];
            s[i] = s[j];
            s[j] = t;
            out[n] = (byte) (data[n] ^ s[(s[i] + s[j]) & 0xFF]);
        }
        return out;
    }
}
