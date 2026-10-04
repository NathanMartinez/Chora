package com.craftworks.music.data.providers.media.navidrome

import com.craftworks.music.data.providers.ProviderException
import com.craftworks.music.data.providers.ProviderFailure
import com.craftworks.music.data.providers.providerDecodeBoundary
import com.craftworks.music.data.providers.providerTransportBoundary
import com.craftworks.music.data.providers.requireProviderHttpSuccess
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

internal class NavidromeAuthentication {
    internal class Session(
        val generation: Any = Any(),
        val token: String? = null,
        val failure: Throwable? = null
    ) {
        val failed: Boolean get() = failure != null
    }

    @Volatile
    private var session = Session()
    private val mutex = Mutex()

    fun snapshot(): Session = session

    suspend fun beginNewEpisode() = mutex.withLock {
        session = Session()
    }

    fun checkEpisode(previous: Session) {
        val current = session
        if (current.generation !== previous.generation) throw staleEpisodeFailure()
        current.failure?.let { throw it }
    }

    suspend fun refresh(previous: Session, authenticate: suspend () -> String): Session = mutex.withLock {
        // A reset is not a completed refresh: old requests must stop, not reuse its empty token.
        if (session.generation !== previous.generation) throw staleEpisodeFailure()
        session.failure?.let { throw it }
        if (session !== previous) return@withLock session

        try {
            val token = authenticate()
            if (token.isBlank()) throw ProviderException(ProviderFailure.InvalidResponse)
            Session(previous.generation, token).also { session = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Share failure until an intentional reset, including for late 401s.
            // Unexpected defects stay unexpected: retain/rethrow them, never classify them.
            session = Session(previous.generation, failure = e)
            throw e
        }
    }

    suspend fun reject(retried: Session): Nothing {
        val failure = ProviderException(ProviderFailure.AuthenticationRejected)
        mutex.withLock {
            if (session.generation !== retried.generation) throw staleEpisodeFailure()
            if (session === retried) session = Session(retried.generation, failure = failure)
        }
        throw failure
    }

    private fun staleEpisodeFailure() = ProviderException(ProviderFailure.AuthenticationRecoveryFailed)
}

internal suspend fun HttpResponse.navidromeLoginToken(now: Instant = Instant.now()): String {
    requireNavidromeHttpSuccess(now)
    val response = navidromeResponseBoundary { body<NavidromeLoginResponse>() }
    return response.token?.takeIf { it.isNotBlank() }
        ?: throw ProviderException(ProviderFailure.InvalidResponse)
}

/** Covers native request/conversion and late transport errors while consuming the response body. */
internal suspend fun <T> navidromeResponseBoundary(receive: suspend () -> T): T =
    providerTransportBoundary { providerDecodeBoundary(receive) }

private fun HttpResponse.requireNavidromeHttpSuccess(now: Instant = Instant.now()) {
    if (status == HttpStatusCode.Unauthorized) throw ProviderException(ProviderFailure.AuthenticationRejected)
    requireProviderHttpSuccess(status.value, headers[HttpHeaders.RetryAfter], now)
}

internal fun HttpClient.installNavidromeAuthentication(
    authentication: NavidromeAuthentication,
    isPublicKey: AttributeKey<Boolean>,
    authenticate: suspend () -> String
) {
    plugin(HttpSend).intercept { request ->
        if (request.attributes.getOrNull(isPublicKey) == true) {
            return@intercept providerTransportBoundary { execute(request) }
        }

        val previous = authentication.snapshot()
        previous.failure?.let { throw it }
        request.headers["X-ND-Authorization"] = "Bearer ${previous.token}"
        val originalCall = providerTransportBoundary { execute(request) }
        if (originalCall.response.status != HttpStatusCode.Unauthorized) {
            originalCall.response.requireNavidromeHttpSuccess()
            return@intercept originalCall
        }

        val refreshed = authentication.refresh(previous, authenticate)
        authentication.checkEpisode(refreshed)
        request.headers["X-ND-Authorization"] = "Bearer ${refreshed.token}"
        val retry = providerTransportBoundary { execute(request) }
        if (retry.response.status == HttpStatusCode.Unauthorized) authentication.reject(refreshed)
        retry.response.requireNavidromeHttpSuccess()
        retry
    }
}
