package com.c2c

import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.websocket.WebSocketServerSession
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.CopyOnWriteArrayList

object ServerCore {
    var ktorServer: ApplicationEngine? = null
    val liveSessions = CopyOnWriteArrayList<WebSocketServerSession>()
    
    val logsFlow = MutableSharedFlow<String>(replay = 50)
    
    val isRunningFlow = MutableStateFlow(false)
    var isRunning: Boolean 
        get() = isRunningFlow.value
        set(value) { isRunningFlow.value = value }
        
    var lastStatusSlab = "System Ready. Waiting for link..."

    fun log(msg: String, isSuccess: Boolean? = null) {
        logsFlow.tryEmit(msg)
        if (isSuccess == true) lastStatusSlab = "SUCCESS: $msg"
        else if (isSuccess == false) lastStatusSlab = "ERROR: $msg"
    }
}