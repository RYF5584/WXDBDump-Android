package io.github.ryf5584.wxdbdump.data

import io.github.ryf5584.wxdbdump.core.Account
import io.github.ryf5584.wxdbdump.core.DbFile
import io.github.ryf5584.wxdbdump.core.DetectionState
import io.github.ryf5584.wxdbdump.core.XposedFailure
import io.github.ryf5584.wxdbdump.payload.Log
import io.github.ryf5584.wxdbdump.payload.proto.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Client for Xposed mode, connecting to the loopback server inside WeChat.
 *
 * <p>Module activation is proven rather than guessed: an HMAC challenge-response must
 * complete, so the app never has to read LSPosed's module list. The exchange runs in both
 * directions, because the server also refuses commands until it has verified us.
 *
 * <p>Every failure branch logs a specific reason; the success path logs only milestones.
 * 
 */
class LoopbackClient(
    private val port: Int = io.github.ryf5584.wxdbdump.payload.proto.Channel.PORT,
    private val token: String = io.github.ryf5584.wxdbdump.payload.proto.Channel.TOKEN,
) {

    data class ProbeResult(val state: DetectionState, val failure: XposedFailure, val serverInfo: String = "")

    private val busy = AtomicBoolean()

    /** Probes connectivity, handshake and version. Failures carry an exact reason. */
    suspend fun probe(timeoutMs: Long = 2500): ProbeResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        var socket: Socket? = null
        try {
            Log.i("net", "probe 开始 → 127.0.0.1:$port (timeout=${timeoutMs}ms)")
            socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs.toInt().coerceAtLeast(300))
            socket.soTimeout = timeoutMs.toInt().coerceAtLeast(300)
            socket.tcpNoDelay = true
            Log.i("net", "probe connect OK（${System.currentTimeMillis() - t0}ms）")

            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))

            val nonce = newNonce()
            writeText(out, "HELLO {\"v\":1,\"client\":\"${io.github.ryf5584.wxdbdump.payload.proto.Channel.CLIENT_VERSION}\",\"nonce\":\"$nonce\"}")
            Log.i("net", "probe 已发 HELLO nonce=$nonce")

            val reply = readText(input)
            if (reply == null) {
                Log.w("net", "probe 失败：服务端未回帧（读了 null）")
                return@withContext ProbeResult(DetectionState.Unavailable(), XposedFailure.CONNECT_FAILED)
            }
            Log.i("net", "probe 收到：" + head(reply))
            if (!reply.startsWith("ACK")) {
                // NACK means our server answered but rejected the handshake
                Log.w("net", "probe 失败：服务端返回非 ACK（$reply）")
                return@withContext ProbeResult(DetectionState.Error(reply), XposedFailure.TOKEN_MISMATCH)
            }
            val proof = extract(reply, "proof") ?: ""
            val expect = hmacProof(nonce, token)
            // Log only whether the digests match: a proof is a per-nonce digest, but echoing
            // both halves of a MAC comparison is a habit worth not forming.
            if (!constantTimeEquals(proof, expect)) {
                Log.w("net", "probe failed: ACK proof mismatch")
                return@withContext ProbeResult(
                    DetectionState.Error("token mismatch"), XposedFailure.TOKEN_MISMATCH,
                )
            }

            // The server issues its own nonce and will not serve a command until we sign it.
            val serverNonce = extract(reply, "nonce")
            if (serverNonce == null) {
                Log.w("net", "probe failed: ACK carried no nonce")
                return@withContext ProbeResult(
                    DetectionState.Error("no server nonce"), XposedFailure.TOKEN_MISMATCH,
                )
            }
            writeText(out, "AUTH {\"v\":1,\"proof\":\"${hmacProof(serverNonce, token)}\"}")
            val ready = readText(input)
            if (ready == null || !ready.startsWith("READY")) {
                Log.w("net", "probe failed: AUTH rejected ($ready)")
                return@withContext ProbeResult(
                    DetectionState.Error(ready ?: "auth rejected"), XposedFailure.TOKEN_MISMATCH,
                )
            }

            val status = exec(socket, out, input, "STATUS") ?: ""
            Log.i("net", "probe 成功（${System.currentTimeMillis() - t0}ms）STATUS=" + head(status))
            return@withContext ProbeResult(DetectionState.Available, XposedFailure.NONE, status)
        } catch (t: Throwable) {
            Log.e("net", "probe 异常（${System.currentTimeMillis() - t0}ms）", t)
            ProbeResult(DetectionState.Unavailable(t.message ?: ""), XposedFailure.CONNECT_FAILED)
        } finally {
            if (liveSocket === socket) liveSocket = null
            runCatching { socket?.close() }
        }
    }

    /** Lists accounts, invoking [onProgress] for each `#P` frame the module pushes. */
    suspend fun listAccounts(
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> },
    ): List<Account> = withContext(Dispatchers.IO) {
        Log.i("net", "→ ACCOUNTS")
        val json = withConnection("ACCOUNTS") { socket, out, input ->
            exec(socket, out, input, "ACCOUNTS", onProgress)
        }
        if (json == null) {
            Log.w("net", "ACCOUNTS 返回 null（连接或命令失败，见上面的错误日志）")
            return@withContext emptyList()
        }
        val list = parseAccounts(json)
        Log.i("net", "← ACCOUNTS 解析出 ${list.size} 个账号（原始长度 ${json.length}）")
        list
    }

    /** Lists the databases of one account. */
    suspend fun listDatabases(
        dirName: String,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> },
    ): List<DbFile> = withContext(Dispatchers.IO) {
        Log.i("net", "→ DBS $dirName")
        val json = withConnection("DBS") { socket, out, input ->
            exec(socket, out, input, "DBS $dirName", onProgress)
        }
        if (json == null) {
            Log.w("net", "DBS 返回 null（连接或命令失败）")
            return@withContext emptyList()
        }
        val list = parseDbs(json)
        Log.i("net", "← DBS 解析出 ${list.size} 个库（原始长度 ${json.length}）")
        list
    }

    /** Generic request-response command on the control plane. */
    suspend fun rawRequest(cmd: String): String? = withContext(Dispatchers.IO) {
        val op = cmd.substringBefore(' ')
        withConnection(op) { socket, out, input -> exec(socket, out, input, cmd) }
    }

    /** Streams the raw ciphertext of a database into [sink]. */
    suspend fun fetchTo(path: String, sink: java.io.OutputStream, onProgress: (Long) -> Unit): Boolean =
        stream("FETCH", path, sink, onProgress)

    /**
     * Streams the decrypted plaintext of a database into [sink].
     *
     * <p>The module decrypts inside WeChat using its own SQLCipher and the real
     * cipher spec, which is why stock SQLCipher parameters never match.
     */
    suspend fun dumpTo(path: String, sink: java.io.OutputStream, onProgress: (Long) -> Unit): Boolean =
        stream("DUMP", path, sink, onProgress)

    private suspend fun stream(
        op: String,
        path: String,
        sink: java.io.OutputStream,
        onProgress: (Long) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        var socket: Socket? = null
        try {
            val conn = open(op) ?: run {
                Log.w("net", "$op 终止：无法建立连接 path=$path")
                return@withContext false
            }
            socket = conn.first
            val out = conn.second
            val input = conn.third
            writeText(out, "$op $path")

            val headFrame = readText(input)
            if (headFrame == null) {
                Log.w("net", "$op 失败：服务端未返回头部 path=$path")
                return@withContext false
            }
            val headObj = runCatching { JSONObject(headFrame) }.getOrNull()
            if (headObj == null) {
                Log.w("net", "$op 失败：头部不是 JSON：${head(headFrame)}")
                return@withContext false
            }
            if (!headObj.optBoolean("ok", false)) {
                Log.w("net", "$op 服务端拒绝：${head(headFrame)}")
                return@withContext false
            }
            val declared = headObj.optLong("size", -1L)
            val plain = headObj.optBoolean("plaintext", false)
            Log.i("net", "$op 开始 path=$path 服务端声明 size=$declared plaintext=$plain")

            var total = 0L
            var chunks = 0
            while (true) {
                val frame = readFrame(input)
                if (frame == null) {
                    Log.w("net", "$op 中途断流 path=$path（已收 $total 字节 / $chunks 块）")
                    return@withContext false
                }
                if (frame.first == TYPE_TEXT) {
                    val txt = frame.second.decodeToString()
                    if (txt == "END") {
                        Log.i(
                            "net",
                            "$op 完成 path=$path 共 $total 字节 / $chunks 块，耗时 ${System.currentTimeMillis() - t0}ms",
                        )
                        return@withContext true
                    }
                    Log.w("net", "$op 收到意外文本帧：${head(txt)}")
                    return@withContext false
                }
                sink.write(frame.second)
                total += frame.second.size
                chunks++
                if (chunks == 1 || chunks % 64 == 0) {
                    Log.i("net", "$op 进度 path=$path 已收 $total 字节（第 $chunks 块）")
                }
                onProgress(total)
            }
            @Suppress("UNREACHABLE_CODE")
            true
        } catch (t: Throwable) {
            Log.e("net", "$op 异常 path=$path", t)
            false
        } finally {
            if (liveSocket === socket) liveSocket = null
            runCatching { socket?.close() }
        }
    }

    // ---------------------------------------------------------------- internals

    private suspend fun <T> withConnection(
        label: String,
        block: (Socket, DataOutputStream, DataInputStream) -> T,
    ): T? = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            val conn = open(label) ?: return@withContext null
            socket = conn.first
            block(conn.first, conn.second, conn.third)
        } catch (t: Throwable) {
            Log.e("net", "$label 命令异常", t)
            null
        } finally {
            if (liveSocket === socket) liveSocket = null
            runCatching { socket?.close() }
        }
    }

    /**
     * Socket of the command currently in flight, if any.
     *
     * <p>SO_TIMEOUT only bounds a single read, and coroutine cancellation cannot interrupt a
     * blocking socket read at all. A watchdog therefore has to close the socket to make the
     * read fail immediately; [abortActive] is that handle.
     */
    @Volatile
    private var liveSocket: java.net.Socket? = null

    /** Closes the in-flight connection, unblocking whoever is reading from it. */
    fun abortActive() {
        runCatching { liveSocket?.close() }
        liveSocket = null
    }

    /** Connects and performs the handshake. Every failure branch logs a reason. */
    /** Constant-time compare, so a wrong proof cannot be discovered byte by byte. */
    private fun constantTimeEquals(a: String?, b: String?): Boolean {
        if (a == null || b == null || a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    private fun open(label: String): Triple<Socket, DataOutputStream, DataInputStream>? {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", port), 2000)
            socket.soTimeout = 30000
            socket.tcpNoDelay = true
            liveSocket = socket
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val nonce = newNonce()
            writeText(out, "HELLO {\"v\":1,\"client\":\"${Channel.CLIENT_VERSION}\",\"nonce\":\"$nonce\"}")
            val reply = readText(input)
            if (reply == null) {
                Log.w("net", "[$label] handshake failed: no ACK from the server")
                return null
            }
            // The ACK proves the module holds the token; the server then challenges us back,
            // so we answer with a proof over its nonce before it will serve any command.
            val serverProof = extract(reply, "proof")
            if (!constantTimeEquals(hmacProof(nonce, token), serverProof)) {
                Log.w("net", "[$label] handshake failed: ACK proof mismatch")
                return null
            }
            val serverNonce = extract(reply, "nonce")
            if (serverNonce == null) {
                Log.w("net", "[$label] handshake failed: ACK carried no nonce")
                return null
            }
            writeText(out, "AUTH {\"v\":1,\"proof\":\"${hmacProof(serverNonce, token)}\"}")
            val ready = readText(input)
            if (ready == null || !ready.startsWith("READY")) {
                Log.w("net", "[$label] handshake failed: server rejected AUTH ($ready)")
                return null
            }
            Triple(socket, out, input)
        } catch (t: Throwable) {
            Log.e("net", "[$label] 建连/握手异常（127.0.0.1:$port）", t)
            null
        }
    }

    /**
     * Request-response command.
     *
     * <p>The server may push several `#P {...}` progress frames before the real result.
     * Progress frames are consumed and reported; only the result frame returns.
     */
    private fun exec(
        socket: Socket,
        out: DataOutputStream,
        input: DataInputStream,
        cmd: String,
        onProgress: ((Int, Int, String) -> Unit)? = null,
    ): String? {
        val t0 = System.currentTimeMillis()
        writeText(out, cmd)
        var progressFrames = 0
        while (true) {
            val r = readText(input) ?: return null
            if (r.startsWith(PROGRESS_PREFIX)) {
                progressFrames++
                if (onProgress != null) {
                    val o = runCatching { JSONObject(r.substring(PROGRESS_PREFIX.length)) }.getOrNull()
                    if (o != null) {
                        onProgress(o.optInt("done"), o.optInt("total"), o.optString("stage"))
                    }
                }
                continue
            }
            Log.i(
                "net",
                "← ${cmd.substringBefore(' ')} 返回 ${r.length} 字符（${System.currentTimeMillis() - t0}ms，进度帧 $progressFrames）：" + head(r),
            )
            return r
        }
    }

    /**
     * Fetches a small binary payload, currently avatars.
     *
     * <p>It stays in memory rather than on disk, since avatars are only tens of kilobytes.
     */
    suspend fun fetchBytes(op: String, arg: String, maxBytes: Int = 4 * 1024 * 1024): ByteArray? =
        withContext(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                val conn = open(op) ?: return@withContext null
                socket = conn.first
                val out = conn.second
                val input = conn.third
                writeText(out, "$op $arg")

                val headFrame = readText(input) ?: return@withContext null
                val headObj = runCatching { JSONObject(headFrame) }.getOrNull() ?: return@withContext null
                if (!headObj.optBoolean("ok", false)) {
                    Log.w("net", "$op 服务端拒绝：${head(headFrame)}")
                    return@withContext null
                }
                val buf = java.io.ByteArrayOutputStream()
                while (true) {
                    val frame = readFrame(input) ?: return@withContext null
                    if (frame.first == TYPE_TEXT) {
                        if (frame.second.decodeToString() == "END") {
                            return@withContext buf.toByteArray()
                        }
                        return@withContext null
                    }
                    if (buf.size() + frame.second.size > maxBytes) {
                        Log.w("net", "$op 超过上限 $maxBytes，放弃")
                        return@withContext null
                    }
                    buf.write(frame.second)
                }
                @Suppress("UNREACHABLE_CODE")
                null
            } catch (t: Throwable) {
                Log.w("net", "$op 异常", t)
                null
            } finally {
                runCatching { socket?.close() }
            }
        }

    private fun writeText(out: DataOutputStream, text: String) {
        val b = text.toByteArray(Charsets.UTF_8)
        out.writeInt(b.size)
        out.writeByte(TYPE_TEXT.toInt())
        out.write(b)
        out.flush()
    }

    private fun readFrame(input: DataInputStream): Pair<Byte, ByteArray>? {
        val len = input.readInt()
        if (len < 0 || len > 8 * 1024 * 1024) {
            Log.w("net", "非法帧长度 $len，断开")
            return null
        }
        val type = input.readByte()
        val payload = ByteArray(len)
        input.readFully(payload)
        return type to payload
    }

    private fun readText(input: DataInputStream): String? {
        val f = readFrame(input) ?: return null
        if (f.first != TYPE_TEXT) {
            Log.w("net", "期望文本帧但收到 type=${f.first}")
            return null
        }
        return f.second.decodeToString()
    }

    private fun parseAccounts(json: String): List<Account> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Account(
                dirName = o.optString("id"),
                dir = o.optString("dir"),
                uin = o.optInt("uin"),
                uinSource = o.optString("uinSource"),
                wxid = o.optString("wxid"),
                nickname = o.optString("nickname"),
                alias = o.optString("alias"),
                mobile = o.optString("mobile"),
                hasAvatar = o.optBoolean("hasAvatar", false),
                dbCount = o.optInt("dbCount"),
                totalBytes = o.optLong("totalBytes"),
                decryptable = o.optBoolean("decryptable"),
                note = o.optString("note"),
            )
        }
    } catch (t: Throwable) {
        Log.w("net", "ACCOUNTS 解析失败，原文前 200 字符：" + head(json, 200), t)
        emptyList()
    }

    private fun parseDbs(json: String): List<DbFile> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            DbFile(
                name = o.optString("name"),
                path = o.optString("path"),
                sizeBytes = o.optLong("size"),
                encrypted = o.optBoolean("encrypted"),
                hasWal = o.optBoolean("wal"),
                hasShm = o.optBoolean("shm"),
                hasKey = o.optBoolean("hasKey"),
                nonEssential = io.github.ryf5584.wxdbdump.payload.model.DbCatalog
                    .isNonEssential(o.optString("name")),
                category = o.optString("category", "OTHER"),
                categoryKey = o.optString("categoryKey", "OTHER"),
                description = o.optString("desc", ""),
            )
        }
    } catch (t: Throwable) {
        Log.w("net", "DBS 解析失败，原文前 200 字符：" + head(json, 200), t)
        emptyList()
    }

    companion object {
        private const val TYPE_TEXT: Byte = 0x01

        /** Prefix marking a progress frame rather than a result. */
        private const val PROGRESS_PREFIX = "#P "

        fun newNonce(): String {
            val b = ByteArray(16)
            java.security.SecureRandom().nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }

        fun hmacProof(nonce: String, token: String): String = runCatching {
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(token.toByteArray(), "HmacSHA256"))
            mac.doFinal(nonce.toByteArray()).joinToString("") { "%02x".format(it) }
        }.getOrElse {
            Log.e("net", "HmacSHA256 计算失败", it)
            ""
        }

        private fun head(s: String?, max: Int = 120): String {
            if (s == null) return "null"
            return if (s.length <= max) s else s.substring(0, max) + "…(共${s.length}字符)"
        }

        private fun extract(json: String, key: String): String? {
            val needle = "\"$key\":\""
            val i = json.indexOf(needle)
            if (i < 0) return null
            val start = i + needle.length
            val end = json.indexOf('"', start)
            return if (end < 0) null else json.substring(start, end)
        }
    }
}

/** Streams captured bytes straight to the SAF target, avoiding an extra copy. */
class SafSink(private val target: java.io.OutputStream) : java.io.OutputStream() {
    override fun write(b: Int) = target.write(b)
    override fun write(b: ByteArray) = target.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = target.write(b, off, len)
    override fun flush() = target.flush()
    override fun close() = target.close()
}
