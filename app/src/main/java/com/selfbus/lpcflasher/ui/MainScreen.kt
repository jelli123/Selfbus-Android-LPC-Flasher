package com.selfbus.lpcflasher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selfbus.lpcflasher.data.*
import kotlinx.coroutines.launch

private fun plainLabel(text: String): String =
    text.replace(Regex("^[^\\p{L}\\p{N}]+\\s*"), "")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: FlasherViewModel,
    onOpenFile: () -> Unit,
    onSaveFile: (fileName: String, content: String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val logEntries by viewModel.logEntries.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    var showSettings by remember { mutableStateOf(false) }

    val t = I18n::t

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LPC11xx Flasher") },
                actions = {
                    // Language toggle
                    TextButton(onClick = {
                        viewModel.setLanguage(
                            if (uiState.language == I18n.Lang.DE) I18n.Lang.EN else I18n.Lang.DE
                        )
                    }) {
                        Text(if (uiState.language == I18n.Lang.DE) "EN" else "DE")
                    }
                    // Settings
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Icon(Icons.Default.Settings, contentDescription = t("settings"))
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
            // ---- Connection Section ----
            ConnectionSection(uiState, viewModel, onSaveFile)

            // ---- Firmware File Section ----
            FirmwareSection(uiState, viewModel, onOpenFile)

            // ---- Firmware Catalog Section ----
            CatalogSection(uiState, viewModel)

            // ---- Action Buttons ----
            ActionButtons(uiState, viewModel, onSaveFile, scope)

            // ---- Progress Bar ----
            AnimatedVisibility(uiState.progress >= 0) {
                Column {
                    LinearProgressIndicator(
                        progress = (uiState.progress / 100f).coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth().height(8.dp)
                    )
                    Text("${uiState.progress}%", style = MaterialTheme.typography.bodySmall)
                }
            }

            // ---- Log Section ----
            LogSection(logEntries, uiState.debugVisible, viewModel, clipboardManager, onSaveFile)

            // ---- Settings (collapsible) ----
            AnimatedVisibility(showSettings) {
                SettingsSection(viewModel)
            }
        }
    }

    // ---- Safety Dialog ----
    if (uiState.showSafetyDialog && uiState.safetyCheckResults != null) {
        SafetyCheckDialog(
            results = uiState.safetyCheckResults!!,
            onConfirm = { viewModel.confirmSafetyAndFlash() },
            onDismiss = { viewModel.cancelSafetyDialog() }
        )
    }
}

