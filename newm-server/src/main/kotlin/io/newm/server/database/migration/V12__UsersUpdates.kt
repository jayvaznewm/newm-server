package io.newm.server.database.migration

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("unused")
class V12__UsersUpdates : BaseJavaMigration() {
    override fun migrate(context: Context?) {
        transaction {
            exec(
                """
                    ALTER TABLE users
                        ADD COLUMN IF NOT EXISTS instagram_url text,
                        ADD COLUMN IF NOT EXISTS company_name text,
                        ADD COLUMN IF NOT EXISTS company_logo_url text,
                        ADD COLUMN IF NOT EXISTS company_ip_rights boolean
                """.trimIndent()
            )
        }
    }
}
