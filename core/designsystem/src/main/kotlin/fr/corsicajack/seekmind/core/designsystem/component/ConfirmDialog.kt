package fr.corsicajack.seekmind.core.designsystem.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import fr.corsicajack.seekmind.core.designsystem.R
import fr.corsicajack.seekmind.core.designsystem.preview.SeekMindPreviews
import fr.corsicajack.seekmind.core.designsystem.theme.SeekMindTheme

/**
 * Confirmation of an action such as a deletion. The confirm button says what it does
 * (« Supprimer »), never « OK »; [destructive] draws it in the error colour, in addition to its label.
 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(text = title) },
        text = { Text(text = text) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = if (destructive) {
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.textButtonColors()
                },
            ) { Text(text = confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(text = dismissLabel) } },
    )
}

@SeekMindPreviews
@Composable
private fun ConfirmDialogPreview() {
    SeekMindTheme(dynamicColor = false) {
        ConfirmDialog(
            title = stringResource(R.string.designsystem_preview_dialog_title),
            text = stringResource(R.string.designsystem_preview_dialog_text),
            confirmLabel = stringResource(R.string.designsystem_preview_dialog_confirm),
            dismissLabel = stringResource(R.string.designsystem_preview_dialog_dismiss),
            onConfirm = {},
            onDismiss = {},
            destructive = true,
        )
    }
}
