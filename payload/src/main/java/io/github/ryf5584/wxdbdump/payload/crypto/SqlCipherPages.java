package io.github.ryf5584.wxdbdump.payload.crypto;

import java.io.File;
import java.io.RandomAccessFile;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import io.github.ryf5584.wxdbdump.payload.Log;

/**
 * Page-level decryptor for WeChat's WCDB/SQLCipher databases.
 *
 * <p>Layout, verified against a real database opened by sqlite3:
 * <pre>
 *   salt  = file[0:16]
 *   key   = PBKDF2-HMAC-SHA1(passphrase, salt, 4000, 32)      // AES-256
 *   page N (1-based), offset o = (N-1) * pageSize:
 *     iv        = file[o + pageSize - 16 : o + pageSize]      // trailing 16 bytes
 *     plaintext = AES-256-CBC-decrypt(file[o : o + pageSize - 16], key, iv)
 *                 + 16 zero bytes                            // SQLite reserved area
 * </pre>
 *
 * <p>Page 1 is special: its first 16 ciphertext bytes are overwritten by the salt,
 * and block C1 depends on the lost C0, so the first 32 decrypted bytes are garbage.
 * That region is fully known, so it is rebuilt instead of decrypted.
 *
 * <p>Requires reserved-bytes-per-page = 16, which is what WCDB uses.
 */
public final class SqlCipherPages {

    private static final String TAG = "pages";

    private static final int SALT_LEN = 16;
    private static final int KDF_ITER = 4000;
    private static final int KEY_BITS = 256;

    /** SQLite header magic; also the plaintext of page 1 bytes 0..15. */
    private static final byte[] MAGIC = {
        'S', 'Q', 'L', 'i', 't', 'e', ' ', 'f', 'o', 'r', 'm', 'a', 't', ' ', '3', 0,
    };

    /** Page sizes to try; WeChat uses 1024, the others cover older/edge databases. */
    private static final int[] PAGE_SIZE_CANDIDATES = {1024, 4096, 2048, 512};

    /** SQLite b-tree page type bytes, used as the detection oracle. */
    private static final byte[] BTREE_TYPES = {2, 5, 10, 13};

    /** Fraction of sampled pages that must look like b-tree pages to accept a key. */
    private static final double MIN_BTREE_RATIO = 0.30;

    /** Pages sampled when scoring a candidate page size. */
    private static final int SAMPLE_PAGES = 40;

    private SqlCipherPages() {
    }

