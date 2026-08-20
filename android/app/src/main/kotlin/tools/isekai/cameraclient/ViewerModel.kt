package tools.isekai.cameraclient

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import uniffi.isekai_client_ffi.Camera
import uniffi.isekai_client_ffi.ClientConfig
import uniffi.isekai_client_ffi.ClientException
import uniffi.isekai_client_ffi.ConnectionState
import uniffi.isekai_client_ffi.FrameSink
import uniffi.isekai_client_ffi.PathStatus
import uniffi.isekai_client_ffi.ViewerSession
import uniffi.isekai_client_ffi.connect
import uniffi.isekai_client_ffi.endpointIdOf
import uniffi.isekai_client_ffi.generateEndpointKeyPem
import uniffi.isekai_client_ffi.listCameras
import uniffi.isekai_client_ffi.pairWithCode

private const val TAG = "IsekaiFFI"

/** Reconnect backoff caps out at 30s; after this many tries, wait for a manual retry. */
private const val MAX_RECONNECT_ATTEMPTS = 5

enum class Phase { IDLE, CONNECTING, CONNECTED, STREAMING, RECONNECTING, CLOSED, FAILED }

data class ViewerUiState(
    val phase: Phase = Phase.IDLE,
    val statusDetail: String = "",
    val connectionId: String = "",
    val endpointId: String = "",
    val frame: Bitmap? = null,
    val frameCount: Int = 0,
    /** Which route the video is taking, and whether it could take another. */
    val paths: PathStatus? = null,
    val errorMessage: String? = null,
) {
    val statusText: String
        get() =
            when (phase) {
                Phase.IDLE -> "Idle"
                Phase.CONNECTING -> "Connecting…"
                Phase.CONNECTED -> "Connected — waiting for video"
                Phase.STREAMING -> "Streaming"
                Phase.RECONNECTING -> "Reconnecting…"
                Phase.CLOSED -> "Closed"
                Phase.FAILED -> "Failed"
            }
}

/**
 * Drives one viewer session: settings in, frames and status out. Mirrors the
 * iOS app's `ViewerModel`: Auth0 sign-in (falling back to a hand-pasted
 * token), the encrypted Endpoint key, and camera listing/pairing are all
 * task 6 additions on top of task 5's bare connect/status/video skeleton.
 */
class ViewerModel(application: Application) : AndroidViewModel(application) {
    val auth = AuthStore(application)

    private val _settings = MutableStateFlow(ViewerSettings.load(application))
    val settings: StateFlow<ViewerSettings> = _settings.asStateFlow()

    /** Pasted by hand; only consulted when no Auth0 session is signed in. */
    private val _manualToken =
        MutableStateFlow(SecureStore.get(application, SecureStore.AUTH0_MANUAL_TOKEN) ?: "")
    val manualToken: StateFlow<String> = _manualToken.asStateFlow()

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    private val _cameras = MutableStateFlow<List<Camera>?>(null)
    val cameras: StateFlow<List<Camera>?> = _cameras.asStateFlow()

    private val _cameraStatus = MutableStateFlow<String?>(null)
    val cameraStatus: StateFlow<String?> = _cameraStatus.asStateFlow()

    private val _isBusyWithCameras = MutableStateFlow(false)
    val isBusyWithCameras: StateFlow<Boolean> = _isBusyWithCameras.asStateFlow()

    private var session: ViewerSession? = null
    private var lastSeq: Long = -1

    // A drop the core reports on its own (network loss, server close, the
    // Doze idle-timeout documented in the setup notes' task 4 findings)
    // should be retried; a user tapping Disconnect should not resurrect
    // itself a second later. Both paths end at applyState/fail, so this
    // counter -- reset on a manual connect() and on any sign of a healthy
    // connection -- is what tells them apart.
    private var reconnectJob: Job? = null
    private var reconnectAttempt: Int = 0

    // `ViewerSession.disconnect()` tears down asynchronously -- the core
    // itself calls back with `onState(Closed, ...)` once its shutdown
    // finishes (`isekai-client-ffi/src/lib.rs`'s `hold_shutdown.cancelled()`
    // await), same as an unrequested drop. Bumped on every disconnect() and
    // attemptConnect(), and stamped onto the ModelFrameSink created for that
    // attempt, so a callback arriving after its session has already been
    // superseded (by a manual disconnect, or a fresh connect() racing an old
    // one) is recognized as stale and ignored, rather than corrupting the
    // current session's state or triggering a bogus reconnect.
    private var sessionGeneration: Int = 0

