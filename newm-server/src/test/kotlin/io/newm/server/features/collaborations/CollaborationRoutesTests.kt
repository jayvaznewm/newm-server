package io.newm.server.features.collaborations

import com.google.common.truth.Truth.assertThat
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.newm.server.BaseApplicationTests
import io.newm.server.features.collaboration.database.CollaborationEntity
import io.newm.server.features.collaboration.database.CollaborationTable
import io.newm.server.features.collaboration.model.Collaboration
import io.newm.server.features.collaboration.model.CollaborationIdBody
import io.newm.server.features.collaboration.model.Collaborator
import io.newm.server.features.model.CountResponse
import io.newm.server.features.song.database.SongEntity
import io.newm.server.features.song.database.SongTable
import io.newm.server.features.user.database.UserEntity
import io.newm.server.features.user.database.UserTable
import io.newm.shared.ktx.existsHavingId
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.UUID

class CollaborationRoutesTests : BaseApplicationTests() {

    @BeforeEach
    fun beforeEach() {
        transaction {
            CollaborationTable.deleteAll()
            SongTable.deleteAll()
        }
    }

    @Test
    fun testPostCollaboration() = runBlocking {
        val startTime = LocalDateTime.now()

        // Add Song directly into database
        val songId = transaction {
            SongEntity.new {
                ownerId = EntityID(testUserId, UserTable)
                title = "Song"
                genres = arrayOf("Genre")
            }.id.value
        }

        val expectedCollaboration = Collaboration(
            songId = songId,
            email = "collaborator@email.com",
            role = "Role",
            royaltyRate = 0.5f
        )

        // Post
        val response = client.post("v1/collaborations") {
            bearerAuth(testUserToken)
            contentType(ContentType.Application.Json)
            setBody(expectedCollaboration)
        }
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        val collaborationId = response.body<CollaborationIdBody>().collaborationId

        // Read Collaboration directly from database & verify it
        val actualCollaboration = transaction { CollaborationEntity[collaborationId] }.toModel()
        assertThat(actualCollaboration.createdAt).isAtLeast(startTime)
        assertThat(actualCollaboration.songId).isEqualTo(expectedCollaboration.songId)
        assertThat(actualCollaboration.email).isEqualTo(expectedCollaboration.email)
        assertThat(actualCollaboration.role).isEqualTo(expectedCollaboration.role)
        assertThat(actualCollaboration.royaltyRate).isEqualTo(expectedCollaboration.royaltyRate)
        assertThat(actualCollaboration.accepted).isEqualTo(false)
    }

    @Test
    fun testPatchCollaboration() = runBlocking {
        // Add Collaboration directly into database
        val collaboration1 = addCollaborationToDatabase(testUserId, 1)

        val collaboration2 = Collaboration(
            email = "collaborator2@email.com",
            role = "Role2",
            royaltyRate = 0.2f
        )

        // Patch collaboration1 with collaboration2
        val response = client.patch("v1/collaborations/${collaboration1.id}") {
            bearerAuth(testUserToken)
            contentType(ContentType.Application.Json)
            setBody(collaboration2)
        }
        assertThat(response.status).isEqualTo(HttpStatusCode.NoContent)

        // Read Collaboration directly from database & verify it
        val collaboration = transaction { CollaborationEntity[collaboration1.id!!] }.toModel()
        assertThat(collaboration.id).isEqualTo(collaboration1.id)
        assertThat(collaboration.createdAt).isEqualTo(collaboration1.createdAt)
        assertThat(collaboration.songId).isEqualTo(collaboration1.songId)
        assertThat(collaboration.email).isEqualTo(collaboration2.email)
        assertThat(collaboration.role).isEqualTo(collaboration2.role)
        assertThat(collaboration.royaltyRate).isEqualTo(collaboration2.royaltyRate)
        assertThat(collaboration.accepted).isEqualTo(collaboration1.accepted)
    }

    @Test
    fun testDeleteCollaboration() = runBlocking {
        // Add Collaboration directly into database
        val collaborationId = addCollaborationToDatabase(testUserId, 1).id!!

        // delete it
        val response = client.delete("v1/collaborations/$collaborationId") {
            bearerAuth(testUserToken)
        }
        assertThat(response.status).isEqualTo(HttpStatusCode.NoContent)

        // make sure doesn't exist in database
        val exists = transaction { CollaborationEntity.existsHavingId(collaborationId) }
        assertThat(exists).isFalse()
    }

    @Test
    fun testGetCollaboration() = runBlocking {
        // Add Collaboration directly into database
        val expectedCollaboration = addCollaborationToDatabase(testUserId, 1)

        // Get it
        val response = client.get("v1/collaborations/${expectedCollaboration.id}") {
            bearerAuth(testUserToken)
            accept(ContentType.Application.Json)
        }

        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        val actualCollaboration = response.body<Collaboration>()
        assertThat(actualCollaboration).isEqualTo(expectedCollaboration)
    }

