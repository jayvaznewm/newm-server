package io.newm.server.features.song.repo

import io.ktor.utils.io.ByteReadChannel
import io.newm.chain.grpc.Utxo
import io.newm.server.features.song.database.SongEntity
import io.newm.server.features.song.model.AudioStreamData
import io.newm.server.features.song.model.AudioUploadReport
import io.newm.server.features.song.model.MintPaymentResponse
import io.newm.server.features.song.model.MintingStatus
import io.newm.server.features.song.model.Song
import io.newm.server.features.song.model.SongFilters
import java.util.UUID

interface SongRepository {
    suspend fun add(song: Song, ownerId: UUID): UUID
    suspend fun update(songId: UUID, song: Song, requesterId: UUID? = null)
    suspend fun delete(songId: UUID, requesterId: UUID)
    suspend fun get(songId: UUID): Song
    suspend fun getAll(filters: SongFilters, offset: Int, limit: Int): List<Song>
    suspend fun getAllCount(filters: SongFilters): Long
    suspend fun getGenres(filters: SongFilters, offset: Int, limit: Int): List<String>
    suspend fun getGenreCount(filters: SongFilters): Long
    suspend fun uploadAudio(songId: UUID, requesterId: UUID, data: ByteReadChannel): AudioUploadReport
    suspend fun generateAudioStreamData(songId: UUID): AudioStreamData
    suspend fun processStreamTokenAgreement(songId: UUID, requesterId: UUID, accepted: Boolean)

    suspend fun processAudioEncoding(songId: UUID)

    suspend fun getMintingPaymentAmount(songId: UUID, requesterId: UUID): MintPaymentResponse

    suspend fun getMintingPaymentEstimate(collaborators: Int): MintPaymentResponse

    suspend fun generateMintingPaymentTransaction(
        songId: UUID,
        requesterId: UUID,
        sourceUtxos: List<Utxo>,
        changeAddress: String
    ): String

    suspend fun processCollaborations(songId: UUID)

    suspend fun updateSongMintingStatus(songId: UUID, mintingStatus: MintingStatus, errorMessage: String = "")

    suspend fun distribute(songId: UUID)

    fun set(songId: UUID, editor: (SongEntity) -> Unit)

    fun saveOrUpdateReceipt(songId: UUID, mintPaymentResponse: MintPaymentResponse)
}
