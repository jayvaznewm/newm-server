package io.newm.server.features.minting

import com.amazonaws.services.sqs.model.Message
import io.newm.chain.grpc.monitorPaymentAddressRequest
import io.newm.server.aws.SqsMessageReceiver
import io.newm.server.config.repo.ConfigRepository
import io.newm.server.config.repo.ConfigRepository.Companion.CONFIG_KEY_EVEARA_STATUS_CHECK_MINUTES
import io.newm.server.config.repo.ConfigRepository.Companion.CONFIG_KEY_MINT_MONITOR_PAYMENT_ADDRESS_TIMEOUT_MIN
import io.newm.server.features.arweave.repo.ArweaveRepository
import io.newm.server.features.cardano.repo.CardanoRepository
import io.newm.server.features.daemon.QuartzSchedulerDaemon
import io.newm.server.features.minting.repo.MintingRepository
import io.newm.server.features.scheduler.EvearaReleaseStatusJob
import io.newm.server.features.song.model.MintingStatus
import io.newm.server.features.song.model.Song
import io.newm.server.features.song.repo.SongRepository
import io.newm.shared.koin.inject
import io.newm.shared.ktx.info
import kotlinx.serialization.json.Json
import org.koin.core.parameter.parametersOf
import org.quartz.JobBuilder.newJob
import org.quartz.JobKey
import org.quartz.SimpleScheduleBuilder.simpleSchedule
import org.quartz.TriggerBuilder.newTrigger
import org.slf4j.Logger
import kotlin.time.Duration.Companion.minutes

class MintingMessageReceiver : SqsMessageReceiver {
    private val log: Logger by inject { parametersOf(javaClass.simpleName) }
    private val songRepository: SongRepository by inject()
    private val cardanoRepository: CardanoRepository by inject()
    private val arweaveRepository: ArweaveRepository by inject()
    private val mintingRepository: MintingRepository by inject()
    private val configRepository: ConfigRepository by inject()
    private val quartzSchedulerDaemon: QuartzSchedulerDaemon by inject()
    private val json: Json by inject()

    override suspend fun onMessageReceived(message: Message) {
        val mintingStatusSqsMessage: MintingStatusSqsMessage = json.decodeFromString(message.body)
        log.info { "received: $mintingStatusSqsMessage" }

        when (mintingStatusSqsMessage.mintingStatus) {
            MintingStatus.Undistributed -> {
                throw IllegalStateException("No SQS message expected for MintingStatus: ${MintingStatus.Undistributed}!")
            }

            MintingStatus.StreamTokenAgreementApproved -> {
                throw IllegalStateException("No SQS message expected for MintingStatus: ${MintingStatus.StreamTokenAgreementApproved}!")
            }

            MintingStatus.MintingPaymentRequested -> {
                val song = songRepository.get(mintingStatusSqsMessage.songId)
                val paymentKey = cardanoRepository.getKey(song.paymentKeyId!!)
                val response = cardanoRepository.awaitPayment(
                    monitorPaymentAddressRequest {
                        address = paymentKey.address
                        lovelace = song.mintCostLovelace!!.toString()
                        timeoutMs =
                            configRepository.getLong(CONFIG_KEY_MINT_MONITOR_PAYMENT_ADDRESS_TIMEOUT_MIN).minutes.inWholeMilliseconds
                    }
                )
                if (response.success) {
                    // We got paid!!! Move -> MintingPaymentReceived
                    songRepository.updateSongMintingStatus(
                        songId = mintingStatusSqsMessage.songId,
                        mintingStatus = MintingStatus.MintingPaymentReceived
                    )
                }
            }

            MintingStatus.MintingPaymentReceived -> {
                // Payment received. Move -> AwaitingCollaboratorApproval
                songRepository.updateSongMintingStatus(
                    songId = mintingStatusSqsMessage.songId,
                    mintingStatus = MintingStatus.AwaitingCollaboratorApproval
                )
            }

            MintingStatus.AwaitingCollaboratorApproval -> {
                if (songRepository.getUnapprovedCollaboratorCount(mintingStatusSqsMessage.songId) == 0) {
                    // All collaborators have approved. Move -> ReadyToDistribute
                    songRepository.updateSongMintingStatus(
                        songId = mintingStatusSqsMessage.songId,
                        mintingStatus = MintingStatus.ReadyToDistribute
                    )
                } else {
                    // Do Nothing. Once the final collaborator approves, move -> ReadyToDistribute
                }
            }

            MintingStatus.ReadyToDistribute -> {
                songRepository.distribute(mintingStatusSqsMessage.songId)

                // Done submitting distributing. Move -> SubmittedForDistribution
                songRepository.updateSongMintingStatus(
                    songId = mintingStatusSqsMessage.songId,
                    mintingStatus = MintingStatus.SubmittedForDistribution
                )
            }

            MintingStatus.SubmittedForDistribution -> {
                // Schedule a job to check the release status every 24 hours
                val song = songRepository.get(mintingStatusSqsMessage.songId)

                val jobKey = JobKey("EvearaReleaseStatusJob-${song.id}", "EvearaReleaseStatusJobGroup")
                val jobDetail = newJob(EvearaReleaseStatusJob::class.java)
                    .withIdentity(jobKey)
                    .usingJobData("songId", song.id.toString())
                    .usingJobData("userId", song.ownerId.toString())
                    .requestRecovery(true)
                    .build()
                val trigger = newTrigger()
                    .forJob(jobDetail)
                    .withSchedule(
                        simpleSchedule()
                            .withIntervalInMinutes(configRepository.getInt(CONFIG_KEY_EVEARA_STATUS_CHECK_MINUTES))
                            .repeatForever()
                    )
                    .build()

                quartzSchedulerDaemon.scheduleJob(jobDetail, trigger)

                if (!cardanoRepository.isMainnet()) {
                    // If we are on testnet, pretend that the song is already successfully distributed
                    songRepository.update(song.id!!, Song(forceDistributed = true))
                }
            }

            MintingStatus.Distributed -> {
                // Upload 30-second clip, lyrics.txt, streamtokenagreement.pdf, coverArt to arweave and save those URLs
                // on the Song record
                val song = songRepository.get(mintingStatusSqsMessage.songId)
                arweaveRepository.uploadSongAssets(song)

                // Done with arweave. Move -> Pending for minting
                songRepository.updateSongMintingStatus(
                    songId = mintingStatusSqsMessage.songId,
                    mintingStatus = MintingStatus.Pending
                )
            }

            MintingStatus.Declined -> {
                // TODO: Notify the user and our support team via email that the distribution was declined.
                // We'll have to change the minting status manually to re-process things if necessary.
            }

            MintingStatus.Pending -> {
                // Create and submit the mint transaction
                val song = songRepository.get(mintingStatusSqsMessage.songId)
                mintingRepository.mint(song)

                // Done submitting mint transaction. Move -> Minted
                songRepository.updateSongMintingStatus(
                    songId = mintingStatusSqsMessage.songId,
                    mintingStatus = MintingStatus.Minted
                )
            }

            MintingStatus.Minted -> {
                // TODO: Notify the user that the process has been completed
            }
        }
    }
}
