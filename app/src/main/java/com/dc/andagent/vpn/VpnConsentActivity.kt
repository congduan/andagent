package com.dc.andagent.vpn

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 工具线程与授权 Activity 之间的静态握手。
 *
 * `VpnService.prepare()` 只能在 Activity 里用 startActivityForResult 完成，
 * 而工具执行在后台线程，所以用 CountDownLatch 让工具线程等待用户点确认。
 */
object VpnConsentBridge {
    const val EXTRA_REQUEST_ID = "requestId"

    private val pending = ConcurrentHashMap<String, CountDownLatch>()
    private val results = ConcurrentHashMap<String, Boolean>()

    fun newRequest(): String {
        val id = UUID.randomUUID().toString()
        pending[id] = CountDownLatch(1)
        return id
    }

    fun report(id: String, granted: Boolean) {
        results[id] = granted
        pending.remove(id)?.countDown()
    }

    /** 工具线程等待；超时或拒绝返回 false。 */
    fun await(id: String, timeoutMs: Long): Boolean {
        val latch = pending[id] ?: return results[id] == true
        return try {
            if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) results[id] == true else false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } finally {
            pending.remove(id)
            results.remove(id)
        }
    }
}

/** 透明跳板：只做 VpnService.prepare() 的系统授权，然后立即 finish。 */
class VpnConsentActivity : Activity() {

    companion object {
        const val REQUEST_CONSENT = 4311

        fun intent(context: Context, requestId: String): Intent =
            Intent(context, VpnConsentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(VpnConsentBridge.EXTRA_REQUEST_ID, requestId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val pending = VpnService.prepare(this)
            if (pending == null) {
                finishWith(true)
            } else {
                startActivityForResult(pending, REQUEST_CONSENT)
            }
        } catch (_: Exception) {
            finishWith(false)
        }
    }

    @Deprecated("VpnService.prepare result is only deliverable through the legacy callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CONSENT) return
        finishWith(resultCode == RESULT_OK)
    }

    private fun finishWith(granted: Boolean) {
        intent?.getStringExtra(VpnConsentBridge.EXTRA_REQUEST_ID)?.let {
            VpnConsentBridge.report(it, granted)
        }
        finish()
    }
}
