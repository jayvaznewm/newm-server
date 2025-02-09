package io.newm.server.features.song.repo

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.sqs.model.SendMessageRequest
import com.google.iot.cbor.CborInteger
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.encodedPath
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.util.logging.Logger
import io.ktor.utils.io.ByteReadChannel
import io.newm.chain.grpc.Utxo
import io.newm.chain.grpc.outputUtxo
import io.newm.chain.util.toAdaString
import io.newm.chain.util.toHexString
import io.newm.server.aws.cloudfront.cloudfrontAudioStreamData
import io.newm.server.aws.s3.s3UrlStringOf
import io.newm.server.config.repo.ConfigRepository
import io.newm.server.config.repo.ConfigRepository.Companion.CONFIG_KEY_DISTRIBUTION_PRICE_USD
import io.newm.server.config.repo.ConfigRepository.Companion.CONFIG_KEY_MINT_PRICE
import io.newm.server.features.cardano.database.KeyTable
import io.newm.server.features.cardano.model.Key
import io.newm.server.features.cardano.repo.CardanoRepository
import io.newm.server.features.collaboration.model.CollaborationStatus
import io.newm.server.features.collaboration.model.CollaboratorFilters
import io.newm.server.features.collaboration.repo.CollaborationRepository
import io.newm.server.features.distribution.DistributionRepository
import io.newm.server.features.email.repo.EmailRepository
import io.newm.server.features.minting.MintingStatusSqsMessage
import io.newm.server.features.song.database.SongEntity
import io.newm.server.features.song.database.SongReceiptEntity
import io.newm.server.features.song.database.SongReceiptTable
import io.newm.server.features.song.database.SongTable
import io.newm.server.features.song.model.AudioEncodingStatus
import io.newm.server.features.song.model.AudioStreamData
import io.newm.server.features.song.model.AudioUploadReport
import io.newm.server.features.song.model.MintPaymentResponse
import io.newm.server.features.song.model.MintingStatus
import io.newm.server.features.song.model.Song
import io.newm.server.features.song.model.SongFilters
import io.newm.server.features.user.database.UserEntity
import io.newm.server.features.user.database.UserTable
import io.newm.server.features.user.model.UserVerificationStatus
import io.newm.server.ktx.asValidUrl
import io.newm.server.ktx.await
import io.newm.server.ktx.checkLength
import io.newm.server.ktx.getSecureConfigString
import io.newm.shared.exception.HttpConflictException
import io.newm.shared.exception.HttpForbiddenException
import io.newm.shared.exception.HttpUnprocessableEntityException
import io.newm.shared.koin.inject
import io.newm.shared.ktx.debug
import io.newm.shared.ktx.getConfigChild
import io.newm.shared.ktx.getConfigString
import io.newm.shared.ktx.getInt
import io.newm.shared.ktx.getLong
import io.newm.shared.ktx.getString
import io.newm.shared.ktx.info
import io.newm.shared.ktx.orNull
import io.newm.shared.ktx.orZero
import io.newm.shared.ktx.propertiesFromResource
import io.newm.shared.ktx.toTempFile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.tika.Tika
import org.jaudiotagger.audio.AudioFileIO
import org.jetbrains.annotations.VisibleForTesting
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.transactions.transaction
import org.koin.core.parameter.parametersOf
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.net.URL
import java.time.LocalDateTime
import java.util.Properties
import java.util.UUID

