package net.pcg.hearth.remote.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Talks to Eric's Jellyfin server. The access token stays in [JellyfinCredentialStore].
 * Passwords are sent once to AuthenticateByName and are not kept.
 */
internal class JellyfinRemote(
    private val store: JellyfinCredentialStore,
    private val transport: JellyfinTransport,
    version: String = "0.2.4",
    private val deviceName: String = "Hearth Remote",
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {}
) {
    private val versionName = version.ifBlank { "0.2.4" }
    private val mutex = Mutex()
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<JellyfinUiState> = _state.asStateFlow()

    suspend fun signIn(rawUrl: String, username: String, password: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val url = JellyfinApi.normalizeServerUrl(rawUrl)
                val name = username.trim()
                if (url.isEmpty() || name.isEmpty()) {
                    val detail = "Missing server or username"
                    log("← SignInFailed $detail")
                    _state.value = JellyfinUiState(
                        serverUrl = url,
                        problem = JellyfinProblem.SignInFailed,
                        detail = detail
                    )
                    return@withLock
                }
                try {
                    val result = exchange(
                        JellyfinCall(
                            method = "POST",
                            url = JellyfinApi.authenticateUrl(url),
                            headers = headers(token = null, json = true),
                            body = JellyfinApi.authBody(name, password)
                        ),
                        secret = password
                    )
                    if (result.code !in 200..299) {
                        val detail = httpDetail(result, password)
                        _state.value = JellyfinUiState(
                            serverUrl = url,
                            problem = JellyfinProblem.SignInFailed,
                            detail = detail
                        )
                        return@withLock
                    }
                    val (userId, signedInName, token) = JellyfinApi.parseAuth(result.text())
                    val login = JellyfinLogin(
                        serverUrl = url,
                        userId = userId,
                        userName = signedInName.ifBlank { name },
                        accessToken = token
                    )
                    store.write(login)
                    pull(login)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val detail = "${e.javaClass.simpleName}: ${JellyfinApi.redact(e.message.orEmpty(), password)}".trim()
                    _state.value = JellyfinUiState(
                        serverUrl = url,
                        problem = JellyfinProblem.SignInFailed,
                        detail = detail
                    )
                }
            }
        }
    }

    suspend fun signOut() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val url = store.serverUrl().ifBlank { _state.value.serverUrl }
                store.clearLogin()
                _state.value = JellyfinUiState(serverUrl = url)
            }
        }
    }

    suspend fun refresh() {
        withContext(Dispatchers.IO) {
            mutex.withLock { pull(store.read()) }
        }
    }

    suspend fun selectSession(deviceId: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                store.setSelectedDeviceId(deviceId)
                pull(store.read())
            }
        }
    }

    suspend fun playPause() = command { login, session, _ ->
        post(login, JellyfinApi.playPauseUrl(login.serverUrl, session.id), body = null)
    }

    suspend fun seekTo(ticks: Long) = command { login, session, _ ->
        post(login, JellyfinApi.seekUrl(login.serverUrl, session.id, ticks.coerceAtLeast(0L)), body = null)
    }

    suspend fun setSubtitle(track: SubtitleTrack) = command { login, session, playing ->
        val index = when (track) {
            SubtitleTrack.Off -> -1
            else -> JellyfinApi.matchingSubtitle(playing?.streams.orEmpty(), track) ?: return@command
        }
        post(login, JellyfinApi.commandUrl(login.serverUrl, session.id), JellyfinApi.subtitleBody(index))
    }

    suspend fun play(itemId: String) = command { login, session, _ ->
        post(login, JellyfinApi.playNowUrl(login.serverUrl, session.id, itemId), body = null)
    }

    fun poster(itemId: String, tag: String?): ByteArray? {
        val login = store.read() ?: return null
        if (itemId.isBlank()) return null
        return try {
            val result = exchange(
                JellyfinCall(
                    method = "GET",
                    url = JellyfinApi.imageUrl(login.serverUrl, itemId, tag),
                    headers = headers(login.accessToken, json = false),
                    body = null
                )
            )
            if (result.code in 200..299 && result.bytes.isNotEmpty()) result.bytes else null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun command(
        block: (JellyfinLogin, JellyfinSession, JellyfinNowPlaying?) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val login = store.read() ?: return@withLock
                val session = currentSession(login) ?: return@withLock
                try {
                    block(login, session, _state.value.nowPlaying)
                    pull(login)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: JellyfinHttpException) {
                    noteFailure(login, e.code)
                } catch (_: Exception) {
                    _state.value = _state.value.copy(problem = JellyfinProblem.RequestFailed)
                }
            }
        }
    }

    private fun currentSession(login: JellyfinLogin): JellyfinSession? {
        val fallback = _state.value.sessions.find { it.id == _state.value.selectedSessionId }
        return try {
            val sessions = JellyfinApi.parseSessions(get(login, JellyfinApi.sessionsUrl(login.serverUrl)).text())
            JellyfinApi.pickSession(sessions, store.deviceId(), store.selectedDeviceId()).selected ?: fallback
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            fallback
        }
    }

    private fun initialState(): JellyfinUiState {
        val saved = store.read()
        if (saved == null) return JellyfinUiState(serverUrl = store.serverUrl())
        return JellyfinUiState(
            serverUrl = saved.serverUrl,
            signedIn = true,
            userName = saved.userName
        )
    }

    private fun pull(login: JellyfinLogin?) {
        if (login == null) {
            _state.value = JellyfinUiState(serverUrl = _state.value.serverUrl)
            return
        }
        try {
            val sessions = JellyfinApi.parseSessions(get(login, JellyfinApi.sessionsUrl(login.serverUrl)).text())
            val pick = JellyfinApi.pickSession(sessions, store.deviceId(), store.selectedDeviceId())
            val upNext = try {
                JellyfinApi.parseNextUp(get(login, JellyfinApi.nextUpUrl(login.serverUrl, login.userId)).text())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            val selected = pick.selected
            if (selected != null && selected.deviceId.isNotBlank()) {
                store.setSelectedDeviceId(selected.deviceId)
            }
            _state.value = JellyfinUiState(
                serverUrl = login.serverUrl,
                signedIn = true,
                userName = login.userName,
                problem = if (pick.choices.isEmpty()) JellyfinProblem.NoSession else JellyfinProblem.None,
                loaded = true,
                sessions = pick.choices,
                selectedSessionId = selected?.id,
                nowPlaying = selected?.nowPlaying,
                upNext = upNext,
                observedAtMs = clock()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: JellyfinHttpException) {
            noteFailure(login, e.code)
        } catch (_: Exception) {
            _state.value = _state.value.copy(
                signedIn = true,
                userName = login.userName,
                serverUrl = login.serverUrl,
                problem = JellyfinProblem.RequestFailed,
                loaded = true
            )
        }
    }

    private fun noteFailure(login: JellyfinLogin, code: Int) {
        val detail = "HTTP $code"
        if (code == 401) {
            store.clearLogin()
            _state.value = JellyfinUiState(
                serverUrl = login.serverUrl,
                problem = JellyfinProblem.Unauthorized,
                detail = detail,
                loaded = true
            )
        } else {
            _state.value = _state.value.copy(
                signedIn = true,
                serverUrl = login.serverUrl,
                userName = login.userName,
                problem = JellyfinProblem.RequestFailed,
                detail = detail,
                loaded = true
            )
        }
    }

    private fun exchange(call: JellyfinCall, secret: String? = null): JellyfinResult {
        val path = JellyfinApi.logPath(call.url)
        log("→ ${call.method} $path")
        try {
            val result = transport.exchange(call)
            val suffix = if (result.code in 200..299) "" else " " + JellyfinApi.redact(result.text(), secret)
            log("← ${result.code} ${call.method} $path$suffix".trim())
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(
                "← ${e.javaClass.simpleName} ${call.method} $path ${JellyfinApi.redact(e.message.orEmpty(), secret)}".trim()
            )
            throw e
        }
    }

    private fun httpDetail(result: JellyfinResult, secret: String): String {
        val body = JellyfinApi.redact(result.text(), secret)
        return if (body.isBlank()) "HTTP ${result.code}" else "HTTP ${result.code} $body"
    }

    private fun get(login: JellyfinLogin, url: String): JellyfinResult {
        val result = exchange(JellyfinCall("GET", url, headers(login.accessToken, json = false), body = null))
        if (result.code !in 200..299) throw JellyfinHttpException(result.code)
        return result
    }

    private fun post(login: JellyfinLogin, url: String, body: String?) {
        val result = exchange(
            JellyfinCall("POST", url, headers(login.accessToken, json = body != null), body)
        )
        if (result.code !in 200..299) throw JellyfinHttpException(result.code)
    }

    private fun headers(token: String?, json: Boolean): Map<String, String> {
        val headers = linkedMapOf(
            "Accept" to "application/json",
            "User-Agent" to "HearthRemote",
            "Authorization" to JellyfinApi.embyAuthorization(
                store.deviceId(),
                versionName,
                deviceName,
                token
            )
        )
        if (!token.isNullOrBlank()) headers["X-Emby-Token"] = token
        if (json) headers["Content-Type"] = "application/json"
        return headers
    }
}
