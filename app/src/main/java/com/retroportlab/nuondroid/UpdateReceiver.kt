package com.retroportlab.nuondroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

/** Result of an update install: shows the system confirmation screen, or reports why it failed. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(confirm)
            }
            return
        }
        if (status == PackageInstaller.STATUS_SUCCESS) return   // the app is replaced and relaunched by the system
        val msg = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> "Update cancelled"
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                "This update is signed differently from the installed NUONDroid. Install it manually from the releases page."
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage to install the update"
            else -> "Update failed" + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.let { ": $it" } ?: "")
        }
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
    }
}
