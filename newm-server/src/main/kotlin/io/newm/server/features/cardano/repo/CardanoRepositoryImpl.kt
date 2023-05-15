package io.newm.server.features.cardano.repo

import com.amazonaws.handlers.AsyncHandler
import com.amazonaws.services.kms.AWSKMSAsync
import com.amazonaws.services.kms.model.DecryptRequest
import com.amazonaws.services.kms.model.DecryptResult
import com.amazonaws.services.kms.model.EncryptRequest
import com.amazonaws.services.kms.model.EncryptResult
import com.github.benmanes.caffeine.cache.Caffeine
import com.google.protobuf.ByteString
import io.ktor.network.util.DefaultByteBufferPool
import io.newm.chain.grpc.IsMainnetRequest
import io.newm.chain.grpc.MonitorPaymentAddressRequest
import io.newm.chain.grpc.NewmChainGrpcKt.NewmChainCoroutineStub
import io.newm.chain.grpc.SubmitTransactionResponse
import io.newm.chain.grpc.TransactionBuilderRequestKt
import io.newm.chain.grpc.TransactionBuilderResponse
import io.newm.chain.grpc.Utxo
import io.newm.chain.grpc.queryUtxosRequest
import io.newm.chain.grpc.submitTransactionRequest
import io.newm.chain.util.b64ToByteArray
import io.newm.chain.util.toB64String
import io.newm.chain.util.toHexString
import io.newm.kogmios.protocols.model.QueryCurrentProtocolBabbageParametersResult
import io.newm.server.config.repo.ConfigRepository
import io.newm.server.config.repo.ConfigRepository.Companion.CONFIG_KEY_ENCRYPTION_PASSWORD
import io.newm.server.config.repo.ConfigRepository.Companion.CONFIG_KEY_ENCRYPTION_SALT
import io.newm.server.features.cardano.database.KeyEntity
import io.newm.server.features.cardano.database.KeyTable
import io.newm.server.features.cardano.model.EncryptionRequest
import io.newm.server.features.cardano.model.Key
import io.newm.shared.koin.inject
import io.newm.shared.ktx.debug
import io.newm.shared.ktx.isValidHex
import io.newm.shared.ktx.isValidPassword
import org.jetbrains.exposed.sql.transactions.transaction
import org.koin.core.parameter.parametersOf
import org.slf4j.Logger
import org.springframework.security.crypto.encrypt.BytesEncryptor
import org.springframework.security.crypto.encrypt.Encryptors
import java.time.Duration
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

