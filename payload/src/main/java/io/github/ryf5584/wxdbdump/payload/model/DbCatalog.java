package io.github.ryf5584.wxdbdump.payload.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Purpose text and category for the common WeChat databases.
 *
 * <p>Annotation only: it never affects decryption or enumeration, so a wrong guess only
 * changes the description text.
 *
 * <p>Derived from the table names inside each database. WeChat adds and splits databases
 * between versions, so only the long-lived ones are listed; everything else falls back to
 * keyword matching.
 */
public final class DbCatalog {

    /** Category key used for grouping and colour; unknown keys become OTHER. */
    public static final String CHAT = "CHAT";
    public static final String CONTACT = "CONTACT";
    public static final String SNS = "SNS";
    public static final String FAVORITE = "FAVORITE";
    public static final String FTS = "FTS";
    public static final String FILE_IDX = "FILE_IDX";
    public static final String WALLET = "WALLET";
    public static final String MINIAPP = "MINIAPP";
    public static final String FINDER = "FINDER";
    public static final String MEDIA = "MEDIA";
    public static final String EMOJI = "EMOJI";
    public static final String STATS = "STATS";
    public static final String CONFIG = "CONFIG";
    public static final String OTHER = "OTHER";

    /** Exact file name to category and description. */
    private static final Map<String, String[]> EXACT = new LinkedHashMap<>();

    private static void put(String name, String cat, String desc) {
        EXACT.put(name.toLowerCase(), new String[]{cat, desc});
    }

