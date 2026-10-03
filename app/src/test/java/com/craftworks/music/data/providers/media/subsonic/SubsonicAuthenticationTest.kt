package com.craftworks.music.data.providers.media.subsonic

import com.craftworks.music.data.model.ProviderType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SubsonicAuthenticationTest {
    @Test
    fun successfulNavidromeResponsePreservesProviderDetection() {
        val response = SubsonicBody(status = "ok", type = "navidrome", version = "1.16.1")

        assertEquals(ProviderType.NAVIDROME, response.toAuthenticationResponse().providerType)
    }

    @Test
    fun successfulSubsonicResponsePreservesProviderDetection() {
        val response = SubsonicBody(status = "ok", type = "subsonic", version = "1.16.1")

        assertEquals(ProviderType.SUBSONIC, response.toAuthenticationResponse().providerType)
    }

    @Test
    fun logicalAuthenticationFailureInResponseBodyIsRejected() {
        val response = Json.decodeFromString<SubsonicResponse>(
            """{"subsonic-response":{"status":"failed","type":"navidrome","version":"1.16.1","error":{"code":40,"message":"Untrusted server detail"}}}"""
        )

        val exception = assertThrows(IllegalStateException::class.java) {
            response.subsonicResponse.toAuthenticationResponse()
        }
        assertEquals("Subsonic authentication failed", exception.message)
    }

    @Test
    fun failedStatusWithoutErrorDetailsIsRejected() {
        val response = SubsonicBody(status = "failed", type = "subsonic", version = "1.16.1")

        assertThrows(IllegalStateException::class.java) { response.toAuthenticationResponse() }
    }

    @Test
    fun errorIsRejectedEvenWhenStatusClaimsSuccess() {
        val response = SubsonicBody(
            status = "ok", type = "navidrome", version = "1.16.1",
            error = SubsonicError(code = 40, message = "Untrusted server detail")
        )

        assertThrows(IllegalStateException::class.java) { response.toAuthenticationResponse() }
    }
}
