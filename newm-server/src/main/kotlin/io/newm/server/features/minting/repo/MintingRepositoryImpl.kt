package io.newm.server.features.minting.repo

import com.google.common.annotations.VisibleForTesting
import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborInteger
import com.google.iot.cbor.CborMap
import com.google.iot.cbor.CborTextString
import com.google.protobuf.ByteString
import com.google.protobuf.kotlin.toByteString
import io.newm.chain.grpc.PlutusData
import io.newm.chain.grpc.RedeemerTag
import io.newm.chain.grpc.Signature
import io.newm.chain.grpc.Utxo
import io.newm.chain.grpc.nativeAsset
import io.newm.chain.grpc.outputUtxo
import io.newm.chain.grpc.plutusData
import io.newm.chain.grpc.plutusDataList
import io.newm.chain.grpc.plutusDataMap
import io.newm.chain.grpc.plutusDataMapItem
import io.newm.chain.grpc.redeemer
import io.newm.chain.grpc.signature
import io.newm.chain.util.Blake2b
import io.newm.chain.util.Sha3
import io.newm.chain.util.hexToByteArray
import io.newm.server.config.repo.ConfigRepository
import io.newm.server.config.repo.ConfigRepository.Companion.CONFIG_KEY_MINT_CASH_REGISTER_COLLECTION_AMOUNT
import io.newm.server.config.repo.ConfigRepository.Companion.CONFIG_KEY_MINT_CASH_REGISTER_MIN_AMOUNT
import io.newm.server.config.repo.ConfigRepository.Companion.CONFIG_KEY_MINT_CIP68_POLICY
import io.newm.server.config.repo.ConfigRepository.Companion.CONFIG_KEY_MINT_CIP68_SCRIPT_ADDRESS
import io.newm.server.config.repo.ConfigRepository.Companion.CONFIG_KEY_MINT_SCRIPT_UTXO_REFERENCE
import io.newm.server.config.repo.ConfigRepository.Companion.CONFIG_KEY_MINT_STARTER_TOKEN_UTXO_REFERENCE
import io.newm.server.features.cardano.model.Key
import io.newm.server.features.cardano.repo.CardanoRepository
import io.newm.server.features.collaboration.model.Collaboration
import io.newm.server.features.collaboration.repo.CollaborationRepository
import io.newm.server.features.minting.model.MintInfo
import io.newm.server.features.song.model.Song
import io.newm.server.features.user.database.UserEntity
import io.newm.server.features.user.model.User
import io.newm.server.features.user.repo.UserRepository
import io.newm.server.ktx.sign
import io.newm.server.ktx.toReferenceUtxo
import io.newm.shared.koin.inject
import io.newm.shared.ktx.info
import io.newm.shared.ktx.orZero
import io.newm.shared.ktx.toHexString
import io.newm.txbuilder.ktx.toCborObject
import io.newm.txbuilder.ktx.toPlutusData
import org.jetbrains.exposed.sql.transactions.transaction
import org.koin.core.parameter.parametersOf
import org.slf4j.Logger
import java.math.BigDecimal
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class MintingRepositoryImpl(
    private val userRepository: UserRepository,
    private val collabRepository: CollaborationRepository,
    private val cardanoRepository: CardanoRepository,
    private val configRepository: ConfigRepository,
) : MintingRepository {

    private val log: Logger by inject { parametersOf(javaClass.simpleName) }

    override suspend fun mint(song: Song): MintInfo {
        return cardanoRepository.withLock {
            val user = userRepository.get(song.ownerId!!)
            val collabs = collabRepository.getAllBySongId(song.id!!)
            val cip68Metadata = buildStreamTokenMetadata(song, user, collabs)
            val streamTokensTotal = 100_000_000L
            var streamTokensRemaining = 100_000_000L
            val splitCollabs =
                collabs.filter { it.royaltyRate!! > BigDecimal.ZERO }.sortedByDescending { it.royaltyRate }
            log.info { "splitCollabs for songId: ${song.id} - $splitCollabs" }
            val royaltySum = splitCollabs.sumOf { it.royaltyRate!! }
            require(royaltySum.compareTo(100.toBigDecimal()) == 0) { "Collaboration royalty rates must sum to 100 but was $royaltySum" }
            val streamTokenSplits = splitCollabs.mapIndexed { index, collaboration ->
                val collabUser = userRepository.findByEmail(collaboration.email!!)
                val splitMultiplier = collaboration.royaltyRate!!.toDouble() / 100.0
                val amount = if (index < splitCollabs.lastIndex) {
                    // round down to nearest whole token
                    (streamTokensTotal * splitMultiplier).toLong()
                } else {
                    streamTokensRemaining
                }
                streamTokensRemaining -= amount
                Pair(collabUser.walletAddress!!, amount)
            }
            log.info { "Royalty splits for songId: ${song.id} - $streamTokenSplits" }
            val paymentKey = cardanoRepository.getKey(song.paymentKeyId!!)
            val mintPriceLovelace = song.mintCostLovelace.toString()
            val cip68ScriptAddress = configRepository.getString(CONFIG_KEY_MINT_CIP68_SCRIPT_ADDRESS)
            val cip68Policy = configRepository.getString(CONFIG_KEY_MINT_CIP68_POLICY)
            val cashRegisterCollectionAmount = configRepository.getLong(CONFIG_KEY_MINT_CASH_REGISTER_COLLECTION_AMOUNT)
            val cashRegisterMinAmount = configRepository.getLong(CONFIG_KEY_MINT_CASH_REGISTER_MIN_AMOUNT)
            val paymentUtxo = cardanoRepository.queryLiveUtxos(paymentKey.address)
                .first { it.lovelace == mintPriceLovelace && it.nativeAssetsCount == 0 }
            val cashRegisterKey =
                requireNotNull(cardanoRepository.getKeyByName("cashRegister")) { "cashRegister key not defined!" }
            val cashRegisterUtxos = cardanoRepository.queryLiveUtxos(cashRegisterKey.address)
                .filter { it.nativeAssetsCount == 0 }
                .sortedByDescending { it.lovelace.toLong() }
                .take(5)
            val cashRegisterAmount = cashRegisterUtxos.sumOf { it.lovelace.toLong() }
            require(cashRegisterUtxos.isNotEmpty()) { "cashRegister has no utxos!" }
            val moneyBoxKey = if (cashRegisterAmount >= cashRegisterCollectionAmount + cashRegisterMinAmount) {
                // we should collect to the moneybox
                cardanoRepository.getKeyByName("moneyBox")
            } else {
                null
            }
            val moneyBoxUtxos = moneyBoxKey?.let {
                cardanoRepository.queryLiveUtxos(moneyBoxKey.address)
                    .filter { it.nativeAssetsCount == 0 }
                    .sortedByDescending { it.lovelace.toLong() }
                    .take(5)
            }

            // sort utxos lexicographically smallest to largest to find the one we'll use as the reference utxo
            val refUtxo = (
                cashRegisterUtxos + listOf(paymentUtxo) + (moneyBoxUtxos ?: emptyList())
                ).sortedWith { o1, o2 ->
                o1.hash.compareTo(o2.hash).let { if (it == 0) o1.ix.compareTo(o2.ix) else it }
            }.first()
            val (refTokenName, fracTokenName) = calculateTokenNames(refUtxo)
            val collateralKey =
                requireNotNull(cardanoRepository.getKeyByName("collateral")) { "collateral key not defined!" }
            val collateralUtxo = requireNotNull(
                cardanoRepository.queryLiveUtxos(collateralKey.address)
                    .filter { it.nativeAssetsCount == 0 }
                    .maxByOrNull { it.lovelace.toLong() }
            ) { "collateral utxo not found!" }
            val starterTokenUtxoReference =
                configRepository.getString(CONFIG_KEY_MINT_STARTER_TOKEN_UTXO_REFERENCE).toReferenceUtxo()
            val scriptUtxoReference =
                configRepository.getString(CONFIG_KEY_MINT_SCRIPT_UTXO_REFERENCE).toReferenceUtxo()

            val signingKeys = listOfNotNull(cashRegisterKey, moneyBoxKey, paymentKey, collateralKey)

            var transactionBuilderResponse =
                buildMintingTransaction(
                    paymentUtxo = paymentUtxo,
                    cashRegisterUtxos = cashRegisterUtxos,
                    changeAddress = cashRegisterKey.address,
                    moneyBoxUtxos = moneyBoxUtxos,
                    moneyBoxAddress = moneyBoxKey?.address,
                    cashRegisterCollectionAmount = cashRegisterCollectionAmount,
                    collateralUtxo = collateralUtxo,
                    collateralReturnAddress = collateralKey.address,
                    cip68ScriptAddress = cip68ScriptAddress,
                    cip68Metadata = cip68Metadata,
                    cip68Policy = cip68Policy,
                    refTokenName = refTokenName,
                    fracTokenName = fracTokenName,
                    streamTokenSplits = streamTokenSplits,
                    requiredSigners = signingKeys,
                    starterTokenUtxoReference = starterTokenUtxoReference,
                    mintScriptUtxoReference = scriptUtxoReference,
                    signatures = signTransactionDummy(signingKeys)
                )

            // check for errors in tx building
            if (transactionBuilderResponse.hasErrorMessage()) {
                throw IllegalStateException("TransactionBuilder Error!: ${transactionBuilderResponse.errorMessage}")
            }

            val transactionIdBytes = transactionBuilderResponse.transactionId.hexToByteArray()

            // get signatures for this transaction
            transactionBuilderResponse =
                buildMintingTransaction(
                    paymentUtxo = paymentUtxo,
                    cashRegisterUtxos = cashRegisterUtxos,
                    changeAddress = cashRegisterKey.address,
                    moneyBoxUtxos = moneyBoxUtxos,
                    moneyBoxAddress = moneyBoxKey?.address,
                    cashRegisterCollectionAmount = cashRegisterCollectionAmount,
                    collateralUtxo = collateralUtxo,
                    collateralReturnAddress = collateralKey.address,
                    cip68ScriptAddress = cip68ScriptAddress,
                    cip68Metadata = cip68Metadata,
                    cip68Policy = cip68Policy,
                    refTokenName = refTokenName,
                    fracTokenName = fracTokenName,
                    streamTokenSplits = streamTokenSplits,
                    requiredSigners = signingKeys,
                    starterTokenUtxoReference = starterTokenUtxoReference,
                    mintScriptUtxoReference = scriptUtxoReference,
                    signatures = signTransaction(transactionIdBytes, signingKeys),
                )

            // check for errors in tx building
            if (transactionBuilderResponse.hasErrorMessage()) {
                throw IllegalStateException("TransactionBuilder Error!: ${transactionBuilderResponse.errorMessage}")
            }

            val submitTransactionResponse =
                cardanoRepository.submitTransaction(transactionBuilderResponse.transactionCbor)
            return@withLock if (submitTransactionResponse.result == "MsgAcceptTx") {
                MintInfo(
                    transactionId = submitTransactionResponse.txId,
                    policyId = cip68Policy,
                    assetName = fracTokenName,
                )
            } else {
                throw IllegalStateException(submitTransactionResponse.result)
            }
        }
    }

    @VisibleForTesting
    internal fun signTransaction(
        transactionIdBytes: ByteArray,
        signingKeys: List<Key>
    ): List<Signature> = signingKeys.map { key ->
        signature {
            vkey = key.vkey.toByteString()
            sig = key.sign(transactionIdBytes).toByteString()
        }
    }

    @VisibleForTesting
    internal fun signTransactionDummy(signingKeys: List<Key>) = signingKeys.map { key ->
        signature {
            vkey = key.vkey.toByteString()
            sig = ByteArray(64).toByteString()
        }
    }

    @VisibleForTesting
    internal suspend fun buildMintingTransaction(
        paymentUtxo: Utxo,
        cashRegisterUtxos: List<Utxo>,
        changeAddress: String,
        moneyBoxUtxos: List<Utxo>?,
        moneyBoxAddress: String?,
        cashRegisterCollectionAmount: Long,
        collateralUtxo: Utxo,
        collateralReturnAddress: String,
        cip68ScriptAddress: String,
        cip68Metadata: PlutusData,
        cip68Policy: String,
        refTokenName: String,
        fracTokenName: String,
        streamTokenSplits: List<Pair<String, Long>>,
        requiredSigners: List<Key>,
        starterTokenUtxoReference: Utxo,
        mintScriptUtxoReference: Utxo,
        signatures: List<Signature> = emptyList()
    ) = cardanoRepository.buildTransaction {
        with(sourceUtxos) {
            add(paymentUtxo)
            moneyBoxUtxos?.let { addAll(it) }
            addAll(cashRegisterUtxos)
        }
        with(outputUtxos) {
            // reference NFT output to cip68 script address
            add(
                outputUtxo {
                    address = cip68ScriptAddress
                    // lovelace = "0" auto-calculated minutxo
                    nativeAssets.add(
                        nativeAsset {
                            policy = cip68Policy
                            name = refTokenName
                            amount = "1"
                        }
                    )
                    datumHash = Blake2b.hash256(cip68Metadata.toCborObject().toCborByteArray()).toHexString()
                }
            )

            // fraction SFT output to each artist's wallet
            streamTokenSplits.forEach { (artistWalletAddress, streamTokenAmount) ->
                add(
                    outputUtxo {
                        address = artistWalletAddress
                        // lovelace = "0" auto-calculated minutxo
                        nativeAssets.add(
                            nativeAsset {
                                policy = cip68Policy
                                name = fracTokenName
                                amount = streamTokenAmount.toString()
                            }
                        )
                    }
                )
            }

            // collect some into the moneyBox
            moneyBoxAddress?.let {
                add(
                    outputUtxo {
                        address = it
                        lovelace = (
                            cashRegisterCollectionAmount + (
                                moneyBoxUtxos?.sumOf { it.lovelace.toLong() }.orZero()
                                )
                            ).toString()
                    }
                )
            }
        }

        this.changeAddress = changeAddress

        with(mintTokens) {
            add(
                nativeAsset {
                    policy = cip68Policy
                    name = refTokenName
                    amount = "1"
                }
            )
            add(
                nativeAsset {
                    policy = cip68Policy
                    name = fracTokenName
                    amount = "100000000"
                }
            )
        }

        collateralUtxos.add(collateralUtxo)
        this.collateralReturnAddress = collateralReturnAddress

        this.requiredSigners.addAll(requiredSigners.map { key -> key.requiredSigner().toByteString() })

        with(referenceInputs) {
            add(starterTokenUtxoReference)
            add(mintScriptUtxoReference)
        }

        if (signatures.isNotEmpty()) {
            this.signatures.addAll(signatures)
        }

        redeemers.add(
            redeemer {
                tag = RedeemerTag.MINT
                index = 0L
                data = plutusData {
                    constr = 0
                    list = plutusDataList { }
                }
                // calculated
                // exUnits = exUnits {
                //    mem = 2895956L
                //    steps = 793695629L
                // }
            }
        )

        with(datums) {
            add(cip68Metadata)
        }

        // Add a simple transaction metadata note for NEWM Mint - See: CIP-20
        transactionMetadataCbor = ByteString.copyFrom(
            CborMap.create(
                mapOf(
                    CborInteger.create(674) to CborMap.create(
                        mapOf(
                            CborTextString.create("msg") to CborArray.create().apply {
                                add(CborTextString.create("NEWM Mint"))
                            }
                        )
                    )
                )
            ).toCborByteArray()
        )
    }

    /**
     * Return the token name for the reference token (100) and the fractional tokens (444)
     */
    @VisibleForTesting
    internal fun calculateTokenNames(utxo: Utxo): Pair<String, String> {
        val txHash = Sha3.hash256(utxo.hash.hexToByteArray())
        val txHashHex = (ByteArray(1) { utxo.ix.toByte() } + txHash).copyOfRange(0, 28).toHexString()
        return Pair(
            PREFIX_REF_TOKEN + txHashHex,
            PREFIX_FRAC_TOKEN + txHashHex,
        )
    }

    @VisibleForTesting
    internal fun buildStreamTokenMetadata(song: Song, user: User, collabs: List<Collaboration>): PlutusData {
        return plutusData {
            constr = 0
            list = plutusDataList {
                with(listItem) {
                    add(
                        plutusData {
                            map = plutusDataMap {
                                with(mapItem) {
                                    add(
                                        plutusDataMapItem {
                                            mapItemKey = "name".toPlutusData()
                                            mapItemValue = song.title!!.toPlutusData()
                                        }
                                    )
                                    add(
                                        plutusDataMapItem {
                                            mapItemKey = "image".toPlutusData()
                                            mapItemValue = song.arweaveCoverArtUrl!!.toPlutusData()
                                        }
                                    )
                                    add(
                                        plutusDataMapItem {
                                            mapItemKey = "mediaType".toPlutusData()
                                            mapItemValue = "image/webp".toPlutusData()
                                        }
                                    )
                                    add(
                                        plutusDataMapItem {
                                            mapItemKey = "music_metadata_version".toPlutusData()
                                            mapItemValue = plutusData { int = 2 } // CIP-60 Version 2
                                        }
                                    )
                                    add(createPlutusDataRelease(song, collabs))
                                    add(createPlutusDataFiles(song, user, collabs))
                                    // DO NOT ADD LINKS for now. Revisit later
                                    // add(createPlutusDataLinks(user, collabs))
                                }
                            }
                        }
                    )
                    // CIP-68 Version
                    add(plutusData { int = 1L })
                }
            }
        }
    }

    private fun createPlutusDataRelease(song: Song, collabs: List<Collaboration>) = plutusDataMapItem {
        mapItemKey = "release".toPlutusData()
        mapItemValue = plutusData {
            map = plutusDataMap {
                with(mapItem) {
                    add(
                        plutusDataMapItem {
                            mapItemKey = "release_type".toPlutusData()
                            mapItemValue =
                                "Single".toPlutusData() // All Singles for now
                        }
                    )
                    add(
                        plutusDataMapItem {
                            mapItemKey = "release_title".toPlutusData()
                            mapItemValue = (
                                song.album
                                    ?: song.title
                                )!!.toPlutusData() // NOTE: for single, track title is used as album name
                        }
                    )
                    add(
                        plutusDataMapItem {
                            mapItemKey = "release_date".toPlutusData()
                            mapItemValue = song.releaseDate!!.toString().toPlutusData()
                        }
                    )
                    song.publicationDate?.let {
                        add(
                            plutusDataMapItem {
                                mapItemKey = "publication_date".toPlutusData()
                                mapItemValue = it.toString().toPlutusData()
                            }
                        )
                    }
                    add(
                        plutusDataMapItem {
                            mapItemKey = "distributor".toPlutusData()
                            mapItemValue = "https://newm.io".toPlutusData()
                        }
                    )
                    val visualArtists =
                        collabs.filter { it.role.equals("Artwork", ignoreCase = true) && it.credited == true }
                    if (visualArtists.isNotEmpty()) {
                        transaction {
                            add(
                                plutusDataMapItem {
                                    mapItemKey = "visual_artist".toPlutusData()
                                    mapItemValue = if (visualArtists.size > 1) {
                                        plutusData {
                                            list = plutusDataList {
                                                listItem.addAll(
                                                    visualArtists.map { collab ->
                                                        UserEntity.getByEmail(collab.email!!)!!
                                                            .toModel(false).stageOrFullName
                                                            .toPlutusData()
                                                    }
                                                )
                                            }
                                        }
                                    } else {
                                        UserEntity.getByEmail(visualArtists[0].email!!)!!
                                            .toModel(false).stageOrFullName.toPlutusData()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun createPlutusDataFiles(song: Song, user: User, collabs: List<Collaboration>) = plutusDataMapItem {
        mapItemKey = "files".toPlutusData()
        mapItemValue = plutusData {
            list = plutusDataList {
                with(listItem) {
                    add(
                        plutusData {
                            map = plutusDataMap {
                                with(mapItem) {
                                    add(
                                        plutusDataMapItem {
                                            mapItemKey = "name".toPlutusData()
                                            mapItemValue = "Streaming Royalty Share Agreement".toPlutusData()
                                        }
                                    )
                                    add(
                                        plutusDataMapItem {
                                            mapItemKey = "mediaType".toPlutusData()
                                            mapItemValue = "application/pdf".toPlutusData()
                                        }
                                    )
                                    add(
                                        plutusDataMapItem {
                                            mapItemKey = "src".toPlutusData()
                                            mapItemValue = song.arweaveTokenAgreementUrl!!.toPlutusData()
                                        }
                                    )
                                }
                            }
                        }
                    )
                    add(
                        plutusData {
                            map = plutusDataMap {
                                with(mapItem) {
                                    add(
                                        plutusDataMapItem {
                                            mapItemKey = "name".toPlutusData()
                                            mapItemValue = song.title!!.toPlutusData()
                                        }
                                    )
                                    add(
                                        plutusDataMapItem {
                                            mapItemKey = "mediaType".toPlutusData()
                                            mapItemValue = "audio/mpeg".toPlutusData()
                                        }
                                    )
                                    add(
                                        plutusDataMapItem {
                                            mapItemKey = "src".toPlutusData()
                                            mapItemValue = song.arweaveClipUrl!!.toPlutusData()
                                        }
                                    )
                                    add(createPlutusDataSong(song, user, collabs))
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    private fun createPlutusDataSong(song: Song, user: User, collabs: List<Collaboration>) = plutusDataMapItem {
        mapItemKey = "song".toPlutusData()
        mapItemValue = plutusData {
            map = plutusDataMap {
                with(mapItem) {
                    add(
                        plutusDataMapItem {
                            mapItemKey = "song_title".toPlutusData()
                            mapItemValue = song.title!!.toPlutusData()
                        }
                    )
                    add(
                        plutusDataMapItem {
                            mapItemKey = "song_duration".toPlutusData()
                            mapItemValue =
                                song.duration!!.milliseconds.inWholeSeconds.seconds.toIsoString().toPlutusData()
                        }
                    )
                    add(
                        plutusDataMapItem {
                            mapItemKey = "track_number".toPlutusData()
                            mapItemValue = 1.toPlutusData()
                        }
                    )
                    song.moods?.takeUnless { it.isEmpty() }?.let { moods ->
                        add(
                            plutusDataMapItem {
                                mapItemKey = "mood".toPlutusData()
                                mapItemValue = if (moods.size > 1) {
                                    plutusData {
                                        list = plutusDataList {
                                            listItem.addAll(
                                                moods.map { it.toPlutusData() }
                                            )
                                        }
                                    }
                                } else {
                                    moods[0].toPlutusData()
                                }
                            }
                        )
                    }
                    add(createPlutusDataArtists(user, collabs))
                    if (!song.genres.isNullOrEmpty()) {
                        add(
                            plutusDataMapItem {
                                mapItemKey = "genres".toPlutusData()
                                mapItemValue = plutusData {
                                    list = plutusDataList {
                                        listItem.addAll(song.genres.map { it.toPlutusData() })
                                    }
                                }
                            }
                        )
                    }
                    add(
                        plutusDataMapItem {
                            mapItemKey = "copyright".toPlutusData()
                            mapItemValue =
                                "© ${song.compositionCopyrightYear} ${song.compositionCopyrightOwner}, ℗ ${song.phonographicCopyrightYear} ${song.phonographicCopyrightOwner}".toPlutusData()
                        }
                    )
                    if (!song.arweaveLyricsUrl.isNullOrBlank()) {
                        add(
                            plutusDataMapItem {
                                mapItemKey = "lyrics".toPlutusData()
                                mapItemValue = song.arweaveLyricsUrl.toPlutusData()
                            }
                        )
                    }
                    if (!song.parentalAdvisory.isNullOrBlank()) {
                        add(
                            plutusDataMapItem {
                                mapItemKey = "parental_advisory".toPlutusData()
                                mapItemValue = song.parentalAdvisory.toPlutusData()
                            }
                        )
                        add(
                            plutusDataMapItem {
                                mapItemKey = "explicit".toPlutusData()
                                mapItemValue =
                                    (!song.parentalAdvisory.equals("Non-Explicit", ignoreCase = true)).toString()
                                        .toPlutusData()
                            }
                        )
                    }
                    add(
                        plutusDataMapItem {
                            mapItemKey = "isrc".toPlutusData()
                            mapItemValue = song.isrc!!.toPlutusData()
                        }
                    )
                    if (!song.iswc.isNullOrBlank()) {
                        add(
                            plutusDataMapItem {
                                mapItemKey = "iswc".toPlutusData()
                                mapItemValue = song.iswc.toPlutusData()
                            }
                        )
                    }
                    if (!song.ipis.isNullOrEmpty()) {
                        add(
                            plutusDataMapItem {
                                mapItemKey = "ipi".toPlutusData()
                                mapItemValue = plutusData {
                                    list = plutusDataList {
                                        listItem.addAll(song.ipis.map { it.toPlutusData() })
                                    }
                                }
                            }
                        )
                    }

                    // TODO: add country_of_origin from idenfy data? - maybe not MVP

                    val lyricists =
                        collabs.filter { it.role.equals("Author (Lyrics)", ignoreCase = true) && it.credited == true }
                    if (lyricists.isNotEmpty()) {
                        transaction {
                            add(
                                plutusDataMapItem {
                                    mapItemKey = "lyricists".toPlutusData()
                                    mapItemValue = plutusData {
                                        list = plutusDataList {
                                            listItem.addAll(
                                                lyricists.map { collab ->
                                                    UserEntity.getByEmail(collab.email!!)!!
                                                        .toModel(false).stageOrFullName.toPlutusData()
                                                }
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }

                    val contributingArtists =
                        collabs.filter { it.role in contributingArtistRoles && it.credited == true }
                    if (contributingArtists.isNotEmpty()) {
                        transaction {
                            add(
                                plutusDataMapItem {
                                    mapItemKey = "contributing_artists".toPlutusData()
                                    mapItemValue = plutusData {
                                        list = plutusDataList {
                                            listItem.addAll(
                                                contributingArtists.map { collab ->
                                                    "${
                                                        UserEntity.getByEmail(collab.email!!)!!
                                                            .toModel(false).stageOrFullName
                                                    }, ${collab.role}".toPlutusData()
                                                }
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }

                    val mixEngineers =
                        collabs.filter { it.role.equals("Mixing Engineer", ignoreCase = true) && it.credited == true }
                    if (mixEngineers.isNotEmpty()) {
                        transaction {
                            add(
                                plutusDataMapItem {
                                    mapItemKey = "mix_engineer".toPlutusData()
                                    mapItemValue = if (mixEngineers.size > 1) {
                                        plutusData {
                                            list = plutusDataList {
                                                listItem.addAll(
                                                    mixEngineers.map { collab ->
                                                        UserEntity.getByEmail(collab.email!!)!!
                                                            .toModel(false).stageOrFullName
                                                            .toPlutusData()
                                                    }
                                                )
                                            }
                                        }
                                    } else {
                                        UserEntity.getByEmail(mixEngineers[0].email!!)!!
                                            .toModel(false).stageOrFullName.toPlutusData()
                                    }
                                }
                            )
                        }
                    }

                    val masteringEngineers = collabs.filter {
                        it.role.equals(
                            "Mastering Engineer",
                            ignoreCase = true
                        ) && it.credited == true
                    }
                    if (masteringEngineers.isNotEmpty()) {
                        transaction {
                            add(
                                plutusDataMapItem {
                                    mapItemKey = "mastering_engineer".toPlutusData()
                                    mapItemValue = if (masteringEngineers.size > 1) {
                                        plutusData {
                                            list = plutusDataList {
                                                listItem.addAll(
                                                    masteringEngineers.map { collab ->
                                                        UserEntity.getByEmail(collab.email!!)!!
                                                            .toModel(false).stageOrFullName.toPlutusData()
                                                    }
                                                )
                                            }
                                        }
                                    } else {
                                        UserEntity.getByEmail(masteringEngineers[0].email!!)!!
                                            .toModel(false).stageOrFullName.toPlutusData()
                                    }
                                }
                            )
                        }
                    }

                    val recordingEngineers = collabs.filter {
                        it.role.equals(
                            "Recording Engineer",
                            ignoreCase = true
                        ) && it.credited == true
                    }
                    if (recordingEngineers.isNotEmpty()) {
                        transaction {
                            add(
                                plutusDataMapItem {
                                    mapItemKey = "recording_engineer".toPlutusData()
                                    mapItemValue = if (recordingEngineers.size > 1) {
                                        plutusData {
                                            list = plutusDataList {
                                                listItem.addAll(
                                                    recordingEngineers.map { collab ->
                                                        UserEntity.getByEmail(collab.email!!)!!
                                                            .toModel(false).stageOrFullName.toPlutusData()
                                                    }
                                                )
                                            }
                                        }
                                    } else {
                                        UserEntity.getByEmail(recordingEngineers[0].email!!)!!
                                            .toModel(false).stageOrFullName.toPlutusData()
                                    }
                                }
                            )
                        }
                    }

                    val producers = collabs.filter {
                        it.role in producerRoles && it.credited == true
                    }
                    if (producers.isNotEmpty()) {
                        transaction {
                            add(
                                plutusDataMapItem {
                                    mapItemKey = "producer".toPlutusData()
                                    mapItemValue = if (producers.size > 1) {
                                        plutusData {
                                            list = plutusDataList {
                                                listItem.addAll(
                                                    producers.map { collab ->
                                                        UserEntity.getByEmail(collab.email!!)!!
                                                            .toModel(false).stageOrFullName
                                                            .toPlutusData()
                                                    }
                                                )
                                            }
                                        }
                                    } else {
                                        UserEntity.getByEmail(producers[0].email!!)!!
                                            .toModel(false).stageOrFullName.toPlutusData()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun createPlutusDataArtists(user: User, collabs: List<Collaboration>) = plutusDataMapItem {
        mapItemKey = "artists".toPlutusData()
        mapItemValue = plutusData {
            list = plutusDataList {
                with(listItem) {
                    add(
                        plutusData {
                            map = plutusDataMap {
                                with(mapItem) {
                                    add(
                                        plutusDataMapItem {
                                            mapItemKey = "name".toPlutusData()
                                            mapItemValue = user.stageOrFullName.toPlutusData()
                                        }
                                    )
                                }
                            }
                        }
                    )
                    transaction {
                        collabs.filter { it.role.equals("Artist", ignoreCase = true) && it.credited == true }
                            .forEach { collab ->
                                add(
                                    plutusData {
                                        map = plutusDataMap {
                                            with(mapItem) {
                                                add(
                                                    plutusDataMapItem {
                                                        mapItemKey = "name".toPlutusData()
                                                        mapItemValue = UserEntity.getByEmail(collab.email!!)!!
                                                            .toModel(false).stageOrFullName.toPlutusData()
                                                    }
                                                )
                                            }
                                        }
                                    }
                                )
                            }
                    }
                }
            }
        }
    }

    private fun createPlutusDataLinks(user: User, collabs: List<Collaboration>) = transaction {
        plutusDataMapItem {
            val collabUsers = listOf(user) + collabs.filter { it.credited == true }
                .mapNotNull { UserEntity.getByEmail(it.email!!)?.toModel(false) }

            val websites = collabUsers.mapNotNull { it.websiteUrl }.distinct()
            val instagrams = collabUsers.mapNotNull { it.instagramUrl }.distinct()
            val twitters = collabUsers.mapNotNull { it.twitterUrl }.distinct()

            mapItemKey = "links".toPlutusData()
            mapItemValue = plutusData {
                map = plutusDataMap {
                    with(mapItem) {
                        if (websites.isNotEmpty()) {
                            add(
                                plutusDataMapItem {
                                    mapItemKey = "website".toPlutusData()
                                    mapItemValue = if (websites.size > 1) {
                                        plutusData {
                                            list = plutusDataList {
                                                listItem.addAll(websites.map { it.toPlutusData() })
                                            }
                                        }
                                    } else {
                                        websites[0].toPlutusData()
                                    }
                                }
                            )
                        }
                        if (instagrams.isNotEmpty()) {
                            add(
                                plutusDataMapItem {
                                    mapItemKey = "instagram".toPlutusData()
                                    mapItemValue = if (instagrams.size > 1) {
                                        plutusData {
                                            list = plutusDataList {
                                                listItem.addAll(instagrams.map { it.toPlutusData() })
                                            }
                                        }
                                    } else {
                                        instagrams[0].toPlutusData()
                                    }
                                }
                            )
                        }
                        if (twitters.isNotEmpty()) {
                            add(
                                plutusDataMapItem {
                                    mapItemKey = "twitter".toPlutusData()
                                    mapItemValue = if (twitters.size > 1) {
                                        plutusData {
                                            list = plutusDataList {
                                                listItem.addAll(twitters.map { it.toPlutusData() })
                                            }
                                        }
                                    } else {
                                        twitters[0].toPlutusData()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        // FIXME: these should come from the eveara api and we should flag our db with which ones are contributing roles
        private val contributingArtistRoles = listOf(
            "Composer (Music)",
            "Contributor",
            "Guitar",
            "Drums",
            "Bass",
            "Keyboards",
            "Vocal",
            "Harmonica",
            "Saxophone",
            "Violin",
            "Orchestra",
            "Organ",
            "Choir",
            "Piano",
            "Horns",
            "Strings",
            "Synthesizer",
            "Percussion"
        )

        private val producerRoles = listOf(
            "Producer",
            "Executive Producer"
        )

        private const val PREFIX_REF_TOKEN = "000643b0" // (100)
        private const val PREFIX_FRAC_TOKEN = "001bc280" // (444)
    }
}
