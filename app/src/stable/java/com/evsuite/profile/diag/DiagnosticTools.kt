package com.evsuite.profile.diag

import android.content.Context
import android.widget.LinearLayout

/** Stable channel: the vendor-surface probes are not packaged. Nothing is added to the dialog. */
object DiagnosticTools {
    fun install(
        @Suppress("UNUSED_PARAMETER") context: Context,
        @Suppress("UNUSED_PARAMETER") container: LinearLayout,
    ): () -> String = { "" }
}
