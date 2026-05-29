package com.andy.macdock.ui.main

import android.content.Context
import android.app.Activity
import android.content.ContextWrapper
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.andy.macdock.MacAppInfo
import com.andy.macdock.NearbyService

// Custom Premium Colors matching macOS Dock style
val PrimaryDark = Color(0xFF0F172A)     // Slate 900
val SecondaryDark = Color(0xFF1E293B)   // Slate 800
val CardBackground = Color(0xFF334155)  // Slate 700
val NeonIndigo = Color(0xFF6366F1)      // Indigo 500
val NeonCyan = Color(0xFF06B6D4)        // Cyan 500
val AccentGreen = Color(0xFF10B981)     // Emerald 500
val WarningRed = Color(0xFFEF4444)      // Red 500

// macOS Dock Style Glassmorphic Background for overall layout
val DockBorderColor = Color.White.copy(alpha = 0.15f)
val DockBgColor = Color.White.copy(alpha = 0.08f)

@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                android.Manifest.permission.NEARBY_WIFI_DEVICES,
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_ADVERTISE,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    var hasPermissions by remember {
        mutableStateOf(requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasPermissions = results.values.all { it }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(PrimaryDark, SecondaryDark)
                )
            )
    ) {
        if (!hasPermissions) {
            PermissionRequestScreen(
                onRequestPermissions = { launcher.launch(requiredPermissions) }
            )
        } else {
            NearbyControllerScreen(modifier = modifier)
        }
    }
}

