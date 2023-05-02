package io.newm.server.features.collaboration.database

import io.newm.server.features.collaboration.model.Collaboration
import io.newm.server.features.collaboration.model.CollaborationFilters
import io.newm.server.features.collaboration.model.CollaborationStatus
import io.newm.server.features.song.database.SongTable
import io.newm.server.features.user.database.UserEntity
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.AndOp
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SizedIterable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.mapLazy
import org.jetbrains.exposed.sql.select
import java.time.LocalDateTime
import java.util.UUID

class CollaborationEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    val createdAt: LocalDateTime by CollaborationTable.createdAt
    var songId: EntityID<UUID> by CollaborationTable.songId
    var email: String by CollaborationTable.email
    var role: String? by CollaborationTable.role
    var royaltyRate: Float? by CollaborationTable.royaltyRate
    var credited: Boolean by CollaborationTable.credited
    var status: CollaborationStatus by CollaborationTable.status

    fun toModel(): Collaboration = Collaboration(
        id = id.value,
        createdAt = createdAt,
        songId = songId.value,
        email = email,
        role = role,
        royaltyRate = royaltyRate,
        credited = credited,
        status = status
    )

    companion object : UUIDEntityClass<CollaborationEntity>(CollaborationTable) {

        fun all(userId: UUID, filters: CollaborationFilters): SizedIterable<CollaborationEntity> {
            val inbound = filters.inbound == true
            val ops = mutableListOf<Op<Boolean>>()
            ops += if (inbound) {
                CollaborationTable.email.lowerCase() eq UserEntity[userId].email.lowercase()
            } else {
                SongTable.ownerId eq userId
            }
            with(filters) {
                olderThan?.let {
                    ops += CollaborationTable.createdAt less it
                }
                newerThan?.let {
                    ops += CollaborationTable.createdAt greater it
                }
                ids?.let {
                    ops += CollaborationTable.id inList it
                }
                songIds?.let {
                    ops += CollaborationTable.songId inList it
                }
                statuses?.let {
                    ops += CollaborationTable.status inList it
                }
            }
            val andOp = AndOp(ops)
            return if (inbound) {
                find(andOp)
            } else {
                CollaborationEntity.wrapRows(
                    CollaborationTable.join(
                        otherTable = SongTable,
                        joinType = JoinType.INNER,
                        additionalConstraint = { CollaborationTable.songId eq SongTable.id }
                    ).slice(CollaborationTable.columns).select(andOp)
                )
            }
        }

        fun collaborators(userId: UUID): SizedIterable<Pair<String, Long>> {
            return CollaborationTable.join(
                otherTable = SongTable,
                joinType = JoinType.INNER,
                additionalConstraint = { CollaborationTable.songId eq SongTable.id }
            ).slice(CollaborationTable.email.lowerCase(), SongTable.id.count())
                .select { SongTable.ownerId eq userId }
                .groupBy(CollaborationTable.email.lowerCase())
                .mapLazy { it[CollaborationTable.email.lowerCase()] to it[SongTable.id.count()] }
        }
    }
}