// ==== CONNECTION SECTION ====

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSection(
    uiState: FlasherViewModel.UiState,
    viewModel: FlasherViewModel,
    onSaveFile: (fileName: String, content: String) -> Unit
) {
    val t = I18n::t

    LaunchedEffect(uiState.availableDevices.size) {
        if (uiState.availableDevices.isEmpty()) {
            viewModel.selectDeviceIndex(0)
        } else if (uiState.selectedDeviceIndex >= uiState.availableDevices.size) {
            viewModel.selectDeviceIndex(0)
        }
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Usb, contentDescription = null, tint = if (uiState.isConnected) Color(0xFF4CAF50) else Color.Gray)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (uiState.isConnected) "🟢 ${t("connected")}" else "🔴 ${t("disconnected")}",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            if (uiState.chipName != null) {
                Text("${t("chip")}: ${uiState.chipName}", style = MaterialTheme.typography.bodyMedium)
                uiState.chipSpecs?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
            }

            if (uiState.uid != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("UID: ${uiState.uid}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(4.dp))
                    val clipboard = LocalClipboardManager.current
                    IconButton(onClick = { clipboard.setText(AnnotatedString(uiState.uid)) }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy UID", modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = {
                        val ts = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.getDefault()).format(java.util.Date())
                        onSaveFile("uid_$ts.txt", "UID: ${uiState.uid}")
                    }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Save, contentDescription = t("saveUid"), modifier = Modifier.size(16.dp))
                    }
                }
            }

            // Device list
            if (uiState.availableDevices.isEmpty()) {
                Text(t("noDevicesFound"), color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            } else {
                val selected = uiState.availableDevices.getOrNull(uiState.selectedDeviceIndex)
                    ?: uiState.availableDevices.first()

                if (uiState.availableDevices.size > 1) {
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                        OutlinedTextField(
                            value = selected.second,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(t("device")) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            uiState.availableDevices.forEachIndexed { index, (_, name) ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        viewModel.selectDeviceIndex(index)
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                } else {
                    Text("🔌 ${selected.second}", style = MaterialTheme.typography.bodySmall)
                }

                val hasPermission = viewModel.serial.hasPermission(selected.first.device)
                Text(
                    if (hasPermission) "\u2705 ${t("usbPermissionGranted")}" else "\u26A0\uFE0F ${t("usbPermissionRequired")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasPermission) Color(0xFF2E7D32) else Color(0xFFB71C1C)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val devices = uiState.availableDevices

                // Reset button (always available)
                OutlinedButton(
                    onClick = { viewModel.resetDevice() },
                    enabled = devices.isNotEmpty() && !uiState.isBusy
                ) {
                    Text(t("reset"))
                }

                IconButton(onClick = { viewModel.refreshDeviceList() }) {
                    Icon(Icons.Default.Refresh, contentDescription = t("refresh"))
                }
            }
        }
    }
}

// ==== FIRMWARE FILE SECTION ====

@Composable
fun FirmwareSection(
    uiState: FlasherViewModel.UiState,
    viewModel: FlasherViewModel,
    onOpenFile: () -> Unit
) {
    val t = I18n::t

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t("firmwareFile"), style = MaterialTheme.typography.titleMedium)

            Button(onClick = onOpenFile) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(t("openFile"))
            }

            if (uiState.firmwareFileName != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${uiState.firmwareFileName} (${uiState.firmwareSize} Bytes)",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ==== CATALOG SECTION ====

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogSection(
    uiState: FlasherViewModel.UiState,
    viewModel: FlasherViewModel
) {
    val t = I18n::t
    val lang = if (uiState.language == I18n.Lang.DE) "de" else "en"

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t("firmwareCatalog"), style = MaterialTheme.typography.titleMedium)

            // Category dropdown
            var catExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = catExpanded, onExpandedChange = { catExpanded = it }) {
                OutlinedTextField(
                    value = uiState.selectedCategory?.let { key ->
                        uiState.categories.find { it.first == key }?.let { (_, cat) ->
                            "${cat.icon} ${cat.name[lang] ?: cat.name["en"] ?: key}"
                        }
                    } ?: t("selectCategory"),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(t("category")) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = catExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
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

            // Device dropdown
            if (uiState.devices.isNotEmpty()) {
                var devExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = devExpanded, onExpandedChange = { devExpanded = it }) {
                    OutlinedTextField(
                        value = uiState.selectedDevice?.let { id ->
                            uiState.devices.find { it.first == id }?.let { (_, dev) ->
                                dev.name[lang] ?: dev.name["en"] ?: id
                            }
                        } ?: t("selectDevice"),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(t("device")) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = devExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
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

            // Firmware variant dropdown
            if (uiState.isLoadingVariants) {
                Text("⏳ ${t("loading")}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            } else if (uiState.firmwareVariants.isNotEmpty()) {
                var varExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = varExpanded, onExpandedChange = { varExpanded = it }) {
                    OutlinedTextField(
                        value = uiState.selectedVariant?.let {
                            FirmwareCatalog.formatFirmwareName(it.name, it.hints)
                        } ?: t("selectFirmware"),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(t("firmware")) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = varExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
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

                // Hint text
                uiState.selectedVariant?.let { variant ->
                    val hintText = FirmwareCatalog.getHintText(variant.hints, lang)
                    if (hintText.isNotEmpty()) {
                        Surface(
                            color = Color(0xFFFFF8E1),
                            tonalElevation = 1.dp,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF5D4037), modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    hintText,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                    color = Color(0xFF3E2723)
                                )
                            }
                        }
                    }
                }

                // Load button
                Button(
                    onClick = { viewModel.loadSelectedCatalogFirmware() },
                    enabled = uiState.selectedVariant != null && !uiState.isBusy
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(t("loadFirmware"))
                }
            }
        }
    }
}

