package io.github.ryf5584.wxdbdump.payload.engine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds captured keys.
 *
 * Two points matter:
 * 1. Every database of an account shares one key, since the key depends only on the uin.
 *    Capturing any single database therefore unlocks them all.
 * 2. Keys are indexed by path and by account; lookup tries the exact path first, then
 */
public final class KeyVault {

    /** One captured key. */
    public static final class DbKey {
        public final String path;
        public final byte[] key;
        public final int pageSize;
        /** WCDB CipherVersion ordinal; version1 is 0. */
        public final int cipherVersion;
        /** Origin: hook, derive or other. */
        public final String source;
        /**
         * Whether the in-process {@code canOpen()} re-check passed.
         *
         * <p><b>This is not a precondition for use.</b> WeChat holds write connections on these
         * databases, so opening a second handle for canOpen() fails with a false negative rather
         * than an invalid key. Real verification happens on the app side by decrypting.
         */
        public final boolean verified;

        public DbKey(String path, byte[] key, int pageSize, int cipherVersion,
                     String source, boolean verified) {
            this.path = path;
            this.key = key;
            this.pageSize = pageSize;
            this.cipherVersion = cipherVersion;
            this.source = source;
            this.verified = verified;
        }

        public String keyHex() {
            return io.github.ryf5584.wxdbdump.payload.Digest.bytesToHex(key);
        }
    }

    private final Map<String, DbKey> byPath = new ConcurrentHashMap<>();
    /** Account directory name to key. */
    private final Map<String, DbKey> byAccount = new ConcurrentHashMap<>();
    /** Key bytes to verification state. */
    private final Map<String, Boolean> verifiedKeys = new ConcurrentHashMap<>();

    /**
     * Stores a key.
     *
     * <p>Verification changed from "must open successfully before being stored" to "stored and
     * flagged, with the app deciding later", because holding a write connection makes
     * canOpen() a false negative and the strict rule rejected databases that decrypt fine.
     */
    public void put(DbKey k) {
        if (k == null || k.path == null || k.key == null || k.key.length == 0) return;
        byPath.put(k.path, k);
        String acc = accountIdOf(k.path);
        if (acc != null) {
            if (byAccount.putIfAbsent(acc, k) == null) {
                io.github.ryf5584.wxdbdump.payload.Log.banner(
                        "ACCOUNT KEY READY",
                        "account=" + acc
                                + "\n来源=" + k.source
                                + "\nkeyLen=" + k.key.length
                                + "\ncanOpen 复验=" + (k.verified ? "通过" : "未通过(可能是 WAL 占用导致的假阴性，不影响解密)")
                                + "\n→ 该账号下**所有**加密库现在都能直接解密，不需要微信逐个打开过");
            }
        }
        if (k.verified) {
            verifiedKeys.put(fingerprint(k.key, k.pageSize), Boolean.TRUE);
        }
    }

    /** Alias for {@link #put}. */
    public void putVerified(DbKey k) {
        put(k);
    }

    /** Exact path hit. */
    public DbKey get(String path) {
        return byPath.get(path);
    }

    /**
     * Looks up a key by path, exact hit first, then account level.
     * The account-level fallback keeps the rest of an account's databases from being
     * skipped: one verified key applies to all of them.
     */
    public DbKey getForPath(String path) {
        DbKey exact = byPath.get(path);
        if (exact != null) return exact;
        String acc = accountIdOf(path);
        return acc == null ? null : byAccount.get(acc);
    }

    /** Whether a usable key exists for the path, exact or account level. */
    public boolean has(String path) {
        return getForPath(path) != null;
    }

    /** Whether the exact path matched; used to distinguish reuse from a direct hit. */
    public boolean hasExact(String path) {
        return byPath.containsKey(path);
    }

    public int size() {
        return byPath.size();
    }

    public int accountCount() {
        return byAccount.size();
    }

    public String describe() {
        return "exact=" + byPath.size() + " account=" + byAccount.size();
    }

    public void clear() {
        byPath.clear();
        byAccount.clear();
        verifiedKeys.clear();
    }

    /** Whether this key and page size were verified before. */
    public boolean isKeyKnownGood(byte[] key, int pageSize) {
        return key != null && verifiedKeys.containsKey(fingerprint(key, pageSize));
    }

    /** Whether this key was verified for any database of an account. */
    public boolean isKeyKnownGoodAnyPageSize(byte[] key) {
        return key != null && (verifiedKeys.containsKey(fingerprint(key, 1024))
                || verifiedKeys.containsKey(fingerprint(key, 4096)));
    }

    /**
     * Extracts the account directory name from a database path.
     * Returns null when the path does not match, in which case no reuse is attempted.
     */
    public static String accountIdOf(String path) {
        if (path == null) return null;
        final String marker = "/MicroMsg/";
        int i = path.indexOf(marker);
        if (i < 0) return null;
        String rest = path.substring(i + marker.length());
        int slash = rest.indexOf('/');
        String seg = slash < 0 ? rest : rest.substring(0, slash);
        return isHex32(seg) ? seg : null;
    }

    private static boolean isHex32(String s) {
        if (s == null || s.length() != 32) return false;
        for (int i = 0; i < 32; i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!ok) return false;
        }
        return true;
    }

    private static String fingerprint(byte[] key, int pageSize) {
        return pageSize + ":" + io.github.ryf5584.wxdbdump.payload.Digest.bytesToHex(key);
    }
}
