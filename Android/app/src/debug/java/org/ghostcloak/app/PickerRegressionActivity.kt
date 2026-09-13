package org.ghostcloak.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.compose.material3.MaterialTheme
import org.ghostcloak.app.ui.screens.AttachmentComposer


/** Debug-only host: deliberately uses the same FragmentActivity integration as MainActivity. */
class PickerRegressionActivity : FragmentActivity() {
    var lastRequestCode: Int? = null
    var cancellations = 0
    var deferResult = false
    private var deferredCode: Int? = null
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (deferResult) deferredCode = requestCode
        else super.onActivityResult(requestCode, resultCode, data)
    }
    @Suppress("DEPRECATION")
    fun deliverDeferredResult() {
        super.onActivityResult(requireNotNull(deferredCode), RESULT_CANCELED, null)
        deferredCode = null
    }
    override fun onSaveInstanceState(outState: Bundle) {
        deferredCode?.let { outState.putInt("deferred-picker-code", it) }
        super.onSaveInstanceState(outState)
    }
    private val pendingPhoto = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) {
        if (it == null) cancellations++
    }
    private val pendingDocument = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) {
        if (it == null) cancellations++
    }
    fun launchPending(label: String) {
        if (label == "Photo") pendingPhoto.launch(androidx.activity.result.PickVisualMediaRequest(
            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
        else pendingDocument.launch(arrayOf("*/*"))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState?.containsKey("deferred-picker-code") == true)
            deferredCode = savedInstanceState.getInt("deferred-picker-code")
        setContent { MaterialTheme { AttachmentComposer("picker-regression", true) {} } }
    }

    @Suppress("DEPRECATION")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        lastRequestCode = requestCode
        // Do not bypass the superclass: Fragment 1.2.5 throws here for registry request codes.
        super.startActivityForResult(intent, requestCode, options)
    }
}
