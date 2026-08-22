package edu.fnosari.momedm.managed

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

class ManagedLinkService : Service() {
    companion object { fun start(context: Context, fromBoot: Boolean = false) {} }
    override fun onBind(intent: Intent?): IBinder? = null
}
