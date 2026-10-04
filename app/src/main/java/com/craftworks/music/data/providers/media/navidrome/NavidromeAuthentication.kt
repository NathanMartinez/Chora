package com.craftworks.music.data.providers.media.navidrome

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class NavidromeAuthenticationException : IllegalStateException(
    "Navidrome authentication failed."
)

internal class NavidromeAuthentication {
    internal class Session(
        val generation: Any = Any(),
        val token: String? = null,
        val failed: Boolean = false
    )

    @Volatile
    private var session = Session()
    private val mutex = Mutex()

    fun snapshot(): Session = session

    suspend fun beginNewEpisode() = mutex.withLock {
        session = Session()
    }

    fun checkEpisode(previous: Session) {
        val current = session
        if (current.generation !== previous.generation || current.failed) {
            throw NavidromeAuthenticationException()
        }
    }

    suspend fun refresh(previous: Session, authenticate: suspend () -> String): Session = mutex.withLock {
        // A reset is not a completed refresh: old requests must stop, not reuse its empty token.
        if (session.generation !== previous.generation) throw NavidromeAuthenticationException()
        if (session.failed) throw NavidromeAuthenticationException()
        if (session !== previous) return@withLock session

        try {
            val token = authenticate()
            if (token.isBlank()) throw NavidromeAuthenticationException()
            Session(previous.generation, token).also { session = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Share failure until an intentional reset, including for late 401s.
            session = Session(previous.generation, failed = true)
            throw NavidromeAuthenticationException()
        }
    }

    suspend fun reject(retried: Session): Nothing {
        mutex.withLock {
            if (session === retried) session = Session(retried.generation, failed = true)
        }
        throw NavidromeAuthenticationException()
    }
}

internal suspend fun HttpResponse.navidromeLoginToken(): String {
    if (status.value !in 200..299) throw NavidromeAuthenticationException()
    val response = try {
        body<NavidromeLoginResponse>()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Do not retain response bodies or decoder messages in an authentication error.
        throw NavidromeAuthenticationException()
    }
    return response.token?.takeIf { it.isNotBlank() } ?: throw NavidromeAuthenticationException()
}

internal fun HttpClient.installNavidromeAuthentication(
    authentication: NavidromeAuthentication,
    isPublicKey: AttributeKey<Boolean>,
    authenticate: suspend () -> String
) {
    plugin(HttpSend).intercept { request ->
        if (request.attributes.getOrNull(isPublicKey) == true) return@intercept execute(request)

        val previous = authentication.snapshot()
        if (previous.failed) throw NavidromeAuthenticationException()
        request.headers["X-ND-Authorization"] = "Bearer ${previous.token}"
        val originalCall = execute(request)
        if (originalCall.response.status != HttpStatusCode.Unauthorized) return@intercept originalCall

        val refreshed = authentication.refresh(previous, authenticate)
        authentication.checkEpisode(refreshed)
        request.headers["X-ND-Authorization"] = "Bearer ${refreshed.token}"
        val retry = execute(request)
        if (retry.response.status == HttpStatusCode.Unauthorized) authentication.reject(refreshed)
        retry
    }
}
