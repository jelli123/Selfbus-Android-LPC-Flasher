package com.selfbus.lpcflasher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selfbus.lpcflasher.data.FirmwareCatalog
import com.selfbus.lpcflasher.data.I18n

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BusUpdaterScreen(
    viewModel: BusUpdaterViewModel,
    onOpenFile: () -> Unit,
    onSaveFile: (fileName: String, content: String) -> Unit,
    onMenuClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val log by viewModel.log.collectAsState()
    var showInfo by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    // Pending destructive action awaiting confirmation ("erase" or "restart").
    var pendingDanger by remember { mutableStateOf<String?>(null) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("KNX Bus-Updater") },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Default.Menu, contentDescription = I18n.t("bu_menu"))
                    }
                },
                actions = {
                    // Language toggle (shared globally with the LPC Flasher)
                    TextButton(onClick = {
                        viewModel.setLanguage(
                            if (uiState.language == I18n.Lang.DE) I18n.Lang.EN else I18n.Lang.DE
                        )
                    }) {
                        Text(if (uiState.language == I18n.Lang.DE) "EN" else "DE")
                    }
                    // Info / about
                    IconButton(onClick = { showInfo = true }) {
                        Icon(Icons.Default.Info, contentDescription = I18n.t("about"))
                    }
                    // Settings
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = I18n.t("bu_settings"))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---- Gateway / connection ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(I18n.t("bu_gateway"), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        if (uiState.isDiscovering) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            OutlinedButton(
                                onClick = { viewModel.discoverGateways() },
                                enabled = !uiState.isBusy && !uiState.isConnected
                            ) { Text(I18n.t("bu_search")) }
                        }
                    }

                    // Discovered gateways (selectable)
                    if (uiState.discoveredGateways.isNotEmpty()) {
                        Text(I18n.t("bu_foundGateways"), style = MaterialTheme.typography.bodySmall)
                        uiState.discoveredGateways.forEachIndexed { index, gw ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = uiState.selectedGatewayIndex == index,
                                        enabled = !uiState.isBusy && !uiState.isConnected,
                                        onClick = { viewModel.selectGateway(index) }
                                    )
                            ) {
                                RadioButton(
                                    selected = uiState.selectedGatewayIndex == index,
                                    onClick = { viewModel.selectGateway(index) },
                                    enabled = !uiState.isBusy && !uiState.isConnected
                                )
                                Column {
                                    Text(gw.name, fontSize = 13.sp)
                                    Text(
                                        "${gw.ip}:${gw.port}",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = uiState.gatewayIp,
                            onValueChange = viewModel::setGatewayIp,
                            label = { Text(I18n.t("bu_ipAddress")) },
                            singleLine = true,
                            enabled = !uiState.isBusy && !uiState.isConnected,
                            modifier = Modifier.weight(2f)
                        )
                        OutlinedTextField(
                            value = uiState.gatewayPort,
                            onValueChange = viewModel::setGatewayPort,
                            label = { Text(I18n.t("bu_port")) },
                            singleLine = true,
                            enabled = !uiState.isBusy && !uiState.isConnected,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = uiState.ownAddress,
                        onValueChange = viewModel::setOwnAddress,
                        label = { Text(I18n.t("bu_ownAddress")) },
                        singleLine = true,
                        enabled = !uiState.isBusy && !uiState.isConnected,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // ---- Device search ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(I18n.t("bu_deviceToProgram"), style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = uiState.deviceSearchMode == BusUpdaterViewModel.DeviceSearchMode.PROG_BUTTON,
                            onClick = { viewModel.setDeviceSearchMode(BusUpdaterViewModel.DeviceSearchMode.PROG_BUTTON) },
                            label = { Text(I18n.t("bu_progButton")) },
                            enabled = !uiState.isBusy && !uiState.isConnected
                        )
                        FilterChip(
                            selected = uiState.deviceSearchMode == BusUpdaterViewModel.DeviceSearchMode.DEVICE_ADDRESS,
                            onClick = { viewModel.setDeviceSearchMode(BusUpdaterViewModel.DeviceSearchMode.DEVICE_ADDRESS) },
                            label = { Text(I18n.t("bu_deviceAddressChip")) },
                            enabled = !uiState.isBusy && !uiState.isConnected
                        )
                        FilterChip(
                            selected = uiState.deviceSearchMode == BusUpdaterViewModel.DeviceSearchMode.MANUAL,
                            onClick = { viewModel.setDeviceSearchMode(BusUpdaterViewModel.DeviceSearchMode.MANUAL) },
                            label = { Text(I18n.t("bu_manual")) },
                            enabled = !uiState.isBusy && !uiState.isConnected
                        )
                    }
                    when (uiState.deviceSearchMode) {
                        BusUpdaterViewModel.DeviceSearchMode.PROG_BUTTON -> {
                            Text(
                                I18n.t("bu_progButtonHint"),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        BusUpdaterViewModel.DeviceSearchMode.DEVICE_ADDRESS -> {
                            OutlinedTextField(
                                value = uiState.deviceAddressInput,
                                onValueChange = viewModel::setDeviceAddressInput,
                                label = { Text(I18n.t("bu_deviceAddressLabel")) },
                                singleLine = true,
                                enabled = !uiState.isBusy && !uiState.isConnected,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                I18n.t("bu_deviceAddressHint"),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        BusUpdaterViewModel.DeviceSearchMode.MANUAL -> {
                            OutlinedTextField(
                                value = uiState.progAddress,
                                onValueChange = viewModel::setProgAddress,
                                label = { Text(I18n.t("bu_bootloaderAddress")) },
                                singleLine = true,
                                enabled = !uiState.isBusy && !uiState.isConnected,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    OutlinedTextField(
                        value = uiState.uidInput,
                        onValueChange = viewModel::setUidInput,
                        label = { Text(I18n.t("bu_uidUnlock")) },
                        placeholder = { Text(I18n.t("bu_uidPlaceholder")) },
                        singleLine = true,
                        enabled = !uiState.isBusy && !uiState.isConnected,
                        modifier = Modifier.fillMaxWidth()
                    )
                    uiState.foundDeviceAddress?.let {
                        Text("${I18n.t("bu_found")} $it", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.connectAndIdentify() },
                            enabled = !uiState.isBusy && !uiState.isConnected,
                            modifier = Modifier.weight(1f)
                        ) { Text(I18n.t("bu_connect")) }
                        OutlinedButton(
                            onClick = { viewModel.disconnect() },
                            enabled = !uiState.isBusy && uiState.isConnected,
                            modifier = Modifier.weight(1f)
                        ) { Text(I18n.t("bu_disconnect")) }
                    }
                }
            }

            // ---- Device info ----
            if (uiState.uid != null || uiState.bootloaderInfo != null || uiState.appVersion != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(I18n.t("bu_deviceInfo"), style = MaterialTheme.typography.titleMedium)
                        uiState.uid?.let {
                            Text("UID: $it", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        }
                        uiState.knxSerial?.let {
                            Text("KNX#: $it", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        }
                        uiState.bootloaderInfo?.let {
                            Text("${I18n.t("bu_bootloaderLabel")} $it", fontSize = 13.sp)
                        }
                        uiState.appVersion?.let {
                            if (it.isNotBlank()) Text("${I18n.t("bu_appVersionLabel")} $it", fontSize = 13.sp)
                        }
                    }
                }
            }

            // ---- Firmware ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(I18n.t("bu_firmware"), style = MaterialTheme.typography.titleMedium)

                    // Firmware catalog (only bootloader-based "flashstart" versions)
                    BusUpdaterCatalog(uiState, viewModel)

                    HorizontalDivider()

                    uiState.firmwareFileName?.let {
                        Text(
                            "$it (${uiState.firmwareSize} Bytes ab 0x%X)".format(uiState.firmwareStartAddress),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    OutlinedButton(
                        onClick = onOpenFile,
                        enabled = !uiState.isBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(I18n.t("bu_selectOwnFile")) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = uiState.eraseBeforeFlash,
                            onCheckedChange = viewModel::setEraseBeforeFlash,
                            enabled = !uiState.isBusy
                        )
                        Text(I18n.t("bu_eraseBeforeFlash"))
                    }
                }
            }

            // ---- Actions ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(I18n.t("bu_actions"), style = MaterialTheme.typography.titleMedium)
                    Button(
                        onClick = { viewModel.flashFirmware() },
                        enabled = !uiState.isBusy && uiState.isConnected && uiState.firmwareFileName != null,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(I18n.t("bu_flash")) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { pendingDanger = "erase" },
                            enabled = !uiState.isBusy && uiState.isConnected,
                            modifier = Modifier.weight(1f)
                        ) { Text(I18n.t("bu_erase")) }
                        OutlinedButton(
                            onClick = { pendingDanger = "restart" },
                            enabled = !uiState.isBusy && uiState.isConnected,
                            modifier = Modifier.weight(1f)
                        ) { Text(I18n.t("bu_restart")) }
                    }
                }
            }

            // ---- Progress ----
            if (uiState.progress >= 0) {
                Column {
                    LinearProgressIndicator(
                        progress = { (uiState.progress / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(8.dp)
                    )
                    val rateText = buildString {
                        append("${uiState.progress}%")
                        if (uiState.currentRateBps > 0) {
                            append("  •  ${I18n.t("bu_rateCurrent")} ${formatRate(uiState.currentRateBps)}")
                        }
                        if (uiState.averageRateBps > 0) {
                            append("  •  ${I18n.t("bu_rateAverage")} ${formatRate(uiState.averageRateBps)}")
                        }
                    }
                    Text(rateText, style = MaterialTheme.typography.bodySmall)
                }
            }

            // ---- Log ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            I18n.t("bu_log"),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { clipboardManager.setText(AnnotatedString(viewModel.getLogText())) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = I18n.t("bu_copyLog"), modifier = Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = { onSaveFile(viewModel.getLogFileName(), viewModel.getLogText()) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = I18n.t("bu_saveLog"), modifier = Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = {
                                com.selfbus.lpcflasher.data.LogShare.shareLog(
                                    context = context,
                                    text = viewModel.getLogText(),
                                    chooserTitle = I18n.t("bu_shareLog"),
                                    baseName = viewModel.getLogFileName().removeSuffix(".txt"),
                                    zipThresholdLines = uiState.zipThresholdLines
                                )
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = I18n.t("bu_shareLog"), modifier = Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = { viewModel.clearLog() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = I18n.t("bu_clearLog"), modifier = Modifier.size(18.dp))
                        }
                        TextButton(onClick = { viewModel.toggleDebug() }) {
                            Text(if (uiState.debugVisible) "Debug ▲" else "Debug ▼", fontSize = 12.sp)
                        }
                    }
                    val visibleLog = log.filter { !it.debug || uiState.debugVisible }
                    if (visibleLog.isEmpty()) {
                        Text("—", style = MaterialTheme.typography.bodySmall)
                    } else {
                        visibleLog.takeLast(200).forEach { line ->
                            Text(
                                line.text,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = if (line.debug) MaterialTheme.colorScheme.outline else Color.Unspecified,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            Text(
                I18n.t("bu_experimentalHint"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }

    if (showInfo) {
        AboutDialog(onDismiss = { showInfo = false })
    }

    if (showSettings) {
        BusUpdaterSettingsDialog(
            zipThresholdLines = uiState.zipThresholdLines,
            calimeroLogLevel = uiState.calimeroLogLevel,
            onZipThresholdChange = viewModel::setZipThresholdLines,
            onLogLevelChange = viewModel::setCalimeroLogLevel,
            onDismiss = { showSettings = false }
        )
    }

    // Confirmation for destructive actions (erase / restart): after these the
    // device may have no valid application, so a new connection is only possible
    // by pressing the device's programming button.
    if (pendingDanger != null) {
        val isErase = pendingDanger == "erase"
        AlertDialog(
            onDismissRequest = { pendingDanger = null },
            icon = { Icon(Icons.Default.Info, contentDescription = null) },
            title = { Text(I18n.t(if (isErase) "bu_erase" else "bu_restart")) },
            text = { Text(I18n.t("bu_dangerWarning")) },
            confirmButton = {
                TextButton(onClick = {
                    val action = pendingDanger
                    pendingDanger = null
                    if (action == "erase") viewModel.eraseFlash() else viewModel.restartDevice()
                }) { Text(I18n.t("bu_execute")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDanger = null }) { Text(I18n.t("bu_cancel")) }
            }
        )
    }
}

/** Format a byte-per-second rate as a human-readable string (B/s, KB/s). */
private fun formatRate(bps: Double): String {
    return if (bps >= 1024) "%.1f KB/s".format(bps / 1024.0) else "%.0f B/s".format(bps)
}

/** Settings dialog for the Bus-Updater (log zip threshold + calimero log level). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BusUpdaterSettingsDialog(
    zipThresholdLines: Int,
    calimeroLogLevel: String,
    onZipThresholdChange: (Int) -> Unit,
    onLogLevelChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var thresholdText by remember(zipThresholdLines) { mutableStateOf(zipThresholdLines.toString()) }
    val levels = listOf("ERROR", "WARN", "INFO", "DEBUG", "TRACE")
    var levelExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
        title = { Text(I18n.t("bu_settings")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Zip threshold
                OutlinedTextField(
                    value = thresholdText,
                    onValueChange = { v ->
                        thresholdText = v.filter { it.isDigit() }.take(7)
                        thresholdText.toIntOrNull()?.let(onZipThresholdChange)
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(I18n.t("bu_zipThreshold")) },
                    supportingText = { Text(I18n.t("bu_zipThresholdHint")) },
                    modifier = Modifier.fillMaxWidth()
                )

                // Calimero log level
                ExposedDropdownMenuBox(
                    expanded = levelExpanded,
                    onExpandedChange = { levelExpanded = it }
                ) {
                    OutlinedTextField(
                        value = calimeroLogLevel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(I18n.t("bu_logLevel")) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = levelExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = levelExpanded,
                        onDismissRequest = { levelExpanded = false }
                    ) {
                        levels.forEach { level ->
                            DropdownMenuItem(
                                text = { Text(level) },
                                onClick = {
                                    onLogLevelChange(level)
                                    levelExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    )
}

/**
 * "About" popup showing the app version and Selfbus copyright.
 * Shared between the Bus-Updater and the LPC Flasher screens.
 */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val versionName = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (_: Exception) {
        "?"
    }
    val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        },
        icon = { Icon(Icons.Default.Info, contentDescription = null) },
        title = { Text("Selfbus Flasher") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${I18n.t("bu_version")} v$versionName", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "© $year selfbus.org",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "https://selfbus.org",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    )
}

/**
 * Firmware catalog selector for the Bus-Updater. Reuses the shared
 * [FirmwareCatalog] but only offers the bootloader-based "flashstart" variants.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BusUpdaterCatalog(
    uiState: BusUpdaterViewModel.UiState,
    viewModel: BusUpdaterViewModel
) {
    val enabled = !uiState.isBusy && !uiState.isConnected
    val lang = if (uiState.language == I18n.Lang.DE) "de" else "en"

    // Category
    var catExpanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = catExpanded, onExpandedChange = { if (enabled) catExpanded = it }) {
        OutlinedTextField(
            value = uiState.selectedCategory?.let { key ->
                uiState.categories.find { it.first == key }?.let { (_, cat) ->
                    "${cat.icon} ${cat.name[lang] ?: cat.name["en"] ?: key}"
                }
            } ?: I18n.t("selectCategory"),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(I18n.t("category")) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = catExpanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = catExpanded, onDismissRequest = { catExpanded = false }) {
            uiState.categories.forEach { (key, cat) ->
                DropdownMenuItem(
                    text = { Text("${cat.icon} ${cat.name[lang] ?: cat.name["en"] ?: key}") },
                    onClick = { viewModel.selectCategory(key); catExpanded = false }
                )
            }
        }
    }

    // Device
    if (uiState.devices.isNotEmpty()) {
        var devExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = devExpanded, onExpandedChange = { if (enabled) devExpanded = it }) {
            OutlinedTextField(
                value = uiState.selectedDevice?.let { id ->
                    uiState.devices.find { it.first == id }?.let { (_, dev) ->
                        dev.name[lang] ?: dev.name["en"] ?: id
                    }
                } ?: I18n.t("selectDevice"),
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                label = { Text(I18n.t("device")) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = devExpanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled).fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = devExpanded, onDismissRequest = { devExpanded = false }) {
                uiState.devices.forEach { (id, dev) ->
                    DropdownMenuItem(
                        text = { Text(dev.name[lang] ?: dev.name["en"] ?: id) },
                        onClick = { viewModel.selectDevice(id); devExpanded = false }
                    )
                }
            }
        }
    }

    // Firmware variant (flashstart only)
    if (uiState.isLoadingVariants) {
        Text(I18n.t("bu_loadingVariants"), style = MaterialTheme.typography.bodySmall)
    } else if (uiState.selectedDevice != null) {
        if (uiState.firmwareVariants.isEmpty()) {
            Text(
                I18n.t("bu_noFlashstart"),
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            var varExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = varExpanded, onExpandedChange = { if (enabled) varExpanded = it }) {
                OutlinedTextField(
                    value = uiState.selectedVariant?.let {
                        FirmwareCatalog.formatFirmwareName(it.name, it.hints)
                    } ?: I18n.t("bu_selectFirmware"),
                    onValueChange = {},
                    readOnly = true,
                    enabled = enabled,
                    label = { Text(I18n.t("bu_firmwareFlashstart")) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = varExpanded) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled).fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = varExpanded, onDismissRequest = { varExpanded = false }) {
                    uiState.firmwareVariants.forEach { file ->
                        DropdownMenuItem(
                            text = { Text(FirmwareCatalog.formatFirmwareName(file.name, file.hints)) },
                            onClick = { viewModel.selectVariant(file); varExpanded = false }
                        )
                    }
                }
            }
            Button(
                onClick = { viewModel.loadSelectedCatalogFirmware() },
                enabled = uiState.selectedVariant != null && !uiState.isBusy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(I18n.t("bu_loadFirmware")) }
        }
    }
}
