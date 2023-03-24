package io.newm.server.features.song.repo

import io.newm.server.ext.checkLength
import com.amazonaws.HttpMethod
import com.amazonaws.services.s3.AmazonS3
import io.ktor.server.application.*
import io.ktor.util.logging.*
import io.newm.shared.exception.HttpForbiddenException
import io.newm.shared.exception.HttpUnprocessableEntityException
import io.newm.server.features.song.database.SongEntity
import io.newm.server.features.song.model.MintingStatus
import io.newm.server.features.song.model.Song
import io.newm.server.features.song.model.SongFilters
import io.newm.server.features.user.database.UserTable
import io.newm.shared.ext.*
import io.newm.shared.koin.inject
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.transactions.transaction
import org.koin.core.parameter.parametersOf
import java.time.Instant
import java.util.UUID

internal class SongRepositoryImpl(
    private val environment: ApplicationEnvironment,
    private val s3: AmazonS3
) : SongRepository {

    private val logger: Logger by inject { parametersOf(javaClass.simpleName) }

    override suspend fun add(song: Song, ownerId: UUID): UUID {
        logger.debug { "add: song = $song" }
        val title = song.title ?: throw HttpUnprocessableEntityException("missing title")
        val genres = song.genres ?: throw HttpUnprocessableEntityException("missing genres")
        song.checkFieldLengths()
        return transaction {
            SongEntity.new {
                this.ownerId = EntityID(ownerId, UserTable)
                this.title = title
                this.genres = genres.toTypedArray()
                moods = song.moods?.toTypedArray()
                coverArtUrl = song.coverArtUrl
                description = song.description
                credits = song.credits
                duration = song.duration
                streamUrl = song.streamUrl
                nftPolicyId = song.nftPolicyId
                nftName = song.nftName
                mintingStatus = song.mintingStatus ?: mintingStatus
                marketplaceStatus = song.marketplaceStatus ?: marketplaceStatus
            }.id.value
        }
    }

    override suspend fun update(songId: UUID, song: Song, requesterId: UUID?) {
        logger.debug { "update: songId = $songId, song = $song, requesterId = $requesterId" }
        song.checkFieldLengths()
        transaction {
            val entity = SongEntity[songId]
            requesterId?.let { entity.checkRequester(it) }
            with(song) {
                title?.let { entity.title = it }
                genres?.let { entity.genres = it.toTypedArray() }
                moods?.let { entity.moods = it.toTypedArray() }
                coverArtUrl?.let { entity.coverArtUrl = it }
                description?.let { entity.description = it }
                credits?.let { entity.credits = it }
                duration?.let { entity.duration = it }
                streamUrl?.let { entity.streamUrl = it }
                nftPolicyId?.let { entity.nftPolicyId = it }
                nftName?.let { entity.nftName = it }
                mintingStatus?.let { entity.mintingStatus = it }
                marketplaceStatus?.let { entity.marketplaceStatus = it }
            }
        }
    }

    override suspend fun delete(songId: UUID, requesterId: UUID) {
        logger.debug { "delete: songId = $songId, requesterId = $requesterId" }
        transaction {
            val entity = SongEntity[songId]
            entity.checkRequester(requesterId)
            entity.delete()
        }
    }

    override suspend fun get(songId: UUID): Song {
        logger.debug { "get: songId = $songId" }
        return transaction {
            SongEntity[songId].toModel()
        }
    }

    override suspend fun getAll(filters: SongFilters, offset: Int, limit: Int): List<Song> {
        logger.debug { "getAll: filters = $filters, offset = $offset, limit = $limit" }
        return transaction {
            SongEntity.all(filters)
                .limit(n = limit, offset = offset.toLong())
                .map(SongEntity::toModel)
        }
    }

    override suspend fun getAllCount(filters: SongFilters): Long {
        logger.debug { "getAllCount: filters = $filters" }
        return transaction {
            SongEntity.all(filters).count()
        }
    }

    override suspend fun getGenres(filters: SongFilters, offset: Int, limit: Int): List<String> {
        logger.debug { "getGenres: filters = $filters, offset = $offset, limit = $limit" }
        return transaction {
            SongEntity.genres(filters)
                .limit(n = limit, offset = offset.toLong())
                .toList()
        }
    }

    override suspend fun getGenreCount(filters: SongFilters): Long {
        logger.debug { "getGenresCount: filters = $filters" }
        return transaction {
            SongEntity.genres(filters).count()
        }
    }

    override suspend fun generateAudioUploadUrl(
        songId: UUID,
        requesterId: UUID,
        fileName: String
    ): String {
        logger.debug { "generateAudioUploadUrl: songId = $songId, fileName = $fileName" }

        if ('/' in fileName) throw HttpUnprocessableEntityException("Invalid fileName: $fileName")

        transaction {
            SongEntity[songId].checkRequester(requesterId)
        }

        val config = environment.getConfigChild("aws.s3.audio")
        val expiration = Instant.now()
            .plusSeconds(config.getLong("timeToLive"))
            .toDate()

        return s3.generatePresignedUrl(
            config.getString("bucketName"),
            "$songId/$fileName",
            expiration,
            HttpMethod.PUT
        ).toString()
    }

    override suspend fun processStreamTokenAgreement(songId: UUID, requesterId: UUID, accepted: Boolean) {
        logger.debug { "processStreamTokenAgreement: songId = $songId, accepted = $accepted" }

        val bucketName = environment.getConfigString("aws.s3.agreement.bucketName")
        val fileName = environment.getConfigString("aws.s3.agreement.fileName")
        val filePath = "$songId/$fileName"

        val update = { status: MintingStatus ->
            transaction {
                SongEntity[songId].run {
                    checkRequester(requesterId)
                    mintingStatus = status
                }
            }
        }

        if (accepted) {
            if (!s3.doesObjectExist(bucketName, filePath)) {
                throw HttpUnprocessableEntityException("missing: $filePath")
            }
            update(MintingStatus.StreamTokenAgreementApproved)
        } else {
            update(MintingStatus.Undistributed)
            s3.deleteObject(bucketName, filePath)
        }
    }

    private fun SongEntity.checkRequester(requesterId: UUID) {
        if (ownerId.value != requesterId) throw HttpForbiddenException("operation allowed only by owner")
    }

    private fun Song.checkFieldLengths() {
        title?.checkLength("title")
        genres?.forEachIndexed { index, genre -> genre.checkLength("genres$index") }
        moods?.forEachIndexed { index, mood -> mood.checkLength("moods$index") }
        description?.checkLength("description", 250)
        credits?.checkLength("credits")
        nftName?.checkLength("nftName")
    }
}
