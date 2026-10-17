package io.github.ryf5584.wxdbdump.payload.discovery;

import java.util.ArrayList;
import java.util.List;

import io.github.ryf5584.wxdbdump.payload.EngineHost;
import io.github.ryf5584.wxdbdump.payload.Log;
import io.github.ryf5584.wxdbdump.payload.Progress;
import io.github.ryf5584.wxdbdump.payload.model.Account;
import io.github.ryf5584.wxdbdump.payload.paths.WeChatPaths;

/**
 * Account enumeration from the filesystem alone, with no dependency on WeChat classes.
 *
 * <p>A directory under MicroMsg counts as an account when both hold:
 * <ul>
 *   <li>its name matches 32 hex characters, which is how account directories are built</li>
 *   <li>it contains EnMicroMsg.db or mmkv_private</li>
 * </ul>
 * 
 */
public final class AccountEnumerator {

    private AccountEnumerator() {
    }

    public static List<Account> enumerate(EngineHost host) {
        return enumerate(host, Progress.NONE);
    }

    public static List<Account> enumerate(EngineHost host, Progress progress) {
        EngineHost.FileSource fs = host.files();
        progress.on(0, 0, "扫描账号目录…");

        // Prefer WeChat's own sign-in records, where each file name marks a real account.
        // Resource directories look identical, so the name alone would over-collect.
        List<Account> marked = fromIdentityRecords(fs, progress);
        if (!marked.isEmpty()) {
            Log.i("enum", "按 identity_records 找到 " + marked.size() + " 个账号");
            progress.on(marked.size(), marked.size(), "共发现 " + marked.size() + " 个账号");
            return marked;
        }
        Log.w("enum", "identity_records 无有效记录，回退到目录名扫描");
        return byDirName(fs, progress);
    }

    /** Reads WeChat's sign-in records. Empty when unavailable, and the caller falls back. */
    private static List<Account> fromIdentityRecords(EngineHost.FileSource fs, Progress progress) {
        List<Account> out = new ArrayList<>();
        String[] names;
        try {
            names = fs.list(WeChatPaths.IDENTITY_RECORDS);
        } catch (Throwable t) {
            return out;
        }
        if (names == null || names.length == 0) return out;

        progress.on(0, names.length, "核对登录记录…");
        for (String name : names) {
            if (!WeChatPaths.isAccountDirName(name)) continue;
            String dir = WeChatPaths.MICRO_MSG + name + "/";
            if (!hasAccountData(fs, dir)) continue;
            out.add(build(name, dir));
            progress.on(out.size(), names.length, "核对账号 " + out.size() + "/" + names.length);
        }
        return out;
    }

    /** Fallback: a 32-hex name with a main database or account MMKV inside. */
    private static List<Account> byDirName(EngineHost.FileSource fs, Progress progress) {
        List<Account> out = new ArrayList<>();
        String[] children;
        try {
            children = fs.list(WeChatPaths.MICRO_MSG);
        } catch (Throwable t) {
            Log.e("enum", "列 MicroMsg 目录失败", t);
            return out;
        }
        if (children == null) return out;

        int candidates = 0;
        for (String name : children) {
            if (WeChatPaths.isAccountDirName(name)) candidates++;
        }
        progress.on(0, candidates, "核对账号目录…");

        int checked = 0;
        for (String name : children) {
            if (!WeChatPaths.isAccountDirName(name)) continue;
            checked++;
            progress.on(checked, candidates, "核对账号 " + checked + "/" + candidates);
            String dir = WeChatPaths.MICRO_MSG + name + "/";
            if (!hasAccountData(fs, dir)) continue;
            out.add(build(name, dir));
        }
        return out;
    }

    private static boolean hasAccountData(EngineHost.FileSource fs, String dir) {
        return fs.exists(dir + WeChatPaths.MAIN_DB) || fs.exists(dir + WeChatPaths.MMKV_PRIVATE);
    }

    private static Account build(String name, String dir) {
        Account a = new Account(name, dir);
        a.lastActiveMs = System.currentTimeMillis();
        return a;
    }
}
