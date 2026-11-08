package com.dc.andagent.shell

import android.content.Context
import android.provider.Settings
import com.dc.andagent.adb.AdbClient
import com.dc.andagent.tools.AdbSessionHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * R107a / R102：本机提权路由——loopback ADB 单后端（本机无线调试 + 内嵌 ADB 客户端，
 * 复用 [AdbSessionHub]），取代旧提权桥成为 PrivilegeRouter 唯一后端。
 * 老设备（Android 7-10 无无线调试）无降级后端：一律返回引导文案。
 *
 * Settings.Secure 读数在 JVM 单测里不可实测，故把判定 / 引导 / 重试决策抽成 companion
 * 纯函数（[isBackendAvailable] / [resolveGuidance] / [retryPortOrNull]），便于真机迭代。
 */
data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = exitCode == 0
    fun asToolResult(): String = buildString {
        append("exit=$exitCode\n")
        if (stdout.isNotBlank()) append(stdout)
        if (stderr.isNotBlank()) append("\n[stderr] ").append(stderr)
    }
}

class PrivilegeRouter(private val context: Context) {

    /** connect / 重试路径单锁，防并发重复拉起 ADB 会话。AdbSessionHub 自身另有 lock，不重复包裹。 */
    private val routerLock = Any()

    init { AdbSessionHub.init(context.applicationContext) }

    /* ------------------------------ status ------------------------------ */

    /** 读本机无线调试端口；读不到（-1/0）时原样返回，由 [isBackendAvailable] 判不可用。 */
    private fun readWirelessPort(): Int = runCatching {
        Settings.Secure.getInt(context.contentResolver, KEY_WIRELESS_PORT, -1)
    }.getOrDefault(-1)

    /** 附加佐证键：部分 ROM 用它表示 ADB 调试开关。仅作诊断展示，不否决端口判定。 */
    private fun readAdbEnabled(): Int = runCatching {
        Settings.Secure.getInt(context.contentResolver, KEY_ADB_ENABLED, -1)
    }.getOrDefault(-1)

    fun isAvailable(): Boolean = isBackendAvailable(readWirelessPort())

    /** {"backend":"loopback-adb","available":bool,"detail":"..."}，供设置页「本机提权」卡。 */
    fun statusJson(): String {
        val port = readWirelessPort()
        val available = isBackendAvailable(port)
        val detail = if (available) {
            "已检测到无线调试端口 $port；本机 ADB 提权后端可用（连接 127.0.0.1:$port）。\n" +
                "Wireless debugging port $port detected; the on-device ADB backend is available (connect 127.0.0.1:$port)."
        } else {
            resolveGuidance(port)
        }
        return buildJsonObject {
            put("backend", BACKEND)
            put("available", available)
            put("detail", detail)
            put("adbEnabled", readAdbEnabled())
        }.toString()
    }

    /* ---------------------------- execution ---------------------------- */

    /**
     * 以 shell 身份执行 [cmd]。无可用后端 / 连接失败一律返回带引导文案的 [ShellResult]，
     * 绝不向工具执行器抛异常。
     *
     * [timeoutMs] 保留与旧提权桥 run 对齐的调用签名（如 pm install 传 120_000）；
     * 实际阻塞上限由 ADB 传输层的 ACK(8s) / 会话(90s) 读超时决定——openStream 是阻塞式
     * 非 suspend 调用，无法被协程取消中断（R107a 真机迭代项）。
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun execAsShell(cmd: String, timeoutMs: Long = 15_000): ShellResult =
        withContext(Dispatchers.IO) {
            val firstPort = readWirelessPort()
            if (!isBackendAvailable(firstPort)) {
                return@withContext ShellResult(-1, "", resolveGuidance(firstPort))
            }
            val client = synchronized(routerLock) { connectLocked(firstPort) }
                ?: return@withContext ShellResult(-1, "", resolveGuidance(readWirelessPort()))
            val out = client.openStream(cmd)
            if (out.startsWith("ERROR")) ShellResult(-1, "", out)
            else ShellResult(0, out, "")
        }

    /**
     * 在 [routerLock] 内连接 loopback 目标：已存活连接直接复用；connect 失败时重读端口，
     * 若是有效新端口则重试一次（重启 / 重开无线调试后端口会变）；仍失败返回 null。
     */
    private fun connectLocked(firstPort: Int): AdbClient? {
        val firstKey = loopbackKey(firstPort)
        AdbSessionHub.client(firstKey)?.let { return it }
        var port = firstPort
        var r = AdbSessionHub.connect(LOOPBACK_HOST, port)
        if (!r.startsWith("OK")) {
            val retry = retryPortOrNull(firstPort, readWirelessPort())
            if (retry != null) {
                port = retry
                r = AdbSessionHub.connect(LOOPBACK_HOST, port)
            }
            if (!r.startsWith("OK")) return null
        }
        return AdbSessionHub.client(loopbackKey(port))
    }

    companion object {
        const val BACKEND = "loopback-adb"
        const val LOOPBACK_HOST = "127.0.0.1"
        const val KEY_WIRELESS_PORT = "adb_wireless_debugging_port"
        const val KEY_ADB_ENABLED = "adb_enabled"

        /** 静态引导文案（无端口信息时供各工具错误分支复用），双语。 */
        const val ADB_REQUIRED =
            "需要 Android 11+ 无线调试（本机 ADB 提权）：开发者选项→无线调试→开启，然后在 andagent 设置页 ADB 区连接 127.0.0.1:<端口>。\n" +
                "Requires Android 11+ Wireless debugging (on-device ADB): Developer options → Wireless debugging → On, then connect 127.0.0.1:<port> in andagent Settings → ADB."

        fun loopbackKey(port: Int): String = "$LOOPBACK_HOST:$port"

        /** 端口有效（1..65535）即视为后端可用；读不到端口（-1/0）时不可用。 */
        fun isBackendAvailable(wirelessPort: Int): Boolean = wirelessPort in 1..65535

        /**
         * 引导文案生成（纯函数）：portDetected>0 时把实际端口写进连接指引；
         * <=0 时引导用户开启「开发者选项→无线调试」。
         */
        fun resolveGuidance(portDetected: Int): String = if (portDetected > 0) {
            "需要 Android 11+ 无线调试：开发者选项→无线调试→开启，然后在 andagent 设置页 ADB 区连接 127.0.0.1:$portDetected。\n" +
                "Requires Android 11+ Wireless debugging: Developer options → Wireless debugging → On, then connect 127.0.0.1:$portDetected in andagent Settings → ADB."
        } else {
            "需要 Android 11+ 无线调试：开发者选项→无线调试→开启（当前未检测到无线调试端口），开启后在 andagent 设置页 ADB 区连接 127.0.0.1:<端口>。\n" +
                "Requires Android 11+ Wireless debugging: Developer options → Wireless debugging → On (no wireless debugging port detected yet), then connect 127.0.0.1:<port> in andagent Settings → ADB."
        }

        /**
         * 首次连接失败后的重读重试决策（纯函数）：仅当复读到有效且与首次不同的端口时
         * 才允许重试一次（设备重启后无线调试端口会变）；否则返回 null 直接报错。
         */
        fun retryPortOrNull(firstPort: Int, reReadPort: Int): Int? =
            if (reReadPort in 1..65535 && reReadPort != firstPort) reReadPort else null
    }
}
