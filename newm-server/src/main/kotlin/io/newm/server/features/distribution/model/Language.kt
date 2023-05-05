package io.newm.server.features.distribution.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Language(
    @SerialName("language_code")
    val languageCode: String,
    @SerialName("name")
    val name: String
)
