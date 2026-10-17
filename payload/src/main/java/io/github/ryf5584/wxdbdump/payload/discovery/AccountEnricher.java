package io.github.ryf5584.wxdbdump.payload.discovery;

import java.util.List;

import io.github.ryf5584.wxdbdump.payload.EngineHost;
import io.github.ryf5584.wxdbdump.payload.Log;
import io.github.ryf5584.wxdbdump.payload.Progress;
import io.github.ryf5584.wxdbdump.payload.crypto.UinResolver;
import io.github.ryf5584.wxdbdump.payload.identity.AccountIdentity;
import io.github.ryf5584.wxdbdump.payload.model.Account;
import io.github.ryf5584.wxdbdump.payload.model.DbFile;

/**
 * Completes an account that only has directory information: uin, identity, databases.
 *
 * <p>Extracted so both channels share one implementation; otherwise details such as
 * nickname, avatar and widget ownership drift apart.
 *
 * <p>The decryptable flag is deliberately untouched, since it depends on where the key
 * came from, which only each caller knows.
 */
public final class AccountEnricher {

    private AccountEnricher() {
    }

    /**
     * Enriches an account in place.
     *
     * @param prefs identity read once by the caller, avoiding a disk read per account
     */
    /**
     * Light enrichment: resolves the uin, identity and avatar, and estimates the database
     * count and size from a single directory listing. The account list uses this. Full
     * enumeration must not happen here: every header read costs an su process in root mode,
     * and dozens of databases per account would freeze the screen.
     */
    public static void enrichLight(EngineHost host, Account a, AccountIdentity.Prefs prefs) {
        resolveIdentity(host, a, prefs);

        int count = 0;
        long total = 0;
        String[] children = host.files().list(a.dir);
        if (children != null) {
            for (String name : children) {
                if (!name.endsWith(".db")) continue;
                count++;
                long sz = host.files().size(a.dir + name);
                if (sz > 0) total += sz;
            }
        }
        a.dbCount = count;
        a.totalBytes = total;
        Log.i("enrich", "账号 " + a.dirName + " → uin=" + a.uin + " wxid=" + a.wxid
                + " nick=" + a.nickname + " avatar=" + (!a.avatarPath.isEmpty())
                + " 库数≈" + count + "（轻量，未逐个读头）");
    }

    /** Full enrichment: light enrichment plus every database. Called when opening an account. */
    public static void enrich(EngineHost host, Account a, AccountIdentity.Prefs prefs, Progress progress) {
        resolveIdentity(host, a, prefs);

        List<DbFile> dbs = DatabaseEnumerator.enumerate(host, a, progress);
        a.dbs.clear();
        a.dbs.addAll(dbs);

        DbFile main = mainDb(dbs);
        if (main != null && !main.encrypted) {
            // A plaintext main database needs no key and is readable as-is
            a.decryptable = true;
        }
        Log.i("enrich", "账号 " + a.dirName + " → uin=" + a.uin + "(" + a.uinSource + ")"
                + " wxid=" + a.wxid + " nick=" + a.nickname
                + " avatar=" + (!a.avatarPath.isEmpty())
                + " 库数=" + a.dbCount);
    }

    /** Resolves the uin and identity. Shared by both enrichment levels. */
    private static void resolveIdentity(EngineHost host, Account a, AccountIdentity.Prefs prefs) {
        UinResolver.Result r = UinResolver.resolve(host, a.dir, a.dirName);
        a.uin = r.uin;
        a.uinSource = r.source;

        // Global preferences only describe the last signed-in account, so the uin must be checked
        // or a nickname would be attributed to the wrong account.
        if (!prefs.wxid.isEmpty() && (prefs.uin == 0 || prefs.uin == a.uin)) {
            a.wxid = prefs.wxid;
            a.nickname = prefs.nickname;
            a.alias = prefs.alias;
            a.mobile = prefs.mobile;
        } else {
            // Historical accounts use only their own MMKV identity; missing values stay empty
            a.wxid = r.wxid == null ? "" : r.wxid;
            a.nickname = r.nickname == null ? "" : r.nickname;
        }

        // Resolved before the wxid is masked, but not gated on having one: an account whose uin
        // cannot be read still has its avatar on disk, and avatarPath knows how to find it.
        String av = AccountIdentity.avatarPath(host, a.dir, a.wxid);
        a.avatarPath = av == null ? "" : av;

        // Still no wxid, but the avatar is named after its hash, and the account's own
        // plaintext databases record the wxid it belongs to. Recovering it here is what lets
        // the account be shown and exported under a real name instead of a placeholder.
        if (a.wxid.isEmpty()) {
            String own = AccountIdentity.ownWxid(host, a.dir, a.avatarPath);
            if (!own.isEmpty()) {
                a.wxid = own;
                a.wxidSource = "account databases";
            }
        }

        if (a.wxid.isEmpty()) a.wxid = UinResolver.maskUin(a.uin);
    }

    /** The main database, falling back to the first one found. */
    public static DbFile mainDb(List<DbFile> dbs) {
        for (DbFile d : dbs) if ("EnMicroMsg.db".equals(d.name)) return d;
        return dbs.isEmpty() ? null : dbs.get(0);
    }
}
