package com.selfbus.lpcflasher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.selfbus.lpcflasher.data.ControlLine
import com.selfbus.lpcflasher.data.HardwareConfig
import com.selfbus.lpcflasher.data.HardwareConfigs
import com.selfbus.lpcflasher.data.I18n
import com.selfbus.lpcflasher.data.Settings
import java.util.UUID

private const val MAX_CONFIGURABLE_PORTS = 4

/**
 * Lists all hardware wiring profiles (built-in + custom), lets the user pick
 * the active one, and launches create/edit/delete for custom ones.
 */
@Composable
fun HardwareConfigDialog(onDismiss: () -> Unit) {
    val t = I18n::t
    var selectedId by remember { mutableStateOf(Settings.selectedHardwareConfigId) }
    var customConfigs by remember { mutableStateOf(HardwareConfigs.customConfigs()) }
    var editTarget by remember { mutableStateOf<HardwareConfig?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<HardwareConfig?>(null) }

    fun refresh() {
        customConfigs = HardwareConfigs.customConfigs()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("hardwareConfigDialogTitle")) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(t("hardwareConfigBuiltIn"), style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                HardwareConfigs.BUILT_IN.forEach { config ->
                    HardwareConfigRow(
                        config = config,
                        selected = config.id == selectedId,
                        onSelect = {
                            selectedId = config.id
                            HardwareConfigs.select(config.id)
                        }
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(t("hardwareConfigCustom"), style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                customConfigs.forEach { config ->
                    HardwareConfigRow(
                        config = config,
                        selected = config.id == selectedId,
                        onSelect = {
                            selectedId = config.id
                            HardwareConfigs.select(config.id)
                        },
                        onEdit = {
                            editTarget = config
                            showEditor = true
                        },
                        onDelete = { deleteTarget = config }
                    )
                }

                TextButton(onClick = {
                    editTarget = null
                    showEditor = true
                }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(t("hardwareConfigNew"))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(t("close")) }
        }
    )

    if (showEditor) {
        HardwareConfigEditDialog(
            existing = editTarget,
            onDismiss = { showEditor = false },
            onSave = { config ->
                HardwareConfigs.save(config)
                refresh()
                showEditor = false
            }
        )
    }

    deleteTarget?.let { config ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(t("hardwareConfigDeleteConfirmTitle")) },
            text = { Text(t("hardwareConfigDeleteConfirmMessage")) },
            confirmButton = {
                TextButton(onClick = {
                    HardwareConfigs.delete(config.id)
                    selectedId = Settings.selectedHardwareConfigId
                    refresh()
                    deleteTarget = null
                }) {
                    Text(t("hardwareConfigDelete"), color = Color(0xFFD32F2F))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(t("cancel")) }
            }
        )
    }
}

@Composable
private fun HardwareConfigRow(
    config: HardwareConfig,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f)) {
            Text(config.name, style = MaterialTheme.typography.bodyMedium)
            Text(summarize(config), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        if (onEdit != null) {
            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private fun summarize(config: HardwareConfig): String {
    fun port(idx: Int) = "USB${idx + 1}"
    return "TX/RX→${port(config.dataPortIndex)} · " +
        "RESET→${port(config.resetPortIndex)}.${config.resetLine} · " +
        "ISP→${port(config.ispPortIndex)}.${config.ispLine}"
}

/** Create/edit form for a single custom hardware configuration. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HardwareConfigEditDialog(
    existing: HardwareConfig?,
    onDismiss: () -> Unit,
    onSave: (HardwareConfig) -> Unit
) {
    val t = I18n::t
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var dataPort by remember { mutableIntStateOf(existing?.dataPortIndex ?: 0) }
    var resetPort by remember { mutableIntStateOf(existing?.resetPortIndex ?: 0) }
    var resetLine by remember { mutableStateOf(existing?.resetLine ?: ControlLine.DTR) }
    var ispPort by remember { mutableIntStateOf(existing?.ispPortIndex ?: 0) }
    var ispLine by remember { mutableStateOf(existing?.ispLine ?: ControlLine.RTS) }
    var showNameError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing != null) t("hardwareConfigEditTitleEdit") else t("hardwareConfigEditTitleNew")) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; showNameError = false },
                    label = { Text(t("hardwareConfigName")) },
                    isError = showNameError,
                    supportingText = { if (showNameError) Text(t("hardwareConfigNameRequired")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(t("hardwareConfigDataPort"), style = MaterialTheme.typography.labelLarge)
                PortDropdown(selected = dataPort, onSelect = { dataPort = it })

                HorizontalDivider()
                Text(t("hardwareConfigResetLine"), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.weight(1f)) { PortDropdown(selected = resetPort, onSelect = { resetPort = it }) }
                    Box(Modifier.weight(1f)) { LineDropdown(selected = resetLine, onSelect = { resetLine = it }) }
                }

                HorizontalDivider()
                Text(t("hardwareConfigIspLine"), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.weight(1f)) { PortDropdown(selected = ispPort, onSelect = { ispPort = it }) }
                    Box(Modifier.weight(1f)) { LineDropdown(selected = ispLine, onSelect = { ispLine = it }) }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (name.isBlank()) {
                    showNameError = true
                    return@Button
                }
                onSave(
                    HardwareConfig(
                        id = existing?.id ?: UUID.randomUUID().toString(),
                        name = name.trim(),
                        dataPortIndex = dataPort,
                        resetPortIndex = resetPort,
                        resetLine = resetLine,
                        ispPortIndex = ispPort,
                        ispLine = ispLine,
                        builtIn = false
                    )
                )
            }) {
                Text(t("save"))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text(t("cancel")) }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortDropdown(selected: Int, onSelect: (Int) -> Unit) {
    val t = I18n::t
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = "USB${selected + 1}",
            onValueChange = {},
            readOnly = true,
            label = { Text(t("hardwareConfigPort")) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (idx in 0 until MAX_CONFIGURABLE_PORTS) {
                DropdownMenuItem(
                    text = { Text("USB${idx + 1}") },
                    onClick = { onSelect(idx); expanded = false }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LineDropdown(selected: ControlLine, onSelect: (ControlLine) -> Unit) {
    val t = I18n::t
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.name,
            onValueChange = {},
            readOnly = true,
            label = { Text(t("hardwareConfigSignal")) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ControlLine.entries.forEach { line ->
                DropdownMenuItem(
                    text = { Text(line.name) },
                    onClick = { onSelect(line); expanded = false }
                )
            }
        }
    }
}