    @Test
    fun testGetAllCollaborations() = runBlocking {
        // Add Collaborations directly into database
        val expectedCollaborations = mutableListOf<Collaboration>()
        for (offset in 0..30) {
            expectedCollaborations += addCollaborationToDatabase(testUserId, offset)
        }

        // Get all collaborations forcing pagination
        var offset = 0
        val limit = 5
        val actualCollaborations = mutableListOf<Collaboration>()
        while (true) {
            val response = client.get("v1/collaborations") {
                bearerAuth(testUserToken)
                accept(ContentType.Application.Json)
                parameter("offset", offset)
                parameter("limit", limit)
            }
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val collaborations = response.body<List<Collaboration>>()
            if (collaborations.isEmpty()) break
            actualCollaborations += collaborations
            offset += limit
        }

        // verify all
        assertThat(actualCollaborations).isEqualTo(expectedCollaborations)
    }

    @Test
    fun testCollaborationCount() = runBlocking {
        var count = 0L
        while (true) {
            val response = client.get("v1/collaborations/count") {
                bearerAuth(testUserToken)
            }
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val actualCount = response.body<CountResponse>().count
            assertThat(actualCount).isEqualTo(count)

            if (++count == 10L) break

            // Add collaborations directly into database
            addCollaborationToDatabase(testUserId, count.toInt())
        }
    }

    @Test
    fun testGetCollaborationsByIds() = runBlocking {
        // Add collaborations directly into database
        val allCollaborations = mutableListOf<Collaboration>()
        for (offset in 0..30) {
            allCollaborations += addCollaborationToDatabase(testUserId, offset)
        }

        // filter out 1st and last
        val expectedCollaborations = allCollaborations.subList(1, allCollaborations.size - 1)
        val ids = expectedCollaborations.map { it.id }.joinToString()

        // Get all collaborations forcing pagination
        var offset = 0
        val limit = 5
        val actualCollaborations = mutableListOf<Collaboration>()
        while (true) {
            val response = client.get("v1/collaborations") {
                bearerAuth(testUserToken)
                accept(ContentType.Application.Json)
                parameter("offset", offset)
                parameter("limit", limit)
                parameter("ids", ids)
            }
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val collaborations = response.body<List<Collaboration>>()
            if (collaborations.isEmpty()) break
            actualCollaborations += collaborations
            offset += limit
        }

        // verify all
        assertThat(actualCollaborations).isEqualTo(expectedCollaborations)
    }

    @Test
    fun testGetCollaborationsBySongIds() = runBlocking {
        // Add collaborations directly into database
        val allCollaborations = mutableListOf<Collaboration>()
        for (offset in 0..30) {
            allCollaborations += addCollaborationToDatabase(testUserId, offset)
        }

        // filter out 1st and last
        val expectedCollaborations = allCollaborations.subList(1, allCollaborations.size - 1)
        val songIds = expectedCollaborations.map { it.songId }.joinToString()

        // Get all collaborations forcing pagination
        var offset = 0
        val limit = 5
        val actualCollaborations = mutableListOf<Collaboration>()
        while (true) {
            val response = client.get("v1/collaborations") {
                bearerAuth(testUserToken)
                accept(ContentType.Application.Json)
                parameter("offset", offset)
                parameter("limit", limit)
                parameter("songIds", songIds)
            }
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val collaborations = response.body<List<Collaboration>>()
            if (collaborations.isEmpty()) break
            actualCollaborations += collaborations
            offset += limit
        }

        // verify all
        assertThat(actualCollaborations).isEqualTo(expectedCollaborations)
    }

    @Test
    fun testGetCollaborationsByEmails() = runBlocking {
        // Add collaborations directly into database
        val allCollaborations = mutableListOf<Collaboration>()
        for (offset in 0..30) {
            allCollaborations += addCollaborationToDatabase(testUserId, offset)
        }

        // filter out 1st and last
        val expectedCollaborations = allCollaborations.subList(1, allCollaborations.size - 1)
        val emails = expectedCollaborations.map { it.email }.joinToString()

        // Get all collaborations forcing pagination
        var offset = 0
        val limit = 5
        val actualCollaborations = mutableListOf<Collaboration>()
        while (true) {
            val response = client.get("v1/collaborations") {
                bearerAuth(testUserToken)
                accept(ContentType.Application.Json)
                parameter("offset", offset)
                parameter("limit", limit)
                parameter("emails", emails)
            }
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val collaborations = response.body<List<Collaboration>>()
            if (collaborations.isEmpty()) break
            actualCollaborations += collaborations
            offset += limit
        }

        // verify all
        assertThat(actualCollaborations).isEqualTo(expectedCollaborations)
    }

