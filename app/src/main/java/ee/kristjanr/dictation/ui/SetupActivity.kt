package ee.kristjanr.dictation.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import ee.kristjanr.dictation.R
import ee.kristjanr.dictation.databinding.ActivitySetupBinding
import ee.kristjanr.dictation.model.ModelDownloader
import ee.kristjanr.dictation.model.ModelStore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Everything the keyboard cannot do for itself: get RECORD_AUDIO granted, get
 * the model onto the device, and point the user at the system keyboard settings.
 *
 * An [android.inputmethodservice.InputMethodService] has no activity of its own,
 * so it can neither request a runtime permission nor show a download UI. This
 * screen is the companion app from PLAN.md Phase 2.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var store: ModelStore

    private var downloadThread: Thread? = null
    private val downloadCancelled = AtomicBoolean(false)

    private val requestMicrophone =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ModelStore(this)

        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.grantPermission.setOnClickListener {
            requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
        }
        binding.openKeyboardSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        binding.downloadModel.setOnClickListener { startOrCancelDownload() }
        binding.deleteModel.setOnClickListener {
            store.deleteZipformer()
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        downloadCancelled.set(true)
        super.onDestroy()
    }

    private fun refresh() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        binding.permissionStatus.setText(
            if (granted) R.string.setup_permission_granted else R.string.setup_permission_missing
        )
        binding.grantPermission.isEnabled = !granted

        val downloading = downloadThread?.isAlive == true
        val installed = store.isZipformerInstalled()

        binding.modelStatus.text = when {
            downloading -> binding.modelStatus.text
            installed -> getString(R.string.setup_model_installed, store.installedBytes() / MEGABYTE)
            else -> getString(R.string.setup_model_missing)
        }

        binding.downloadModel.setText(
            if (downloading) R.string.setup_cancel_download else R.string.setup_download_model
        )
        binding.downloadModel.isEnabled = downloading || !installed
        binding.deleteModel.isEnabled = installed && !downloading
        binding.progress.visibility = if (downloading) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun startOrCancelDownload() {
        if (downloadThread?.isAlive == true) {
            downloadCancelled.set(true)
            return
        }

        downloadCancelled.set(false)
        binding.progress.isIndeterminate = true

        downloadThread = Thread {
            val result = ModelDownloader().download(
                targetDir = store.zipformerDir,
                shouldContinue = { !downloadCancelled.get() },
                onProgress = { progress -> runOnUiThread { showProgress(progress) } },
            )
            runOnUiThread {
                binding.modelStatus.text = when (result) {
                    is ModelDownloader.Result.Success -> getString(R.string.setup_download_done)
                    is ModelDownloader.Result.Cancelled -> getString(R.string.setup_download_cancelled)
                    is ModelDownloader.Result.Failed -> getString(R.string.setup_download_failed, result.reason)
                }
                refresh()
            }
        }.also { it.start() }

        refresh()
    }

    private fun showProgress(progress: ModelDownloader.Progress) {
        binding.modelStatus.text = getString(
            R.string.setup_downloading,
            progress.fileName,
            progress.fileIndex + 1,
            progress.fileCount,
            progress.bytesDone / MEGABYTE,
        )
        if (progress.bytesTotal > 0) {
            binding.progress.isIndeterminate = false
            binding.progress.max = PROGRESS_STEPS
            binding.progress.progress =
                (progress.bytesDone * PROGRESS_STEPS / progress.bytesTotal).toInt()
        }
    }

    private companion object {
        const val MEGABYTE = 1024L * 1024L
        const val PROGRESS_STEPS = 1000
    }
}
