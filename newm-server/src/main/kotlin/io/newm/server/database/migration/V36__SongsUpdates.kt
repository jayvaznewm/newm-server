package io.newm.server.database.migration

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("unused")
class V36__SongsUpdates : BaseJavaMigration() {
    override fun migrate(context: Context?) {
        transaction {
            exec("ALTER TABLE songs ADD COLUMN IF NOT EXISTS archived BOOLEAN NOT NULL DEFAULT false")
        }
    }
}
