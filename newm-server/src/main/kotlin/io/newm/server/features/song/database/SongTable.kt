package io.newm.server.features.song.database

import io.newm.server.features.cardano.database.KeyTable
import io.newm.server.features.song.model.AudioEncodingStatus
import io.newm.server.features.song.model.MarketplaceStatus
import io.newm.server.features.song.model.MintingStatus
import io.newm.server.features.song.model.SongBarcodeType
import io.newm.server.features.user.database.UserTable
import io.newm.shared.exposed.textArray
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.datetime
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

object SongTable : UUIDTable(name = "songs") {
    val archived: Column<Boolean> = bool("archived").default(false)
    val createdAt: Column<LocalDateTime> = datetime("created_at").defaultExpression(CurrentDateTime)
    val ownerId: Column<EntityID<UUID>> = reference("owner_id", UserTable, onDelete = ReferenceOption.CASCADE)
    val title: Column<String> = text("title")
    val genres: Column<Array<String>> = textArray("genres")
    val moods: Column<Array<String>?> = textArray("moods").nullable()
    val coverArtUrl: Column<String?> = text("cover_art_url").nullable()
    val description: Column<String?> = text("description").nullable()
    val album: Column<String?> = text("album").nullable()
    val track: Column<Int?> = integer("track").nullable()
    val language: Column<String?> = text("language").nullable()
    val coverRemixSample: Column<Boolean> = bool("cover_remix_sample").default(false)
    val compositionCopyrightOwner: Column<String?> = text("comp_copyright_owner").nullable()
    val compositionCopyrightYear: Column<Int?> = integer("comp_copyright_year").nullable()
    val phonographicCopyrightOwner: Column<String?> = text("phono_copyright_owner").nullable()
    val phonographicCopyrightYear: Column<Int?> = integer("phono_copyright_year").nullable()
    val parentalAdvisory: Column<String?> = text("parental_advisory").nullable()
    val barcodeType: Column<SongBarcodeType?> = enumeration("barcode_type", SongBarcodeType::class).nullable()
    val barcodeNumber: Column<String?> = text("barcode_number").nullable()
    val isrc: Column<String?> = text("isrc").nullable()
    val iswc: Column<String?> = text("iswc").nullable()
    val ipis: Column<Array<String>?> = textArray("ipis").nullable()
    val releaseDate: Column<LocalDate?> = date("release_date").nullable()
    val publicationDate: Column<LocalDate?> = date("publication_date").nullable()
    val lyricsUrl: Column<String?> = text("lyrics_url").nullable()
    val tokenAgreementUrl: Column<String?> = text("token_agreement_url").nullable()
    val originalAudioUrl: Column<String?> = text("original_audio_url").nullable()
    val clipUrl: Column<String?> = text("clip_url").nullable()
    val streamUrl: Column<String?> = text("stream_url").nullable()
    val duration: Column<Int?> = integer("duration").nullable()
    val nftPolicyId: Column<String?> = text("nft_policy_id").nullable()
    val nftName: Column<String?> = text("nft_name").nullable()
    val audioEncodingStatus: Column<AudioEncodingStatus> =
        enumeration("audio_encoding_status", AudioEncodingStatus::class).default(AudioEncodingStatus.NotStarted)
    val mintingStatus: Column<MintingStatus> =
        enumeration("minting_status", MintingStatus::class).default(MintingStatus.Undistributed)
    val mintingTxId: Column<String?> = text("minting_tx_id").nullable()
    val marketplaceStatus: Column<MarketplaceStatus> =
        enumeration("marketplace_status", MarketplaceStatus::class).default(MarketplaceStatus.NotSelling)
    val paymentKeyId: Column<EntityID<UUID>?> =
        reference("payment_key_id", KeyTable, onDelete = ReferenceOption.NO_ACTION).nullable()
    val arweaveCoverArtUrl: Column<String?> = text("arweave_cover_art_url").nullable()
    val arweaveLyricsUrl: Column<String?> = text("arweave_lyrics_url").nullable()
    val arweaveTokenAgreementUrl: Column<String?> = text("arweave_token_agreement_url").nullable()
    val arweaveClipUrl: Column<String?> = text("arweave_clip_url").nullable()
    val distributionTrackId: Column<Long?> = long("distribution_track_id").nullable()
    val distributionReleaseId: Column<Long?> = long("distribution_release_id").nullable()
    val mintCostLovelace: Column<Long?> = long("mint_cost_lovelace").nullable()
    val forceDistributed: Column<Boolean?> = bool("force_distributed").nullable()
    val errorMessage: Column<String?> = text("error_message").nullable()
}
