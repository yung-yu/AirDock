package com.example.macdock

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

data class MacAppInfo(val name: String, val bundleId: String)

class NearbyService(
    private val context: Context,
    private val onStatusChanged: (String) -> Unit,
    private val onVerificationRequired: (String, (Boolean) -> Unit) -> Unit,
    private val onAppListReceived: (List<MacAppInfo>) -> Unit
) {
    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val serviceId = "com.antigravity.macdock"
    private var connectedEndpointId: String? = null
    private var tcpClient: TcpClient? = null

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                payload.asBytes()?.let { bytes ->
                    val jsonStr = String(bytes, Charsets.UTF_8)
                    try {
                        val json = JSONObject(jsonStr)
                        if (json.optString("type") == "APP_LIST") {
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
            onVerificationRequired(connectionInfo.authenticationToken) { accept ->
                if (accept) {
                    connectionsClient.acceptConnection(endpointId, payloadCallback)
                } else {
                    connectionsClient.rejectConnection(endpointId)
                }
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpointId = endpointId
                onStatusChanged("Connected")
                stopDiscovery()
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
        tcpClient?.disconnect()
        tcpClient = null
        
        onStatusChanged("Scanning...")
        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_POINT_TO_POINT)
            .build()

        connectionsClient.startDiscovery(
            serviceId,
            object : EndpointDiscoveryCallback() {
                override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                    onStatusChanged("Found Mac: ${info.endpointName}")
                    stopDiscovery()
                    connectionsClient.requestConnection(
                        Build.MODEL,
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

    fun connectToIp(ipAddress: String) {
        disconnect()
        tcpClient = TcpClient(
            onStatusChanged = { status -> onStatusChanged(status) },
            onAppListReceived = { list -> onAppListReceived(list) }
        ).apply {
            connect(ipAddress)
        }
    }

    fun disconnect() {
        tcpClient?.disconnect()
        tcpClient = null
        
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

        tcpClient?.let { client ->
            if (client.isConnected()) {
                client.sendPayload(jsonStr)
                return
            }
        }

        val endpointId = connectedEndpointId ?: return
        val bytes = jsonStr.toByteArray(Charsets.UTF_8)
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(bytes))
    }
}

// MARK: - TCP Socket Client
class TcpClient(
    private val onStatusChanged: (String) -> Unit,
    private val onAppListReceived: (List<MacAppInfo>) -> Unit
) {
    private var socket: Socket? = null
    private var writer: DataOutputStream? = null
    private var reader: DataInputStream? = null
    private var isRunning = false
    private var readThread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun connect(ipAddress: String, port: Int = 12345) {
        disconnect()
        isRunning = true
        mainHandler.post { onStatusChanged("Connecting to IP...") }
        
        readThread = Thread {
            try {
                val sock = Socket()
                socket = sock
                sock.connect(InetSocketAddress(ipAddress, port), 5000) // 5s timeout
                
                writer = DataOutputStream(sock.getOutputStream())
                reader = DataInputStream(sock.getInputStream())
                
                mainHandler.post { onStatusChanged("Connected") }
                
                while (isRunning) {
                    val length = reader?.readInt() ?: break
                    if (length <= 0 || length > 10 * 1024 * 1024) break
                    
                    val buffer = ByteArray(length)
                    reader?.readFully(buffer)
                    
                    val jsonStr = String(buffer, Charsets.UTF_8)
                    val json = JSONObject(jsonStr)
                    if (json.optString("type") == "APP_LIST") {
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
                        mainHandler.post { onAppListReceived(apps) }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (isRunning) {
                    mainHandler.post { onStatusChanged("IP Connection Failed") }
                }
            } finally {
                // Do not loop disconnect if it's already triggered manually
                if (isRunning) {
                    disconnect()
                }
            }
        }
        readThread?.start()
    }

    fun disconnect() {
        val wasRunning = isRunning
        isRunning = false
        if (wasRunning) {
            mainHandler.post { onStatusChanged("Disconnected") }
        }
        try {
            socket?.close()
        } catch (e: Exception) {}
        socket = null
        writer = null
        reader = null
        readThread = null
    }

    fun sendPayload(jsonStr: String) {
        val bytes = jsonStr.toByteArray(Charsets.UTF_8)
        Thread {
            try {
                writer?.let { out ->
                    out.writeInt(bytes.size)
                    out.write(bytes)
                    out.flush()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    fun isConnected(): Boolean {
        return socket?.isConnected == true && !socket!!.isClosed
    }
}
