package com.nuvio.app.features.discord

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.discord.DiscordIdentify.Companion.toIdentifyPayload
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal enum class DiscordGatewayState {
    Idle,
    Connecting,
    Ready,
    Reconnecting,
    AuthenticationFailed,
    Failed,
    Closed,
}

internal class DiscordGatewayConnection(
    private val token: String,
) {
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val client: HttpClient = createDiscordGatewayHttpClient()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var websocket: DefaultClientWebSocketSession? = null
    private var connectionJob: Job? = null
    private var heartbeatJob: Job? = null
    private var sequence: Int? = null
    private var sessionId: String? = null
    private var resumeGatewayUrl: String? = null
    private var pendingPresence: DiscordPresence? = null
    private var reconnectAttempts = 0
    private var closed = false

    private val _state = MutableStateFlow(DiscordGatewayState.Idle)
    val state: StateFlow<DiscordGatewayState> = _state.asStateFlow()

    private val _user = MutableStateFlow<DiscordReadyUser?>(null)
    val user: StateFlow<DiscordReadyUser?> = _user.asStateFlow()

    fun connect() {
        scope.launch {
            if (closed || connectionJob?.isActive == true) return@launch
            connectionJob = scope.launch { runConnection() }
        }
    }

    fun updatePresence(presence: DiscordPresence) {
        scope.launch {
            if (closed) return@launch
            pendingPresence = presence
            if (_state.value == DiscordGatewayState.Ready) {
                sendPresence(presence)
            }
        }
    }

    fun close() {
        scope.launch {
            if (closed) return@launch
            closed = true
            pendingPresence = null
            heartbeatJob?.cancel()
            heartbeatJob = null
            val session = websocket
            websocket = null
            if (session != null) {
                withTimeoutOrNull(CLOSE_TIMEOUT_MS) {
                    runCatching {
                        session.close(CloseReason(CloseReason.Codes.NORMAL, "Presence cleared"))
                    }
                }
            }
            connectionJob?.cancel()
            connectionJob = null
            runCatching { client.close() }
            sessionId = null
            resumeGatewayUrl = null
            sequence = null
            _state.value = DiscordGatewayState.Closed
            InAppLogger.info(TAG, "Connection to gateway closed")
            scope.cancel()
        }
    }

    private suspend fun runConnection() {
        while (currentCoroutineContext().isActive && !closed) {
            _state.value = if (reconnectAttempts == 0) {
                DiscordGatewayState.Connecting
            } else {
                DiscordGatewayState.Reconnecting
            }
            var closeCode: Int? = null
            try {
                InAppLogger.info(TAG, "Connect called")
                val url = resumeGatewayUrl ?: GATEWAY_URL
                val session = client.webSocketSession(url)
                websocket = session
                for (frame in session.incoming) {
                    if (frame is Frame.Text) {
                        handleMessage(frame.readText())
                    }
                }
                closeCode = withTimeoutOrNull(CLOSE_TIMEOUT_MS) {
                    session.closeReason.await()
                }?.code?.toInt()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                InAppLogger.error(TAG, error.message ?: "Gateway connection error")
            }

            heartbeatJob?.cancel()
            heartbeatJob = null
            websocket = null
            if (closed) return

            val code = closeCode
            val fatal = code != null && FATAL_CLOSE_CODES.contains(code)
            InAppLogger.warn(TAG, "Closed with code: $code, can_reconnect: ${code != 4004 && !fatal}")
            if (code == 4004) {
                _state.value = DiscordGatewayState.AuthenticationFailed
                return
            }
            if (fatal) {
                _state.value = DiscordGatewayState.Failed
                return
            }
            if (code == 4007 || code == 4009) resetSession()

            reconnectAttempts += 1
            if (reconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
                _state.value = DiscordGatewayState.Failed
                return
            }
            _state.value = DiscordGatewayState.Reconnecting
            delay(if (code == 4000) RECONNECT_FAST_DELAY_MS else (reconnectAttempts * RECONNECT_STEP_DELAY_MS).coerceAtMost(RECONNECT_MAX_DELAY_MS))
        }
    }

    private suspend fun handleMessage(jsonString: String) {
        val payload = try {
            json.decodeFromString<DiscordPayload>(jsonString)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            InAppLogger.warn(TAG, "Unable to decode payload: ${error.message.orEmpty()}")
            return
        }
        InAppLogger.debug(TAG, "Received op:${payload.op}, seq:${payload.s}, event :${payload.t}")

        payload.s?.let { sequence = it }
        when (payload.op) {
            DiscordOpCode.DISPATCH -> handleDispatch(payload.t, jsonString)
            DiscordOpCode.HEARTBEAT -> sendHeartbeat()
            DiscordOpCode.RECONNECT -> reconnectWebSocket()
            DiscordOpCode.INVALID_SESSION -> handleInvalidSession(jsonString)
            DiscordOpCode.HELLO -> handleHello(jsonString)
            else -> Unit
        }
    }

    private suspend fun handleDispatch(event: String?, jsonString: String) {
        when (event) {
            "READY" -> {
                val ready = decodePayloadData<DiscordReady>(jsonString) ?: return
                sessionId = ready.sessionId
                resumeGatewayUrl = ready.resumeGatewayUrl?.let { "$it/?v=10&encoding=json" }
                ready.user?.let { _user.value = it }
                InAppLogger.info(TAG, "Session ready")
                onSessionReady()
            }
            "RESUMED" -> {
                InAppLogger.info(TAG, "Session Resumed")
                onSessionReady()
            }
            else -> Unit
        }
    }

    private suspend fun onSessionReady() {
        reconnectAttempts = 0
        _state.value = DiscordGatewayState.Ready
        pendingPresence?.let { sendPresence(it) }
    }

    private suspend fun handleInvalidSession(jsonString: String) {
        InAppLogger.info(TAG, "Handling Invalid Session")
        val resumable = decodePayloadData<Boolean>(jsonString) == true
        delay(INVALID_SESSION_DELAY_MS)
        if (resumable && !sessionId.isNullOrBlank() && sequence != null) {
            sendResume()
        } else {
            resetSession()
            sendIdentify()
        }
    }

    private suspend fun handleHello(jsonString: String) {
        if (sequence != null && !sessionId.isNullOrBlank()) {
            sendResume()
        } else {
            sendIdentify()
        }
        val interval = decodePayloadData<DiscordHeartbeat>(jsonString)?.heartbeatInterval ?: return
        InAppLogger.info(TAG, "Setting heartbeatInterval= $interval")
        startHeartbeatJob(interval)
    }

    private fun resetSession() {
        sessionId = null
        sequence = null
        resumeGatewayUrl = null
    }

    private inline fun <reified T> decodePayloadData(jsonString: String): T? =
        try {
            json.decodeFromString<DiscordPayloadData<T>>(jsonString).d
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }

    private suspend fun sendHeartbeat() {
        send(op = DiscordOpCode.HEARTBEAT, d = sequence)
    }

    private suspend fun reconnectWebSocket() {
        websocket?.close(CloseReason(4000, "Attempting to reconnect"))
    }

    private suspend fun sendIdentify() {
        InAppLogger.info(TAG, "Sending ${DiscordOpCode.IDENTIFY}")
        send(op = DiscordOpCode.IDENTIFY, d = token.toIdentifyPayload())
    }

    private suspend fun sendResume() {
        InAppLogger.info(TAG, "Sending ${DiscordOpCode.RESUME}")
        send(
            op = DiscordOpCode.RESUME,
            d = DiscordResume(
                seq = sequence ?: 0,
                sessionId = sessionId,
                token = token,
            ),
        )
    }

    private suspend fun sendPresence(presence: DiscordPresence) {
        InAppLogger.info(TAG, "Sending ${DiscordOpCode.PRESENCE_UPDATE}")
        send(op = DiscordOpCode.PRESENCE_UPDATE, d = presence)
    }

    private fun startHeartbeatJob(interval: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                sendHeartbeat()
                delay(interval)
            }
        }
    }

    private suspend inline fun <reified T> send(op: DiscordOpCode, d: T?) {
        val session = websocket ?: return
        if (!session.isActive) return
        val payload = json.encodeToString(
            DiscordOutgoingPayload(
                op = op,
                d = d,
            ),
        )
        try {
            session.send(Frame.Text(payload))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            InAppLogger.warn(TAG, "Unable to send $op: ${error.message.orEmpty()}")
        }
    }

    private companion object {
        const val TAG = "DiscordRPC"
        const val GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json"
        const val MAX_RECONNECT_ATTEMPTS = 5
        const val RECONNECT_FAST_DELAY_MS = 200L
        const val RECONNECT_STEP_DELAY_MS = 2_000L
        const val RECONNECT_MAX_DELAY_MS = 15_000L
        const val INVALID_SESSION_DELAY_MS = 1_500L
        const val CLOSE_TIMEOUT_MS = 3_000L
        val FATAL_CLOSE_CODES = setOf(4010, 4011, 4012, 4013, 4014)
    }
}
