package tech.relaycorp.courier.domain

import java.io.InputStream
import java.util.Date
import javax.inject.Inject
import tech.relaycorp.courier.common.Logging.logger
import tech.relaycorp.courier.data.database.StoredMessageDao
import tech.relaycorp.courier.data.disk.DiskRepository
import tech.relaycorp.courier.data.model.GatewayType
import tech.relaycorp.courier.data.model.MessageId
import tech.relaycorp.courier.data.model.MessageType
import tech.relaycorp.courier.data.model.StorageSize
import tech.relaycorp.courier.data.model.StoredMessage
import tech.relaycorp.relaynet.cogrpc.readBytesAndClose
import tech.relaycorp.relaynet.messages.Cargo
import tech.relaycorp.relaynet.messages.CargoCollectionAuthorization
import tech.relaycorp.relaynet.ramf.RAMFException
import tech.relaycorp.relaynet.ramf.RAMFMessage

class StoreMessage
@Inject constructor(
    private val storedMessageDao: StoredMessageDao,
    private val diskRepository: DiskRepository,
    private val getStorageUsage: GetStorageUsage
) {

    suspend fun storeCargo(cargoInputStream: InputStream, recipientType: GatewayType): Result {
        val cargoBytes = cargoInputStream.readBytesAndClose()
        val cargo = try {
            Cargo.deserialize(cargoBytes)
        } catch (exc: RAMFException) {
            logger.warning("Malformed Cargo received: ${exc.message}")
            return Result.Error.Malformed
        }

        try {
            cargo.validate(null)
        } catch (exc: RAMFException) {
            logger.warning("Invalid cargo received: ${exc.message}")
            return Result.Error.Invalid
        }

        return storeMessage(MessageType.Cargo, cargo, cargoBytes, recipientType)
    }

    suspend fun storeCCA(ccaSerialized: ByteArray): Result {
        val cca = try {
            CargoCollectionAuthorization.deserialize(ccaSerialized)
        } catch (exc: RAMFException) {
            logger.warning("Malformed CCA received: ${exc.message}")
            return Result.Error.Malformed
        }

        try {
            cca.validate()
        } catch (exc: RAMFException) {
            logger.warning("Invalid CCA received: ${exc.message}")
            return Result.Error.Invalid
        }

        return storeMessage(MessageType.CCA, cca, ccaSerialized, GatewayType.Internet)
    }

    private suspend fun storeMessage(
        type: MessageType,
        message: RAMFMessage<*>,
        data: ByteArray,
        recipientType: GatewayType,
    ): Result {
        val dataSize = StorageSize(data.size.toLong())
        if (!checkForAvailableSpace(dataSize)) return Result.Error.NoSpaceAvailable

        val recipientAddress = if (recipientType == GatewayType.Internet)
            message.recipient.internetAddress ?: return Result.Error.Invalid
        else
            message.recipient.id

        val storagePath = diskRepository.writeMessage(data)
        val storedMessage =
            message.toStoredMessage(type, storagePath, dataSize, recipientAddress, recipientType)
        storedMessageDao.insert(storedMessage)
        return Result.Success(storedMessage)
    }

    private suspend fun checkForAvailableSpace(dataSize: StorageSize) =
        getStorageUsage.get().available >= dataSize

    private fun RAMFMessage<*>.toStoredMessage(
        type: MessageType,
        storagePath: String,
        dataSize: StorageSize,
        recipientAddress: String,
        recipientType: GatewayType,
    ): StoredMessage {
        return StoredMessage(
            recipientAddress,
            recipientType,
            senderId = senderCertificate.subjectId,
            messageId = MessageId(id),
            messageType = type,
            creationTimeUtc = Date.from(creationDate.toInstant()),
            expirationTimeUtc = Date.from(expiryDate.toInstant()),
            size = dataSize,
            storagePath = storagePath
        )
    }

    sealed class Result {
        data class Success(val message: StoredMessage) : Result()
        sealed class Error : Result() {
            object NoSpaceAvailable : Error()
            object Malformed : Error()
            object Invalid : Error()
        }
    }
}
