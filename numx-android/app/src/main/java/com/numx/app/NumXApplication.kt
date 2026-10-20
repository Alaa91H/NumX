package com.numx.app

import android.app.Application

/**
 * Holds the operation engine for the whole process. Unlike a ViewModel, it is not
 * tied to an Activity, so a running generation/deletion/export/restore survives
 * the Activity being recreated or the app being sent to the background.
 */
class NumXApplication : Application() {
    val operations: OperationController by lazy { OperationController(this) }
}
