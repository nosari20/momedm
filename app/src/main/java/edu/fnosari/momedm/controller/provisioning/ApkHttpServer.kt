package edu.fnosari.momedm.controller.provisioning

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream

/** Serves our own APK at `/momedm.apk` for the managed device's Setup Wizard download. */
class ApkHttpServer(
    private val apkPath: String,
    port: Int = QrPayloadBuilder.HTTP_PORT,
    /** Called (on the server's worker thread) each time the APK itself is fetched. */
    private val onApkServed: (() -> Unit)? = null,
) : NanoHTTPD(port) {
    companion object { private const val LOG_TAG = "ApkHttpServer" }

    override fun serve(session: IHTTPSession): Response {
        Log.d(LOG_TAG, "${session.method} ${session.uri} from ${session.remoteIpAddress}")
        if (session.uri != "/${QrPayloadBuilder.APK_FILE_NAME}") return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
        val f = File(apkPath)
        val r = newFixedLengthResponse(Response.Status.OK, "application/vnd.android.package-archive", FileInputStream(f), f.length())
        r.addHeader("Content-Disposition", "attachment; filename=\"${QrPayloadBuilder.APK_FILE_NAME}\"")
        onApkServed?.invoke()
        return r
    }
}
