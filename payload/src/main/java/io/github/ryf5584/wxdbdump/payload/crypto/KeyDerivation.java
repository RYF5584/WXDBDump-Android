package io.github.ryf5584.wxdbdump.payload.crypto;

import java.nio.charset.StandardCharsets;

import io.github.ryf5584.wxdbdump.payload.Digest;
import io.github.ryf5584.wxdbdump.payload.EngineHost;
import io.github.ryf5584.wxdbdump.payload.Log;
import io.github.ryf5584.wxdbdump.payload.paths.WeChatPaths;

/**
 * Offline key derivation, used by root mode where no Xposed hook is present.
 *
 * <pre>
 *   key = first 7 ASCII characters of md5hex(deviceId + uin)
 *   deviceId = RC4-decrypt(files/KeyInfo.bin) with the fixed key _wEcHAT_
 * </pre>
 *
 * <p>Two things that are easy to get wrong:
 * <ol>
 *   <li>The "device id" here is <b>not the phone's real IMEI</b>. WeChat is a normal app, so on
 *       Android 10+ it cannot read the real one and falls back to a fixed default, which is
 *       what lands in KeyInfo.bin. Always read that file rather than hardcoding a constant —
 *       on older devices it can hold the real IMEI.</li>
 *   <li>The device id belongs to the <b>device</b>, not the account: every account on one
 *       device shares it, so knowing the uin is enough to derive the key.</li>
 * </ol>
 */
public final class KeyDerivation {

    /** RC4 key for KeyInfo.bin. */
    private static final String RC4_KEY = "_wEcHAT_";

    /** Key length: the first 7 characters of the MD5 hex digest. */
    private static final int KEY_CHARS = 7;

    private static volatile String cachedDeviceId;

    private KeyDerivation() {
    }

    /** Clears the cache. For device changes and tests. */
    public static synchronized void reset() {
        cachedDeviceId = null;
    }

    /**
     * Reads the device id WeChat uses for key derivation, or null.
     *
     * <p>Only the length is logged; the value is a persistent device identifier.
     */
    public static String deviceId(EngineHost host) {
        String cached = cachedDeviceId;
        if (cached != null) return cached;

        byte[] enc;
        try {
            enc = host.files().readAll(WeChatPaths.KEY_INFO_BIN);
        } catch (Throwable t) {
            Log.w("derive", "failed to read KeyInfo.bin", t);
            return null;
        }
        if (enc == null || enc.length == 0) {
            Log.w("derive", "KeyInfo.bin is empty, cannot derive a key");
            return null;
        }

        byte[] plain = Rc4.apply(RC4_KEY.getBytes(StandardCharsets.UTF_8), enc);
        String id = new String(plain, StandardCharsets.UTF_8).trim();
        if (id.isEmpty()) {
            Log.w("derive", "KeyInfo.bin decrypted to an empty value");
            return null;
        }
        Log.i("derive", "device id read (length " + id.length() + "), offline derivation available");
        cachedDeviceId = id;
        return id;
    }

    /**
     * Derives the key for one account.
     *
     * @param deviceId result of {@link #deviceId(EngineHost)}
     * @param uin      account uin, must be > 0
     * @return 7-byte ASCII key, or null when the arguments are invalid
     */
    public static byte[] key(String deviceId, int uin) {
        if (deviceId == null || deviceId.isEmpty()) return null;
        if (uin <= 0) {
            Log.w("derive", "invalid uin (" + uin + "), cannot derive a key");
            return null;
        }
        String md5 = Digest.md5Hex(deviceId + uin);
        if (md5 == null || md5.length() < KEY_CHARS) return null;
        return md5.substring(0, KEY_CHARS).getBytes(StandardCharsets.UTF_8);
    }
}
