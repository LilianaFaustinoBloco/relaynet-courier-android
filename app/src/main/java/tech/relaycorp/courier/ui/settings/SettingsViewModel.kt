package tech.relaycorp.courier.ui.settings

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import tech.relaycorp.courier.common.BehaviorChannel
import tech.relaycorp.courier.common.PublishChannel
import tech.relaycorp.courier.data.disk.DiskStats
import tech.relaycorp.courier.data.model.StorageSize
import tech.relaycorp.courier.data.preference.StoragePreferences
import tech.relaycorp.courier.domain.DeleteAllStorage
import tech.relaycorp.courier.domain.GetStorageUsage
import tech.relaycorp.courier.ui.BaseViewModel
import tech.relaycorp.courier.ui.common.Click
import tech.relaycorp.courier.ui.common.EnableState
import tech.relaycorp.courier.ui.common.toEnableState
import javax.inject.Inject

class SettingsViewModel
@Inject constructor(
    getStorageUsage: GetStorageUsage,
    deleteAllStorage: DeleteAllStorage,
    storagePreferences: StoragePreferences,
    diskStats: DiskStats
) : BaseViewModel() {

    // Inputs

    private val deleteDataClicks = PublishChannel<Click>()
    fun deleteDataClicked() = deleteDataClicks.trySendBlocking(Click)

    private val maxStorageChanged = PublishChannel<StorageSize>()
    fun maxStorageChanged(value: StorageSize) = maxStorageChanged.trySendBlocking(value)

    // Outputs

    private val deleteDataEnabled = BehaviorChannel<EnableState>()
    fun deleteDataEnabled() = deleteDataEnabled.asFlow()

    private val maxStorage = BehaviorChannel<StorageSize>()
    fun maxStorage() = maxStorage.asFlow()

    private val maxStorageBoundary = BehaviorChannel<SizeBoundary>()
    fun maxStorageBoundary() = maxStorageBoundary.asFlow()

    private val storageStats = BehaviorChannel<StorageStats>()
    fun storageStats() = storageStats.asFlow()

    init {
        getStorageUsage
            .observe()
            .onEach {
                deleteDataEnabled.send((!it.usedByApp.isZero).toEnableState())
            }
            .launchIn(scope)

        combine(
            getStorageUsage.observe(),
            diskStats.observeAvailableStorage()
        ) { usage, available ->
            StorageStats(
                used = usage.usedByApp,
                usedPercentage = usage.percentage,
                available = available,
                total = diskStats.getTotalStorage()
            )
        }
            .onEach(storageStats::send)
            .launchIn(scope)

        deleteDataClicks
            .asFlow()
            .onEach { deleteAllStorage.delete() }
            .launchIn(scope)

        storagePreferences
            .getMaxStorageSize()
            .onEach(maxStorage::send)
            .launchIn(scope)

        maxStorageChanged
            .asFlow()
            .onEach { storagePreferences.setMaxStorageSize(it) }
            .launchIn(scope)

        scope.launch {
            val totalStorageValue = diskStats.getTotalStorage()
            maxStorageBoundary.send(
                SizeBoundary(
                    MIN_STORAGE_SIZE,
                    totalStorageValue,
                    STORAGE_SIZE_STEP
                )
            )
        }
    }

    companion object {
        @VisibleForTesting
        val MIN_STORAGE_SIZE = StorageSize(100_000_000)

        @VisibleForTesting
        val STORAGE_SIZE_STEP = StorageSize(100_000_000)
    }
}
