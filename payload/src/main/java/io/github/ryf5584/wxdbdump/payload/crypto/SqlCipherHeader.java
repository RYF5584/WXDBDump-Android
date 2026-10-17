package io.github.ryf5584.wxdbdump.payload.crypto;

/**
 * SQLite / WCDB header detection: decides whether a database needs the decryption path.
 *
 * <p>Deliberately not name-based: WeChat adds and splits databases between versions, so
 * any hardcoded list goes stale. Reading the first 16 bytes is version-independent.
 */
public final class SqlCipherHeader {

    /** Plaintext SQLite header: "SQLite format 3\0". */
    public static final byte[] SQLITE_HEADER = {
            'S', 'Q', 'L', 'i', 't', 'e', ' ', 'f', 'o', 'r', 'm', 'a', 't', ' ', '3', 0
    };

    private SqlCipherHeader() {
    }

    /** Whether the file is plaintext SQLite. */
    public static boolean isPlaintext(byte[] head) {
        if (head == null || head.length < SQLITE_HEADER.length) return false;
        for (int i = 0; i < SQLITE_HEADER.length; i++) {
            if (head[i] != SQLITE_HEADER[i]) return false;
        }
        return true;
    }

    /** Whether the file is an encrypted database. */
    public static boolean isEncrypted(byte[] head) {
        if (head == null || head.length < 16) return false;
        if (isPlaintext(head)) return false;
        // All-zero means an empty or just-created file, not an encrypted database
        for (byte b : head) {
            if (b != 0) return true;
        }
        return false;
    }

    /**
     * Heuristic for an encrypted database: the length is usually a multiple of the page size
     * (1024 or 4096) and the header is not plaintext.
     */
    public static int guessPageSize(long sizeBytes, byte[] head) {
        if (isPlaintext(head)) return 0;
        if (sizeBytes > 0 && sizeBytes % 4096 == 0) return 4096;
        if (sizeBytes > 0 && sizeBytes % 1024 == 0) return 1024;
        return 4096;
    }
}
