package com.evsuite.profile.update

import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.evsuite.profile.util.QrCode
import com.evsuite.profile.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Gère l'affichage du dialog de mise à jour.
 * 3 phases distinctes :
 *   1. Info      : versions + boutons Télécharger / Manuel / Plus tard
 *   2. Progress  : téléchargement privé, redirections contrôlées, puis vérification
 *   3. Manuel    : instructions GitHub/QR code
 */
object UpdateDialogManager {

    private const val TAG = "UpdateDialogManager"
    private const val GITHUB_RELEASES_URL =
        "https://github.com/malys/EVProfile/releases/tag/unstable"

    fun show(activity: AppCompatActivity, info: UpdateInfo) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_update, null)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(view)
            .setCancelable(false)
            .create()

        // ── Références vues ──────────────────────────────────────────────────
        val groupInfo     = view.findViewById<View>(R.id.group_info)
        val groupProgress = view.findViewById<View>(R.id.group_progress)
        val groupManual   = view.findViewById<View>(R.id.group_manual)

        // Phase 1 — Info
        val tvFrom       = view.findViewById<TextView>(R.id.tv_version_from)
        val tvTo         = view.findViewById<TextView>(R.id.tv_version_to)
        val tvNotes      = view.findViewById<TextView>(R.id.tv_release_notes)
        val rowDataWarn  = view.findViewById<View>(R.id.row_data_warning)
        val btnAuto      = view.findViewById<MaterialButton>(R.id.btn_update_auto)
        val btnManual    = view.findViewById<MaterialButton>(R.id.btn_update_manual)
        val btnLater     = view.findViewById<MaterialButton>(R.id.btn_update_later)
        val btnSkip      = view.findViewById<MaterialButton>(R.id.btn_update_skip)

        // Phase 2 — Progress
        val tvStatus     = view.findViewById<TextView>(R.id.tv_progress_status)
        val progressBar  = view.findViewById<ProgressBar>(R.id.progress_bar)
        val btnCancel    = view.findViewById<MaterialButton>(R.id.btn_cancel_download)

        // Phase 3 — Manuel
        val ivQr         = view.findViewById<ImageView>(R.id.iv_update_qr)
        val tvGhLink     = view.findViewById<TextView>(R.id.tv_update_gh_link)
        val tvApkPath    = view.findViewById<TextView>(R.id.tv_apk_path)
        val tvManualInst = view.findViewById<TextView>(R.id.tv_manual_instructions)
        val btnClose     = view.findViewById<MaterialButton>(R.id.btn_close_manual)

        // ── Remplissage initial ──────────────────────────────────────────────
        val currentVersion = try {
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "?"
        } catch (_: Exception) { "?" }

        tvFrom.text  = "v$currentVersion"
        tvTo.text    = "v${info.versionName}"
        tvNotes.text = info.releaseNotes.ifBlank { "—" }

        val onWifi = isOnWifi(activity)
        rowDataWarn.visibility = if (onWifi) View.GONE else View.VISIBLE


        // ── Bouton NE PLUS ME RAPPELER ───────────────────────────────────────
        btnSkip.setOnClickListener {
            UpdateChecker.skipVersion(activity, info.versionName)
            dialog.dismiss()
        }

        // ── Bouton PLUS TARD ─────────────────────────────────────────────────
        btnLater.setOnClickListener { dialog.dismiss() }

        // ── Panneau Manuel ───────────────────────────────────────────────────
        tvGhLink.setOnClickListener {
            runCatching {
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_RELEASES_URL))
                )
            }
        }
        btnClose.setOnClickListener { dialog.dismiss() }

        QrCode.generate(GITHUB_RELEASES_URL, 152)?.let { ivQr.setImageBitmap(it) }
        tvGhLink.text = GITHUB_RELEASES_URL
        tvGhLink.paintFlags = tvGhLink.paintFlags or Paint.UNDERLINE_TEXT_FLAG

        fun showManualPanel(inDownloads: Boolean = false) {
            if (inDownloads) {
                tvManualInst.text = activity.getString(R.string.update_downloaded_instructions)
                tvApkPath.visibility = View.GONE
            }
            switchTo(groupInfo, groupProgress, groupManual, showGroup = groupManual)
        }

        // ── Bouton INSTALLATION MANUELLE ─────────────────────────────────────
        btnManual.setOnClickListener { showManualPanel() }

        // ── Bouton TÉLÉCHARGER L'APK ──────────────────────────────────────────
        btnAuto.setOnClickListener {
            if (!onWifi) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.update_data_warn_title)
                    .setMessage(R.string.update_data_warn_message)
                    .setPositiveButton(R.string.update_continue) { _, _ ->
                        switchTo(groupInfo, groupProgress, groupManual, showGroup = groupProgress)
                        launchDownload(activity, info, dialog, tvStatus, progressBar, btnCancel) {
                            showManualPanel(inDownloads = true)
                        }
                    }
                    .setNegativeButton(R.string.update_cancel, null)
                    .show()
            } else {
                switchTo(groupInfo, groupProgress, groupManual, showGroup = groupProgress)
                launchDownload(activity, info, dialog, tvStatus, progressBar, btnCancel) {
                    showManualPanel(inDownloads = true)
                }
            }
        }

        dialog.show()
    }

    // ── Téléchargement privé vérifié → dossier Téléchargements public ───

    private fun launchDownload(
        activity: AppCompatActivity,
        info: UpdateInfo,
        dialog: AlertDialog,
        tvStatus: TextView,
        progressBar: ProgressBar,
        btnCancel: MaterialButton,
        onDownloaded: () -> Unit
    ) {
        // Deuxième barrière : UpdateChecker a déjà filtré l'URL, on ne fait jamais
        // confiance à une URL distante au point de la passer telle quelle au système.
        if (!ApkUrlPolicy.isAllowedLogged(info.apkUrl, "Téléchargement")) {
            tvStatus.setText(R.string.update_error_download)
            btnCancel.setText(R.string.update_close)
            return
        }

        tvStatus.setText(R.string.update_downloading)
        progressBar.isIndeterminate = false
        progressBar.progress = 0
        val privateApk = File.createTempFile("EVProfile-ota-", ".apk", activity.cacheDir)
        val job = activity.lifecycleScope.launch {
            val downloaded = ApkDownloader.download(info.apkUrl, privateApk) { percent ->
                progressBar.progress = percent
                tvStatus.text = activity.getString(R.string.update_downloading_pct, percent)
            }
            if (!downloaded) {
                privateApk.runCatching { delete() }
                tvStatus.setText(R.string.update_error_download)
                btnCancel.setText(R.string.update_close)
                return@launch
            }
            // Fail closed before anything reaches public storage.
            if (!ApkSignatureVerifier.matchesRunningApp(activity, privateApk)) {
                privateApk.runCatching { delete() }
                tvStatus.setText(R.string.update_error_signature)
                btnCancel.setText(R.string.update_close)
                return@launch
            }

            val publicApk = withContext(Dispatchers.IO) {
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloads.exists() && !downloads.mkdirs()) return@withContext null
                // UUID makes the final path unique: publication never needs delete-then-rename.
                val destination = File(
                    downloads,
                    "EVProfile-unstable-${info.versionName}-${UUID.randomUUID()}.apk"
                )
                runCatching {
                    privateApk.copyTo(destination, overwrite = false)
                    privateApk.delete()
                    destination
                }.getOrNull()
            }
            if (publicApk == null) {
                privateApk.runCatching { delete() }
                tvStatus.setText(R.string.update_error_download)
                btnCancel.setText(R.string.update_close)
                return@launch
            }

            progressBar.progress = 100
            ApkCleanup.cleanIfNeeded()
            openDownloadsFolder(activity)
            onDownloaded()
        }
        btnCancel.setOnClickListener {
            job.cancel()
            privateApk.runCatching { delete() }
            dialog.dismiss()
        }
    }

    // ── Ouvre le dossier Téléchargements dans le gestionnaire AAOS ───────────

    private fun openDownloadsFolder(context: Context) {
        try {
            val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "Dossier Téléchargements ouvert")
        } catch (e: Exception) {
            Log.w(TAG, "Impossible d'ouvrir Téléchargements : ${e.message}")
        }
    }

    // ── Utilitaires ──────────────────────────────────────────────────────────

    private fun switchTo(vararg allGroups: View, showGroup: View) {
        allGroups.forEach { it.visibility = View.GONE }
        showGroup.visibility = View.VISIBLE
    }

    private fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
