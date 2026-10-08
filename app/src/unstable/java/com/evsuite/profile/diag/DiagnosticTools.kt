package com.evsuite.profile.diag

import android.content.Context
import android.graphics.Typeface
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.evsuite.hardware.probe.RuntimeInterfaceProbe
import com.evsuite.hardware.probe.VendorSurfaceProbe
import com.evsuite.hardware.probe.VendorSurfaceRules
import com.evsuite.profile.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Unstable channel: the two read-only vendor-surface tools in the hidden Diagnostic dialog.
 *
 * - **SOC sources** (CR-044): every candidate source of state of charge on this car.
 * - **Capture before / after** (CR-045): read everything, let the driver change ONE setting on
 *   the car's own screen at standstill, read again, print what changed.
 *
 * Neither writes to the car. Their output joins the report, so Copy / Download / Share carry it.
 */
object DiagnosticTools {

    /** Survives closing and reopening the dialog, so the OEM screen can be opened in between. */
    @Volatile private var before: Map<String, String>? = null

    fun install(context: Context, container: LinearLayout): () -> String {
        // Binds the vendor hubs now, so they are up by the time a button is tapped.
        RuntimeInterfaceProbe.connect(context)

        val output = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 16f
            setTextColor(context.getColor(R.color.text_secondary))
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
        }
        val soc = Button(context).apply { text = context.getString(R.string.diag_tool_soc) }
        val capture = Button(context).apply { text = captureLabel(context) }
        container.addView(soc)
        container.addView(capture)
        container.addView(output)

        fun run(button: Button, work: () -> String) {
            button.isEnabled = false
            val label = button.text
            button.text = context.getString(R.string.diag_tool_running)
            CoroutineScope(Dispatchers.IO).launch {
                val text = runCatching(work).getOrElse { "ERR ${it.javaClass.simpleName}: ${it.message}" }
                withContext(Dispatchers.Main) {
                    output.append(if (output.text.isEmpty()) text else "\n$text")
                    button.text = if (button === capture) captureLabel(context) else label
                    button.isEnabled = true
                }
            }
        }

        soc.setOnClickListener { run(soc) { VendorSurfaceProbe.socSourcesReport() } }
        capture.setOnClickListener {
            run(capture) {
                val first = before
                val now = VendorSurfaceProbe.capture()
                if (first == null) {
                    before = now
                    "─── Capture 1/2 (CR-045) ───\n" +
                        context.getString(R.string.diag_tool_capture_hint, now.size)
                } else {
                    before = null
                    "─── Capture 2/2 (CR-045) ───\n" +
                        VendorSurfaceRules.formatDiff(VendorSurfaceRules.diff(first, now), first.size, now.size)
                }
            }
        }
        return { output.text.toString() }
    }

    private fun captureLabel(context: Context) = context.getString(
        if (before == null) R.string.diag_tool_capture_before else R.string.diag_tool_capture_after
    )
}
