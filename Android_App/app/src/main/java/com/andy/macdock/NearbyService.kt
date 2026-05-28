package com.andy.macdock

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import androidx.core.content.edit

data class MacAppInfo(val name: String, val bundleId: String)

class NearbyService(
    private val context: Context,
    private val onStatusChanged: (String) -> Unit,
    private val onVerificationRequired: (String, (Boolean) -> Unit) -> Unit,
    private val onAppListReceived: (List<MacAppInfo>) -> Unit
) {
    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val serviceId = "com.andy.macdock"
    private var connectedEndpointId: String? = null

    private val sharedPrefs = context.getSharedPreferences("macdock_prefs", Context.MODE_PRIVATE)
    private var connectingMacUuid: String? = null
    private var isConnectingToPaired: Boolean = false

    private val myUUID: String
        get() {
            var uuid = sharedPrefs.getString("my_uuid", null)
            if (uuid == null) {
                uuid = java.util.UUID.randomUUID().toString()
                sharedPrefs.edit { putString("my_uuid", uuid) }
            }
            return uuid
        }

    private fun getPairedDevices(): Map<String, String> {
        val jsonStr = sharedPrefs.getString("paired_devices", "{}") ?: "{}"
        return try {
            val json = JSONObject(jsonStr)
            val map = mutableMapOf<String, String>()
            json.keys().forEach { key ->
                map[key] = json.getString(key)
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun addPairedDevice(macUuid: String, token: String) {
        val paired = getPairedDevices().toMutableMap()
        paired[macUuid] = token
        val json = JSONObject(paired as Map<*, *>)
        sharedPrefs.edit { putString("paired_devices", json.toString()) }
    }

    private fun sendVerifyPairing(endpointId: String, macUuid: String, token: String) {
        try {
            val json = JSONObject().apply {
                put("type", "VERIFY_PAIRING")
                put("uuid", myUUID)
                put("token", token)
            }
            val bytes = json.toString().toByteArray(Charsets.UTF_8)
            connectionsClient.sendPayload(endpointId, Payload.fromBytes(bytes))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun sendPairingRequest(endpointId: String) {
        try {
            val json = JSONObject().apply {
                put("type", "PAIRING_REQUEST")
                put("uuid", myUUID)
            }
            val bytes = json.toString().toByteArray(Charsets.UTF_8)
            connectionsClient.sendPayload(endpointId, Payload.fromBytes(bytes))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                payload.asBytes()?.let { bytes ->
                    val jsonStr = String(bytes, Charsets.UTF_8)
                    try {
                        val json = JSONObject(jsonStr)
                        val type = json.optString("type")
                        if (type == "APP_LIST") {
                            val appsArray = json.getJSONArray("apps")
                            val apps = mutableListOf<MacAppInfo>()
                            for (i in 0 until appsArray.length()) {
                                val item = appsArray.getJSONObject(i)
                                apps.add(
                                    MacAppInfo(
                                        item.getString("name"),
                                        item.getString("bundleId")
                                    )
                                )
                            }
                            onAppListReceived(apps)
                            onStatusChanged("Connected")
                        } else if (type == "PAIRING_RESPONSE") {
                            val macUuid = json.optString("uuid")
                            val token = json.optString("token")
                            if (!macUuid.isNullOrEmpty() && !token.isNullOrEmpty()) {
                                addPairedDevice(macUuid, token)
                                onStatusChanged("Paired & Connected")
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            val parts = connectionInfo.endpointName.split("|")
            val remoteUuid = parts.getOrNull(1)
            connectingMacUuid = remoteUuid
            
            val paired = getPairedDevices()
            val isPaired = remoteUuid != null && paired.containsKey(remoteUuid)
            
            if (isPaired) {
                // Auto-accept connection for previously paired device
                connectionsClient.acceptConnection(endpointId, payloadCallback)
            } else {
                onVerificationRequired(connectionInfo.authenticationToken) { accept ->
                    if (accept) {
                        connectionsClient.acceptConnection(endpointId, payloadCallback)
                    } else {
                        connectionsClient.rejectConnection(endpointId)
                    }
                }
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpointId = endpointId
                onStatusChanged("Verifying...")
                stopDiscovery()
                
                val remoteUuid = connectingMacUuid
                if (remoteUuid != null) {
                    val paired = getPairedDevices()
                    if (paired.containsKey(remoteUuid)) {
                        sendVerifyPairing(endpointId, remoteUuid, paired[remoteUuid]!!)
                    } else {
                        sendPairingRequest(endpointId)
                    }
                } else {
                    onStatusChanged("Connected")
                }
            } else {
                onStatusChanged("Connection Failed: ${result.status.statusMessage}")
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (connectedEndpointId == endpointId) {
                connectedEndpointId = null
                onStatusChanged("Disconnected")
            }
        }
    }

    fun startDiscovery() {
        onStatusChanged("Scanning...")
        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_POINT_TO_POINT)
            .build()

        connectionsClient.startDiscovery(
            serviceId,
            object : EndpointDiscoveryCallback() {
                override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                    val parts = info.endpointName.split("|")
                    val macName = parts.getOrNull(0) ?: info.endpointName
                    val macUuid = parts.getOrNull(1)
                    
                    onStatusChanged("Found Mac: $macName")
                    stopDiscovery()
                    
                    val paired = getPairedDevices()
                    if (macUuid != null && paired.containsKey(macUuid)) {
                        isConnectingToPaired = true
                        connectingMacUuid = macUuid
                    } else {
                        isConnectingToPaired = false
                        connectingMacUuid = null
                    }

                    connectionsClient.requestConnection(
                        "${Build.MODEL}|$myUUID",
                        endpointId,
                        connectionLifecycleCallback
                    ).addOnFailureListener {
                        onStatusChanged("Request connection failed: ${it.localizedMessage}")
                    }
                }

                override fun onEndpointLost(endpointId: String) {
                    onStatusChanged("Lost device")
                }
            },
            discoveryOptions
        ).addOnFailureListener {
            onStatusChanged("Discovery failed: ${it.localizedMessage}")
        }
    }

    fun stopDiscovery() {
        connectionsClient.stopDiscovery()
    }

    fun disconnect() {
        connectedEndpointId?.let {
            connectionsClient.disconnectFromEndpoint(it)
            connectedEndpointId = null
        }
        onStatusChanged("Disconnected")
    }

    fun openApp(bundleId: String) {
        val json = JSONObject().apply {
            put("type", "OPEN_APP")
            put("bundleId", bundleId)
        }
        val jsonStr = json.toString()

        val endpointId = connectedEndpointId ?: return
        val bytes = jsonStr.toByteArray(Charsets.UTF_8)
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(bytes))
    }

    fun unpairAll() {
        sharedPrefs.edit()
            .remove("paired_devices")
            .remove("macdock_selected_apps")
            .apply()
        disconnect()
    }
}
