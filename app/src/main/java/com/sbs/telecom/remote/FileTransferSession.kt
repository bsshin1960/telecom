package com.sbs.telecom.remote

import android.util.Log
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object FileTransferSession {
    interface MessageListener {
        fun onMessageReceived(message: String)
    }

    var activeListener: MessageListener? = null
    var activeSession: WebSocketSession? = null
    var isPeerAndroid: Boolean = false

    fun sendCommand(cmd: String) {
        val session = activeSession
        if (session != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    session.send(Frame.Text(cmd))
                    Log.d("FileTransferSession", "Sent: $cmd")
                } catch (e: Exception) {
                    Log.e("FileTransferSession", "Send failed: ${e.message}")
                }
            }
        } else {
            Log.w("FileTransferSession", "No active session to send command: $cmd")
        }
    }
}
