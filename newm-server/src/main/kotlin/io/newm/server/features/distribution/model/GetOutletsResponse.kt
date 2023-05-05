package io.newm.server.features.distribution.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GetOutletsResponse(
    @SerialName("success")
    val success: Boolean,
    @SerialName("message")
    val message: String,
    @SerialName("data")
    val outlets: List<EvearaOutlet>
)