    static {
        // ---------------- Core ----------------
        put("EnMicroMsg.db", CHAT,
                "主数据库。绝大部分数据都在这：聊天记录(message)、联系人(rcontact)、"
                        + "会话列表(rconversation)、群成员(chatroom)、消息媒体索引等。导出优先看这个。");

        // ---------------- Chat and messages ----------------
        put("FTS5IndexMicroMsg_encrypt.db", FTS,
                "聊天记录的全文检索索引(FTS5)。只用于微信内「搜索聊天记录」，"
                        + "内容可由 EnMicroMsg.db 重建，不是必要数据。");
        put("FTS5IndexMicroMsg.db", FTS,
                "聊天记录全文检索索引(FTS5)，未加密版本。可由 EnMicroMsg.db 重建。");
        put("msg.db", CHAT, "消息库（部分版本拆出的独立消息存储）。");
        put("message.db", CHAT, "消息库（部分版本拆出的独立消息存储）。");
        put("MicroMsgPriority.db", CHAT,
                "消息优先级与置顶排序。决定会话列表里谁排前面，属于可再生的展示态数据。");
        put("MsgGroupInfo.db", CHAT, "消息分组信息（如「服务通知」等聚合分组的归组关系）。");
        put("DupCheck-1.db", CHAT, "消息去重校验。用于防止同一条消息重复入库。");
        put("TextStatus.db", CHAT, "「状态」文本内容（微信状态的文案与素材记录）。");

        // ---------------- Contacts ----------------
        put("OpenIMContact.db", CONTACT, "开放 IM / 企业微信联系人。");
        put("Contact.db", CONTACT, "联系人（部分版本拆库后的独立联系人库）。");
        put("BizFans.db", CONTACT, "公众号粉丝关系（你关注的/关注你的公众号相关记录）。");

        // ---------------- Moments and Channels ----------------
        put("SnsMicroMsg.db", SNS,
                "朋友圈。SnsInfo 表存朋友圈动态正文、发布者、时间、点赞评论关系。");
        put("sns_show_teach.db", SNS, "朋友圈新手引导展示记录（无业务数据）。");
        put("FinderMM029.db", FINDER, "视频号主库（分片 029）。视频号账号/互动/推荐相关。");
        put("FinderWCDB.db", FINDER, "视频号通用 WCDB 库。");
        put("FinderMessage006.db", FINDER, "视频号消息。");
        put("FinderAccounts_01.db", FINDER, "视频号账号信息。");
        put("FinderLiveShopMsg.db", FINDER, "视频号直播小店消息。");
        put("FinderLiveTipsBar001.db", FINDER, "视频号直播提示条状态（展示态）。");
        put("FinderRedDotHistoryRecord.db", FINDER, "视频号红点历史记录（展示态）。");

        // ---------------- Favourites ----------------
        put("enFavorite.db", FAVORITE, "收藏。收藏的链接/图片/文件/聊天的索引与备注。");

        // ---------------- Files and media ----------------
        put("WxFileIndex.db", FILE_IDX,
                "文件索引。聊天里收发过的图片/视频/文件在磁盘上的路径、大小、类型、时间，"
                        + "配合 EnMicroMsg.db 的 msg 表可把消息还原成具体文件。");
        put("HashInfo.db", FILE_IDX, "文件哈希信息。用于秒传/去重判定，不含业务内容。");
        put("DeviceFileExtInfo.db", FILE_IDX, "设备侧文件扩展信息。");
        put("MBExternalVideoInfo.db", MEDIA, "外部视频信息（播放来源与缓存记录）。");
        put("MMPlayerMediaInfo.db", MEDIA, "播放器媒资信息（播放进度/清晰度等）。");
        put("Media.db", MEDIA, "媒体文件索引（部分版本的独立媒体库）。");
        put("Edge.db", MEDIA, "本地边缘计算缓存（视频号/播放器的本地缓存元数据）。");

        // ---------------- Payments and wallet ----------------
        put("WalletCoreDB-2.db", WALLET, "微信支付核心库。零钱/银行卡/交易记录相关。");
        put("WalletPay.db", WALLET, "微信支付订单与支付记录。");
        put("newuba.db", STATS, "用户行为埋点(UBA)。仅用于统计上报，无聊天内容。");

        // ---------------- Mini programs ----------------
        put("AppBrandComm.db", MINIAPP, "小程序通信与会话。小程序授权、会话、缓存元数据。");
        put("WxExpt.db", CONFIG, "AB 实验配置。客户端灰度开关，无业务数据。");
        put("WxCgiReport.db", STATS, "CGI 上报缓存。网络请求上报的暂存，无业务数据。");
        put("PreDownloadCheck.db", CONFIG, "预下载校验记录。");
        put("CommonOneMicroMsg.db", OTHER, "通用一次性业务库（各业务模块共用的小表）。");
        put("UnEncryptNewBiz.db", OTHER, "未加密的新业务库（微信有意不加密的业务数据）。");
        put("EcsConversation.db", OTHER, "客服/企微会话（微信客服 ECS）。");
        put("EcsWeShop.db", OTHER, "微信小店客服（视频号小店 ECS）。");
        put("KaraCore.db", MEDIA, "Kara 音乐/卡拉 OK 业务核心库。");
        put("KaraMM.db", MEDIA, "Kara 音乐业务库。");
        put("GameLife.db", OTHER, "游戏中心/游戏人生业务库。");
        put("VoIPDB-8.db", OTHER, "音视频通话记录。");
        put("VoIPDB.db", OTHER, "音视频通话记录。");
        put("W1wPersonalMsg.db", OTHER, "企业微信(WeCom)个人消息。");
        put("EnResDown.db", OTHER, "资源下载记录（表情/插件等资源包）。");
        put("AdvertisePushMsg.db", OTHER, "广告推送消息。");
    }