    /**
     * Decrypt {@code src} into {@code dst}.
     *
     * @param key 7-byte ASCII passphrase
     * @return true when the output starts with the SQLite magic
     */
    public static boolean decrypt(File src, File dst, byte[] key) {
        long started = System.currentTimeMillis();
        try (RandomAccessFile in = new RandomAccessFile(src, "r")) {
            int pageSize = detectPageSize(in, key);
            if (pageSize <= 0) {
                Log.w(TAG, "unknown page size: " + src.getName());
                return false;
            }

            byte[] salt = new byte[SALT_LEN];
            in.seek(0);
            in.readFully(salt);
            byte[] dk = deriveKey(key, salt);
            SecretKeySpec spec = new SecretKeySpec(dk, "AES");
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");

            int pages = (int) (in.length() / pageSize);
            byte[] buf = new byte[pageSize];
            byte[] zeroTail = new byte[SALT_LEN];

            try (RandomAccessFile out = new RandomAccessFile(dst, "rw")) {
                out.setLength(0);
                for (int page = 1; page <= pages; page++) {
                    in.seek((page - 1L) * pageSize);
                    if (in.read(buf) < pageSize) break;
                    byte[] plain = decryptPage(cipher, spec, buf, pageSize - SALT_LEN, pageSize - SALT_LEN);
                    if (page == 1) rebuildFirstPage(plain, pageSize, pages);
                    out.write(plain);
                    out.write(zeroTail);
                }
            }

            if (!looksLikeSqlite(dst)) {
                Log.w(TAG, src.getName() + ": decrypted output is not a valid SQLite database");
                return false;
            }
            Log.i(TAG, src.getName() + ": " + pages + " pages @ " + pageSize
                    + " in " + (System.currentTimeMillis() - started) + "ms");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "decrypt failed: " + src.getName(), t);
            return false;
        }
    }

    /**
     * Pick the page size that produces the most valid b-tree pages.
     *
     * <p>Decrypting with the wrong page size yields uniformly random first bytes
     * (~1.6% hit rate); the correct one scores far higher. Returns 0 when no
     * candidate reaches {@link #MIN_BTREE_RATIO}.
     */
    public static int detectPageSize(RandomAccessFile in, byte[] key) {
        try {
            if (in.length() < 2048) return 0;
            byte[] salt = new byte[SALT_LEN];
            in.seek(0);
            in.readFully(salt);
            byte[] dk = deriveKey(key, salt);
            SecretKeySpec spec = new SecretKeySpec(dk, "AES");
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");

            int best = 0;
            int sampled = 0;
            double bestRatio = 0.0;
            for (int pageSize : PAGE_SIZE_CANDIDATES) {
                int pages = (int) (in.length() / pageSize);
                if (pages < 2) continue;
                byte[] buf = new byte[pageSize];
                int good = 0;
                int total = 0;
                // Page 1 has a rebuilt header, so sampling starts at page 2.
                for (int page = 2; page <= pages && total < SAMPLE_PAGES; page++) {
                    in.seek((page - 1L) * pageSize);
                    if (in.read(buf) < pageSize) break;
                    byte[] plain = decryptPage(cipher, spec, buf, pageSize - SALT_LEN, pageSize - SALT_LEN);
                    total++;
                    if (isBtreeType(plain[0])) good++;
                }
                sampled = Math.max(sampled, total);
                double ratio = total > 0 ? (double) good / total : 0.0;
                if (ratio > bestRatio) {
                    bestRatio = ratio;
                    best = pageSize;
                }
            }
            if (bestRatio >= MIN_BTREE_RATIO) return best;

            // Too few pages to score reliably: fall back to the WeChat default and let the
            // structural validation in decrypt() reject it if the guess was wrong.
            // With a proper sample, a low score means the key or parameters do not match —
            // guessing 1024 there would only produce plausible-looking garbage.
            if (best == 0 || sampled < 4) {
                if (in.length() % 1024 == 0 && in.length() >= 2048) return 1024;
            }
            return 0;
        } catch (Throwable t) {
            Log.w(TAG, "page size detection failed", t);
            return 0;
        }
    }

    private static byte[] deriveKey(byte[] key, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(
                new String(key, "US-ASCII").toCharArray(), salt, KDF_ITER, KEY_BITS);
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                .generateSecret(spec).getEncoded();
    }

    /** Decrypt one page body. {@code ivOffset} points at the trailing IV in {@code src}. */
    private static byte[] decryptPage(Cipher cipher, SecretKeySpec key,
                                      byte[] src, int ivOffset, int len) throws Exception {
        cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(src, ivOffset, SALT_LEN));
        return cipher.doFinal(src, 0, len - len % 16);
    }

    /**
     * Rebuild page 1's first 32 bytes.
     *
     * <p>Bytes 0..15 are the salt-covered magic; bytes 16..31 are the fixed SQLite
     * header fields. The change counter is left at 0 because SQLite does not validate it.
     */
    private static void rebuildFirstPage(byte[] page, int pageSize, int pages) {
        if (page.length < 32) return;
        System.arraycopy(MAGIC, 0, page, 0, SALT_LEN);
        page[16] = (byte) (pageSize >>> 8);
        page[17] = (byte) pageSize;
        page[18] = 1;                       // write format version
        page[19] = 1;                       // read format version
        page[20] = (byte) SALT_LEN;         // reserved bytes per page
        page[21] = 64;                      // max embedded payload fraction
        page[22] = 32;                      // min embedded payload fraction
        page[23] = 32;                      // leaf payload fraction
        page[24] = 0;                       // change counter
        page[25] = 0;
        page[26] = 0;
        page[27] = 0;
        page[28] = (byte) (pages >>> 24);
        page[29] = (byte) (pages >>> 16);
        page[30] = (byte) (pages >>> 8);
        page[31] = (byte) pages;
    }

    private static boolean isBtreeType(byte b) {
        for (byte t : BTREE_TYPES) if (b == t) return true;
        return false;
    }

    /**
     * Structural validation of a decrypted database.
     *
     * <p>The magic alone proves nothing here: page 1's first 32 bytes are rebuilt by
     * {@link #rebuildFirstPage}, so a wrong key still yields a file that starts with
     * "SQLite format 3". Real validation has to look at the header fields and at page 2.
     */
    public static boolean looksLikeSqlite(File file) {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            if (in.length() < 1024) return false;
            byte[] h = new byte[100];
            in.readFully(h);
            for (int i = 0; i < SALT_LEN; i++) if (h[i] != MAGIC[i]) return false;

            int pageSize = ((h[16] & 0xFF) << 8) | (h[17] & 0xFF);
            if (pageSize < 512 || pageSize > 65536 || (pageSize & (pageSize - 1)) != 0) return false;

            int reserved = h[20] & 0xFF;
            if (reserved != 0 && reserved != SALT_LEN && reserved != 32) return false;

            int schemaFormat = ((h[44] & 0xFF) << 24) | ((h[45] & 0xFF) << 16)
                    | ((h[46] & 0xFF) << 8) | (h[47] & 0xFF);
            if (schemaFormat < 1 || schemaFormat > 4) return false;

            int encoding = ((h[56] & 0xFF) << 24) | ((h[57] & 0xFF) << 16)
                    | ((h[58] & 0xFF) << 8) | (h[59] & 0xFF);
            if (encoding < 1 || encoding > 3) return false;

            // Page 1 holds the schema table, so every other page must be a b-tree page.
            if (in.length() >= pageSize * 2L) {
                in.seek(pageSize);
                int type = in.read();
                return isBtreeType((byte) type);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
