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

    fun setCount(value: Int) = controller.setCount(value)

    fun startGeneration() = controller.startGeneration()

    fun cancel() = controller.cancel()

    fun deleteAllAdded() = controller.deleteAllAdded()

    fun resetStats() = controller.resetStats()

    fun restoreFromContacts() = controller.restoreFromContacts()

    fun exportAll() = controller.exportAll()

    fun exportRangeFiles() = controller.exportRangeFiles()

    fun shareLastExport() = controller.shareLastExport()
}
