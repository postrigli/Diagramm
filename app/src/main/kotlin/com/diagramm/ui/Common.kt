package com.diagramm.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import com.diagramm.R
import com.diagramm.model.ByteFormat
import com.diagramm.model.FileCategory
import com.diagramm.model.Node
import com.diagramm.model.NodeKind
import java.io.File

/** Formats byte counts with localised units ("ГБ" in Russian) and the locale's decimal separator. */
@Composable
fun rememberSizeFormatter(): (Long) -> String {
    val units = stringArrayResource(R.array.size_units).toList()
    val locale = LocalConfiguration.current.locales[0]
    return remember(units, locale) { { bytes: Long -> ByteFormat.format(bytes, units, locale) } }
}

/** Splits the same formatting into number and unit, for the collector badge. */
@Composable
fun rememberSizeSplitter(): (Long) -> Pair<String, String> {
    val units = stringArrayResource(R.array.size_units).toList()
    val locale = LocalConfiguration.current.locales[0]
    return remember(units, locale) { { bytes: Long -> ByteFormat.split(bytes, units, locale) } }
}

/** Display name of a node; synthetic nodes get localised names. */
@Composable
fun nodeTitle(node: Node): String = when (node.kind) {
    NodeKind.FREE_SPACE -> stringResource(R.string.free_space)
    NodeKind.HIDDEN_SPACE -> stringResource(R.string.hidden_space)
    else -> node.name
}

@Composable
fun categoryLabel(category: FileCategory): String = stringResource(
    when (category) {
        FileCategory.FOLDER -> R.string.cat_folders
        FileCategory.IMAGE -> R.string.cat_images
        FileCategory.VIDEO -> R.string.cat_video
        FileCategory.AUDIO -> R.string.cat_audio
        FileCategory.DOCUMENT -> R.string.cat_documents
        FileCategory.ARCHIVE -> R.string.cat_archives
        FileCategory.APP -> R.string.cat_apps
        FileCategory.OTHER -> R.string.cat_other
    },
)

@Composable
fun sourceTitle(source: SourceItem): String = when (source.type) {
    SourceType.LOCAL ->
        if (source.isPrimary) stringResource(R.string.source_internal)
        else source.label ?: stringResource(R.string.source_removable)
    SourceType.GDRIVE -> stringResource(R.string.source_gdrive)
    SourceType.YANDEX -> stringResource(R.string.source_yandex)
    SourceType.APPS -> stringResource(R.string.source_apps)
}

/** Opens a file in another app: local files through FileProvider, cloud items in the browser. */
fun openNode(context: Context, node: Node, local: Boolean) {
    try {
        val intent = if (local) {
            val file = File(node.id)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            val link = node.link ?: return
            Intent(Intent.ACTION_VIEW, Uri.parse(link))
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.msg_cannot_open, Toast.LENGTH_SHORT).show()
    } catch (e: IllegalArgumentException) {
        Toast.makeText(context, R.string.msg_cannot_open, Toast.LENGTH_SHORT).show()
    }
}

/** Opens Android's own settings page of an app (storage, permissions, uninstall, force stop...). */
fun openAppSettings(context: Context, packageName: String) {
    try {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.msg_cannot_open, Toast.LENGTH_SHORT).show()
    }
}
