package com.example.macdock.ui.main

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.example.macdock.MacAppInfo
import com.example.macdock.NearbyService

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

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun NearbyControllerScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var connectionStatus by remember { mutableStateOf("Disconnected") }
    var verificationPin by remember { mutableStateOf<String?>(null) }
    var showVerificationDialog by remember { mutableStateOf(false) }
    var verificationHandler by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    var macApps by remember { mutableStateOf<List<MacAppInfo>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }

    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    val nearbyService = remember {
        NearbyService(
            context = context.applicationContext,
            onStatusChanged = { status -> connectionStatus = status },
            onVerificationRequired = { pin, handler ->
                verificationPin = pin
                verificationHandler = handler
                showVerificationDialog = true
            },
            onAppListReceived = { list -> macApps = list }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            nearbyService.stopDiscovery()
            nearbyService.disconnect()
        }
    }

    Column(
        modifier = modifier
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
                        text = "Mac Dock",
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
                
                IconButton(
                    onClick = {
                        if (connectionStatus == "Connected") {
                            nearbyService.disconnect()
                        } else {
                            nearbyService.startDiscovery()
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
                    showVerificationDialog = false
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
                            showVerificationDialog = false
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
                            showVerificationDialog = false
                        }
                    ) {
                        Text("Reject", color = WarningRed)
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
                // App Grid Layout
                val filteredApps = macApps

                if (filteredApps.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (macApps.isEmpty()) "No apps found on Mac" else "No matching apps",
                            color = Color.LightGray,
                            fontSize = 16.sp
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
                                    onClick = { nearbyService.openApp(app.bundleId) }
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
                                    onClick = { nearbyService.openApp(app.bundleId) }
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
}

@Composable
fun MacGridAppItem(
    app: MacAppInfo,
    isLandscape: Boolean,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DockBgColor),
        shape = RoundedCornerShape(20.dp),
        modifier = (if (isLandscape) {
            Modifier.width(165.dp).fillMaxHeight()
        } else {
            Modifier.fillMaxWidth()
        })
        .clickable(onClick = onClick)
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