    private static final String[][] PATTERNS = {
            // keyword, category, description
            {"fts", FTS, "全文检索索引。可由主库重建，非必要数据。"},
            {"index", FILE_IDX, "索引类库。用于加速查询，一般可由其它库重建。"},
            {"sns", SNS, "朋友圈相关。"},
            {"favorite", FAVORITE, "收藏相关。"},
            {"finder", FINDER, "视频号相关。"},
            {"wallet", WALLET, "微信支付/钱包相关。"},
            {"pay", WALLET, "微信支付相关。"},
            {"appbrand", MINIAPP, "小程序相关。"},
            {"mini", MINIAPP, "小程序相关。"},
            {"emoji", EMOJI, "表情相关。"},
            {"emoticon", EMOJI, "表情相关。"},
            {"contact", CONTACT, "联系人相关。"},
            {"friend", CONTACT, "联系人相关。"},
            {"conversation", CHAT, "会话相关。"},
            {"session", CHAT, "会话相关。"},
            {"msg", CHAT, "消息相关。"},
            {"message", CHAT, "消息相关。"},
            {"chat", CHAT, "聊天相关。"},
            {"media", MEDIA, "媒体文件相关。"},
            {"video", MEDIA, "视频相关。"},
            {"voice", MEDIA, "语音相关。"},
            {"report", STATS, "统计上报相关，一般不含业务数据。"},
            {"stat", STATS, "统计相关，一般不含业务数据。"},
            {"config", CONFIG, "配置/开关相关。"},
            {"setting", CONFIG, "配置相关。"},
            {"log", STATS, "日志类库。"},
            {"voip", OTHER, "音视频通话相关。"},
            {"game", OTHER, "游戏业务相关。"},
    };

    private DbCatalog() {
    }

    /** Category key. */
    public static String category(String fileName) {
        String[] e = lookup(fileName);
        if (e != null) return e[0];
        String n = norm(fileName);
        for (String[] p : PATTERNS) {
            if (n.contains(p[0])) return p[1];
        }
        return OTHER;
    }

    /** Purpose text shown to the user. */
    public static String describe(String fileName) {
        String[] e = lookup(fileName);
        if (e != null) return e[1];
        String n = norm(fileName);
        for (String[] p : PATTERNS) {
            if (n.contains(p[0])) return p[2];
        }
        return "微信业务库（未收录具体用途）。一般该库的表结构可自行打开浏览确认。";
    }

    private static String[] lookup(String fileName) {
        return fileName == null ? null : EXACT.get(fileName.toLowerCase());
    }

    private static String norm(String fileName) {
        return fileName == null ? "" : fileName.toLowerCase();
    }

    /** Category key to the label shown in the UI. */
    public static String categoryLabel(String cat) {
        if (cat == null) return "其它";
        switch (cat) {
            case CHAT:
                return "聊天";
            case CONTACT:
                return "联系人";
            case SNS:
                return "朋友圈";
            case FAVORITE:
                return "收藏";
            case FTS:
                return "检索索引";
            case FILE_IDX:
                return "文件索引";
            case WALLET:
                return "支付";
            case MINIAPP:
                return "小程序";
            case FINDER:
                return "视频号";
            case MEDIA:
                return "媒体";
            case EMOJI:
                return "表情";
            case STATS:
                return "统计";
            case CONFIG:
                return "配置";
            default:
                return "其它";
        }
    }

    /**
     * Databases whose content is derived from the main database, so losing one is harmless.
     *
     * <p>Mostly category-driven, plus a few named exceptions whose catalogue entry already
     * documents them as regenerable display state.
     */
    public static boolean isNonEssential(String fileName) {
        if (fileName == null) return false;
        String c = category(fileName);
        if (FTS.equals(c) || STATS.equals(c) || CONFIG.equals(c)) return true;

        // Files that are regenerable but whose category is not FTS/STATS/CONFIG. This list
        // mirrors what each entry's own description already says (cache, local cache,
        // display state), so it stays in sync with the catalogue.
        String n = fileName.trim();
        return "MicroMsgPriority.db".equals(n)        // 会话排序展示态
                || "MsgGroupInfo.db".equals(n)          // 消息归组关系
                || "status_info_v4.db".equals(n)        // 状态缓存
                || "Edge.db".equals(n)                  // 本地边缘计算缓存
                || "MBExternalVideoInfo.db".equals(n);  // 视频播放来源与缓存记录
    }
}
