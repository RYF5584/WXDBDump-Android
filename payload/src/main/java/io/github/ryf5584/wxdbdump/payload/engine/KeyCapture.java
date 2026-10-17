package io.github.ryf5584.wxdbdump.payload.engine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.ryf5584.wxdbdump.payload.Digest;
import io.github.ryf5584.wxdbdump.payload.EngineHost;
import io.github.ryf5584.wxdbdump.payload.Log;

/**
 * Key capture pipeline. The hook callback only copies arguments into a queue; validation
 * happens on a single worker thread, because the callback runs on a database thread.
 *
 * Two rules:
 * 1. Only databases under an account directory (MicroMsg/<32 hex>) are relevant.
 * 2. A failed re-check does not reject a key: canOpen() reports false negatives while
 *    WeChat holds a write connection.
 */
public final class KeyCapture {

    /** Minimum information a callback needs to hand over. */
    public static final class Raw {
        public final String path;
        public final byte[] key;
        public final int pageSize;
        public final int cipherVersion;
        public final String source;

        public Raw(String path, byte[] key, int pageSize, int cipherVersion, String source) {
            this.path = path;
            this.key = key;
            this.pageSize = pageSize;
            this.cipherVersion = cipherVersion;
            this.source = source;
        }
    }

    private final EngineHost host;
    private final KeyVault vault;
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "wxdump-keyverify");
                t.setDaemon(true);
                return t;
            });

    private final AtomicInteger captured = new AtomicInteger();
    private final AtomicInteger verified = new AtomicInteger();
    private final AtomicInteger dropped = new AtomicInteger();

    /** Processed path and key pairs; WeChat calls setCipherKey repeatedly. */
    private final Map<String, Boolean> attempted = new ConcurrentHashMap<>();
    /** Non-account paths are reported once to avoid log spam. */
    private final Map<String, Boolean> ignoredDirs = new ConcurrentHashMap<>();

    public KeyCapture(EngineHost host, KeyVault vault) {
        this.host = host;
        this.vault = vault;
    }

    public int capturedCount() {
        return captured.get();
    }

    public int verifiedCount() {
        return verified.get();
    }

    public int droppedCount() {
        return dropped.get();
    }

    /** Called from the hook. Copies and returns immediately. */
    public void offer(String path, byte[] key, int pageSize, int cipherVersion, String source) {
        if (path == null || key == null || key.length == 0) return;
        captured.incrementAndGet();

        // Drop non-account paths such as EnResDown.db to avoid pointless opens and log noise
        String acc = KeyVault.accountIdOf(path);
        if (acc == null) {
            dropped.incrementAndGet();
            String dir = parentOf(path);
            if (ignoredDirs.putIfAbsent(dir, Boolean.TRUE) == null) {
                Log.d("capture", "ignoring capture outside an account directory: " + path);
            }
            return;
        }

        final Raw raw = new Raw(path, key.clone(), pageSize, cipherVersion, source);
        try {
            worker.execute(() -> handle(raw, acc));
        } catch (Throwable t) {
            Log.e("capture", "failed to enqueue", t);
        }
    }

    private void handle(Raw raw, String accountId) {
        try {
            // Process each path and key pair once; repeated opens slow WeChat down
            String attemptKey = raw.path + "|" + Digest.bytesToHex(raw.key);
            if (attempted.putIfAbsent(attemptKey, Boolean.TRUE) != null) return;

            if (vault.hasExact(raw.path)) return;

            // The account already has a usable key, so this database needs no separate check
            KeyVault.DbKey existing = vault.getForPath(raw.path);
            if (existing != null) {
                Log.d("capture", "account " + accountId + " already has a key, skipping re-check: "
                        + shortName(raw.path) + " (source " + existing.source + ")");
                return;
            }

            WcdbVerifier.Verdict v = WcdbVerifier.verify(host, raw.path, raw.key, raw.pageSize);
            vault.put(new KeyVault.DbKey(
                    raw.path, raw.key, v.ok ? v.pageSize : raw.pageSize,
                    raw.cipherVersion, raw.source, v.ok));
            if (v.ok) {
                verified.incrementAndGet();
                Log.i("capture", "re-check passed pageSize=" + v.pageSize + " -> " + shortName(raw.path));
            } else {
                Log.w(
                        "capture",
                        "re-check failed (does not affect use; canOpen gives false negatives while WeChat holds "
                                + shortName(raw.path) + " :: " + v.detail + "; key stored, the app decides");
            }
        } catch (Throwable t) {
            Log.e("capture", "failed to process key", t);
        }
    }

    private static String shortName(String path) {
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }

    private static String parentOf(String path) {
        int i = path.lastIndexOf('/');
        return i > 0 ? path.substring(0, i) : path;
    }

    public void shutdown() {
        try {
            worker.shutdownNow();
        } catch (Throwable ignored) {
        }
    }
}
