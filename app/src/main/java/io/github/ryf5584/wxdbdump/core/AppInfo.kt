package io.github.ryf5584.wxdbdump.core

/** Project metadata shown in the UI and the author footer. */
object AppInfo {

    /** Displayed as the in-app headline. */
    const val NAME = "WeChat DB Dump"

    /** Short tagline under the logo. */
    const val TAGLINE = "Export WeChat databases as plaintext"

    const val GITHUB_URL = "https://github.com/RYF5584/WXDBDump-Android"
    const val GITHUB_HANDLE = "RYF5584"

    const val WECHAT = "RSCompanyCEO"
    const val QQ = "1043695584"
    const val TELEGRAM = "ryf5584"

    /** Opens the Telegram profile in the app when installed. */
    val TELEGRAM_URL: String get() = "https://t.me/$TELEGRAM"

    /** `mqqwpa://` opens the QQ chat directly; the https link is the web fallback. */
    val QQ_URL: String get() = "mqqwpa://im/chat?chat_type=wpa&uin=$QQ"
    val QQ_WEB_URL: String get() = "https://wpa.qq.com/msgrd?v=3&uin=$QQ&site=qq&menu=yes"
}
