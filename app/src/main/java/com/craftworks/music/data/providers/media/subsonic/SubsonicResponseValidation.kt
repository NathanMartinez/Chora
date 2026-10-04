package com.craftworks.music.data.providers.media.subsonic

import com.craftworks.music.data.providers.ProviderException
import com.craftworks.music.data.providers.ProviderFailure
import com.craftworks.music.data.providers.providerDecodeBoundary
import com.craftworks.music.data.providers.providerTransportBoundary
import com.craftworks.music.data.providers.requireProviderHttpSuccess
import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponsePipeline
import io.ktor.http.HttpHeaders

/** Only selected-content operations may interpret protocol code 70 as not found. */
internal fun SubsonicBody.requireSuccess(missingContentIsExplicit: Boolean = false): SubsonicBody {
    if (status == "ok" && error == null) return this
    val code = error?.code
    val failure = when {
        status == "ok" -> ProviderFailure.ProtocolError(code) // Contradictory envelope.
        code == 40 -> ProviderFailure.AuthenticationRejected
        code == 70 && missingContentIsExplicit -> ProviderFailure.MissingContent
        else -> ProviderFailure.ProtocolError(code)
    }
    throw ProviderException(failure)
}

internal fun <T : Any> SubsonicBody.requirePayload(
    missingContentIsExplicit: Boolean = false,
    select: SubsonicBody.() -> T?
): T {
    requireSuccess(missingContentIsExplicit)
    return select() ?: throw ProviderException(ProviderFailure.InvalidResponse)
}

/** Older protocol versions legitimately return no playlist after creation. */
internal fun SubsonicBody.createdPlaylistId(): String? {
    requireSuccess()
    val components = version.split('.')
    val major = components.firstOrNull()?.toIntOrNull()
    val minor = components.getOrNull(1)?.toIntOrNull()
    if (major != null && minor != null && (major > 1 || (major == 1 && minor >= 14))) {
        return requirePayload { playlist }.id
    }
    // Without evidence of the newer response contract, preserve the legacy nullable result.
    return playlist?.id
}

/** The caller owns logical validation so pre-authentication capability probes can read metadata. */
internal suspend fun subsonicResponseBoundary(request: suspend () -> SubsonicResponse): SubsonicBody =
    providerTransportBoundary { providerDecodeBoundary(request) }.subsonicResponse

/** Explicit validation on the Subsonic client only; no native Navidrome recovery is intercepted. */
internal fun HttpClient.installSubsonicHttpValidation() {
    // Body conversion runs on the final call, after redirects and before typed decoding.
    responsePipeline.intercept(HttpResponsePipeline.Receive) {
        requireProviderHttpSuccess(context.response.status.value, context.response.headers[HttpHeaders.RetryAfter])
    }
}
