package com.craftworks.music.data.providers.media.subsonic

import com.craftworks.music.data.model.AuthenticationResponse
import com.craftworks.music.data.model.ProviderType

internal fun SubsonicBody.toAuthenticationResponse(): AuthenticationResponse {
    requireSuccess()

    return AuthenticationResponse(
        isAdmin = user?.adminRole ?: false,
        providerType = when (type) {
            "navidrome" -> ProviderType.NAVIDROME
            else -> ProviderType.SUBSONIC
        }
    )
}
