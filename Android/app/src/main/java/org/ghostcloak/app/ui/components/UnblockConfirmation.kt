package org.ghostcloak.app.ui.components

import androidx.compose.material3.*
import androidx.compose.runtime.Composable

@Composable fun UnblockConfirmation(cancel:()->Unit,confirm:()->Unit) {
    AlertDialog(onDismissRequest=cancel,title={Text("Unblock this contact?")},
        text={Text("They can send you new message requests again.")},
        dismissButton={TextButton(onClick=cancel){Text("Cancel")}},
        confirmButton={TextButton(onClick=confirm){Text("Unblock")}})
}
