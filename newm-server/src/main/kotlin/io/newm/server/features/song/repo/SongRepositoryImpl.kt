package io.newm.server.features.song.repo

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.sqs.model.SendMessageRequest
import com.google.iot.cbor.CborInteger
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.encodedPath
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.util.logging.Logger
import io.newm.chain.grpc.Utxo
import io.newm.chain.grpc.outputUtxo
import io.newm.chain.util.toHexString
import io.newm.server.aws.cloudfront.cloudfrontAudioStreamData
import io.newm.server.aws.s3.createPresignedPost
import io.newm.server.aws.s3.model.ContentLengthRangeCondition
import io.newm.server.aws.s3.model.PresignedPost
import io.newm.server.aws.s3.s3UrlStringOf
import io.newm.server.config.repo.ConfigRepository
import io.newm.server.config.repo.ConfigRepository.Companion.CONFIG_KEY_MINT_PRICE
import io.newm.server.features.cardano.database.KeyTable
import io.newm.server.features.cardano.model.Key
import io.newm.server.features.cardano.repo.CardanoRepository
import io.newm.server.features.collaboration.model.CollaborationFilters
import io.newm.server.features.collaboration.model.CollaborationStatus
import io.newm.server.features.collaboration.repo.CollaborationRepository
import io.newm.server.features.distribution.DistributionRepository
import io.newm.server.features.minting.MintingStatusSqsMessage
import io.newm.server.features.song.database.SongEntity
import io.newm.server.features.song.model.AudioStreamData
import io.newm.server.features.song.model.MintingStatus
import io.newm.server.features.song.model.Song
import io.newm.server.features.song.model.SongFilters
import io.newm.server.features.user.database.UserTable
import io.newm.server.ktx.asValidUrl
import io.newm.server.ktx.await
import io.newm.server.ktx.checkLength
import io.newm.server.ktx.getSecureConfigString
import io.newm.shared.exception.HttpForbiddenException
import io.newm.shared.exception.HttpUnprocessableEntityException
import io.newm.shared.koin.inject
import io.newm.shared.ktx.debug
import io.newm.shared.ktx.getConfigChild
import io.newm.shared.ktx.getConfigString
import io.newm.shared.ktx.getLong
import io.newm.shared.ktx.getString
import io.newm.shared.ktx.info
import io.newm.shared.ktx.megabytesToBytes
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.transactions.transaction
import org.koin.core.parameter.parametersOf
import java.net.URL
import java.util.UUID