internal class CardanoRepositoryImpl(
    private val client: NewmChainCoroutineStub,
    private val kms: AWSKMSAsync,
    private val kmsKeyId: String,
    private val configRepository: ConfigRepository,
) : CardanoRepository {

    private val logger: Logger by inject { parametersOf(javaClass.simpleName) }

    private var _isMainnet: Boolean? = null

    private var _bytesEncryptor: BytesEncryptor? = null

    override suspend fun saveKey(key: Key, name: String?): UUID {
        logger.debug { "add: key = $key, name: $name" }
        val eSkey = encryptSkey(key.skey)
        return transaction {
            KeyEntity.new {
                address = key.address
                skey = eSkey
                vkey = key.vkey.toHexString()
                script = key.script
                scriptAddress = key.scriptAddress
                this.name = name
            }.id.value
        }
    }

    override suspend fun getKey(keyId: UUID): Key {
        logger.debug { "get: keyId = $keyId" }
        val keyEntity = transaction {
            KeyEntity[keyId]
        }
        return keyEntity.toModel(decryptSkey(keyEntity.skey))
    }

    override suspend fun getKeyByName(name: String): Key? {
        logger.debug { "getByName: name = $name" }
        return transaction {
            KeyEntity.find { KeyTable.name eq name }.firstOrNull()
        }?.let {
            it.toModel(decryptSkey(it.skey))
        }
    }

    override suspend fun isMainnet(): Boolean {
        if (_isMainnet == null) {
            // Check with newm-chain to see whether we're connected to a mainnet or a testnet
            _isMainnet = client.isMainnet(IsMainnetRequest.getDefaultInstance()).isMainnet
        }

        return _isMainnet!!
    }

    private val currentEpochCache = Caffeine
        .newBuilder()
        .expireAfterWrite(Duration.ofMinutes(1))
        .build<Int, Long>()
    private val protocolParametersCache = Caffeine
        .newBuilder()
        .expireAfterWrite(Duration.ofHours(1))
        .build<Long, QueryCurrentProtocolBabbageParametersResult>()

    override suspend fun queryLiveUtxos(address: String): List<Utxo> {
        val response = client.queryLiveUtxos(
            queryUtxosRequest {
                this.address = address
            }
        )
        return response.utxosList
    }

    override suspend fun buildTransaction(block: TransactionBuilderRequestKt.Dsl.() -> Unit): TransactionBuilderResponse {
        val request = io.newm.chain.grpc.transactionBuilderRequest {
            block.invoke(this)
        }
        return client.transactionBuilder(request)
    }

    override suspend fun submitTransaction(cborBytes: ByteString): SubmitTransactionResponse {
        val request = submitTransactionRequest {
            cbor = cborBytes
        }
        return client.submitTransaction(request)
    }

    override suspend fun awaitPayment(request: MonitorPaymentAddressRequest) = client.monitorPaymentAddress(request)

    override suspend fun saveEncryptionParams(encryptionRequest: EncryptionRequest) {
        require(encryptionRequest.s.isValidHex()) { "Salt value is not a hex string!" }
        require(encryptionRequest.s.length >= 16) { "Salt value is not long enough!" }
        require(encryptionRequest.password.length > 30) { "Password is not long enough!" }
        require(encryptionRequest.password.isValidPassword()) { "Password must have upper,lower,number characters!" }
        require(!configRepository.exists(CONFIG_KEY_ENCRYPTION_SALT)) { "Salt already exists in config table!" }
        require(!configRepository.exists(CONFIG_KEY_ENCRYPTION_PASSWORD)) { "Password already exists in config table!" }

        val cipherSalt = encryptKmsBytes(encryptionRequest.s.toByteArray())
        val cipherPassword = encryptKmsBytes(encryptionRequest.password.toByteArray())

        configRepository.putString(CONFIG_KEY_ENCRYPTION_SALT, cipherSalt)
        configRepository.putString(CONFIG_KEY_ENCRYPTION_PASSWORD, cipherPassword)
    }

    private suspend fun encryptKmsBytes(bytes: ByteArray): String {
        val plaintextBuffer = DefaultByteBufferPool.borrow()
        try {
            plaintextBuffer.put(bytes)
            plaintextBuffer.flip()
            return suspendCoroutine { continuation ->
                kms.encryptAsync(
                    EncryptRequest().withKeyId(kmsKeyId).withPlaintext(plaintextBuffer),
                    object : AsyncHandler<EncryptRequest, EncryptResult> {
                        override fun onError(exception: Exception) {
                            continuation.resumeWithException(exception)
                        }

                        override fun onSuccess(request: EncryptRequest, result: EncryptResult) {
                            val ciphertextBuffer = result.ciphertextBlob.asReadOnlyBuffer()
                            val ciphertextBytes = ByteArray(ciphertextBuffer.remaining())
                            ciphertextBuffer.get(ciphertextBytes)
                            continuation.resume(ciphertextBytes.toB64String())
                        }
                    }
                )
            }
        } finally {
            DefaultByteBufferPool.recycle(plaintextBuffer)
        }
    }

    val kmsCache = Caffeine.newBuilder().expireAfterWrite(Duration.ofDays(1)).build<String, ByteArray>()

    private suspend fun decryptKmsString(cipherText: String): ByteArray {
        val cachedPlaintext = kmsCache.getIfPresent(cipherText)
        if (cachedPlaintext != null) {
            return cachedPlaintext
        }

        val ciphertextBuffer = DefaultByteBufferPool.borrow()
        try {
            ciphertextBuffer.put(cipherText.b64ToByteArray())
            ciphertextBuffer.flip()

            return suspendCoroutine { continuation ->
                kms.decryptAsync(
                    DecryptRequest().withKeyId(kmsKeyId).withCiphertextBlob(ciphertextBuffer),
                    object : AsyncHandler<DecryptRequest, DecryptResult> {
                        override fun onError(exception: Exception) {
                            kmsCache.invalidate(cipherText)
                            continuation.resumeWithException(exception)
                        }

                        override fun onSuccess(request: DecryptRequest, result: DecryptResult) {
                            val plaintextBuffer = result.plaintext.asReadOnlyBuffer()
                            val plaintext = ByteArray(plaintextBuffer.remaining())
                            plaintextBuffer.get(plaintext)
                            kmsCache.put(cipherText, plaintext)
                            continuation.resume(plaintext)
                        }
                    }
                )
            }
        } finally {
            DefaultByteBufferPool.recycle(ciphertextBuffer)
        }
    }

    private suspend fun encryptSkey(bytes: ByteArray): String {
        return getEncryptor().encrypt(bytes).toB64String()
    }

    private suspend fun decryptSkey(ciphertext: String): ByteArray {
        return getEncryptor().decrypt(ciphertext.b64ToByteArray())
    }

    private suspend fun getEncryptor(): BytesEncryptor {
        return _bytesEncryptor ?: run {
            require(configRepository.exists(CONFIG_KEY_ENCRYPTION_SALT)) { "$CONFIG_KEY_ENCRYPTION_SALT Not found in db!" }
            require(configRepository.exists(CONFIG_KEY_ENCRYPTION_PASSWORD)) { "$CONFIG_KEY_ENCRYPTION_PASSWORD Not found in db!" }
            val cipherTextSalt = configRepository.getString(CONFIG_KEY_ENCRYPTION_SALT)
            val cipherTextPassword = configRepository.getString(CONFIG_KEY_ENCRYPTION_PASSWORD)
            val salt = String(decryptKmsString(cipherTextSalt), Charsets.UTF_8)
            val password = String(decryptKmsString(cipherTextPassword), Charsets.UTF_8)
            _bytesEncryptor = Encryptors.stronger(password, salt)
            _bytesEncryptor!!
        }
    }
}