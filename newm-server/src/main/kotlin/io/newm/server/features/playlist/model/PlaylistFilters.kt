package io.newm.server.features.playlist.model

import io.ktor.server.application.ApplicationCall
import io.newm.server.ext.ids
import io.newm.server.ext.newerThan
import io.newm.server.ext.olderThan
import io.newm.server.ext.ownerIds
import java.time.LocalDateTime
import java.util.UUID

data class PlaylistFilters(
    val olderThan: LocalDateTime?,
    val newerThan: LocalDateTime?,
    val ids: List<UUID>?,
    val ownerIds: List<UUID>?
)

val ApplicationCall.playlistFilters: PlaylistFilters
    get() = PlaylistFilters(olderThan, newerThan, ids, ownerIds)