// ==== ACTION BUTTONS ====

@Composable
fun ActionButtons(
    uiState: FlasherViewModel.UiState,
    viewModel: FlasherViewModel,
    onSaveFile: (String, String) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val t = I18n::t
    val hasDevices = uiState.availableDevices.isNotEmpty()
    val enabled = hasDevices && !uiState.isBusy
    val hasData = viewModel.hexData.isNotEmpty()

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t("operations"), style = MaterialTheme.typography.titleMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { viewModel.flash() },
                    enabled = enabled && hasData,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(t("flashWrite"))
                }

                OutlinedButton(
                    onClick = { viewModel.verify() },
                    enabled = enabled && hasData,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Done, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(t("verifyNow"))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { viewModel.erase() },
                    enabled = enabled,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD32F2F)),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(plainLabel(t("erase")))
                }

                OutlinedButton(
                    onClick = { viewModel.blankCheck() },
                    enabled = enabled,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(plainLabel(t("blankCheck")))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val hex = viewModel.readFlash()
                            if (hex != null) {
                                val ts = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.getDefault()).format(java.util.Date())
                                onSaveFile("flash_dump_$ts.hex", hex)
                            }
                        }
                    },
                    enabled = enabled
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(plainLabel(t("readFlash")))
                }
            }
        }
    }
}

// ==== LOG SECTION ====

@Composable
fun LogSection(
    logEntries: List<Logger.LogEntry>,
    debugVisible: Boolean,
    viewModel: FlasherViewModel,
    clipboardManager: androidx.compose.ui.platform.ClipboardManager,
    onSaveFile: (String, String) -> Unit
) {
    val t = I18n::t
    val listState = rememberLazyListState()
    val filteredEntries = if (debugVisible) logEntries
        else logEntries.filter { it.type != Logger.LogType.DEBUG }

    // Auto-scroll to bottom
    LaunchedEffect(filteredEntries.size) {
        if (filteredEntries.isNotEmpty()) listState.animateScrollToItem(filteredEntries.size - 1)
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("log"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    clipboardManager.setText(AnnotatedString(viewModel.getLogText()))
                    Logger.success(I18n.t("logCopied"))
                }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.ContentCopy, contentDescription = t("copyLog"), modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = {
                    onSaveFile(viewModel.getLogFileName(), viewModel.getLogText())
                    Logger.success(I18n.t("logSaved"))
                }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Save, contentDescription = t("saveLog"), modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = { viewModel.clearLog() }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = t("clearLog"), modifier = Modifier.size(18.dp))
                }
                TextButton(onClick = { viewModel.toggleDebug() }) {
                    Text(if (debugVisible) "Debug ▲" else "Debug ▼", fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(4.dp))

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(Color(0xFF1E1E1E))
                    .padding(8.dp)
            ) {
                items(filteredEntries) { entry ->
                    val color = when (entry.type) {
                        Logger.LogType.INFO -> Color(0xFFBBBBBB)
                        Logger.LogType.SUCCESS -> Color(0xFF4CAF50)
                        Logger.LogType.WARNING -> Color(0xFFFF9800)
                        Logger.LogType.ERROR -> Color(0xFFF44336)
                        Logger.LogType.DEBUG -> Color(0xFF90CAF9)
                    }
                    Text(
                        "[${entry.timestamp}] ${entry.message}",
                        color = color,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
        }
    }
}

// ==== SETTINGS SECTION ====