@Composable
fun PermissionRequestScreen(onRequestPermissions: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Share,
            contentDescription = "Connection Permissions",
            tint = NeonCyan,
            modifier = Modifier.size(80.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Permissions Required",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "This app requires Bluetooth and Local Network permissions to discover and connect to your Mac OS device offline.",
            color = Color.LightGray,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onRequestPermissions,
            colors = ButtonDefaults.buttonColors(containerColor = NeonIndigo),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Text("Grant Permissions", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

@OptIn(ExperimentalAnimationApi::class, ExperimentalFoundationApi::class)
@Composable
fun NearbyControllerScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val viewModel: MainScreenViewModel = viewModel(factory = MainScreenViewModel.Factory)
    
    // Initialize NearbyService through ViewModel (MVVM)
    viewModel.initializeNearbyService(context)

    val connectionStatus = viewModel.connectionStatus
    val verificationPin = viewModel.verificationPin
    val showVerificationDialog = viewModel.showVerificationDialog
    val verificationHandler = viewModel.verificationHandler
    val macApps = viewModel.macApps
    var searchQuery by remember { mutableStateOf("") }

    val sharedPrefs = remember { context.getSharedPreferences("macdock_prefs", Context.MODE_PRIVATE) }
    var selectedApps by remember {
        mutableStateOf(sharedPrefs.getStringSet("macdock_selected_apps", emptySet()) ?: emptySet())
    }
    
    fun updateSelectedApps(newSet: Set<String>) {
        selectedApps = newSet
        sharedPrefs.edit().putStringSet("macdock_selected_apps", newSet).apply()
    }

    var showUnpairConfirmation by remember { mutableStateOf(false) }
    var hasPairedDevices by remember {
        mutableStateOf(sharedPrefs.getString("paired_devices", "{}") != "{}")
    }

    LaunchedEffect(connectionStatus) {
        hasPairedDevices = sharedPrefs.getString("paired_devices", "{}") != "{}"
    }

    var showManageDialog by remember { mutableStateOf(false) }
    var manageSearchQuery by remember { mutableStateOf("") }
    
    val filteredManageApps = remember(macApps, manageSearchQuery) {
        if (manageSearchQuery.isBlank()) {
            macApps
        } else {
            macApps.filter { it.name.contains(manageSearchQuery, ignoreCase = true) }
        }
    }

    var appToRemove by remember { mutableStateOf<MacAppInfo?>(null) }
    var isFullScreenViewOpen by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    if (viewModel.connectionStatus != "Connected" && viewModel.connectionStatus != "Paired & Connected") {
                        viewModel.startDiscovery()
                    }
                }
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    viewModel.stopDiscovery()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopDiscovery()
            viewModel.disconnect()
        }
    }

    DisposableEffect(isFullScreenViewOpen) {
        val activity = context.findActivity()
        activity?.window?.let { window ->
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            if (isFullScreenViewOpen) {
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
                insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            val activity = context.findActivity()
            activity?.window?.let { window ->
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // App Title / Header (Only show full header in Portrait or compress in Landscape)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (connectionStatus == "Connected") Arrangement.End else Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (connectionStatus != "Connected") {
                    Column {
                        Text(
                            text = "AirDock",
                            color = Color.White,
                            fontSize = if (isPortrait) 28.sp else 22.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "Remote Controller",
                            color = NeonCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (connectionStatus == "Connected") {
                        IconButton(
                            onClick = { isFullScreenViewOpen = true },
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(NeonCyan)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Fullscreen,
                                contentDescription = "Full Screen",
                                tint = Color.White
                            )
                        }
                    }
                    // Connection Status Tiny Badge
                    if (connectionStatus != "Connected") {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(DockBgColor)
                                .border(0.5.dp, DockBorderColor, RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(
                                            when (connectionStatus) {
                                                "Connected" -> AccentGreen
                                                "Scanning...", "Connecting to IP..." -> NeonCyan
                                                "Verifying..." -> NeonIndigo
                                                else -> WarningRed
                                            }
                                        )
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (connectionStatus.length > 12) connectionStatus.take(10) + "..." else connectionStatus,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                    
                    if (hasPairedDevices) {
                        IconButton(
                            onClick = { showUnpairConfirmation = true },
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(DockBgColor)
                                .border(0.5.dp, DockBorderColor, RoundedCornerShape(12.dp))
                        ) {
                            Icon(
                                imageVector = Icons.Default.LinkOff,
                                contentDescription = "Unpair Mac",
                                tint = WarningRed
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            if (connectionStatus == "Connected") {
                                viewModel.disconnect()
                            } else {
                                viewModel.startDiscovery()
                            }
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (connectionStatus == "Connected") WarningRed else NeonIndigo)
                    ) {
                        Icon(
                            imageVector = if (connectionStatus == "Connected") Icons.Default.Close else Icons.Default.Refresh,
                            contentDescription = "Connection Action",
                            tint = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Verification Dialog
            if (showVerificationDialog && verificationPin != null) {
                AlertDialog(
                    onDismissRequest = {
                        verificationHandler?.invoke(false)
                        viewModel.showVerificationDialog = false
                    },
                    containerColor = SecondaryDark,
                    title = {
                        Text("Verify Connection", color = Color.White, fontWeight = FontWeight.Bold)
                    },
                    text = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "Confirm that the PIN on your Mac matches:",
                                color = Color.LightGray,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = verificationPin ?: "",
                                color = NeonCyan,
                                fontSize = 36.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 4.sp
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                verificationHandler?.invoke(true)
                                viewModel.showVerificationDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                        ) {
                            Text("Confirm", color = Color.White)
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                verificationHandler?.invoke(false)
                                viewModel.showVerificationDialog = false
                            }
                        ) {
                            Text("Reject", color = WarningRed)
                        }
                    }
                )
            }

            // Unpair Confirmation Dialog
            if (showUnpairConfirmation) {
                AlertDialog(
                    onDismissRequest = { showUnpairConfirmation = false },
                    containerColor = SecondaryDark,
                    title = {
                        Text("Unpair Mac", color = Color.White, fontWeight = FontWeight.Bold)
                    },
                    text = {
                        Text(
                            "Are you sure you want to unpair your Mac? You will need to re-verify the PIN code next time you connect.",
                            color = Color.LightGray
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                viewModel.unpairAll()
                                selectedApps = emptySet()
                                hasPairedDevices = false
                                showUnpairConfirmation = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = WarningRed)
                        ) {
                            Text("Unpair", color = Color.White)
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { showUnpairConfirmation = false }
                        ) {
                            Text("Cancel", color = Color.LightGray)
                        }
                    }
                )
            }

            // Search and App List
            AnimatedVisibility(
                visible = connectionStatus == "Connected",
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    val filteredApps = remember(macApps, selectedApps) {
                        macApps.filter { it.bundleId in selectedApps }
                    }

                    if (filteredApps.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (macApps.isEmpty()) {
                                    "No apps found on Mac"
                                } else {
                                    "Your Dock is empty. Tap '+' to add apps from your Mac."
                                },
                                color = Color.LightGray,
                                fontSize = 16.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(24.dp)
                            )
                        }
                    } else {
                        if (isPortrait) {
                            // Portrait: 2 columns vertical scroll
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                 items(filteredApps) { app ->
                                    MacGridAppItem(
                                        app = app,
                                        isLandscape = false,
                                        onClick = { viewModel.openApp(app.bundleId) },
                                        onLongClick = { appToRemove = app }
                                    )
                                }
                            }
                        } else {
                            // Landscape: 2 rows horizontal scroll (Dock layout)
                            LazyHorizontalGrid(
                                rows = GridCells.Fixed(2),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(filteredApps) { app ->
                                    MacGridAppItem(
                                        app = app,
                                        isLandscape = true,
                                        onClick = { viewModel.openApp(app.bundleId) },
                                        onLongClick = { appToRemove = app }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Default screen when disconnected/scanning
            AnimatedVisibility(
                visible = connectionStatus != "Connected",
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.weight(1f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 40.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Scan Logo",
                        tint = NeonIndigo,
                        modifier = Modifier.size(90.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Control Your Mac Remotely",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Tap Scan on the top right to discover your Mac on the local network.",
                        color = Color.LightGray,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            }
        }

        // FAB for managing apps
        if (connectionStatus == "Connected") {
            FloatingActionButton(
                onClick = {
                    manageSearchQuery = ""
                    showManageDialog = true
                },
                containerColor = NeonIndigo,
                contentColor = Color.White,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
                    .shadow(12.dp, RoundedCornerShape(16.dp))
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Manage Apps"
                )
            }
        }
    }

    // App Removal Dialog
    if (appToRemove != null) {
        AlertDialog(
            onDismissRequest = { appToRemove = null },
            containerColor = SecondaryDark,
            title = {
                Text("Remove App", color = Color.White, fontWeight = FontWeight.Bold)
            },
            text = {
                Text("Remove ${appToRemove?.name} from your display list?", color = Color.LightGray)
            },
            confirmButton = {
                Button(
                    onClick = {
                        appToRemove?.let { app ->
                            updateSelectedApps(selectedApps - app.bundleId)
                        }
                        appToRemove = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WarningRed)
                ) {
                    Text("Remove", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { appToRemove = null }) {
                    Text("Cancel", color = Color.LightGray)
                }
            }
        )
    }

    // Manage Dock Apps Dialog
    if (showManageDialog) {
        AlertDialog(
            onDismissRequest = { showManageDialog = false },
            containerColor = SecondaryDark,
            title = {
                Column {
                    Text("Manage Dock Apps", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = manageSearchQuery,
                        onValueChange = { manageSearchQuery = it },
                        placeholder = { Text("Search apps...", color = Color.Gray) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = NeonCyan,
                            unfocusedBorderColor = DockBorderColor,
                            focusedContainerColor = PrimaryDark,
                            unfocusedContainerColor = PrimaryDark
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            text = {
                Box(modifier = Modifier.heightIn(max = 400.dp).fillMaxWidth()) {
                    if (filteredManageApps.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = if (macApps.isEmpty()) "No apps synced from Mac yet" else "No matching apps",
                                color = Color.Gray
                            )
                        }
                    } else {
                        androidx.compose.foundation.lazy.LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(filteredManageApps) { app ->
                                val isSelected = app.bundleId in selectedApps
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (isSelected) NeonIndigo.copy(alpha = 0.15f) else DockBgColor)
                                        .clickable {
                                            if (isSelected) {
                                                updateSelectedApps(selectedApps - app.bundleId)
                                            } else {
                                                updateSelectedApps(selectedApps + app.bundleId)
                                            }
                                        }
                                        .border(0.5.dp, if (isSelected) NeonIndigo else DockBorderColor, RoundedCornerShape(12.dp))
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(getAppGradient(app.name)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = app.name.take(1).uppercase(),
                                                color = Color.White,
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = app.name,
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        imageVector = if (isSelected) Icons.Default.Check else Icons.Default.Add,
                                        contentDescription = if (isSelected) "Selected" else "Add",
                                        tint = if (isSelected) AccentGreen else Color.LightGray
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showManageDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonIndigo)
                ) {
                    Text("Done", color = Color.White)
                }
            }
        )
    }

    // Full Screen Grid Overlay
    AnimatedVisibility(
        visible = isFullScreenViewOpen,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        BackHandler(enabled = isFullScreenViewOpen) {
            isFullScreenViewOpen = false
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(PrimaryDark, SecondaryDark)
                    )
                )
                .padding(20.dp)
        ) {
            val filteredApps = remember(macApps, selectedApps) {
                macApps.filter { it.bundleId in selectedApps }
            }

            if (filteredApps.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Your Dock is empty. Please add apps first.",
                        color = Color.LightGray,
                        fontSize = 16.sp
                    )
                }
            } else {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .displayCutoutPadding() // Handles phone screen cutouts (notch/camera hole)
                ) {
                    val W = maxWidth
                    val H = maxHeight
                    val N = filteredApps.size
                    val spacing = 16.dp

                    // Find C that maximizes size = min(sizeW, sizeH)
                    var bestC = 1
                    var bestSize = 0.dp
                    
                    for (c in 1..N) {
                        val r = kotlin.math.ceil(N.toFloat() / c).toInt()
                        val sizeW = (W - spacing * (c - 1)) / c
                        val sizeH = (H - spacing * (r - 1)) / r
                        val size = if (sizeW < sizeH) sizeW else sizeH
                        if (size > bestSize) {
                            bestSize = size
                            bestC = c
                        }
                    }

                    // Cap size at 180.dp so icons don't look awkwardly huge if there's only 1 or 2
                    val finalSize = if (bestSize > 180.dp) 180.dp else bestSize

                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(bestC),
                            verticalArrangement = Arrangement.spacedBy(spacing),
                            horizontalArrangement = Arrangement.spacedBy(spacing),
                            modifier = Modifier
                                .width(finalSize * bestC + spacing * (bestC - 1))
                                .wrapContentHeight() // Dynamic height calculation
                        ) {
                            items(filteredApps) { app ->
                                FullScreenAppItem(
                                    app = app,
                                    size = finalSize,
                                    onClick = { viewModel.openApp(app.bundleId) }
                                )
                            }
                        }
                    }
                }
            }

            // Subtle floating close button placed safely in the top-right corner, considering displayCutout
            IconButton(
                onClick = { isFullScreenViewOpen = false },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .displayCutoutPadding()
                    .size(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black.copy(alpha = 0.4f))
                    .border(0.5.dp, DockBorderColor, RoundedCornerShape(18.dp))
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Exit Full Screen",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MacGridAppItem(
    app: MacAppInfo,
    isLandscape: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DockBgColor),
        shape = RoundedCornerShape(20.dp),
        modifier = (if (isLandscape) {
            Modifier.width(165.dp).fillMaxHeight()
        } else {
            Modifier.fillMaxWidth()
        })
        .combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
        .border(0.5.dp, DockBorderColor, RoundedCornerShape(20.dp))
    ) {
        Column(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // macOS Style Squircle App Icon
            Box(
                modifier = Modifier
                    .size(if (isLandscape) 55.dp else 65.dp)
                    .shadow(10.dp, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
                    .background(brush = getAppGradient(app.name)),
                contentAlignment = Alignment.Center
            ) {
                // Large styled single letter placeholder
                Text(
                    text = app.name.take(1).uppercase(),
                    color = Color.White,
                    fontSize = if (isLandscape) 26.sp else 30.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Application Name
            Text(
                text = app.name,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun FullScreenAppItem(
    app: MacAppInfo,
    size: Dp,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .size(size)
            .clickable(onClick = onClick)
            .border(0.5.dp, DockBorderColor, RoundedCornerShape(24.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(brush = getAppGradient(app.name)),
            contentAlignment = Alignment.Center
        ) {
            // Large Single Letter - dynamically scale with icon size
            val fontSize = (size.value * 0.35f).sp
            Text(
                text = app.name.take(1).uppercase(),
                color = Color.White,
                fontSize = fontSize,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.align(Alignment.Center)
            )
            // App Name Overlay at bottom
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(vertical = (size.value * 0.05f).dp, horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = app.name,
                    color = Color.White,
                    fontSize = (size.value * 0.09f).coerceIn(9f, 14f).sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// Curated list of macOS Dock icon-styled gradients
fun getAppGradient(appName: String): Brush {
    val gradients = listOf(
        listOf(Color(0xFF007AFF), Color(0xFF5AC8FA)), // Finder Blue
        listOf(Color(0xFFFFCC00), Color(0xFFFF9500)), // Notes Yellow
        listOf(Color(0xFF34AADC), Color(0xFF007AFF)), // App Store Blue
        listOf(Color(0xFF8E8E93), Color(0xFFD1D1D6)), // System Settings Grey
        listOf(Color(0xFF4CD964), Color(0xFF34AADC)), // Messages Green
        listOf(Color(0xFF5856D6), Color(0xFFC643FC)), // Podcast Purple
        listOf(Color(0xFFFF2D55), Color(0xFFFF9500)), // Red Accent
        listOf(Color(0xFF5856D6), Color(0xFF5AC8FA)), // Indigo Accent
        listOf(Color(0xFF00E676), Color(0xFF00B0FF))  // Emerald Green
    )
    val index = Math.abs(appName.hashCode()) % gradients.size
    return Brush.linearGradient(gradients[index])
}

fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
