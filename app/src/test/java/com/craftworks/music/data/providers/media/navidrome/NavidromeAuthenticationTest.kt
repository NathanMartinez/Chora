package com.craftworks.music.data.providers.media.navidrome

import com.sun.net.httpserver.HttpServer
import de.jensklingenberg.ktorfit.Ktorfit
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NavidromeAuthenticationTest {
    @Test
    fun successfulRefreshIsSharedEvenWhenTokenValueDoesNotChange() = runBlocking {
        val authentication = NavidromeAuthentication()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var logins = 0
        val previous = authentication.snapshot()
        val requests = List(8) {
            async {
                authentication.refresh(previous) {
                    logins++
                    entered.complete(Unit)
                    finish.await()
                    "valid-token"
                }
            }
        }
        entered.await()
        finish.complete(Unit)
        val sessions = requests.awaitAll()
        assertEquals(1, logins)
        sessions.forEach { assertSame(sessions.first(), it) }
        assertEquals("valid-token", sessions.first().token)

        val renewed = authentication.refresh(sessions.first()) { "valid-token" }
        var additionalLogins = 0
        assertSame(renewed, authentication.refresh(sessions.first()) {
            additionalLogins++
            "unused"
        })
        assertEquals(0, additionalLogins)
    }

    @Test
    fun concurrentFailedRefreshAndLaterRequestsShareFailure() = runBlocking {
        val authentication = NavidromeAuthentication()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var logins = 0
        val previous = authentication.snapshot()
        val requests = List(8) {
            async {
                expectAuthenticationFailure {
                    authentication.refresh(previous) {
                        logins++
                        entered.complete(Unit)
                        finish.await()
                        throw IllegalStateException("Untrusted login response")
                    }
                }
            }
        }
        entered.await()
        finish.complete(Unit)
        requests.awaitAll()
        expectAuthenticationFailure {
            authentication.refresh(authentication.snapshot()) {
                logins++
                "unused"
            }
        }
        assertEquals(1, logins)
        assertNull(authentication.snapshot().token)
    }

    @Test
    fun refreshOwnerCancellationPermitsWaitingReplacement() = runBlocking {
        val authentication = NavidromeAuthentication()
        val previous = authentication.snapshot()
        val entered = CompletableDeferred<Unit>()
        val owner = async {
            authentication.refresh(previous) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        val replacement = async(start = CoroutineStart.UNDISPATCHED) {
            authentication.refresh(previous) { "valid-token" }
        }
        owner.cancelAndJoin()
        try {
            owner.await()
            fail("Expected cancellation")
        } catch (e: CancellationException) {
            assertTrue(owner.isCancelled)
        }
        assertEquals("valid-token", replacement.await().token)
    }

    @Test
    fun waitingCoroutineCancellationDoesNotMutateState() = runBlocking {
        val authentication = NavidromeAuthentication()
        val previous = authentication.snapshot()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val owner = async {
            authentication.refresh(previous) {
                entered.complete(Unit)
                finish.await()
                "valid-token"
            }
        }
        entered.await()
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            authentication.refresh(previous) { error("Cancelled waiter must not login") }
        }
        waiter.cancelAndJoin()
        try {
            waiter.await()
            fail("Expected cancellation")
        } catch (e: CancellationException) {
            assertSame(previous, authentication.snapshot())
        }
        finish.complete(Unit)
        assertEquals("valid-token", owner.await().token)
    }

    @Test
    fun explicitResetAllowsSuccessfulRefreshAfterFailure() = runBlocking {
        val authentication = NavidromeAuthentication()
        val previous = authentication.snapshot()
        expectAuthenticationFailure { authentication.refresh(previous) { error("Failed login") } }
        authentication.beginNewEpisode()
        val reset = authentication.snapshot()
        assertNotSame(previous.generation, reset.generation)
        assertNull(reset.token)
        assertEquals(false, reset.failed)
        assertEquals("valid-token", authentication.refresh(reset) { "valid-token" }.token)
    }

    @Test
    fun newEpisodeCanFailIndependentlyAndShareItsFailure() = runBlocking {
        val authentication = NavidromeAuthentication()
        var logins = 0
        repeat(2) {
            val previous = authentication.snapshot()
            expectAuthenticationFailure {
                authentication.refresh(previous) {
                    logins++
                    error("Failed login")
                }
            }
            expectAuthenticationFailure {
                authentication.refresh(authentication.snapshot()) {
                    logins++
                    "unused"
                }
            }
            authentication.beginNewEpisode()
        }
        assertEquals(2, logins)
    }

    @Test
    fun oldRequestCannotInterpretResetAsSuccessfulRefreshOrPoisonIt() = runBlocking {
        val authentication = NavidromeAuthentication()
        val previous = authentication.snapshot()
        authentication.beginNewEpisode()
        val reset = authentication.snapshot()
        expectAuthenticationFailure {
            authentication.refresh(previous) { error("Stale request must not login") }
        }
        assertSame(reset, authentication.snapshot())
        assertNull(reset.token)
        val current = authentication.refresh(reset) { "valid-token" }
        expectAuthenticationFailure {
            authentication.refresh(previous) { error("Stale request must not login") }
        }
        assertSame(current, authentication.snapshot())
    }

    @Test
    fun oldRetryCannotSendAfterResetOrRejectNewGeneration() = runBlocking {
        val authentication = NavidromeAuthentication()
        val previous = authentication.refresh(authentication.snapshot()) { "old-token" }
        authentication.beginNewEpisode()
        val reset = authentication.snapshot()
        expectAuthenticationFailure { authentication.checkEpisode(previous) }
        expectAuthenticationFailure { authentication.reject(previous) }
        assertSame(reset, authentication.snapshot())
        val current = authentication.refresh(reset) { "valid-token" }
        expectAuthenticationFailure { authentication.reject(previous) }
        assertSame(current, authentication.snapshot())
    }

    @Test
    fun resetWaitsForOwnerAndCannotBeOverwrittenByItsCompletion() = runBlocking {
        val authentication = NavidromeAuthentication()
        val previous = authentication.snapshot()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val owner = async {
            authentication.refresh(previous) {
                entered.complete(Unit)
                finish.await()
                "old-token"
            }
        }
        entered.await()
        val reset = async(start = CoroutineStart.UNDISPATCHED) { authentication.beginNewEpisode() }
        assertEquals(false, reset.isCompleted)
        finish.complete(Unit)
        owner.await()
        reset.await()
        val current = authentication.snapshot()
        assertNotSame(previous.generation, current.generation)
        assertNull(current.token)
        assertEquals("valid-token", authentication.refresh(current) { "valid-token" }.token)
    }

    @Test
    fun concurrentInterceptor401sShareSuccessfulLoginAndUseRefreshedHeaders() =
        concurrentInterceptorRequests(loginSucceeds = true)

    @Test
    fun concurrentInterceptor401sShareFailedLoginAndBlockLaterOrdinaryRequests() =
        concurrentInterceptorRequests(loginSucceeds = false)

    @Test
    fun delayedOldInterceptor401CannotRefreshOrPoisonNewEpisode() = delayedOldInterceptor401(delayRetry = false)

    @Test
    fun delayedOldRetry401CannotRejectNewEpisode() = delayedOldInterceptor401(delayRetry = true)

    private fun delayedOldInterceptor401(delayRetry: Boolean) = withServer(concurrent = true) { server, baseUrl ->
        val authentication = NavidromeAuthentication()
        val oldEntered = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val requests = AtomicInteger()
        val logins = AtomicInteger()
        server.createContext("/api/album") { exchange ->
            if (requests.incrementAndGet() == if (delayRetry) 2 else 1) {
                oldEntered.countDown()
                if (releaseOld.await(5, TimeUnit.SECONDS)) respond(exchange, 401, "{}")
                else respond(exchange, 500, "{}")
            } else if (exchange.requestHeaders.getFirst("X-ND-Authorization") == "Bearer valid-token") {
                respond(exchange, 200, "[]")
            } else respond(exchange, 401, "{}")
        }
        server.createContext("/auth/login") { exchange ->
            logins.incrementAndGet()
            respond(exchange, 200, """{"token":"valid-token"}""")
        }
        withService(baseUrl, authentication) { service ->
            kotlinx.coroutines.coroutineScope {
                val old = async(start = CoroutineStart.UNDISPATCHED) {
                    expectAuthenticationFailure { service.getAlbumList() }
                }
                try {
                    assertTrue(withContext(Dispatchers.IO) { oldEntered.await(5, TimeUnit.SECONDS) })
                    authentication.beginNewEpisode()
                    assertTrue(service.getAlbumList().isEmpty())
                } finally {
                    releaseOld.countDown()
                }
                old.await()
                assertTrue(service.getAlbumList().isEmpty())
                assertEquals(if (delayRetry) 2 else 1, logins.get())
                assertEquals(if (delayRetry) 5 else 4, requests.get())
            }
        }
    }

    private fun concurrentInterceptorRequests(loginSucceeds: Boolean) = withServer(concurrent = true) { server, baseUrl ->
        val requestCount = 4 // Below OkHttp's default per-host concurrency limit.
        val originals = CountDownLatch(requestCount)
        val originalRequests = AtomicInteger()
        val retries = AtomicInteger()
        val logins = AtomicInteger()
        server.createContext("/api/album") { exchange ->
            when (exchange.requestHeaders.getFirst("X-ND-Authorization")) {
                "Bearer null" -> {
                    originalRequests.incrementAndGet()
                    originals.countDown()
                    if (originals.await(5, TimeUnit.SECONDS)) respond(exchange, 401, "{}")
                    else respond(exchange, 500, "{}")
                }
                "Bearer valid-token" -> {
                    retries.incrementAndGet()
                    respond(exchange, 200, "[]")
                }
                else -> respond(exchange, 500, "{}")
            }
        }
        server.createContext("/auth/login") { exchange ->
            logins.incrementAndGet()
            respond(exchange, if (loginSucceeds) 200 else 401,
                if (loginSucceeds) """{"token":"valid-token"}""" else "{}")
        }
        withService(baseUrl) { service ->
            kotlinx.coroutines.coroutineScope {
                List(requestCount) {
                    async {
                        if (loginSucceeds) assertTrue(service.getAlbumList().isEmpty())
                        else expectAuthenticationFailure { service.getAlbumList() }
                    }
                }.awaitAll()
            }
            if (!loginSucceeds) expectAuthenticationFailure { service.getAlbumList() }
            assertEquals(requestCount, originalRequests.get())
            assertEquals(if (loginSucceeds) requestCount else 0, retries.get())
            assertEquals(1, logins.get())
        }
    }

    @Test
    fun validLoginResponseReturnsToken() = withResponse(200, """{"token":"valid-token"}""") {
        assertEquals("valid-token", it.navidromeLoginToken())
    }

    @Test
    fun nullMissingAndBlankTokensFail() {
        listOf("""{"token":null}""", "{}", """{"token":""}""", """{"token":"  "}""").forEach { body ->
            withResponse(200, body) { response ->
                expectAuthenticationFailure { response.navidromeLoginToken() }
            }
        }
    }

    @Test
    fun httpFailuresCannotSupplySuccessfulToken() {
        listOf(401, 429, 500).forEach { status ->
            withResponse(status, """{"token":"must-not-be-used"}""") { response ->
                expectAuthenticationFailure { response.navidromeLoginToken() }
            }
        }
    }

    @Test
    fun malformedLoginResponseFailsWithoutDecoderDetails() = withResponse(200, "not JSON") {
        expectAuthenticationFailure { it.navidromeLoginToken() }
    }

    @Test
    fun unauthorizedRetryStopsBeforeAlbumConversionAndDoesNotLoginAgain() = withServer { server, baseUrl ->
        var albumRequests = 0
        var logins = 0
        server.createContext("/api/album") { exchange ->
            albumRequests++
            respond(exchange, 401, """{"error":"not an album list"}""")
        }
        server.createContext("/auth/login") { exchange ->
            logins++
            respond(exchange, 200, """{"token":"valid-token"}""")
        }
        withService(baseUrl) { service ->
            expectAuthenticationFailure { service.getAlbumList() }
            expectAuthenticationFailure { service.getAlbumList() }
            assertEquals(2, albumRequests)
            assertEquals(1, logins)
        }
    }

    @Test
    fun failedNativeLoginIsNotDecodedAsAlbumsOrRetried() = withServer { server, baseUrl ->
        var albumRequests = 0
        var logins = 0
        server.createContext("/api/album") { exchange ->
            albumRequests++
            respond(exchange, 401, """{"error":"not an album list"}""")
        }
        server.createContext("/auth/login") { exchange ->
            logins++
            respond(exchange, 401, """{"error":"invalid credentials"}""")
        }
        withService(baseUrl) { service ->
            expectAuthenticationFailure { service.getAlbumList() }
            expectAuthenticationFailure { service.getAlbumList() }
            assertEquals(1, albumRequests)
            assertEquals(1, logins)
        }
    }

    @Test
    fun successfulRetryIsDecodedNormally() = withServer { server, baseUrl ->
        var albumRequests = 0
        var logins = 0
        server.createContext("/api/album") { exchange ->
            albumRequests++
            if (albumRequests == 1) respond(exchange, 401, "{}") else respond(exchange, 200, "[]")
        }
        server.createContext("/auth/login") { exchange ->
            logins++
            respond(exchange, 200, """{"token":"valid-token"}""")
        }
        withService(baseUrl) { service ->
            assertEquals(emptyList<NavidromeAlbum>(), service.getAlbumList())
            assertEquals(2, albumRequests)
            assertEquals(1, logins)
        }
    }

    @Test
    fun rateLimitedLibraryRequestDoesNotStartAuthentication() = withServer { server, baseUrl ->
        var requests = 0
        server.createContext("/") { exchange ->
            requests++
            respond(exchange, 429, "{}")
        }
        newClient().use { client ->
            client.installNavidromeAuthentication(NavidromeAuthentication(), AttributeKey("isPublic")) {
                fail("429 must not trigger login")
                "unused"
            }
            assertEquals(429, client.get(baseUrl).status.value)
            assertEquals(1, requests)
        }
    }

    private suspend fun expectAuthenticationFailure(block: suspend () -> Any?): NavidromeAuthenticationException {
        try {
            block()
            fail("Expected authentication failure")
        } catch (e: NavidromeAuthenticationException) {
            assertEquals("Navidrome authentication failed.", e.message)
            assertNull(e.cause)
            return e
        }
        error("Unreachable")
    }

    private fun newClient() = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    private suspend fun withService(
        baseUrl: String,
        authentication: NavidromeAuthentication = NavidromeAuthentication(),
        block: suspend (NavidromeService) -> Unit
    ) {
        newClient().use { client ->
            lateinit var service: NavidromeService
            client.installNavidromeAuthentication(authentication, AttributeKey("isPublic")) {
                service.authenticate(NavidromeLoginRequest("test-user", "test-password")).navidromeLoginToken()
            }
            service = Ktorfit.Builder().baseUrl(baseUrl).httpClient(client).build().createNavidromeService()
            block(service)
        }
    }

    private fun withResponse(status: Int, body: String, block: suspend (io.ktor.client.statement.HttpResponse) -> Unit) =
        withServer { server, baseUrl ->
            server.createContext("/") { exchange -> respond(exchange, status, body) }
            newClient().use { client -> block(client.get(baseUrl)) }
        }

    private fun withServer(concurrent: Boolean = false, block: suspend (HttpServer, String) -> Unit) = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = if (concurrent) Executors.newCachedThreadPool() else null
        if (executor != null) server.executor = executor
        server.start()
        try {
            withTimeout(10000) { block(server, "http://127.0.0.1:${server.address.port}/") }
        } finally {
            server.stop(0)
            executor?.shutdownNow()
        }
    }

    private fun respond(exchange: com.sun.net.httpserver.HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.requestBody.close()
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
