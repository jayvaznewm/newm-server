package io.newm.server.features.song

import io.newm.server.features.song.model.Song
import io.newm.server.features.song.model.SongBarcodeType
import java.time.LocalDate

val testSong1 = Song(
    title = "Test Song 1",
    genres = listOf("Genre 1.1", "Genre 1.2"),
    moods = listOf("Mood 1.1", "Mood 1.2"),
    coverArtUrl = "https://projectnewm.io/song1.png",
    description = "Song 1 description",
    album = "Song 1 album",
    track = 1,
    language = "Song 1 language",
    copyright = "Song 1 copyright",
    parentalAdvisory = "Song 1 parentalAdvisory",
    barcodeType = SongBarcodeType.Upc,
    barcodeNumber = "Barcode 1",
    isrc = "Song 1 isrc",
    ipi = listOf("Song 1 ipi 0", "Song 1 ipi 1"),
    releaseDate = LocalDate.of(2023, 1, 1),
    lyricsUrl = "https://projectnewm.io/lirycs1.txt",
)

val testSong2 = Song(
    title = "Test Song 2",
    genres = listOf("Genre 2.1", "Genre 2.2"),
    moods = listOf("Mood 2.1", "Mood 2.2"),
    coverArtUrl = "https://projectnewm.io/song2.png",
    description = "Song 2 description",
    album = "Song 2 album",
    track = 2,
    language = "Song 2 language",
    copyright = "Song 2 copyright",
    parentalAdvisory = "Song 2 parentalAdvisory",
    barcodeType = SongBarcodeType.Ean,
    barcodeNumber = "Barcode 2",
    isrc = "Song 2 isrc",
    ipi = listOf("Song 2 ipi 0", "Song 2 ipi 1"),
    releaseDate = LocalDate.of(2023, 2, 2),
    lyricsUrl = "https://projectnewm.io/lirycs2.txt",
)
