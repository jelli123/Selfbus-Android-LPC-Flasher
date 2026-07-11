package com.selfbus.lpcflasher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.selfbus.lpcflasher.ui.FlasherViewModel
import com.selfbus.lpcflasher.ui.BusUpdaterViewModel
import com.selfbus.lpcflasher.ui.AppRoot
import com.selfbus.lpcflasher.ui.theme.LpcFlasherTheme

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_USB_PERMISSION = "com.selfbus.lpcflasher.USB_PERMISSION"

        // Intel-HEX firmware files have no registered MIME type; Android's document
        // picker reports them as "application/octet-stream". Restricting to this
        // type keeps most pickers focused on firmware files and hides photos, PDFs
        // (application/pdf) and text files (text/plain).
        //
        // NOTE: SAF cannot filter by file extension, and some OEM pickers ignore
        // the MIME filter entirely, so the final ".hex-only" guarantee is enforced
        // after selection in [isAllowedFirmware].
        private val FIRMWARE_MIME_TYPES = arrayOf("application/octet-stream")
    }

    private val viewModel: FlasherViewModel by viewModels()
    private val busUpdaterViewModel: BusUpdaterViewModel by viewModels()
    private var pendingConnectDeviceId: Int? = null

    // SAF file picker – open firmware file (LPC Flasher)
    private val openFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { if (isAllowedFirmware(it)) viewModel.loadFirmwareFile(it) }
    }

    // SAF file picker – open firmware file (KNX Bus-Updater)
    private val openBusFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { if (isAllowedFirmware(it)) busUpdaterViewModel.loadFirmwareFile(it) }
    }

    // SAF file picker – save read-back / log
    private var pendingSaveContent: String? = null
    private val saveFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        uri?.let {
            val content = pendingSaveContent ?: return@let
            contentResolver.openOutputStream(it)?.bufferedWriter()?.use { w -> w.write(content) }
            pendingSaveContent = null
        }
    }

    // USB permission receiver
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    viewModel.refreshDeviceList()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    viewModel.forceDisconnect()
                    viewModel.refreshDeviceList()
                }
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    viewModel.onUsbPermissionResult(granted)
                    pendingConnectDeviceId = null
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register USB broadcast receiver – split by export scope
        val systemFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        val permissionFilter = IntentFilter(ACTION_USB_PERMISSION)
        ContextCompat.registerReceiver(this, usbReceiver, systemFilter, ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(this, usbReceiver, permissionFilter, ContextCompat.RECEIVER_NOT_EXPORTED)

        // Handle USB device attached via intent
        intent?.let { handleUsbIntent(it) }

        // Wire up USB permission callback for auto-request from ViewModel
        viewModel.onRequestUsbPermission = { device -> requestUsbPermission(device) }

        setContent {
            LpcFlasherTheme {
                AppRoot(
                    flasherViewModel = viewModel,
                    busUpdaterViewModel = busUpdaterViewModel,
                    onOpenFile = { openFileLauncher.launch(FIRMWARE_MIME_TYPES) },
                    onSaveFile = { fileName, content ->
                        pendingSaveContent = content
                        saveFileLauncher.launch(fileName)
                    },
                    onOpenBusFile = { openBusFileLauncher.launch(FIRMWARE_MIME_TYPES) }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    private fun handleUsbIntent(intent: Intent) {
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            device?.let { viewModel.onUsbDeviceAttached() }
        }
    }

    fun requestUsbPermission(device: UsbDevice) {
        val manager = getSystemService(USB_SERVICE) as UsbManager
        if (manager.hasPermission(device)) {
            // Permission already granted — signal immediately
            viewModel.onUsbPermissionResult(true)
            return
        }
        pendingConnectDeviceId = device.deviceId
        // FLAG_MUTABLE is required so the system can fill in EXTRA_PERMISSION_GRANTED
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val permissionIntent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        val pi = PendingIntent.getBroadcast(this, 0, permissionIntent, flags)
        manager.requestPermission(device, pi)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        viewModel.forceDisconnect()
    }

    /**
     * Enforce that only Intel-HEX firmware files can be loaded. SAF pickers cannot
     * filter by file extension (and some OEM pickers ignore the MIME filter), so
     * we reject anything that is not a *.hex / *.ihex file after selection.
     */
    private fun isAllowedFirmware(uri: android.net.Uri): Boolean {
        val name = queryDisplayName(uri)?.lowercase()
        val ok = name != null && (name.endsWith(".hex") || name.endsWith(".ihex"))
        if (!ok) {
            android.widget.Toast.makeText(
                this,
                com.selfbus.lpcflasher.data.I18n.t("onlyHexFiles"),
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        return ok
    }

    private fun queryDisplayName(uri: android.net.Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
