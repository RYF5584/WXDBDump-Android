package io.github.ryf5584.wxdbdump.payload.discovery;

import java.util.ArrayList;
import java.util.List;

import io.github.ryf5584.wxdbdump.payload.EngineHost;
import io.github.ryf5584.wxdbdump.payload.Log;
import io.github.ryf5584.wxdbdump.payload.Progress;
import io.github.ryf5584.wxdbdump.payload.crypto.SqlCipherHeader;
import io.github.ryf5584.wxdbdump.payload.model.Account;
import io.github.ryf5584.wxdbdump.payload.model.DbFile;
import io.github.ryf5584.wxdbdump.payload.paths.WeChatPaths;

/**
 * Database enumeration by walking the filesystem, with no hardcoded name list.
 *
 * <p>WeChat adds and splits databases between versions, so any list goes stale.
 * <ol>
 *   <li>finds *.db files recursively and takes their length</li>
 *   <li>checks for matching wal, shm and journal sidecars</li>
 *   <li>reads the first 16 bytes to tell plaintext from encrypted</li>
 * </ol>
 */
public final class DatabaseEnumerator {

    /** Recursion depth limit, guarding against unusual directory structures. */
    private static final int MAX_DEPTH = 3;

    private DatabaseEnumerator() {
    }

    public static List<DbFile> enumerate(EngineHost host, Account account) {
        return enumerate(host, account, Progress.NONE);
    }

    public static List<DbFile> enumerate(EngineHost host, Account account, Progress progress) {
        List<DbFile> out = new ArrayList<>();
        EngineHost.FileSource fs = host.files();

        // Fast path: use the one-shot tree scan when the source supports it, which root mode does
        List<DbScan> batch = fs.scanDatabases(account.dir, MAX_DEPTH);
        if (batch != null) {
            for (DbScan d : batch) {
                DbFile db = new DbFile(d.name, d.path, d.size);
                db.encrypted = d.encrypted;
                db.hasWal = d.wal;
                db.hasShm = d.shm;
                db.hasJournal = d.journal;
                out.add(db);
                progress.on(out.size(), 0, "已读取 " + out.size() + " 个库 · " + d.name);
            }
            long sum = 0;
            for (DbFile f : out) sum += Math.max(0, f.sizeBytes);
            account.dbCount = out.size();
            account.totalBytes = sum;
            Log.i("enum", account.dirName + " → " + out.size() + " 个库 / "
                    + DbFile.formatSize(sum) + "（单次扫描）");
            progress.on(out.size(), out.size(), "共 " + out.size() + " 个数据库");
            return out;
        }

        int[] counter = {0};
        scan(host, fs, account.dir, 0, out, progress, counter);
        long total = 0;
        for (DbFile f : out) total += Math.max(0, f.sizeBytes);
        account.dbCount = out.size();
        account.totalBytes = total;
        Log.i("enum", account.dirName + " → " + out.size() + " 个库 / " + DbFile.formatSize(total));
        progress.on(out.size(), out.size(), "共 " + out.size() + " 个数据库");
        return out;
    }

    private static void scan(EngineHost host, EngineHost.FileSource fs, String dir, int depth,
                             List<DbFile> out, Progress progress, int[] counter) {
        if (depth > MAX_DEPTH) return;
        String[] children;
        try {
            children = fs.list(dir);
        } catch (Throwable t) {
            Log.w("enum", "列举目录失败，跳过：dir=" + dir, t);
            return;
        }
        if (children == null) {
            Log.w("enum", "列举目录返回 null，跳过：dir=" + dir);
            return;
        }

        for (String name : children) {
            String path = dir + name;
            if (name.endsWith(WeChatPaths.DB_SUFFIX)) {
                long size = fs.size(path);
                if (size < 0) continue;
                DbFile db = new DbFile(name, path, size);
                byte[] head = fs.readHead(path, 16);
                db.encrypted = SqlCipherHeader.isEncrypted(head);
                db.hasWal = fs.exists(path + "-wal");
                db.hasShm = fs.exists(path + "-shm");
                db.hasJournal = fs.exists(path + "-journal");
                out.add(db);
                counter[0]++;
                // A zero total means indeterminate progress, while the stage text stays accurate
                progress.on(counter[0], 0, "已读取 " + counter[0] + " 个库 · " + name);
            } else if (fs.exists(path + "/") && !name.startsWith(".")) {
                // Skip obvious media directories to avoid pointless recursion
                if (isMediaDir(name)) continue;
                scan(host, fs, path + "/", depth + 1, out, progress, counter);
            }
        }
    }

    private static boolean isMediaDir(String name) {
        switch (name) {
            case "image2":
            case "video":
            case "voice2":
            case "attachment":
            case "record":
            case "emoji":
            case "cache":
            case "mmkv_private":
                return true;
            default:
                return false;
        }
    }
}
