package com.universalremote.tv.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.universalremote.tv.remote.ConnectionState
import com.universalremote.tv.ui.screens.DevicesScreen
import com.universalremote.tv.ui.screens.KeyboardScreen
import com.universalremote.tv.ui.screens.RemoteScreen
import com.universalremote.tv.ui.screens.SettingsScreen
import com.universalremote.tv.ui.screens.TouchpadScreen
import com.universalremote.tv.ui.screens.VoiceScreen
import com.universalremote.tv.ui.theme.AppTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: MainViewModel = viewModel()
            val themeMode by vm.themeMode.collectAsStateWithLifecycle()
            val dynamicColor by vm.dynamicColor.collectAsStateWithLifecycle()
            val haptics by vm.haptics.collectAsStateWithLifecycle()

            AppTheme(
                themeMode = themeMode,
                dynamicColor = dynamicColor,
                hapticsEnabled = haptics,
            ) {
                AppScaffold(vm)
            }
        }
    }
}

private data class NavItem(val route: String, val label: String, val icon: ImageVector)

private val navItems = listOf(
    NavItem(MainViewModel.ROUTE_REMOTE, "Remote", Icons.Rounded.Tv),
    NavItem(MainViewModel.ROUTE_TOUCHPAD, "Touchpad", Icons.Rounded.TouchApp),
    NavItem(MainViewModel.ROUTE_KEYBOARD, "Keyboard", Icons.Rounded.Keyboard),
    NavItem(MainViewModel.ROUTE_VOICE, "Voice", Icons.Rounded.Mic),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(vm: MainViewModel) {
    val navController = rememberNavController()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val keepScreenOn by vm.keepScreenOn.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val view = LocalView.current

    LaunchedEffect(keepScreenOn) { view.keepScreenOn = keepScreenOn }
    LaunchedEffect(Unit) {
        vm.messages.collect { message -> snackbar.showSnackbar(message) }
    }

    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    val subtitle = when (val state = connection) {
        is ConnectionState.Connected -> state.info.displayName
        is ConnectionState.Connecting -> "Connecting to ${state.device.name}…"
        is ConnectionState.Failed -> state.reason
        ConnectionState.Disconnected -> "No TV connected"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                title = {
                    Column {
                        Text("Universal TV Remote", style = MaterialTheme.typography.titleLarge)
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = when (connection) {
                                is ConnectionState.Connected -> MaterialTheme.colorScheme.secondary
                                is ConnectionState.Failed -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { navController.navigate(MainViewModel.ROUTE_DEVICES) }) {
                        Icon(Icons.Rounded.Wifi, contentDescription = "Devices")
                    }
                    IconButton(onClick = { navController.navigate(MainViewModel.ROUTE_SETTINGS) }) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                navItems.forEach { item ->
                    NavigationBarItem(
                        selected = currentRoute == item.route,
                        onClick = {
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = remember { vm.startDestination },
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(MainViewModel.ROUTE_REMOTE) {
                RemoteScreen(
                    vm = vm,
                    onOpenDevices = { navController.navigate(MainViewModel.ROUTE_DEVICES) },
                    onOpenVoice = { navController.navigate(MainViewModel.ROUTE_VOICE) },
                    onOpenKeyboard = { navController.navigate(MainViewModel.ROUTE_KEYBOARD) },
                    onOpenTouchpad = { navController.navigate(MainViewModel.ROUTE_TOUCHPAD) },
                )
            }
            composable(MainViewModel.ROUTE_TOUCHPAD) { TouchpadScreen(vm) }
            composable(MainViewModel.ROUTE_KEYBOARD) { KeyboardScreen(vm) }
            composable(MainViewModel.ROUTE_VOICE) { VoiceScreen(vm) }
            composable(MainViewModel.ROUTE_SETTINGS) {
                SettingsScreen(
                    vm = vm,
                    onManageDevices = { navController.navigate(MainViewModel.ROUTE_DEVICES) },
                )
            }
            composable(MainViewModel.ROUTE_DEVICES) {
                DevicesScreen(
                    vm = vm,
                    onOpenRemote = {
                        navController.navigate(MainViewModel.ROUTE_REMOTE) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
        }
    }
}