internal class SongRepositoryImpl(
    private val environment: ApplicationEnvironment,
    private val s3: AmazonS3,
    private val configRepository: ConfigRepository,
    private val cardanoRepository: CardanoRepository,
    private val distributionRepository: DistributionRepository,
    private val collaborationRepository: CollaborationRepository,
    private val emailRepository: EmailRepository,
) : SongRepository {

    private val logger: Logger by inject { parametersOf(javaClass.simpleName) }
    private val json: Json by inject()
    private val queueUrl by lazy { environment.getConfigString("aws.sqs.minting.queueUrl") }
    private val mimeTypes: Properties by lazy {
        propertiesFromResource("audio-mime-types.properties")
    }

    override suspend fun add(song: Song, ownerId: UUID): UUID {
        logger.debug { "add: song = $song" }
        val title = song.title ?: throw HttpUnprocessableEntityException("missing title")
        val genres = song.genres ?: throw HttpUnprocessableEntityException("missing genres")
        song.checkFieldLengths()
        return transaction {
            title.checkTitleUnique(ownerId)
            SongEntity.new {
                archived = song.archived ?: false
                this.ownerId = EntityID(ownerId, UserTable)
                this.title = title
                this.genres = genres.toTypedArray()
                moods = song.moods?.toTypedArray()
                coverArtUrl = song.coverArtUrl?.asValidUrl()
                description = song.description
                album = song.album
                track = song.track
                language = song.language
                coverRemixSample = song.coverRemixSample ?: false
                compositionCopyrightOwner = song.compositionCopyrightOwner
                compositionCopyrightYear = song.compositionCopyrightYear
                phonographicCopyrightOwner = song.phonographicCopyrightOwner
                phonographicCopyrightYear = song.phonographicCopyrightYear
                parentalAdvisory = song.parentalAdvisory
                barcodeType = song.barcodeType
                barcodeNumber = song.barcodeNumber
                isrc = song.isrc
                iswc = song.iswc
                ipis = song.ipis?.toTypedArray()
                releaseDate = song.releaseDate
                publicationDate = song.publicationDate
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
                archived?.let { entity.archived = it }
                title?.let {
                    if (!it.equals(entity.title, ignoreCase = true)) {
                        it.checkTitleUnique(entity.ownerId.value)
                    }
                    entity.title = it
                }
                genres?.let { entity.genres = it.toTypedArray() }
                moods?.let { entity.moods = it.toTypedArray() }
                coverArtUrl?.let { entity.coverArtUrl = it.orNull()?.asValidUrl() }
                description?.let { entity.description = it.orNull() }
                album?.let { entity.album = it.orNull() }
                track?.let { entity.track = it }
                language?.let { entity.language = it.orNull() }
                coverRemixSample?.let { entity.coverRemixSample = it }
                compositionCopyrightOwner?.let { entity.compositionCopyrightOwner = it.orNull() }
                compositionCopyrightYear?.let { entity.compositionCopyrightYear = it }
                phonographicCopyrightOwner?.let { entity.phonographicCopyrightOwner = it.orNull() }
                phonographicCopyrightYear?.let { entity.phonographicCopyrightYear = it }
                parentalAdvisory?.let { entity.parentalAdvisory = it.orNull() }
                barcodeType?.let { entity.barcodeType = it }
                barcodeNumber?.let { entity.barcodeNumber = it }
                isrc?.let { entity.isrc = it.orNull() }
                iswc?.let { entity.iswc = it.orNull() }
                ipis?.let { entity.ipis = it.toTypedArray() }
                releaseDate?.let { entity.releaseDate = it }
                publicationDate?.let { entity.publicationDate = it }
                lyricsUrl?.let { entity.lyricsUrl = it.orNull()?.asValidUrl() }
                if (requesterId == null) {
                    // don't allow updating these fields when invoked from REST API
                    tokenAgreementUrl?.let { entity.tokenAgreementUrl = it.orNull()?.asValidUrl() }
                    originalAudioUrl?.let { entity.originalAudioUrl = it.orNull()?.asValidUrl() }
                    clipUrl?.let { entity.clipUrl = it.orNull()?.asValidUrl() }
                    streamUrl?.let { entity.streamUrl = it.orNull()?.asValidUrl() }
                    duration?.let { entity.duration = it }
                    nftPolicyId?.let { entity.nftPolicyId = it.orNull() }
                    nftName?.let { entity.nftName = it.orNull() }
                    mintingTxId?.let { entity.mintingTxId = it.orNull() }
                    audioEncodingStatus?.let { entity.audioEncodingStatus = it }
                    mintingStatus?.let { entity.mintingStatus = it }
                    marketplaceStatus?.let { entity.marketplaceStatus = it }
                    paymentKeyId?.let { entity.paymentKeyId = EntityID(it, KeyTable) }
                    arweaveCoverArtUrl?.let { entity.arweaveCoverArtUrl = it.orNull()?.asValidUrl() }
                    arweaveLyricsUrl?.let { entity.arweaveLyricsUrl = it.orNull()?.asValidUrl() }
                    arweaveClipUrl?.let { entity.arweaveClipUrl = it.orNull()?.asValidUrl() }
                    arweaveTokenAgreementUrl?.let { entity.arweaveTokenAgreementUrl = it.orNull()?.asValidUrl() }
                    distributionTrackId?.let { entity.distributionTrackId = it }
                    distributionReleaseId?.let { entity.distributionReleaseId = it }
                    mintCostLovelace?.let { entity.mintCostLovelace = it }
                    forceDistributed?.let { entity.forceDistributed = it }
                    errorMessage?.let { entity.errorMessage = it.orNull() }
                }
            }
        }
    }

    override fun set(songId: UUID, editor: (SongEntity) -> Unit) {
        logger.debug { "set: songId = $songId" }
        transaction {
            val entity = SongEntity[songId]
            editor(entity)
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

    override suspend fun uploadAudio(
        songId: UUID,
        requesterId: UUID,
        data: ByteReadChannel
    ): AudioUploadReport {
        logger.debug { "uploadAudio: songId = $songId" }

        checkRequester(songId, requesterId)
        val config = environment.getConfigChild("aws.s3.audio")
        val file = data.toTempFile()
        try {
            // enforce file size
            val size = file.length()
            val minSize = config.getLong("minFileSize")
            if (size < minSize) throw HttpUnprocessableEntityException("File is too small: $size bytes")
            val maxSize = config.getLong("maxFileSize")
            if (size > maxSize) throw HttpUnprocessableEntityException("File is too large: $size bytes")

            // enforce supported format
            val type = Tika().detect(file)
            val ext = mimeTypes.getProperty(type)
                ?: throw HttpUnprocessableEntityException("Unsupported media type: $type")

            // enforce duration
            val header = AudioFileIO.readAs(file, ext).audioHeader
            val duration = header.trackLength
            val minDuration = config.getInt("minDuration")
            if (duration < minDuration) throw HttpUnprocessableEntityException("Duration is too short: $duration secs")

            // enforce sampling rate
            val sampleRate = header.sampleRateAsNumber
            val minSampleRate = config.getInt("minSampleRate")
            if (sampleRate < minSampleRate) throw HttpUnprocessableEntityException("Sample rate is too low: $sampleRate Hz")

            val bucket = config.getString("bucketName")
            val key = "$songId/audio.$ext"
            s3.putObject(bucket, key, file)

            val url = s3UrlStringOf(bucket, key)
            transaction {
                with(SongEntity[songId]) {
                    originalAudioUrl = url
                    audioEncodingStatus = AudioEncodingStatus.Started
                }
            }
            return AudioUploadReport(url, type, size, duration, sampleRate)
        } catch (throwable: Throwable) {
            transaction { SongEntity[songId].audioEncodingStatus = AudioEncodingStatus.Failed }
            throw throwable
        } finally {
            file.delete()
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

        checkRequester(songId, requesterId, verified = true)

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
        } else {
            update(songId, Song(mintingStatus = MintingStatus.Undistributed))
            s3.deleteObject(bucketName, key)
        }
    }

    override suspend fun processAudioEncoding(songId: UUID) {
        logger.debug { "processAudioEncoding: songId = $songId" }
        with(get(songId)) {
            when (audioEncodingStatus) {
                AudioEncodingStatus.Started -> {
                    if (clipUrl != null && streamUrl != null) {
                        update(songId, Song(audioEncodingStatus = AudioEncodingStatus.Completed))
                        if (mintingStatus?.ordinal.orZero() >= MintingStatus.MintingPaymentSubmitted.ordinal) {
                            sendMintingStartedNotification(songId)
                        }
                    }
                }

                AudioEncodingStatus.Completed -> {}

                else -> return
            }
            if (mintingStatus == MintingStatus.AwaitingAudioEncoding) {
                updateSongMintingStatus(songId, MintingStatus.AwaitingCollaboratorApproval)
            }
        }
    }

    override suspend fun processCollaborations(songId: UUID) {
        logger.debug { "processCollaborations: songId = $songId" }
        if (transaction { SongEntity[songId].mintingStatus } == MintingStatus.AwaitingCollaboratorApproval) {
            val collaborations =
                collaborationRepository.getAllBySongId(songId).filter { it.royaltyRate.orZero() > BigDecimal.ZERO }
            val allAccepted = collaborations.all { it.status == CollaborationStatus.Accepted }
            if (allAccepted) {
                updateSongMintingStatus(songId, MintingStatus.ReadyToDistribute)
            } else {
                collaborations.filter { it.status != CollaborationStatus.Accepted }.forEach {
                    logger.info("AwaitingCollaboratorApproval ($songId): ${it.email} - ${it.status}")
                }
            }
        }
    }

    override suspend fun getMintingPaymentEstimate(collaborators: Int): MintPaymentResponse {
        logger.debug { "getMintingPaymentEstimate: collaborators = $collaborators" }
        val mintCostBase = configRepository.getLong(CONFIG_KEY_MINT_PRICE)
        // defined in whole usd cents with 6 decimals
        val dspPriceUsd = configRepository.getLong(CONFIG_KEY_DISTRIBUTION_PRICE_USD)
        val minUtxo: Long = cardanoRepository.queryStreamTokenMinUtxo()
        val usdAdaExchangeRate = cardanoRepository.queryAdaUSDPrice().toBigInteger()

        return calculateMintPaymentResponse(
            minUtxo,
            collaborators,
            dspPriceUsd,
            usdAdaExchangeRate,
            mintCostBase,
        )
    }

    override suspend fun getMintingPaymentAmount(songId: UUID, requesterId: UUID): MintPaymentResponse {
        logger.debug { "getMintingPaymentAmount: songId = $songId" }
        // TODO: We might need to change this code in the future if we're charging NEWM tokens in addition to ada
        val numberOfCollaborators = collaborationRepository.getAllBySongId(songId)
            .count { it.royaltyRate.orZero() > BigDecimal.ZERO }
        val mintCostBase = configRepository.getLong(CONFIG_KEY_MINT_PRICE)
        // defined in whole usd cents with 6 decimals
        val dspPriceUsd = configRepository.getLong(CONFIG_KEY_DISTRIBUTION_PRICE_USD)
        val minUtxo: Long = cardanoRepository.queryStreamTokenMinUtxo()
        val usdAdaExchangeRate = cardanoRepository.queryAdaUSDPrice().toBigInteger()

        return calculateMintPaymentResponse(
            minUtxo,
            numberOfCollaborators,
            dspPriceUsd,
            usdAdaExchangeRate,
            mintCostBase,
        ).also {
            // Save the total cost to distribute and mint to the database
            val totalCostLovelace = BigDecimal(it.adaPrice!!).movePointRight(6).toLong()
            update(songId, Song(mintCostLovelace = totalCostLovelace))

            // Save the receipt to the database
            saveOrUpdateReceipt(songId, it)
        }
    }

    @VisibleForTesting
    internal fun calculateMintPaymentResponse(
        minUtxo: Long,
        numberOfCollaborators: Int,
        dspPriceUsd: Long,
        usdAdaExchangeRate: BigInteger,
        mintCostBase: Long,
    ): MintPaymentResponse {
        val changeAmountLovelace = 1000000L // 1 ada
        val dspPriceLovelace =
            dspPriceUsd.toBigDecimal().divide(usdAdaExchangeRate.toBigDecimal(), 6, RoundingMode.CEILING)
                .times(1000000.toBigDecimal()).toBigInteger()

        val sendTokenFee = (numberOfCollaborators * minUtxo)
        val mintCostLovelace = mintCostBase + sendTokenFee

        // usdPrice does not include the extra changeAmountLovelace that we request the wallet to provide as it
        // is returned to the user.
        val usdPrice =
            usdAdaExchangeRate * (mintCostLovelace.toBigInteger() + dspPriceLovelace) / 1000000.toBigInteger()

        val mintPriceUsd = usdAdaExchangeRate * mintCostBase.toBigInteger() / 1000000.toBigInteger()
        val sendTokenFeeUsd = usdAdaExchangeRate * sendTokenFee.toBigInteger() / 1000000.toBigInteger()
        val sendTokenFeePerArtistUsd = usdAdaExchangeRate * minUtxo.toBigInteger() / 1000000.toBigInteger()

        return MintPaymentResponse(
            cborHex = // we send an extra changeAmountLovelace to ensure we have enough ada to cover a return utxo
            CborInteger.create(mintCostLovelace + dspPriceLovelace.toLong() + changeAmountLovelace).toCborByteArray()
                .toHexString(),
            adaPrice = (mintCostLovelace.toBigInteger() + dspPriceLovelace).toAdaString(),
            usdPrice = usdPrice.toAdaString(),
            dspPriceAda = dspPriceLovelace.toAdaString(),
            dspPriceUsd = dspPriceUsd.toBigInteger().toAdaString(),
            mintPriceAda = mintCostBase.toBigInteger().toAdaString(),
            mintPriceUsd = mintPriceUsd.toAdaString(),
            collabPriceAda = sendTokenFee.toBigInteger().toAdaString(),
            collabPriceUsd = sendTokenFeeUsd.toAdaString(),
            collabPerArtistPriceAda = minUtxo.toBigInteger().toAdaString(),
            collabPerArtistPriceUsd = sendTokenFeePerArtistUsd.toAdaString(),
            usdAdaExchangeRate = usdAdaExchangeRate.toAdaString(),
        )
    }

    override suspend fun generateMintingPaymentTransaction(
        songId: UUID,
        requesterId: UUID,
        sourceUtxos: List<Utxo>,
        changeAddress: String
    ): String {
        logger.debug { "generateMintingPaymentTransaction: songId = $songId" }

        checkRequester(songId, requesterId, verified = true)

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

        update(songId, Song(paymentKeyId = keyId))
        updateSongMintingStatus(songId, MintingStatus.MintingPaymentRequested)
        return transaction.transactionCbor.toByteArray().toHexString()
    }

    override suspend fun updateSongMintingStatus(songId: UUID, mintingStatus: MintingStatus, errorMessage: String) {
        // Update DB
        update(
            songId,
            Song(
                mintingStatus = mintingStatus,
                errorMessage = errorMessage,
            )
        )

        when (mintingStatus) {
            MintingStatus.MintingPaymentSubmitted,
            MintingStatus.MintingPaymentReceived,
            MintingStatus.AwaitingAudioEncoding,
            MintingStatus.AwaitingCollaboratorApproval,
            MintingStatus.ReadyToDistribute,
            MintingStatus.SubmittedForDistribution,
            MintingStatus.Distributed,
            MintingStatus.Pending -> {
                // Update SQS
                val messageToSend = json.encodeToString(
                    MintingStatusSqsMessage(
                        songId = songId,
                        mintingStatus = mintingStatus
                    )
                )
                logger.info { "sending: $messageToSend" }
                SendMessageRequest()
                    .withQueueUrl(queueUrl)
                    .withMessageBody(messageToSend)
                    .await()
                logger.info { "sent: $messageToSend" }
            }

            else -> {}
        }

        when (mintingStatus) {
            MintingStatus.MintingPaymentSubmitted -> {
                if (transaction { SongEntity[songId].audioEncodingStatus } == AudioEncodingStatus.Completed) {
                    sendMintingStartedNotification(songId)
                }
            }

            MintingStatus.Minted -> {
                logger.info { "Minted song $songId SUCCESS!" }
                sendMintingNotification("succeeded", songId)
            }

            MintingStatus.Declined -> {
                logger.info { "Declined song $songId FAILED!" }
                sendMintingNotification("failed", songId)
            }

            else -> Unit
        }
    }

    override fun saveOrUpdateReceipt(songId: UUID, mintPaymentResponse: MintPaymentResponse) {
        logger.debug { "saveOrUpdateReceipt: songId = $songId" }
        transaction {
            SongReceiptEntity.find { SongReceiptTable.songId eq songId }.firstOrNull()?.let { receipt ->
                // Update existing receipt
                receipt.createdAt = LocalDateTime.now()
                receipt.adaPrice = BigDecimal(mintPaymentResponse.adaPrice).movePointRight(6).toLong()
                receipt.usdPrice = BigDecimal(mintPaymentResponse.usdPrice).movePointRight(6).toLong()
                receipt.adaDspPrice = BigDecimal(mintPaymentResponse.dspPriceAda).movePointRight(6).toLong()
                receipt.usdDspPrice = BigDecimal(mintPaymentResponse.dspPriceUsd).movePointRight(6).toLong()
                receipt.adaMintPrice = BigDecimal(mintPaymentResponse.mintPriceAda).movePointRight(6).toLong()
                receipt.usdMintPrice = BigDecimal(mintPaymentResponse.mintPriceUsd).movePointRight(6).toLong()
                receipt.adaCollabPrice = BigDecimal(mintPaymentResponse.collabPriceAda).movePointRight(6).toLong()
                receipt.usdCollabPrice = BigDecimal(mintPaymentResponse.collabPriceUsd).movePointRight(6).toLong()
                receipt.usdAdaExchangeRate =
                    BigDecimal(mintPaymentResponse.usdAdaExchangeRate).movePointRight(6).toLong()
            } ?: run {
                // Create new receipt
                SongReceiptEntity.new {
                    this.songId = EntityID(songId, SongTable)
                    createdAt = LocalDateTime.now()
                    adaPrice = BigDecimal(mintPaymentResponse.adaPrice).movePointRight(6).toLong()
                    usdPrice = BigDecimal(mintPaymentResponse.usdPrice).movePointRight(6).toLong()
                    adaDspPrice = BigDecimal(mintPaymentResponse.dspPriceAda).movePointRight(6).toLong()
                    usdDspPrice = BigDecimal(mintPaymentResponse.dspPriceUsd).movePointRight(6).toLong()
                    adaMintPrice = BigDecimal(mintPaymentResponse.mintPriceAda).movePointRight(6).toLong()
                    usdMintPrice = BigDecimal(mintPaymentResponse.mintPriceUsd).movePointRight(6).toLong()
                    adaCollabPrice = BigDecimal(mintPaymentResponse.collabPriceAda).movePointRight(6).toLong()
                    usdCollabPrice = BigDecimal(mintPaymentResponse.collabPriceUsd).movePointRight(6).toLong()
                    usdAdaExchangeRate = BigDecimal(mintPaymentResponse.usdAdaExchangeRate).movePointRight(6).toLong()
                }
            }
        }
    }

    private suspend fun sendMintingStartedNotification(songId: UUID) {
        collaborationRepository.invite(songId)
        sendMintingNotification("started", songId)
    }

    private suspend fun sendMintingNotification(path: String, songId: UUID) {
        val (song, owner) = transaction {
            val song = SongEntity[songId]
            song to UserEntity[song.ownerId]
        }

        val collaborations = collaborationRepository.getAllBySongId(song.id.value)
            .filter { it.royaltyRate.orZero() > BigDecimal.ZERO }

        val collaborators = collaborationRepository.getCollaborators(
            userId = owner.id.value,
            filters = CollaboratorFilters(emails = collaborations.mapNotNull { it.email }),
            offset = 0,
            limit = Int.MAX_VALUE
        )

        emailRepository.send(
            to = owner.email,
            subject = environment.getConfigString("mintingNotifications.$path.subject"),
            messageUrl = environment.getConfigString("mintingNotifications.$path.messageUrl"),
            messageArgs = mapOf(
                "owner" to owner.stageOrFullName,
                "song" to song.title,
                "collabs" to collaborations.joinToString(separator = "") { collaboration ->
                    "<li>${
                        collaborators.firstOrNull { it.email.equals(collaboration.email, ignoreCase = true) }
                            ?.user?.stageOrFullName ?: collaboration.email
                    }: ${collaboration.royaltyRate}%</li>"
                }
            )
        )
    }

    override suspend fun distribute(songId: UUID) {
        val song = get(songId)

        distributionRepository.distributeSong(song)
    }

    private fun checkRequester(songId: UUID, requesterId: UUID, verified: Boolean = false) = transaction {
        SongEntity[songId].checkRequester(requesterId, verified)
    }

    private fun SongEntity.checkRequester(requesterId: UUID, verified: Boolean = false) {
        if (ownerId.value != requesterId) throw HttpForbiddenException("operation allowed only by owner")
        if (verified && UserEntity[requesterId].verificationStatus != UserVerificationStatus.Verified) {
            throw HttpUnprocessableEntityException("operation allowed only after owner is KYC verified")
        }
    }

    private fun String.checkTitleUnique(ownerId: UUID) {
        if (SongEntity.exists(ownerId, this)) {
            throw HttpConflictException("Title already exists: $this")
        }
    }

    private fun Song.checkFieldLengths() {
        title?.checkLength("title")
        genres?.forEachIndexed { index, genre -> genre.checkLength("genres$index") }
        moods?.forEachIndexed { index, mood -> mood.checkLength("moods$index") }
        description?.checkLength("description", 250)
        album?.checkLength("album")
        language?.checkLength("language")
        compositionCopyrightOwner?.checkLength("compositionCopyrightOwner")
        phonographicCopyrightOwner?.checkLength("phonographicCopyrightOwner")
        parentalAdvisory?.checkLength("parentalAdvisory")
        barcodeNumber?.checkLength("barcodeNumber")
        isrc?.checkLength("isrc")
        iswc?.checkLength("iswc")
        ipis?.forEachIndexed { index, ipi -> ipi.checkLength("ipi$index") }
        nftName?.checkLength("nftName")
    }
}
