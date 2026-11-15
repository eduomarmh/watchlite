package com.watchlite.companion

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/** "Share → WatchLite": queues the shared file(s) for the watch and closes immediately. */
class ShareToWatchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = ArrayList<Uri>()
        @Suppress("DEPRECATION")
        when (intent.action) {
            Intent.ACTION_SEND -> (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris += it }
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris += it }
        }
        intent.clipData?.let { c -> if (uris.isEmpty()) for (i in 0 until c.itemCount) c.getItemAt(i).uri?.let { uris += it } }
        when {
            uris.isEmpty() -> Toast.makeText(this, R.string.share_nothing, Toast.LENGTH_SHORT).show()
            !PhoneLinkService.connected -> Toast.makeText(this, R.string.not_connected, Toast.LENGTH_LONG).show()
            else -> {
                // the read grant for shared URIs ends with this activity: copy them first (stays on screen, invisible)
                Toast.makeText(this, getString(R.string.share_sending, uris.size), Toast.LENGTH_SHORT).show()
                Thread {
                    val local = uris.mapNotNull { FileTransfer.stage(this, it) }
                    runOnUiThread { FileTransfer.enqueue(this, local); finish() }
                }.start()
                return
            }
        }
        finish()
    }
}
