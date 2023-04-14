package io.newm.server

import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.testing.TestApplication
import io.mockk.mockk
import io.newm.server.auth.jwt.database.JwtTable
import io.newm.server.auth.twofactor.database.TwoFactorAuthTable
import io.newm.server.config.database.ConfigTable
import io.newm.server.features.cardano.database.KeyTable
import io.newm.server.features.cardano.repo.CardanoRepository
import io.newm.server.features.collaboration.database.CollaborationTable
import io.newm.server.features.playlist.database.PlaylistTable
import io.newm.server.features.playlist.database.SongsInPlaylistsTable
import io.newm.server.features.song.database.SongTable
import io.newm.server.features.user.database.UserEntity
import io.newm.server.features.user.database.UserTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.koin.core.context.GlobalContext.loadKoinModules
import org.koin.dsl.module
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
open class BaseApplicationTests {

    protected val application = TestApplication {
        environment {
            config = ApplicationConfig("test-application.conf")
        }
    }

    protected val client: HttpClient by lazy {
        application.createClient {
            install(ContentNegotiation) {
                json()
            }
        }.also { client ->
            loadKoinModules(
                module {
                    single { client }
                }
            )
        }
    }

    protected val testUserId: UUID by lazy {
        transaction {
            UserEntity.new {
                email = "tester@projectnewm.io"
            }
        }.id.value
    }

    protected val testUserToken: String
        get() = testUserId.toString()

    @BeforeAll
    fun beforeAll() {
        Database.connect(
            HikariDataSource().apply {
                driverClassName = container.driverClassName
                jdbcUrl = container.jdbcUrl
                username = container.username
                password = container.password
            }
        )
        transaction {
            SchemaUtils.create(
                ConfigTable,
                UserTable,
                TwoFactorAuthTable,
                JwtTable,
                KeyTable,
                SongTable,
                CollaborationTable,
                PlaylistTable,
                SongsInPlaylistsTable,
            )
        }
        application.start()
        loadKoinModules(
            module {
                single { mockk<CardanoRepository>(relaxed = true) }
            }
        )
    }

    @AfterAll
    fun afterAll() {
        application.stop()
    }

    companion object {
        @Container
        val container = PostgreSQLContainer<Nothing>("postgres:12").apply {
            withDatabaseName("newm-db")
            withUsername("tester")
            withPassword("newm1234")
        }
    }
}
