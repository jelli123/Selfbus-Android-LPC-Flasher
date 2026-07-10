package com.selfbus.lpcflasher.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import kotlinx.coroutines.launch

private enum class AppDestination { LpcFlasher, BusUpdater }

/**
 * Top-level navigation host with a hamburger drawer.
 * The LPC Flasher is the default destination.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(
    flasherViewModel: FlasherViewModel,
    busUpdaterViewModel: BusUpdaterViewModel,
    onOpenFile: () -> Unit,
    onSaveFile: (fileName: String, content: String) -> Unit,
    onOpenBusFile: () -> Unit
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var destination by remember { mutableStateOf(AppDestination.LpcFlasher) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Selfbus Flasher",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Memory, contentDescription = null) },
                    label = { Text("LPC Flasher (USB)") },
                    selected = destination == AppDestination.LpcFlasher,
                    onClick = {
                        destination = AppDestination.LpcFlasher
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Wifi, contentDescription = null) },
                    label = { Text("KNX Bus-Updater") },
                    selected = destination == AppDestination.BusUpdater,
                    onClick = {
                        destination = AppDestination.BusUpdater
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        }
    ) {
        when (destination) {
            AppDestination.LpcFlasher -> MainScreen(
                viewModel = flasherViewModel,
                onOpenFile = onOpenFile,
                onSaveFile = onSaveFile,
                onMenuClick = { scope.launch { drawerState.open() } }
            )
            AppDestination.BusUpdater -> BusUpdaterScreen(
                viewModel = busUpdaterViewModel,
                onOpenFile = onOpenBusFile,
                onSaveFile = onSaveFile,
                onMenuClick = { scope.launch { drawerState.open() } }
            )
        }
    }
}
