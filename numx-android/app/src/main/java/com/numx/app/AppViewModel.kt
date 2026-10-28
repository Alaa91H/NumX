package com.numx.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin Activity-facing wrapper around the application-scoped OperationController.
 *
 * The controller owns the database, the running job and the UI state. This class
 * only forwards actions, so recreating the Activity (rotation, returning from the
 * background) never cancels a long operation and never loses its progress.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val controller = (app as NumXApplication).operations

    val state: StateFlow<UiState> = controller.state

    fun updateStart(value: String) = controller.updateStart(value)

    fun updateCount(value: String) = controller.updateCount(value)

    fun setCount(value: Long) = controller.setCount(value)

    fun selectCountry(iso2: String) = controller.selectCountry(iso2)

    fun setLineType(type: LineType) = controller.setLineType(type)

    fun setGenerationMode(value: GenerationMode) = controller.setGenerationMode(value)

    fun setExportFormat(value: ExportFormat) = controller.setExportFormat(value)

    fun selectFullCountry(format: ExportFormat) = controller.selectFullCountry(format)

    fun setMessage(value: String) = controller.setMessage(value)

    fun startGeneration() = controller.startGeneration()

    fun startFileGeneration(uri: String) = controller.startFileGeneration(uri)

    fun startFileGenerationToTree(uri: String) = controller.startFileGenerationToTree(uri)

    fun exportAddedToFile(uri: String) = controller.exportAddedToFile(uri)

    fun exportAddedToDirectory(uri: String) = controller.exportAddedToDirectory(uri)

    fun refreshStorageInfo() = controller.refreshStorageInfo()

    fun setCloudStorageAllowed(value: Boolean) = controller.setCloudStorageAllowed(value)

    fun cancel() = controller.cancel()

    fun deleteAllAdded() = controller.deleteAllAdded()

    fun resetStats() = controller.resetStats()

    fun restoreFromContacts() = controller.restoreFromContacts()

    fun shareLastExport() = controller.shareLastExport()

    fun setLookupInput(value: String) = controller.setLookupInput(value)

    fun setLookupCountry(iso2: String) = controller.setLookupCountry(iso2)

    fun lookup() = controller.lookup()

    fun setEmergencyCountry(iso2: String) = controller.setEmergencyCountry(iso2)

    fun addEmergencyContacts(entries: List<EmergencyEntry>) =
        controller.addEmergencyContacts(entries)
}
