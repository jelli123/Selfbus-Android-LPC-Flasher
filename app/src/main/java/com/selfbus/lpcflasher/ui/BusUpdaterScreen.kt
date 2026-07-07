package com.selfbus.lpcflasher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selfbus.lpcflasher.data.FirmwareCatalog

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BusUpdaterScreen(
    viewModel: BusUpdaterViewModel,
    onOpenFile: () -> Unit,
    onMenuClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val log by viewModel.log.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("KNX Bus-Updater") },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Default.Menu, contentDescription = "Menü")
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
                        Text("Gateway (KNXnet/IP)", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        if (uiState.isDiscovering) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            OutlinedButton(
                                onClick = { viewModel.discoverGateways() },
                                enabled = !uiState.isBusy && !uiState.isConnected
                            ) { Text("Suchen") }
                        }
                    }

                    // Discovered gateways (selectable)
                    if (uiState.discoveredGateways.isNotEmpty()) {
                        Text("Gefundene Gateways:", style = MaterialTheme.typography.bodySmall)
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
                            label = { Text("IP-Adresse") },
                            singleLine = true,
                            enabled = !uiState.isBusy && !uiState.isConnected,
                            modifier = Modifier.weight(2f)
                        )
                        OutlinedTextField(
                            value = uiState.gatewayPort,
                            onValueChange = viewModel::setGatewayPort,
                            label = { Text("Port") },
                            singleLine = true,
                            enabled = !uiState.isBusy && !uiState.isConnected,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = uiState.ownAddress,
                        onValueChange = viewModel::setOwnAddress,
                        label = { Text("Eigene KNX-Adresse") },
                        singleLine = true,
                        enabled = !uiState.isBusy && !uiState.isConnected,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // ---- Device search ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Zu programmierendes Gerät", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = uiState.deviceSearchMode == BusUpdaterViewModel.DeviceSearchMode.PROG_BUTTON,
                            onClick = { viewModel.setDeviceSearchMode(BusUpdaterViewModel.DeviceSearchMode.PROG_BUTTON) },
                            label = { Text("Prog.-Knopf") },
                            enabled = !uiState.isBusy && !uiState.isConnected
                        )
                        FilterChip(
                            selected = uiState.deviceSearchMode == BusUpdaterViewModel.DeviceSearchMode.SERIAL,
                            onClick = { viewModel.setDeviceSearchMode(BusUpdaterViewModel.DeviceSearchMode.SERIAL) },
                            label = { Text("Seriennr.") },
                            enabled = !uiState.isBusy && !uiState.isConnected
                        )
                        FilterChip(
                            selected = uiState.deviceSearchMode == BusUpdaterViewModel.DeviceSearchMode.DEVICE_ADDRESS,
                            onClick = { viewModel.setDeviceSearchMode(BusUpdaterViewModel.DeviceSearchMode.DEVICE_ADDRESS) },
                            label = { Text("Geräteadresse") },
                            enabled = !uiState.isBusy && !uiState.isConnected
                        )
                        FilterChip(
                            selected = uiState.deviceSearchMode == BusUpdaterViewModel.DeviceSearchMode.MANUAL,
                            onClick = { viewModel.setDeviceSearchMode(BusUpdaterViewModel.DeviceSearchMode.MANUAL) },
                            label = { Text("Manuell") },
                            enabled = !uiState.isBusy && !uiState.isConnected
                        )
                    }
                    when (uiState.deviceSearchMode) {
                        BusUpdaterViewModel.DeviceSearchMode.PROG_BUTTON -> {
                            Text(
                                "Programmierknopf am Zielgerät drücken, dann verbinden.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        BusUpdaterViewModel.DeviceSearchMode.SERIAL -> {
                            OutlinedTextField(
                                value = uiState.knxSerialInput,
                                onValueChange = viewModel::setKnxSerialInput,
                                label = { Text("KNX-Seriennummer") },
                                placeholder = { Text("013A:XXXXXXXX") },
                                singleLine = true,
                                enabled = !uiState.isBusy && !uiState.isConnected,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "Findet das laufende Gerät und startet es in den Bootloader. " +
                                    "Hinweis: Der Bootloader selbst antwortet ggf. nicht auf die Seriennummer.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        BusUpdaterViewModel.DeviceSearchMode.DEVICE_ADDRESS -> {
                            OutlinedTextField(
                                value = uiState.deviceAddressInput,
                                onValueChange = viewModel::setDeviceAddressInput,
                                label = { Text("Geräteadresse (Normalbetrieb, z. B. 1.1.5)") },
                                singleLine = true,
                                enabled = !uiState.isBusy && !uiState.isConnected,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "Startet das Gerät über die KNX-Adresse in den Bootloader (Programmiermodus).",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        BusUpdaterViewModel.DeviceSearchMode.MANUAL -> {
                            OutlinedTextField(
                                value = uiState.progAddress,
                                onValueChange = viewModel::setProgAddress,
                                label = { Text("Bootloader-Adresse (z. B. 15.15.192)") },
                                singleLine = true,
                                enabled = !uiState.isBusy && !uiState.isConnected,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    OutlinedTextField(
                        value = uiState.uidInput,
                        onValueChange = viewModel::setUidInput,
                        label = { Text("UID zum Entsperren (optional)") },
                        placeholder = { Text("leer = automatisch vom Bootloader lesen") },
                        singleLine = true,
                        enabled = !uiState.isBusy && !uiState.isConnected,
                        modifier = Modifier.fillMaxWidth()
                    )
                    uiState.foundDeviceAddress?.let {
                        Text("Gefunden: $it", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.connectAndIdentify() },
                            enabled = !uiState.isBusy && !uiState.isConnected,
                            modifier = Modifier.weight(1f)
                        ) { Text("Verbinden") }
                        OutlinedButton(
                            onClick = { viewModel.disconnect() },
                            enabled = !uiState.isBusy && uiState.isConnected,
                            modifier = Modifier.weight(1f)
                        ) { Text("Trennen") }
                    }
                }
            }

            // ---- Device info ----
            if (uiState.uid != null || uiState.bootloaderInfo != null || uiState.appVersion != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Geräteinformationen", style = MaterialTheme.typography.titleMedium)
                        uiState.uid?.let {
                            Text("UID: $it", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        }
                        uiState.knxSerial?.let {
                            Text("KNX#: $it", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        }
                        uiState.bootloaderInfo?.let {
                            Text("Bootloader: $it", fontSize = 13.sp)
                        }
                        uiState.appVersion?.let {
                            if (it.isNotBlank()) Text("App-Version: $it", fontSize = 13.sp)
                        }
                    }
                }
            }

            // ---- Firmware ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Firmware", style = MaterialTheme.typography.titleMedium)

                    // Firmware catalog (only bootloader-based "flashstart" versions)
                    BusUpdaterCatalog(uiState, viewModel)

                    Divider()

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
                    ) { Text("Eigene Datei wählen (.hex / .bin)") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = uiState.eraseBeforeFlash,
                            onCheckedChange = viewModel::setEraseBeforeFlash,
                            enabled = !uiState.isBusy
                        )
                        Text("Bereich vor dem Flashen löschen")
                    }
                }
            }

            // ---- Actions ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Aktionen", style = MaterialTheme.typography.titleMedium)
                    Button(
                        onClick = { viewModel.flashFirmware() },
                        enabled = !uiState.isBusy && uiState.isConnected && uiState.firmwareFileName != null,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Flashen") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.eraseFlash() },
                            enabled = !uiState.isBusy && uiState.isConnected,
                            modifier = Modifier.weight(1f)
                        ) { Text("Löschen") }
                        OutlinedButton(
                            onClick = { viewModel.restartDevice() },
                            enabled = !uiState.isBusy && uiState.isConnected,
                            modifier = Modifier.weight(1f)
                        ) { Text("Neustart") }
                    }
                }
            }

            // ---- Progress ----
            if (uiState.progress >= 0) {
                Column {
                    LinearProgressIndicator(
                        progress = (uiState.progress / 100f).coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth().height(8.dp)
                    )
                    Text("${uiState.progress}%", style = MaterialTheme.typography.bodySmall)
                }
            }

            // ---- Log ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Protokoll", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { viewModel.clearLog() }) { Text("Leeren") }
                    }
                    if (log.isEmpty()) {
                        Text("—", style = MaterialTheme.typography.bodySmall)
                    } else {
                        log.takeLast(200).forEach { line ->
                            Text(
                                line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            Text(
                "Hinweis: KNX-Flashing ist experimentell und noch nicht am Gerät getestet. " +
                    "Nur Vollflash-Modus. Bei Protokollfehlern kann ein Gerät unbrauchbar werden.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
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

    // Category
    var catExpanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = catExpanded, onExpandedChange = { if (enabled) catExpanded = it }) {
        OutlinedTextField(
            value = uiState.selectedCategory?.let { key ->
                uiState.categories.find { it.first == key }?.let { (_, cat) ->
                    "${cat.icon} ${cat.name["de"] ?: cat.name["en"] ?: key}"
                }
            } ?: "Kategorie wählen",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Kategorie") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = catExpanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = catExpanded, onDismissRequest = { catExpanded = false }) {
            uiState.categories.forEach { (key, cat) ->
                DropdownMenuItem(
                    text = { Text("${cat.icon} ${cat.name["de"] ?: cat.name["en"] ?: key}") },
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
                        dev.name["de"] ?: dev.name["en"] ?: id
                    }
                } ?: "Gerät wählen",
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                label = { Text("Gerät") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = devExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = devExpanded, onDismissRequest = { devExpanded = false }) {
                uiState.devices.forEach { (id, dev) ->
                    DropdownMenuItem(
                        text = { Text(dev.name["de"] ?: dev.name["en"] ?: id) },
                        onClick = { viewModel.selectDevice(id); devExpanded = false }
                    )
                }
            }
        }
    }

    // Firmware variant (flashstart only)
    if (uiState.isLoadingVariants) {
        Text("⏳ Lade Firmware-Versionen ...", style = MaterialTheme.typography.bodySmall)
    } else if (uiState.selectedDevice != null) {
        if (uiState.firmwareVariants.isEmpty()) {
            Text(
                "Keine Flashstart-Version für dieses Gerät verfügbar.",
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            var varExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = varExpanded, onExpandedChange = { if (enabled) varExpanded = it }) {
                OutlinedTextField(
                    value = uiState.selectedVariant?.let {
                        FirmwareCatalog.formatFirmwareName(it.name, it.hints)
                    } ?: "Firmware wählen",
                    onValueChange = {},
                    readOnly = true,
                    enabled = enabled,
                    label = { Text("Firmware (Flashstart)") },
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
            Button(
                onClick = { viewModel.loadSelectedCatalogFirmware() },
                enabled = uiState.selectedVariant != null && !uiState.isBusy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Firmware laden") }
        }
    }
}
