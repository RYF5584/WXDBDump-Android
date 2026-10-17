package io.github.ryf5584.wxdbdump.payload.model;

/** Business category of a database. Affects UI grouping only, never decryption. */
public enum DbCategory {
    /** Main database: messages, contacts, sessions. */
    MAIN("主库"),
    /** Session list. */
    SESSION("会话"),
    /** Contacts. */
    CONTACT("联系人"),
    /** Moments. */
    SNS("朋友圈"),
    /** Favourites. */
    FAVORITE("收藏"),
    /** Full-text search index. */
    FTS("检索索引"),
    /** File index. */
    FILE_INDEX("文件索引"),
    /** Other business databases. */
    OTHER("其它");

    public final String label;

    DbCategory(String label) {
        this.label = label;
    }

    /** Guesses a category from the file name. Heuristic only; a wrong guess changes nothing. */
    public static DbCategory guess(String fileName) {
        if (fileName == null) return OTHER;
        String n = fileName.toLowerCase();
        if (n.equals("enmicromsg.db")) return MAIN;
        if (n.contains("fts") || n.contains("indexmicromsg")) return FTS;
        if (n.contains("snsmicro")) return SNS;
        if (n.contains("favorite")) return FAVORITE;
        if (n.contains("wxfileindex") || n.contains("fileindex")) return FILE_INDEX;
        if (n.contains("conversation") || n.contains("msg")) return SESSION;
        if (n.contains("contact") || n.contains("userinfo")) return CONTACT;
        return OTHER;
    }
}