    @Test
    fun testGetCollaborationsByOlderThan() = runBlocking {
        // Add collaborations directly into database
        val allCollaborations = mutableListOf<Collaboration>()
        for (offset in 0..30) {
            allCollaborations += addCollaborationToDatabase(testUserId, offset)
        }

        // filter out newest one
        val expectedCollaborations = allCollaborations.subList(0, allCollaborations.size - 1)
        val olderThan = allCollaborations.last().createdAt

        // Get all collaborations forcing pagination
        var offset = 0
        val limit = 5
        val actualCollaborations = mutableListOf<Collaboration>()
        while (true) {
            val response = client.get("v1/collaborations") {
                bearerAuth(testUserToken)
                accept(ContentType.Application.Json)
                parameter("offset", offset)
                parameter("limit", limit)
                parameter("olderThan", olderThan)
            }
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val collaborations = response.body<List<Collaboration>>()
            if (collaborations.isEmpty()) break
            actualCollaborations += collaborations
            offset += limit
        }

        // verify all
        assertThat(actualCollaborations).isEqualTo(expectedCollaborations)
    }

    @Test
    fun testGetCollaborationsByNewerThan() = runBlocking {
        // Add collaborations directly into database
        val allCollaborations = mutableListOf<Collaboration>()
        for (offset in 0..30) {
            allCollaborations += addCollaborationToDatabase(testUserId, offset)
        }

        // filter out oldest one
        val expectedCollaborations = allCollaborations.subList(1, allCollaborations.size)
        val newerThan = allCollaborations.first().createdAt

        // Get all collaborations forcing pagination
        var offset = 0
        val limit = 5
        val actualCollaborations = mutableListOf<Collaboration>()
        while (true) {
            val response = client.get("v1/collaborations") {
                bearerAuth(testUserToken)
                accept(ContentType.Application.Json)
                parameter("offset", offset)
                parameter("limit", limit)
                parameter("newerThan", newerThan)
            }
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val collaborations = response.body<List<Collaboration>>()
            if (collaborations.isEmpty()) break
            actualCollaborations += collaborations
            offset += limit
        }

        // verify all
        assertThat(actualCollaborations).isEqualTo(expectedCollaborations)
    }

    @Test
    fun testGetAllCollaborators() = runBlocking {
        // Add Collaborations directly into database
        val expectedCollaborators = mutableListOf<Collaborator>()
        for (offset in 0..30) {
            val email = "collaborator$offset@email.com"
            val user = if (offset % 2 == 0) {
                transaction {
                    UserEntity.new {
                        this.email = email
                    }
                }.toModel(false)
            } else {
                null
            }
            val songCount = (offset % 4) + 1
            for (i in 1..songCount) {
                addCollaborationToDatabase(testUserId, i, email)
            }
            expectedCollaborators += Collaborator(email, songCount.toLong(), user)
        }

        // Get all collaborations forcing pagination
        var offset = 0
        val limit = 5
        val actualCollaborators = mutableListOf<Collaborator>()
        while (true) {
            val response = client.get("v1/collaborations/collaborators") {
                bearerAuth(testUserToken)
                accept(ContentType.Application.Json)
                parameter("offset", offset)
                parameter("limit", limit)
            }
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val collaborators = response.body<List<Collaborator>>()
            if (collaborators.isEmpty()) break
            actualCollaborators += collaborators
            offset += limit
        }

        // verify all
        val actualSorted = actualCollaborators.sortedBy { it.email }
        val expectedSorted = expectedCollaborators.sortedBy { it.email }
        assertThat(actualSorted).isEqualTo(expectedSorted)
    }

    @Test
    fun testCollaboratorCount() = runBlocking {
        var count = 0L
        while (true) {
            val response = client.get("v1/collaborations/collaborators/count") {
                bearerAuth(testUserToken)
            }
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val actualCount = response.body<CountResponse>().count
            assertThat(actualCount).isEqualTo(count)

            if (++count == 10L) break

            // Add collaborations directly into database
            addCollaborationToDatabase(testUserId, count.toInt())
        }
    }
}

private fun addCollaborationToDatabase(userId: UUID, offset: Int, email: String? = null): Collaboration {
    val songId = transaction {
        SongEntity.new {
            ownerId = EntityID(userId, UserTable)
            title = "Song$offset"
            genres = arrayOf("Genre$offset")
        }.id
    }
    return transaction {
        CollaborationEntity.new {
            this.songId = songId
            this.email = email ?: "collaborator$offset@email.com"
            role = "Role$offset"
            royaltyRate = 1f / (offset + 2)
            accepted = (offset % 2) == 0
        }
    }.toModel()
}
