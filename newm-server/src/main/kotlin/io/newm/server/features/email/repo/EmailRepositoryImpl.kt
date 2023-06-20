package io.newm.server.features.email.repo

import io.ktor.server.application.ApplicationEnvironment
import io.newm.server.ktx.getSecureString
import io.newm.shared.koin.inject
import io.newm.shared.ktx.debug
import io.newm.shared.ktx.format
import io.newm.shared.ktx.getBoolean
import io.newm.shared.ktx.getConfigChild
import io.newm.shared.ktx.getInt
import io.newm.shared.ktx.toUrl
import io.newm.shared.ktx.warn
import org.apache.commons.mail.DefaultAuthenticator
import org.apache.commons.mail.HtmlEmail
import org.koin.core.parameter.parametersOf
import org.slf4j.Logger

internal class EmailRepositoryImpl(
    private val environment: ApplicationEnvironment
) : EmailRepository {

    private val logger: Logger by inject { parametersOf(javaClass.simpleName) }

    override suspend fun send(
        to: String,
        subject: String,
        messageUrl: String,
        messageArgs: Map<String, Any>
    ) = send(listOf(to), emptyList(), subject, messageUrl, messageArgs)

    override suspend fun send(
        to: List<String>,
        bcc: List<String>,
        subject: String,
        messageUrl: String,
        messageArgs: Map<String, Any>
    ) {
        logger.debug { "send to: $to, bcc: $bcc, subject: $subject" }

        val message = messageUrl
            .toUrl()
            .readText()
            .format(messageArgs)

        with(environment.getConfigChild("email")) {
            if (getBoolean("enabled")) {
                HtmlEmail().apply {
                    hostName = getSecureString("smtpHost")
                    setSmtpPort(getInt("smtpPort"))
                    isSSLOnConnect = getBoolean("sslOnConnect")
                    setAuthenticator(
                        DefaultAuthenticator(
                            getSecureString("userName"),
                            getSecureString("password")
                        )
                    )
                    setFrom(getSecureString("from"))
                    to.forEach { addTo(it) }
                    bcc.forEach { addBcc(it) }
                    this.subject = subject
                    setHtmlMsg(message)
                }.send()
            } else {
                logger.warn { "Email notifications disabled - skipped sending message" }
            }
        }
    }
}