package io.newm.server.features.song.model

import kotlinx.serialization.Serializable

@Serializable
data class StreamTokenAgreementRequest(
    val accepted: Boolean
)
