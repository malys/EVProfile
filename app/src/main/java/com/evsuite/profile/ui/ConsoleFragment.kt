package com.evsuite.profile.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.evsuite.profile.R
import com.evsuite.hardware.AppLogger
import com.evsuite.hardware.EVHardware

class ConsoleFragment : Fragment() {

    private lateinit var textStatus: TextView
    private lateinit var textLog: TextView
    private lateinit var scrollView: ScrollView

    private val logListener: () -> Unit = {
        activity?.runOnUiThread { if (isAdded) renderLog() }
    }

    /** Total AppLogger already rendered — used to add only new lines. */
    private var renderedTotal = 0L

    // Periodic auto-refresh (status + logs) without breaking the scroll position.
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            if (isAdded) {
                renderStatus()
                // totalCount and not size: the size freezes as soon as the buffer is full.
                if (AppLogger.totalCount != renderedTotal) renderLog()
            }
            handler.postDelayed(this, 800L)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_console, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        textStatus = view.findViewById(R.id.console_status)
        textLog    = view.findViewById(R.id.console_text)
        scrollView = view.findViewById(R.id.console_scroll)

        view.findViewById<Button>(R.id.btn_clear_console).setOnClickListener {
            AppLogger.clear()
        }

        renderStatus()
        renderLog()
    }

    override fun onResume() {
        super.onResume()
        AppLogger.addListener(logListener)
        handler.post(refresh)
        renderStatus()
        renderLog()
    }

    override fun onPause() {
        super.onPause()
        AppLogger.removeListener(logListener)
        handler.removeCallbacks(refresh)
    }

    // ---- Status banner ----

    private fun renderStatus() {
        val sb = SpannableStringBuilder()

        fun appendStatus(label: String, ok: Boolean) {
            val line = if (ok) "  ✓  $label\n" else "  ✗  $label\n"
            val color = colorOf(if (ok) R.color.ev_ok else R.color.console_error)
            val start = sb.length
            sb.append(line)
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, 0)
        }

        appendStatus("Katman1 — CarPropertyManager", EVHardware.isCarPropertyManagerReady())
        appendStatus("Katman1 — CarHvacManager",     EVHardware.isCarHvacManagerReady())
        appendStatus("Katman4 — VPM created",         EVHardware.isKatman4VpmCreated())
        appendStatus("Katman4 — Service connected",   EVHardware.isKatman4Ready())

        textStatus.text = sb
    }

    // ---- Log list ----

    /** True if the user is (almost) at the bottom → we follow the new logs; otherwise we leave it. */
    private fun isAtBottom(): Boolean {
        val child = scrollView.getChildAt(0) ?: return true
        val marginPx = (24 * resources.displayMetrics.density).toInt()
        return child.bottom - (scrollView.height + scrollView.scrollY) <= marginPx
    }

    private fun renderLog() {
        // Memorizes the position BEFORE touching the text: you do not re-scroll at the bottom
        // only if the user was already following the bottom (otherwise we preserve their reading).
        val followBottom = isAtBottom()

        // Incremental rendering: we only add what has happened since the last rendering.
        // null = entries have been evicted (or the buffer has been emptied) → made full.
        val newEntries = AppLogger.entriesSince(renderedTotal)
        val sb: SpannableStringBuilder
        if (newEntries == null) {
            sb = SpannableStringBuilder()
            AppLogger.entries.forEach { appendEntry(sb, it) }
        } else {
            if (newEntries.isEmpty()) return
            sb = SpannableStringBuilder(textLog.text)
            newEntries.forEach { appendEntry(sb, it) }
        }
        renderedTotal = AppLogger.totalCount
        textLog.text = sb
        if (followBottom) scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    /** Adds a colored entry at the end of [sb]. */
    private fun appendEntry(sb: SpannableStringBuilder, entry: AppLogger.Entry) {
        val prefix = "[${entry.time}] "
        val tag    = "${entry.tag}: "
        val msg    = "${entry.msg}\n"
        val color  = colorOf(when (entry.level) {
            AppLogger.Level.ERROR -> R.color.console_error
            AppLogger.Level.WARN  -> R.color.console_warn
            AppLogger.Level.DEBUG -> R.color.console_debug
            AppLogger.Level.INFO  -> R.color.console_info
        })
        val start = sb.length
        sb.append(prefix).append(tag).append(msg)
        sb.setSpan(ForegroundColorSpan(colorOf(R.color.console_timestamp)), start, start + prefix.length, 0)
        sb.setSpan(ForegroundColorSpan(colorOf(R.color.console_tag)), start + prefix.length, start + prefix.length + tag.length, 0)
        sb.setSpan(ForegroundColorSpan(color), start + prefix.length + tag.length, sb.length, 0)
    }

    /** Resolves a color from the current theme (light or dark) at render time. */
    private fun colorOf(res: Int): Int = ContextCompat.getColor(requireContext(), res)
}