internal class SongRepositoryImpl(
    private val environment: ApplicationEnvironment,
    private val s3: AmazonS3,
    private val configRepository: ConfigRepository,
    private val cardanoRepository: CardanoRepository,
    private val distributionRepository: DistributionRepository,
    private val collaborationRepository: CollaborationRepository,
) : SongRepository {

    private val logger: Logger by inject { parametersOf(javaClass.simpleName) }
    private val json: Json by inject()
    private val queueUrl by lazy { environment.getConfigString("aws.sqs.minting.queueUrl") }

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
                coverArtUrl = song.coverArtUrl?.asValidUrl()
                description = song.description
                album = song.album
                track = song.track
                language = song.language
                copyright = song.copyright
                parentalAdvisory = song.parentalAdvisory
                barcodeType = song.barcodeType
                barcodeNumber = song.barcodeNumber
                isrc = song.isrc
                iswc = song.iswc
                ipis = song.ipis?.toTypedArray()
                releaseDate = song.releaseDate
                lyricsUrl = song.lyricsUrl?.asValidUrl()
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
                coverArtUrl?.let { entity.coverArtUrl = it.asValidUrl() }
                description?.let { entity.description = it }
                album?.let { entity.album = it }
                track?.let { entity.track = it }
                language?.let { entity.language = it }
                copyright?.let { entity.copyright = it }
                parentalAdvisory?.let { entity.parentalAdvisory = it }
                barcodeType?.let { entity.barcodeType = it }
                barcodeNumber?.let { entity.barcodeNumber = it }
                isrc?.let { entity.isrc = it }
                iswc?.let { entity.iswc = it }
                ipis?.let { entity.ipis = it.toTypedArray() }
                releaseDate?.let { entity.releaseDate = it }
                lyricsUrl?.let { entity.lyricsUrl = it.asValidUrl() }
                if (requesterId == null) {
                    // don't allow updating these fields when invoked from REST API
                    publicationDate?.let { entity.publicationDate = it }
                    tokenAgreementUrl?.let { entity.tokenAgreementUrl = it }
                    originalAudioUrl?.let { entity.originalAudioUrl = it.asValidUrl() }
                    clipUrl?.let { entity.clipUrl = it.asValidUrl() }
                    streamUrl?.let { entity.streamUrl = it.asValidUrl() }
                    duration?.let { entity.duration = it }
                    nftPolicyId?.let { entity.nftPolicyId = it }
                    nftName?.let { entity.nftName = it }
                    mintingStatus?.let { entity.mintingStatus = it }
                    marketplaceStatus?.let { entity.marketplaceStatus = it }
                    paymentKeyId?.let { entity.paymentKeyId = EntityID(it, KeyTable) }
                    arweaveCoverArtUrl?.let { entity.arweaveCoverArtUrl = it.asValidUrl() }
                    arweaveLyricsUrl?.let { entity.arweaveLyricsUrl = it.asValidUrl() }
                    arweaveClipUrl?.let { entity.arweaveClipUrl = it.asValidUrl() }
                    arweaveTokenAgreementUrl?.let { entity.arweaveTokenAgreementUrl = it.asValidUrl() }
                    distributionTrackId?.let { entity.distributionTrackId = it }
                    distributionReleaseId?.let { entity.distributionReleaseId = it }
                    mintCostLovelace?.let { entity.mintCostLovelace = it }
                }
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

    override suspend fun generateAudioUpload(
        songId: UUID,
        requesterId: UUID,
        fileName: String
    ): PresignedPost {
        logger.debug { "generateAudioUpload: songId = $songId, fileName = $fileName" }

        if ('/' in fileName) throw HttpUnprocessableEntityException("Invalid fileName: $fileName")

        val config = environment.getConfigChild("aws.s3.audio")
        val bucket = config.getString("bucketName")
        val key = "$songId/$fileName"
        val url = s3UrlStringOf(bucket, key)
        transaction {
            val entity = SongEntity[songId]
            entity.checkRequester(requesterId)
            entity.originalAudioUrl = url
        }

        return s3.createPresignedPost {
            this.bucket = bucket
            this.key = key
            credentials = DefaultAWSCredentialsProviderChain.getInstance().credentials
            conditions = listOf(
                ContentLengthRangeCondition(
                    min = config.getLong("minUploadSizeMB").megabytesToBytes(),
                    max = config.getLong("maxUploadSizeMB").megabytesToBytes()
                )
            )
            expiresSeconds = config.getLong("timeToLive")
        }
    }

    override suspend fun generateAudioStreamData(songId: UUID): AudioStreamData {
        val song = get(songId)
        if (song.streamUrl == null) {
            throw HttpUnprocessableEntityException("streamUrl is null")
        }

        val songStreamUrl = URL(song.streamUrl)
        val mediaHostUrl = URL(environment.getConfigString("aws.cloudFront.audioStream.hostUrl"))
        val kpid = environment.getSecureConfigString("aws.cloudFront.audioStream.keyPairId")
        val pk = environment.getSecureConfigString("aws.cloudFront.audioStream.privateKey")
        val cookieDom = environment.getConfigString("ktor.deployment.cookieDomain")

        // fix up the url so that the url does not point to old cloudfront distros
        val streamUrl = URLBuilder().apply {
            protocol = URLProtocol.createOrDefault(mediaHostUrl.protocol)
            host = mediaHostUrl.host
            encodedPath = songStreamUrl.path
        }.build()

        return cloudfrontAudioStreamData {
            url = streamUrl.toString()
            keyPairId = kpid
            privateKey = pk
            cookieDomain = cookieDom
        }
    }

    override suspend fun processStreamTokenAgreement(songId: UUID, requesterId: UUID, accepted: Boolean) {
        logger.debug { "processStreamTokenAgreement: songId = $songId, accepted = $accepted" }

        checkRequester(songId, requesterId)

        val bucketName = environment.getConfigString("aws.s3.agreement.bucketName")
        val fileName = environment.getConfigString("aws.s3.agreement.fileName")
        val key = "$songId/$fileName"

        if (accepted) {
            if (!s3.doesObjectExist(bucketName, key)) {
                throw HttpUnprocessableEntityException("missing: $key")
            }
            update(
                songId = songId,
                song = Song(
                    mintingStatus = MintingStatus.StreamTokenAgreementApproved,
                    tokenAgreementUrl = s3UrlStringOf(bucketName, key)
                )
            )
            collaborationRepository.invite(songId)
        } else {
            update(songId, Song(mintingStatus = MintingStatus.Undistributed))
            s3.deleteObject(bucketName, key)
        }
    }

    override suspend fun getUnapprovedCollaboratorCount(songId: UUID): Int {
        val song = get(songId)
        return collaborationRepository.getAll(
            userId = song.ownerId!!,
            filters = CollaborationFilters(
                inbound = null,
                songIds = listOf(songId),
                olderThan = null,
                newerThan = null,
                ids = null,
                statuses = null,
            ),
            offset = 0,
            limit = Int.MAX_VALUE,
        ).count { (it.royaltyRate ?: -1.0f) > 0.0f && it.status != CollaborationStatus.Accepted }
    }

    override suspend fun getMintingPaymentAmountCborHex(songId: UUID, requesterId: UUID): String {
        logger.debug { "getMintingPaymentAmount: songId = $songId" }
        // TODO: We might need to change this code in the future if we're charging NEWM tokens in addition to ada
        val numberOfCollaborators = collaborationRepository.getAll(
            userId = requesterId,
            filters = CollaborationFilters(
                inbound = null,
                songIds = listOf(songId),
                olderThan = null,
                newerThan = null,
                ids = null,
                statuses = null,
            ),
            offset = 0,
            limit = Int.MAX_VALUE,
        ).count { (it.royaltyRate ?: -1.0f) > 0.0f }
        val mintCostBase = configRepository.getLong(CONFIG_KEY_MINT_PRICE)
        val minUtxo: Long = cardanoRepository.queryStreamTokenMinUtxo()
        val mintCostTotal = mintCostBase + (numberOfCollaborators * minUtxo)

        // Save the mint cost to the database
        update(songId, Song(mintCostLovelace = mintCostTotal))

        return CborInteger.create(mintCostTotal).toCborByteArray().toHexString()
    }

    override suspend fun generateMintingPaymentTransaction(
        songId: UUID,
        requesterId: UUID,
        sourceUtxos: List<Utxo>,
        changeAddress: String
    ): String {
        logger.debug { "generateMintingPaymentTransaction: songId = $songId" }

        checkRequester(songId, requesterId)

        val song = get(songId)
        val key = Key.generateNew()
        val keyId = cardanoRepository.saveKey(key)
        val transaction = cardanoRepository.buildTransaction {
            this.sourceUtxos.addAll(sourceUtxos)
            this.outputUtxos.add(
                outputUtxo {
                    address = key.address
                    lovelace = song.mintCostLovelace.toString()
                }
            )
            this.changeAddress = changeAddress
        }

        update(songId, Song(mintingStatus = MintingStatus.MintingPaymentRequested, paymentKeyId = keyId))
        return transaction.transactionCbor.toByteArray().toHexString()
    }

    override suspend fun updateSongMintingStatus(songId: UUID, mintingStatus: MintingStatus) {
        // Update DB
        update(
            songId,
            Song(mintingStatus = mintingStatus)
        )

        // Update SQS
        val messageToSend = MintingStatusSqsMessage(
            songId = songId,
            mintingStatus = mintingStatus
        )
        SendMessageRequest()
            .withQueueUrl(queueUrl)
            .withMessageBody(json.encodeToString(messageToSend))
            .await()
        logger.info { "sent: $messageToSend" }
    }

    override suspend fun distribute(songId: UUID) {
        val song = get(songId)

        distributionRepository.distributeSong(song)
    }

    private fun checkRequester(songId: UUID, requesterId: UUID) = transaction {
        SongEntity[songId].checkRequester(requesterId)
    }

    private fun SongEntity.checkRequester(requesterId: UUID) {
        if (ownerId.value != requesterId) throw HttpForbiddenException("operation allowed only by owner")
    }

    private fun Song.checkFieldLengths() {
        title?.checkLength("title")
        genres?.forEachIndexed { index, genre -> genre.checkLength("genres$index") }
        moods?.forEachIndexed { index, mood -> mood.checkLength("moods$index") }
        description?.checkLength("description", 250)
        album?.checkLength("album")
        language?.checkLength("language")
        copyright?.checkLength("copyright")
        parentalAdvisory?.checkLength("parentalAdvisory")
        barcodeNumber?.checkLength("barcodeNumber")
        isrc?.checkLength("isrc")
        iswc?.checkLength("iswc")
        ipis?.forEachIndexed { index, ipi -> ipi.checkLength("ipi$index") }
        nftName?.checkLength("nftName")
    }
}
