package com.auxnon.funprint

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

/** Prints shared images in the background, one share at a time, and reports with a toast. */
class PrintService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        val files = intent?.getStringArrayListExtra(EXTRA_FILES).orEmpty().map(::File)
        worker.execute {
            val message = try {
                Printer(this).print(files.map(Raster::packets))
                if (files.size == 1) "Printed" else "Printed ${files.size} images"
            } catch (e: PrintException) {
                "Print failed: ${e.message}"
            } catch (e: Exception) {
                "Print failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                files.forEach(File::delete)
            }
            main.post {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                stopSelf(startId) // only stops once the latest share is done
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun goForeground() {
        val channel = NotificationChannel(CHANNEL, "Printing", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_print)
            .setContentTitle("Printing…")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, notification)
        }
    }

    companion object {
        const val EXTRA_FILES = "files"
        private const val CHANNEL = "print"
    }
}