@Composable
fun SettingsSection(viewModel: FlasherViewModel) {
    val t = I18n::t
    var readChunkSlider by remember { mutableFloatStateOf(Settings.readChunkSizeIndex.toFloat()) }
    var writeChunkSlider by remember { mutableFloatStateOf(Settings.writeChunkSizeIndex.toFloat()) }
    var autoReset by remember { mutableStateOf(Settings.autoResetAfterFlash) }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t("settings"), style = MaterialTheme.typography.titleMedium)

            SettingRow(t("baudRate"), Settings.baudRate.toString()) {
                Settings.baudRate = it.toIntOrNull() ?: 115200
            }
            SettingRow(t("oscillator"), Settings.oscillator.toString()) {
                Settings.oscillator = it.toIntOrNull() ?: 12000
            }
            SettingRow("T1 (ms)", Settings.t1Timing.toString()) {
                Settings.t1Timing = it.toIntOrNull() ?: 100
            }
            SettingRow("T2 (ms)", Settings.t2Timing.toString()) {
                Settings.t2Timing = it.toIntOrNull() ?: 200
            }
            SettingRow(t("resetDuration"), Settings.resetDuration.toString()) {
                Settings.resetDuration = it.toIntOrNull() ?: 100
            }
            SettingRow(t("postResetDelay"), Settings.postResetDelay.toString()) {
                Settings.postResetDelay = it.toIntOrNull() ?: 100
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = autoReset,
                    onCheckedChange = {
                        autoReset = it
                        Settings.autoResetAfterFlash = it
                    }
                )
                Text(t("autoReset"), style = MaterialTheme.typography.bodyMedium)
            }

            // Read/Write chunk sizes
            TextButton(onClick = {
                Settings.readChunkSizeIndex = 2   // 512
                Settings.writeChunkSizeIndex = 1  // 512
                Settings.uuLineDelay = 0
                Settings.readLineDelay = 1
                readChunkSlider = 2f
                writeChunkSlider = 1f
            }) {
                Text(t("autoTransferSettings"))
            }

            Text("${t("readChunk")} ${Settings.readChunkSize} ${t("bytes")}", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = readChunkSlider,
                onValueChange = { readChunkSlider = it },
                onValueChangeFinished = { Settings.readChunkSizeIndex = readChunkSlider.toInt() },
                valueRange = 0f..2f,
                steps = 1
            )

            Text("${t("writeChunk")} ${Settings.writeChunkSize} ${t("bytes")}", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = writeChunkSlider,
                onValueChange = { writeChunkSlider = it },
                onValueChangeFinished = { Settings.writeChunkSizeIndex = writeChunkSlider.toInt() },
                valueRange = 0f..1f,
                steps = 0
            )

            SettingRow(t("uuLineDelayMs"), Settings.uuLineDelay.toString()) {
                Settings.uuLineDelay = it.toIntOrNull() ?: 0
            }
            SettingRow(t("readLineDelayMs"), Settings.readLineDelay.toString()) {
                Settings.readLineDelay = it.toIntOrNull() ?: 1
            }
        }
    }
}

@Composable
fun SettingRow(label: String, value: String, onValueChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { onValueChange(it) },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}

// ==== SAFETY CHECK DIALOG ====

@Composable
fun SafetyCheckDialog(
    results: FirmwareSafety.AllCheckResults,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val t = I18n::t
    val summary = results.summary

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (summary.passed) {
                    if (summary.warnings.isNotEmpty()) "⚠️ ${t("warningsFound")}" else "✅ ${t("checksPassedTitle")}"
                } else "🛑 ${t("errorsFound")}"
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (summary.info.isNotEmpty()) {
                    Text("ℹ️ ${t("information")}", style = MaterialTheme.typography.titleSmall)
                    summary.info.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.height(8.dp))
                }
                if (summary.warnings.isNotEmpty()) {
                    Text("⚠️ ${t("warnings")}", style = MaterialTheme.typography.titleSmall, color = Color(0xFFF57F17))
                    summary.warnings.forEach { w ->
                        Text("• ${w.message}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFF57F17))
                        w.details?.forEach { (k, v) ->
                            Text("    $k: $v", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (summary.errors.isNotEmpty()) {
                    Text("🛑 ${t("criticalErrors")}", style = MaterialTheme.typography.titleSmall, color = Color(0xFFD32F2F))
                    summary.errors.forEach { e ->
                        Text("• ${e.message}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFD32F2F))
                        e.details?.forEach { (k, v) ->
                            Text("    $k: $v", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (summary.passed) Color(0xFF4CAF50) else Color(0xFFFF9800)
                )
            ) {
                Text(if (summary.passed) t("proceedFlash") else t("flashAnyway"))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text(t("cancel")) }
        }
    )
}
