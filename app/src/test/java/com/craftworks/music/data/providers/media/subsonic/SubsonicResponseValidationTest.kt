package com.craftworks.music.data.providers.media.subsonic

import com.craftworks.music.data.model.LibraryType
import com.craftworks.music.data.model.MediaProviderData
import com.craftworks.music.data.providers.ProviderException
import com.craftworks.music.data.providers.ProviderFailure
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class SubsonicResponseValidationTest {
    private fun envelope(status: String = "ok", error: SubsonicError? = null) =
        SubsonicBody(status, "subsonic", "1.16.1", error = error)

    @Test
    fun wrongCredentialsAreAuthenticationRejected() {
        assertFailure(ProviderFailure.AuthenticationRejected) {
            envelope("failed", SubsonicError(40, "secret server detail")).toAuthenticationResponse()
        }
    }

    @Test
    fun mechanismAndAuthorizationErrorsRemainProtocolErrors() {
        listOf(41, 42, 43, 44, 50, 999).forEach { code ->
            assertFailure(ProviderFailure.ProtocolError(code)) {
                envelope("failed", SubsonicError(code)).toAuthenticationResponse()
            }
        }
    }

    @Test
    fun missingCodeIsPreservedAsUnknownProtocolError() {
        val response = Json.decodeFromString<SubsonicResponse>(
            body(""", "error":{"message":"secret detail"}""", "failed")
        )
        assertFailure(ProviderFailure.ProtocolError()) { response.subsonicResponse.toAuthenticationResponse() }
        assertFailure(ProviderFailure.ProtocolError()) { envelope("failed").requireSuccess() }
    }

    @Test
    fun contradictorySuccessIsConservativelyProtocolFailure() {
        listOf(40, 70, 999).forEach { code ->
            assertFailure(ProviderFailure.ProtocolError(code)) {
                envelope(error = SubsonicError(code)).requireSuccess(missingContentIsExplicit = true)
            }
        }
    }

    @Test
    fun code70RequiresSelectedContentContext() {
        val response = envelope("failed", SubsonicError(70))
        assertFailure(ProviderFailure.ProtocolError(70)) { response.requireSuccess() }
        assertFailure(ProviderFailure.MissingContent) { response.requirePayload(true) { album } }
    }

    @Test
    fun requiredPayloadAbsenceIsInvalidResponseAfterLogicalSuccess() {
        assertFailure(ProviderFailure.InvalidResponse) { envelope().requirePayload { album } }
        assertFailure(ProviderFailure.InvalidResponse) { envelope().requirePayload { artist } }
        assertFailure(ProviderFailure.InvalidResponse) { envelope().requirePayload { playlist } }
    }

    @Test
    fun logicalFailureIsCheckedBeforePayloadSelection() {
        var selected = false
        assertFailure(ProviderFailure.AuthenticationRejected) {
            envelope("failed", SubsonicError(40)).requirePayload {
                selected = true
                album
            }
        }
        assertFalse(selected)
    }

    @Test
    fun emptyAndOptionalSuccessRemainSuccessful() {
        val response = envelope().copy(searchResult3 = SearchResult3(song = emptyList()))
        assertSame(response, response.requireSuccess())
        assertTrue(response.requireSuccess().searchResult3?.song.orEmpty().isEmpty())
        assertNull(response.requireSuccess().artistInfo)
        assertNull(response.requireSuccess().lyricsList)
    }

    @Test
    fun legacyPlaylistCreationCanOmitPayload() {
        assertNull(envelope().copy(version = "1.13.0").createdPlaylistId())
    }

    @Test
    fun modernPlaylistCreationRequiresPayload() {
        assertFailure(ProviderFailure.InvalidResponse) { envelope().createdPlaylistId() }
    }

    @Test
    fun knownTransportFailuresAreTranslatedWithoutRawCauses() = runBlocking {
        listOf(SocketTimeoutException("secret"), ConnectException("secret"), UnknownHostException("secret")).forEach {
            expectFailure(ProviderFailure.Unavailable) { subsonicResponseBoundary { throw it } }
        }
    }

    @Test
    fun cancellationAndExistingSemanticFailurePropagateUnchanged() = runBlocking {
        listOf(CancellationException("cancelled"), ProviderException(ProviderFailure.RateLimited())).forEach { original ->
            val caught = try {
                subsonicResponseBoundary { throw original }
                null
            } catch (failure: Throwable) { failure }
            assertSame(original, caught)
        }
    }

    @Test
    fun programmingDefectsAreNotTranslated() = runBlocking {
        listOf(NullPointerException(), ClassCastException(), IllegalStateException(), IllegalArgumentException(), NotImplementedError()).forEach { original ->
            val caught = try {
                subsonicResponseBoundary { throw original }
                null
            } catch (failure: Throwable) { failure }
            assertSame(original, caught)
            assertFalse(caught is ProviderException)
        }
    }

    @Test
    fun providerAuthRejectsHttp200LogicalFailureWithoutRetryOrRejectingBlankInputLocally() =
        withProvider(200, body(""", "error":{"code":40,"message":"secret detail"}""", "failed")) { provider, requests ->
            expectFailure(ProviderFailure.AuthenticationRejected) { provider.authenticate("fixture", "") }
            assertEquals("", provider.providerData.password)
            assertEquals(1, requests.get())
        }

    @Test
    fun providerAuthPreservesProtocolCodes() {
        listOf(41, 42, 43, 44, 50, 999).forEach { code ->
            withProvider(200, body(""", "error":{"code":$code}""", "failed")) { provider, requests ->
                expectFailure(ProviderFailure.ProtocolError(code)) { provider.authenticate("fixture", "fixture") }
                assertEquals(1, requests.get())
            }
        }
    }

    @Test
    fun malformedAuthResponseIsInvalidResponse() = withProvider(200, "secret malformed body") { provider, _ ->
        expectFailure(ProviderFailure.InvalidResponse) { provider.authenticate("fixture", "fixture") }
    }

    @Test
    fun httpFailuresAreValidatedBeforeDecodingTheirErrorBodies() {
        listOf(302, 401, 403, 503).forEach { status ->
            withProvider(status, "secret non-JSON body") { provider, requests ->
                expectFailure(ProviderFailure.HttpError(status)) { provider.authenticate("fixture", "fixture") }
                assertEquals(1, requests.get())
            }
        }
    }

    @Test
    fun ordinaryHttpRedirectStillReachesSuccessfulAuthentication() =
        withProvider(200, body(), redirect = true) { provider, requests ->
            provider.authenticate("fixture", "fixture")
            assertEquals(2, requests.get())
        }

    @Test
    fun rateLimitedAuthRetainsNormalizedDelayOnly() =
        withProvider(429, "secret body", "12") { provider, requests ->
            expectFailure(ProviderFailure.RateLimited(12.seconds)) { provider.authenticate("fixture", "fixture") }
            assertEquals(1, requests.get())
        }

    @Test
    fun missingSuccessfulAlbumThrowsSemanticFailureOnActualDetailPath() = withProvider(200, body()) { provider, _ ->
        expectFailure(ProviderFailure.InvalidResponse) { provider.getAlbumDetail("album") }
    }

    @Test
    fun successfulAlbumDetailMapsNormally() = withProvider(200, body(""", "album":$album""")) { provider, _ ->
        val result = provider.getAlbumDetail("album")
        assertEquals("album", result.id)
        assertEquals("Fixture", result.name)
    }

    @Test
    fun explicitMissingAlbumMapsToMissingContent() =
        withProvider(200, body(""", "error":{"code":70}""", "failed")) { provider, _ ->
            expectFailure(ProviderFailure.MissingContent) { provider.getAlbumDetail("album") }
        }

    @Test
    fun albumProtocolErrorDoesNotBecomeMissingPayloadOrNullPointer() =
        withProvider(200, body(""", "error":{"code":50}""", "failed")) { provider, _ ->
            expectFailure(ProviderFailure.ProtocolError(50)) { provider.getAlbumDetail("album") }
        }

    @Test
    fun optionalLyricsRadioFoldersAndMetadataRemainEmptySuccess() = withProvider(200, body()) { provider, _ ->
        assertTrue(provider.getLyrics("song").isEmpty())
        assertTrue(provider.getInternetRadioStations().isEmpty())
        assertTrue(provider.getMusicFolderList().isEmpty())
        assertNull(provider.getAlbumArtistInfo("artist", null))
    }

    @Test
    fun explicitlyEmptyMusicFoldersObjectIsLegitimateSuccess() =
        withProvider(200, body(""", "musicFolders":{}""")) { provider, _ ->
            assertTrue(provider.getMusicFolderList().isEmpty())
        }

    @Test
    fun emptySelectedPlaylistStillSucceeds() = withProvider(200, body(""", "playlist":$playlist""")) { provider, _ ->
        assertTrue(provider.getPlaylistSongList("playlist").isEmpty())
    }

    @Test
    fun absentSelectedPlaylistIsInvalidResponse() = withProvider(200, body()) { provider, _ ->
        expectFailure(ProviderFailure.InvalidResponse) { provider.getPlaylistSongList("playlist") }
    }

    @Test
    fun capabilityProbeDoesNotMistakePreAuthRejectionForUnsupportedServer() =
        withProvider(200, body(""", "openSubsonic":true,"error":{"code":40}""", "failed")) { provider, _ ->
            assertTrue(provider.ping())
            expectFailure(ProviderFailure.AuthenticationRejected) { provider.authenticate("fixture", "") }
        }

    @Test
    fun mutationFailuresThrowInsteadOfReportingSuccess() =
        withProvider(200, body(""", "error":{"code":50}""", "failed")) { provider, _ ->
            mutations.forEach { mutation -> expectFailure(ProviderFailure.ProtocolError(50)) { mutation(provider) } }
        }

    @Test
    fun mutationAcknowledgementsRemainSuccessful() = withProvider(200, body()) { provider, _ ->
        mutations.forEach { mutation ->
            val result = mutation(provider)
            if (result is Boolean) assertTrue(result)
        }
    }

    private val mutations: List<suspend (SubsonicMediaProvider) -> Any?> = listOf(
        { it.addToPlaylist("playlist", listOf("song")) },
        { it.createFavorite(listOf("song"), LibraryType.SONG) },
        { it.deleteFavorite(listOf("song"), LibraryType.SONG) },
        { it.createInternetRadioStation("Fixture", "https://fixture.invalid", null) },
        { it.deleteInternetRadioStation("radio") },
        { it.deletePlaylist("playlist") },
        { it.setRating(listOf("song"), 3, LibraryType.SONG) },
        { it.scrobble("song", playbackRate = 1f, submission = true, event = null) }
    )

    private fun assertFailure(expected: ProviderFailure, block: () -> Any?) {
        assertSafe(expected, assertThrows(ProviderException::class.java) { block() })
    }

    private suspend fun expectFailure(expected: ProviderFailure, block: suspend () -> Any?) {
        val exception = try { block(); null } catch (failure: ProviderException) { failure }
        assertTrue("Expected semantic failure", exception != null)
        assertSafe(expected, exception!!)
    }

    private fun assertSafe(expected: ProviderFailure, exception: ProviderException) {
        assertEquals(expected, exception.failure)
        assertEquals("Provider operation failed.", exception.message)
        assertEquals("${ProviderException::class.java.name}: Provider operation failed.", exception.toString())
        assertNull(exception.cause)
        assertEquals(0, exception.suppressed.size)
    }

    private fun body(payload: String = "", status: String = "ok") =
        """{"subsonic-response":{"status":"$status","type":"subsonic","version":"1.16.1"$payload}}"""

    private val album = """{"id":"album","name":"Fixture","songCount":0,"duration":0,"created":"2026-10-04"}"""
    private val playlist = """{"id":"playlist","name":"Fixture","songCount":0,"duration":0,"created":"2026-10-04","changed":"2026-10-04"}"""

    private fun withProvider(
        status: Int, responseBody: String, retryAfter: String? = null, redirect: Boolean = false,
        block: suspend (SubsonicMediaProvider, AtomicInteger) -> Unit
    ) = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = AtomicInteger()
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            exchange.requestBody.close()
            if (redirect && exchange.requestURI.path != "/redirected") {
                exchange.responseHeaders.set("Location", "/redirected")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
                return@createContext
            }
            exchange.responseHeaders.set("Content-Type", "application/json")
            retryAfter?.let { exchange.responseHeaders.set("Retry-After", it) }
            val bytes = responseBody.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val provider = SubsonicMediaProvider().apply {
                id = "fixture-provider"
                data = MediaProviderData(emptyList())
                providerData = SubsonicProviderData("http://127.0.0.1:${server.address.port}", "fixture", "fixture")
            }
            withTimeout(10000) { block(provider, requests) }
        } finally {
            server.stop(0)
        }
    }
}
