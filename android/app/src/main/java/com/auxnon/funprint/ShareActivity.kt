package com.auxnon.funprint

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import java.io.File

/**
 * Invisible share target. Asks for Bluetooth permission the first time, copies the shared
 * images somewhere the service can read them, hands off to [PrintService] and closes.
 */
@SuppressLint("MissingPermission")
class ShareActivity : Activity() {
    private var uris: List<Uri> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uris = sharedUris()
        val missing = missingPermissions()
        when {
            uris.isEmpty() -> done("Nothing to print")
            adapter() == null -> done("This device has no Bluetooth")
            missing.isNotEmpty() -> requestPermissions(missing, REQ_PERMISSIONS)
            else -> ensureBluetoothOn()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode != REQ_PERMISSIONS) return
        if (bluetoothPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            ensureBluetoothOn()
        } else {
            done("Print needs Bluetooth permission")
        }
    }

    @Deprecated("Framework Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_ENABLE_BT) return
        if (resultCode == RESULT_OK) startPrinting() else done("Bluetooth is off")
    }

    private fun ensureBluetoothOn() {
        if (adapter()?.isEnabled == true) {
            startPrinting()
        } else {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BT)
        }
    }

    private fun startPrinting() {
        Thread {
            // Our read grant on the shared URIs ends with this activity, so copy them first.
            val files = uris.mapIndexedNotNull { i, uri -> runCatching { copy(uri, i) }.getOrNull() }
            runOnUiThread {
                if (files.isEmpty()) {
                    done("Couldn't read the image")
                } else {
                    startForegroundService(
                        Intent(this, PrintService::class.java)
                            .putStringArrayListExtra(PrintService.EXTRA_FILES, ArrayList(files))
                    )
                    done(if (files.size == 1) "Printing…" else "Printing ${files.size} images…")
                }
            }
        }.start()
    }

    private fun copy(uri: Uri, index: Int): String {
        val file = File(cacheDir, "share-${System.nanoTime()}-$index")
        contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file.path
    }

    private fun sharedUris(): List<Uri> {
        val extras = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            )
            Intent.ACTION_SEND_MULTIPLE -> (
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            ).orEmpty()
            else -> emptyList()
        }
        if (extras.isNotEmpty()) return extras
        val clip = intent.clipData ?: return emptyList()
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    }

    private fun bluetoothPermissions(): List<String> = if (Build.VERSION.SDK_INT >= 31) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION) // needed to scan before Android 12
    }

    /**
     * Bluetooth is required. Notifications are optional, but without them Android 13+ hides
     * the service's "Printed" toast, so ask for them in the same first-run prompt.
     */
    private fun missingPermissions(): Array<String> {
        val bluetooth = bluetoothPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (bluetooth.isEmpty()) return emptyArray()
        val notifications = Manifest.permission.POST_NOTIFICATIONS
        val askNotifications = Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(notifications) != PackageManager.PERMISSION_GRANTED
        return (bluetooth + listOfNotNull(notifications.takeIf { askNotifications })).toTypedArray()
    }

    private fun adapter(): BluetoothAdapter? = getSystemService(BluetoothManager::class.java)?.adapter

    private fun done(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        private const val REQ_PERMISSIONS = 1
        private const val REQ_ENABLE_BT = 2
    }
}
