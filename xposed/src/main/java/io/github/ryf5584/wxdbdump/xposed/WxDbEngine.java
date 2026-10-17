package io.github.ryf5584.wxdbdump.xposed;

import java.util.List;

import io.github.ryf5584.wxdbdump.payload.EngineHost;
import io.github.ryf5584.wxdbdump.payload.Log;
import io.github.ryf5584.wxdbdump.payload.Progress;
import io.github.ryf5584.wxdbdump.payload.discovery.AccountEnricher;
import io.github.ryf5584.wxdbdump.payload.discovery.AccountEnumerator;
import io.github.ryf5584.wxdbdump.payload.discovery.DatabaseEnumerator;
import io.github.ryf5584.wxdbdump.payload.engine.KeyCapture;
import io.github.ryf5584.wxdbdump.payload.engine.KeyVault;
import io.github.ryf5584.wxdbdump.payload.identity.AccountIdentity;
import io.github.ryf5584.wxdbdump.payload.model.Account;
import io.github.ryf5584.wxdbdump.payload.model.DbFile;

/**
 * In-process engine wiring enumeration, keys and export together for {@link LoopbackServer}.
 * It runs inside WeChat, so it can read the private directory and call WCDB and MMKV.
 */
public final class WxDbEngine {

    private final EngineHost host;
    private final KeyVault vault = new KeyVault();
    private final KeyCapture capture;

    /** Account cache. Enumeration takes seconds and the account screen requests it often. */
    private volatile List<Account> cachedAccounts;
    private volatile long cachedAt;
    private static final long CACHE_TTL_MS = 8000;

    public WxDbEngine(EngineHost host) {
        this.host = host;
        this.capture = new KeyCapture(host, vault);
    }

    public EngineHost host() {
        return host;
    }

    public KeyVault vault() {
        return vault;
    }

    public KeyCapture capture() {
        return capture;
    }

    /** Installs hooks and returns which anchors matched. */
    public String installHooks() {
        String report = new WcdbKeyHooks(host, capture).install();
        Log.i("engine", "hook installation result: " + report);
        return report;
    }

    /** Clears the cache and forces re-enumeration. */
    public List<Account> refreshAccounts() {
        cachedAccounts = null;
        return listAccounts();
    }

    public List<Account> listAccounts() {
        return listAccounts(Progress.NONE);
    }

    /** Enumerates accounts: directory, uin, identity, databases. */
    public List<Account> listAccounts(Progress progress) {
        List<Account> cached = cachedAccounts;
        long age = System.currentTimeMillis() - cachedAt;
        if (cached != null && age >= 0 && age < CACHE_TTL_MS) {
            Log.i("engine", "reusing the account cache (age=" + age + "ms)");
            progress.on(1, 1, "cached");
            return cached;
        }

        long t0 = System.currentTimeMillis();
        List<Account> accounts = AccountEnumerator.enumerate(host, progress);

        // The global identity belongs to the last_login_uin account; read once and reuse
        AccountIdentity.Prefs prefs = AccountIdentity.read(host);
        int n = accounts.size();
        for (int i = 0; i < n; i++) {
            Account a = accounts.get(i);
            progress.on(i, n, "reading account " + (i + 1) + "/" + n + ": databases and identity");

            // The account list needs only lightweight data; the database count comes from a single
            // directory listing. Full enumeration happens on demand.
            AccountEnricher.enrichLight(host, a, prefs);

            // Xposed-specific check: whether the main database key was already captured
            // (vault.has reuses any key captured for the same account)
            a.decryptable = vault.has(a.dir + "EnMicroMsg.db");
            if (!a.decryptable) a.note = "Switch to this account once in WeChat to capture its key";
        }

        Log.i("engine", "enumeration done: " + accounts.size() + " accounts in "
                +(System.currentTimeMillis() - t0) + "ms, keys " + vault.describe());
        progress.on(n, n, "done");
        cachedAccounts = accounts;
        cachedAt = System.currentTimeMillis();
        return accounts;
    }

    private static DbFile findMain(List<DbFile> dbs) {
        for (DbFile d : dbs) if ("EnMicroMsg.db".equals(d.name)) return d;
        return dbs.isEmpty() ? null : dbs.get(0);
    }

    /** Decrypts one database into dstPath using WeChat's own SQLCipher. */
    public PlainDumper.Result dumpPlain(String srcPath, String dstPath) {
        KeyVault.DbKey k = vault.getForPath(srcPath);
        if (k == null) {
            return PlainDumper.Result.fail("no key for this account yet; opening WeChat captures it");
        }
        ClassLoader cl = host.loader();
        List<WcdbCipher.SpecOption> specs =
                WcdbCipher.specOptions(cl, srcPath, k.pageSize > 0 ? k.pageSize : 0);
        StringBuilder sb = new StringBuilder();
        for (WcdbCipher.SpecOption o : specs) sb.append(o.label).append(' ');
        Log.i("engine", "decrypting " + srcPath.substring(srcPath.lastIndexOf('/') + 1)
                + " keyLen=" + k.key.length + " spec candidates=[" + sb.toString().trim() + "]");
        return PlainDumper.export(cl, srcPath, k.key, specs, dstPath);
    }
}