    // Backpressure, mirroring iOS's ViewerSink: decoding can fall behind the
    // stream, so keep only the newest pending frame rather than a queue.
    private val pendingFrame = AtomicReference<Pair<ByteArray, ULong>?>(null)
    private val draining = AtomicBoolean(false)

    // Whether the current suspension was this model's own doing (backgrounded
    // while connected) rather than a user tapping Disconnect -- only the
    // former should reconnect automatically on foreground. Mirrors iOS's own
    // committed design (`docs/ios_camera_client_plan.md` risk R3: "iOS
    // suspends UDP sockets in the background... use scenePhase to
    // suspend/resume the connection") rather than fighting Android's
    // equivalent Doze/App Standby throttling (task 4's findings) with a
    // foreground service this project never designed for sustained
    // background streaming in the first place.
    private var suspendedForBackground = false

    private val appLifecycleObserver =
        object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = suspendForBackground()

            override fun onStart(owner: LifecycleOwner) = resumeFromForeground()
        }

    init {
        prepareEndpointId()
        ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycleObserver)
    }

    fun updateSettings(transform: (ViewerSettings) -> ViewerSettings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        updated.save(getApplication())
    }

    fun updateManualToken(token: String) {
        _manualToken.value = token
        SecureStore.set(getApplication(), SecureStore.AUTH0_MANUAL_TOKEN, token)
    }

    fun signIn() {
        viewModelScope.launch {
            try {
                auth.signIn()
                _cameraStatus.value = null
            } catch (e: Auth0Client.Failure) {
                Log.e(TAG, "sign in failed", e)
                _cameraStatus.value = e.message
            }
        }
    }

    fun signOut() = auth.signOut()

    private fun prepareEndpointId() {
        viewModelScope.launch {
            try {
                val pem = withContext(Dispatchers.Default) { loadOrCreateEndpointKeyPem() }
                val id = withContext(Dispatchers.Default) { endpointIdOf(pem) }
                _uiState.value = _uiState.value.copy(endpointId = id)
            } catch (e: Exception) {
                Log.e(TAG, "endpoint key", e)
                _uiState.value = _uiState.value.copy(errorMessage = "Endpoint key: ${e.message}")
            }
        }
    }

    /**
     * The stored key, generating and persisting one on first use. Stored
     * encrypted (see [SecureStore]) -- task 5's version kept this in a plain
     * internal-storage file, which this replaces outright rather than
     * migrating: an app this early in development has no installed base
     * whose identity is worth preserving across the switch, and starting
     * fresh exercises the real `register=true` path cleanly instead of
     * always hitting the "already registered" case old test keys had built
     * up.
     */
    private fun loadOrCreateEndpointKeyPem(): String {
        val app = getApplication<Application>()
        SecureStore.get(app, SecureStore.ENDPOINT_KEY_PEM)?.let { return it }
        val pem = generateEndpointKeyPem()
        SecureStore.set(app, SecureStore.ENDPOINT_KEY_PEM, pem)
        return pem
    }

    /** Forget the Endpoint key so the next connect mints a new identity. */
    fun resetEndpointKey() {
        if (session != null) return
        SecureStore.delete(getApplication(), SecureStore.ENDPOINT_KEY_PEM)
        _uiState.value = _uiState.value.copy(errorMessage = null)
        prepareEndpointId()
    }

    // --- Cameras -----------------------------------------------------------

    /** Ask the proxy which cameras this Endpoint may connect to. */
    fun refreshCameras() {
        runControlPlane { config, pem, token ->
            val found = listCameras(config, pem, token)
            val apply: () -> Unit = {
                _cameras.value = found
                _cameraStatus.value =
                    if (found.isEmpty()) "No cameras yet — pair with one below." else null
                // A camera that has gone is not one to still be pointing at.
                if (found.none { it.listenerId == _settings.value.listenerId }) {
                    updateSettings { it.copy(listenerId = "", expectedEndpoint = "") }
                }
            }
            apply
        }
    }

    /** Redeem a code the camera server displayed (typed, or from a QR scan). */
    fun pair(rawCode: String) {
        val code = rawCode.trim()
        if (code.isEmpty()) return
        runControlPlane { config, pem, token ->
            val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            val paired = pairWithCode(config, pem, token, code, deviceName)
            val apply: () -> Unit = {
                _cameras.value = paired.cameras
                // Pairing worked and the grant stands either way, but if
                // nothing was written down then every later connection to
                // this camera reports itself unchecked -- and this is the
                // only place that can say why, so it is carried onto
                // whatever is shown. Mirrors iOS's own `unchecked` message.
                val unchecked =
                    paired.notRemembered?.let {
                        " This device could not remember which Endpoint ($it), so connections " +
                            "to it cannot be checked against the pairing."
                    } ?: ""
                val camera = paired.camera
                if (camera == null) {
                    // Paired with a camera that is not running. The code is
                    // spent and the grant stands; it appears here when it is
                    // next up.
                    _cameraStatus.value =
                        "Paired with ${paired.ownerEndpoint}, which is not running right now. " +
                        "It will appear here when it is." + unchecked
                } else {
                    _cameraStatus.value = "Paired with ${camera.label}." + unchecked
                    // Select it, and clear any capability: a grant is what
                    // authorizes this now, and a stale capability would be
                    // carried instead of it.
                    updateSettings {
                        it.copy(
                            listenerId = camera.listenerId,
                            expectedEndpoint = camera.ownerEndpoint,
                            capability = "",
                        )
                    }
                }
            }
            apply
        }
    }

    fun selectCamera(camera: Camera) {
        updateSettings {
            it.copy(
                listenerId = camera.listenerId,
                expectedEndpoint = camera.ownerEndpoint,
                capability = "",
            )
        }
    }

    /**
     * A listener typed in by hand, which is not the camera that was picked
     * from the list.
     *
     * The Endpoint that came with that pick no longer describes it, and left
     * standing it would refuse a perfectly good hand-carried connection while
     * naming a camera the user is not asking for. Mirrors iOS's own
     * `enterListenerByHand`.
     */
    fun enterListenerByHand(id: String) {
        updateSettings { it.copy(listenerId = id, expectedEndpoint = "") }
    }

    /**
     * One shape for both control-plane calls: gather inputs on the main
     * thread, do the blocking work off it, apply the result back on it. The
     * core blocks for an Identity round trip and a QUIC connection to the
     * proxy, so none of it can run on the main thread.
     */
    private fun runControlPlane(work: suspend (ClientConfig, String, String) -> () -> Unit) {
        if (_isBusyWithCameras.value) return
        _isBusyWithCameras.value = true
        _cameraStatus.value = null
        viewModelScope.launch {
            try {
                val pem = withContext(Dispatchers.Default) { loadOrCreateEndpointKeyPem() }
                val token = currentToken()
                val config = clientConfig(_settings.value)
                val apply = withContext(Dispatchers.Default) { work(config, pem, token) }
                apply()
            } catch (e: Exception) {
                Log.e(TAG, "control plane", e)
                _cameraStatus.value = e.message
            } finally {
                _isBusyWithCameras.value = false
            }
        }
    }

    // --- Connect -------------------------------------------------------------

    /** User-facing entry point: a fresh attempt, with a fresh backoff. */
    fun connect() {
        if (session != null) return
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        attemptConnect()
    }

    private fun attemptConnect() {
        val generation = ++sessionGeneration

        _uiState.value =
            _uiState.value.copy(
                phase = Phase.CONNECTING,
                statusDetail = "",
                errorMessage = null,
                frame = null,
                frameCount = 0,
                connectionId = "",
            )
        lastSeq = -1

        val settingsSnapshot = _settings.value
        settingsSnapshot.save(getApplication())

        viewModelScope.launch {
            try {
                val pem = withContext(Dispatchers.Default) { loadOrCreateEndpointKeyPem() }
                val token = currentToken()
                val config = clientConfig(settingsSnapshot)
                val sink = ModelFrameSink(generation)
                // The core blocks for the Identity round trip and the QUIC
                // dial to the proxy, so this cannot run on the main thread.
                val newSession =
                    withContext(Dispatchers.Default) {
                        connect(config, pem, token, null, sink)
                    }
                if (generation != sessionGeneration) {
                    // Superseded (disconnect(), or another connect()) while
                    // this dial was in flight. Tear it down quietly rather
                    // than adopting a session nothing is tracking anymore.
                    newSession.disconnect()
                    return@launch
                }
                session = newSession
                reconnectAttempt = 0
                val current = _uiState.value
                _uiState.value =
                    current.copy(
                        connectionId = newSession.connectionId(),
                        phase = if (current.phase == Phase.CONNECTING) Phase.CONNECTED else current.phase,
                    )
            } catch (e: Exception) {
                if (generation != sessionGeneration) return@launch
                Log.e(TAG, "connect failed", e)
                // Not a retry: the camera answered as an Endpoint other than
                // the one it was paired as, which is not transient and reads
                // the same every time until the pairing is redone -- so it
                // must not look like the failures worth trying again. Mirrors
                // the Rust core's own reasoning (`ClientError::WrongPeer`'s
                // doc comment) for why this is its own variant rather than a
                // `Connect` string.
                val retryable = e !is ClientException.WrongPeer
                fail(e.message ?: e.toString(), retryable)
            }
        }
    }

    /**
     * Backs off 1s, 2s, 4s, 8s, 16s, capped at 30s, up to
     * [MAX_RECONNECT_ATTEMPTS] tries -- then leaves the phase as FAILED/CLOSED
     * for a manual retry rather than looping forever against, say, a
     * genuinely revoked capability.
     */
    private fun scheduleReconnect() {
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) return
        reconnectJob?.cancel()
        val delaySeconds = 1L shl reconnectAttempt
        val delayMs = (delaySeconds * 1000L).coerceAtMost(30_000L)
        reconnectAttempt++
        _uiState.value =
            _uiState.value.copy(
                phase = Phase.RECONNECTING,
                statusDetail = "Reconnecting in ${delayMs / 1000}s… (attempt $reconnectAttempt of $MAX_RECONNECT_ATTEMPTS)",
            )
        reconnectJob =
            viewModelScope.launch {
                delay(delayMs)
                attemptConnect()
            }
    }

    /** The signed-in session's access token, renewed if stale; falls back to the manual one. */
    private suspend fun currentToken(): String {
        if (auth.isSignedIn) return auth.accessToken()
        val manual = _manualToken.value.trim()
        if (manual.isEmpty()) throw AuthError.NotSignedIn
        return manual
    }

    private fun clientConfig(s: ViewerSettings) =
        ClientConfig(
            identityUrl = s.identityUrl.trim(),
            proxyUrl = s.proxyUrl.trim(),
            protocol = s.protocolName.trim(),
            capability = s.capability.trim(),
            listenerId = s.listenerId.trim(),
            expectedEndpoint = s.expectedEndpoint.trim(),
            register = s.register,
            insecureSkipVerify = s.insecureSkipVerify,
            enableMigration = s.enableMigration,
            logFilter = s.logFilter.trim(),
        )

    /** User-initiated: cancels any pending reconnect rather than racing it. */
    fun disconnect() {
        suspendedForBackground = false
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        sessionGeneration++
        session?.disconnect()
        session = null
        _uiState.value = _uiState.value.copy(phase = Phase.CLOSED, statusDetail = "", paths = null)
    }

    /**
     * Switch between the Isekai Link relay and a direct path. Mirrors iOS's
     * `ViewerModel.migrate()`.
     *
     * The switch is asynchronous -- `uiState.paths` updates when the
     * connection reports it (see [ModelFrameSink.onPath]), not here -- and if
     * the new path turns out to carry nothing the core returns to the relay
     * by itself after a few seconds.
     */
    fun migrate() {
        val current = session ?: return
        if (!current.migrate()) {
            _uiState.value = _uiState.value.copy(errorMessage = "No direct path is available yet.")
        }
    }

    /**
     * The whole app (not just this screen) left the foreground. Tears the
     * connection down deliberately rather than leaving it for Doze to kill
     * mid-handshake -- same call `disconnect()` makes, just remembering to
     * undo itself on [resumeFromForeground] since this wasn't the user
     * asking to stop.
     */
    private fun suspendForBackground() {
        val phase = _uiState.value.phase
        val wasActive =
            phase == Phase.CONNECTING || phase == Phase.CONNECTED ||
                phase == Phase.STREAMING || phase == Phase.RECONNECTING
        if (!wasActive) return
        suspendedForBackground = true
        reconnectJob?.cancel()
        reconnectJob = null
        sessionGeneration++
        session?.disconnect()
        session = null
        _uiState.value =
            _uiState.value.copy(
                phase = Phase.CLOSED,
                statusDetail = "Paused in background",
                paths = null,
            )
    }

    private fun resumeFromForeground() {
        if (!suspendedForBackground) return
        suspendedForBackground = false
        connect()
    }

    private fun fail(
        message: String,
        retryable: Boolean = true,
    ) {
        session = null
        _uiState.value =
            _uiState.value.copy(
                phase = Phase.FAILED,
                errorMessage = message,
                statusDetail = message,
                paths = null,
            )
        if (retryable) scheduleReconnect()
    }

    // Called by ModelFrameSink, always on a background (tokio worker) thread.
    private fun applyState(
        state: ConnectionState,
        detail: String,
    ) {
        val newPhase =
            when (state) {
                ConnectionState.CONNECTING -> Phase.CONNECTING
                ConnectionState.CONNECTED -> Phase.CONNECTED
                ConnectionState.STREAMING -> Phase.STREAMING
                ConnectionState.CLOSED -> Phase.CLOSED
                ConnectionState.FAILED -> Phase.FAILED
            }
        val terminal = state == ConnectionState.CLOSED || state == ConnectionState.FAILED
        val current = _uiState.value
        _uiState.value =
            current.copy(
                phase = newPhase,
                statusDetail = detail,
                connectionId = if (state == ConnectionState.CONNECTED && detail.isNotEmpty()) detail else current.connectionId,
                errorMessage = if (state == ConnectionState.FAILED) detail else current.errorMessage,
                // The paths a session reported are that session's, not a
                // fact about the camera in general -- the next real
                // connection is what gets to say what they are next.
                paths = if (terminal) null else current.paths,
            )
        // A connection healthy enough to reach here proves the backoff no
        // longer applies.
        if (state == ConnectionState.CONNECTED || state == ConnectionState.STREAMING) {
            reconnectAttempt = 0
        }
        // The core reporting a terminal state on its own -- not through
        // disconnect() -- leaves `session` pointing at a dead session unless
        // cleared here, which would otherwise permanently block every future
        // connect() via its `session != null` guard.
        if (terminal) {
            session = null
            scheduleReconnect()
        }
    }

    private fun presentFrame(
        bitmap: Bitmap?,
        seq: Long,
    ) {
        if (bitmap == null) return
        // Frames decode concurrently and hop back independently; drop one
        // that lost the race to a newer frame -- same rule as iOS.
        if (seq < lastSeq) return
        lastSeq = seq
        val current = _uiState.value
        _uiState.value =
            current.copy(
                frame = bitmap,
                frameCount = current.frameCount + 1,
                phase = if (current.phase == Phase.CONNECTED) Phase.STREAMING else current.phase,
            )
    }

    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(appLifecycleObserver)
        sessionGeneration++
        session?.disconnect()
        session = null
        super.onCleared()
    }

    /**
     * Receives the Rust core's callbacks. UniFFI invokes these on the tokio
     * runtime's worker threads, so nothing here may touch Compose state
     * directly -- everything goes through the model's StateFlow, which is
     * safe to write from any thread.
     */
    private inner class ModelFrameSink(private val generation: Int) : FrameSink {
        private fun isCurrent() = generation == sessionGeneration

        override fun onFrame(
            jpeg: ByteArray,
            seq: ULong,
        ) {
            if (!isCurrent()) return
            // Keep only the newest frame; drop older ones if decode falls
            // behind, mirroring iOS's ViewerSink.
            pendingFrame.set(jpeg to seq)
            if (draining.compareAndSet(false, true)) {
                viewModelScope.launch(Dispatchers.Default) { drain() }
            }
        }

        private suspend fun drain() {
            while (true) {
                val (jpeg, seq) = pendingFrame.getAndSet(null) ?: run {
                    draining.set(false)
                    return
                }
                if (!isCurrent()) continue
                val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                presentFrame(bitmap, seq.toLong())
            }
        }

        override fun onState(
            state: ConnectionState,
            detail: String,
        ) {
            if (!isCurrent()) return
            applyState(state, detail)
        }

        override fun onPath(status: PathStatus) {
            if (!isCurrent()) return
            _uiState.update { it.copy(paths = status) }
        }

        override fun onRtt(rttMs: Double) {
            // Not surfaced in the UI yet.
        }

        override fun onLog(line: String) {
            Log.d(TAG, "core: $line")
        }
    }
}
