package com.pasiflonet.mobile

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.pasiflonet.mobile.databinding.ItemMessageRowBinding
import com.pasiflonet.mobile.td.TdLibManager
import org.drinkless.tdlib.TdApi
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ChatAdapter(
    private var items: List<TdApi.Message>,
    private val onDetailsClick: (TdApi.Message) -> Unit
) : RecyclerView.Adapter<ChatAdapter.VH>() {

    class VH(val b: ItemMessageRowBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemMessageRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = items[position]

        val time = Date(msg.date.toLong() * 1000)
        holder.b.tvTime.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(time)

        var typeStr = "TXT"
        var fileIdToAutoDownload = 0

        when (val c = msg.content) {
            is TdApi.MessagePhoto -> {
                typeStr = "IMG"
                val best = c.photo.sizes.maxByOrNull { it.width * it.height }
                fileIdToAutoDownload = best?.photo?.id ?: 0
            }
            is TdApi.MessageVideo -> {
                typeStr = "VID"
                fileIdToAutoDownload = c.video.video.id
            }
            is TdApi.MessageAnimation -> {
                typeStr = "GIF"
                fileIdToAutoDownload = c.animation.animation.id
            }
            is TdApi.MessageDocument -> {
                typeStr = "DOC"
                fileIdToAutoDownload = c.document.document.id
            }
        }
        holder.b.tvMediaType.text = typeStr

        if (fileIdToAutoDownload != 0) {
            TdLibManager.downloadFile(fileIdToAutoDownload)
        }

        val idStr = msg.id.toString()
        holder.b.tvMsgId.text = if (idStr.length > 6) idStr.takeLast(6) else idStr

        holder.b.tvMsgText.text = extractText(msg)
        holder.b.tvSize.text = extractSizeHuman(msg)

        holder.b.btnDetails.setOnClickListener { onDetailsClick(msg) }
    }

    fun updateList(newItems: List<TdApi.Message>) {
        items = newItems.sortedByDescending { it.date }
        notifyDataSetChanged()
    }

    private fun extractText(m: TdApi.Message): String {
        return when (val c = m.content) {
            is TdApi.MessageText -> c.text.text
            is TdApi.MessagePhoto -> c.caption.text.ifBlank { "(image)" }
            is TdApi.MessageVideo -> c.caption.text.ifBlank { "(video)" }
            is TdApi.MessageAnimation -> c.caption.text.ifBlank { "(animation)" }
            is TdApi.MessageDocument -> c.caption.text.ifBlank { "(document)" }
            else -> "(message)"
        }
    }

    private fun extractSizeHuman(m: TdApi.Message): String {
        val bytes = extractBestLocalOrExpectedBytes(m)
        return if (bytes <= 0L) "-" else formatBytes(bytes)
    }

    private fun extractBestLocalOrExpectedBytes(m: TdApi.Message): Long {
        fun localBytes(path: String?): Long {
            if (path.isNullOrBlank()) return 0L
            return try {
                val f = File(path)
                if (f.exists() && f.isFile) f.length() else 0L
            } catch (_: Exception) { 0L }
        }

        return when (val c = m.content) {
            is TdApi.MessagePhoto -> {
                val best = c.photo.sizes.maxByOrNull { it.width * it.height }
                val f = best?.photo
                val lb = localBytes(f?.local?.path)
                when {
                    lb > 0L -> lb
                    (f?.expectedSize ?: 0) > 0 -> f!!.expectedSize.toLong()
                    (f?.size ?: 0) > 0 -> f!!.size.toLong()
                    else -> 0L
                }
            }
            is TdApi.MessageVideo -> {
                val f = c.video.video
                val lb = localBytes(f.local.path)
                when {
                    lb > 0L -> lb
                    f.expectedSize > 0 -> f.expectedSize.toLong()
                    f.size > 0 -> f.size.toLong()
                    else -> 0L
                }
            }
            is TdApi.MessageAnimation -> {
                val f = c.animation.animation
                val lb = localBytes(f.local.path)
                when {
                    lb > 0L -> lb
                    f.expectedSize > 0 -> f.expectedSize.toLong()
                    f.size > 0 -> f.size.toLong()
                    else -> 0L
                }
            }
            is TdApi.MessageDocument -> {
                val f = c.document.document
                val lb = localBytes(f.local.path)
                when {
                    lb > 0L -> lb
                    f.expectedSize > 0 -> f.expectedSize.toLong()
                    f.size > 0 -> f.size.toLong()
                    else -> 0L
                }
            }
            else -> 0L
        }
    }

    private fun formatBytes(bytes: Long): String {
        val kb = bytes.toDouble() / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.2fGB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1fMB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.0fKB", kb)
            else -> "${bytes}B"
        }
    }
}
