package com.z1r0v.streamableupload

import android.content.Context
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.before
import com.aliucord.utils.RxUtils.await
import com.discord.stores.StoreStream
import com.discord.utilities.message.MessageUtils
import com.discord.widgets.chat.input.AttachmentManager
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

@AliucordPlugin
class StreamableUpload : Plugin() {

    private val videoMimeTypes = setOf(
        "video/mp4",
        "video/webm",
        "video/quicktime",
        "video/x-matroska",
        "video/avi",
        "video/x-msvideo",
    )

    override fun start(context: Context) {
        // Patch the method that sends pending uploads + message together
        patcher.before<AttachmentManager>(
            "sendAttachments",
            // parameter types for the method signature — adjust if your
            // Aliucord build uses a slightly different overload
            Long::class.java,          // channelId
            String::class.java,        // message content
            List::class.java           // list of pending attachments
        ) { param ->
            val channelId = param.args[0] as Long
            val content   = param.args[1] as String
            @Suppress("UNCHECKED_CAST")
            val uploads   = (param.args[2] as List<*>).toMutableList()

            val videoItems = uploads.filter { item ->
                val mime = getMimeType(item) ?: return@filter false
                mime in videoMimeTypes
            }

            if (videoItems.isEmpty()) return@before  // nothing to intercept

            // Cancel the original call entirely; we'll re-dispatch manually
            param.result = null  // returning null from a `before` hook cancels original

            Thread {
                val links = mutableListOf<String>()

                for (item in videoItems) {
                    val uri  = getUri(item)  ?: continue
                    val name = getName(item) ?: "upload.mp4"
                    val mime = getMimeType(item) ?: "video/mp4"
                    val url  = uploadToStreamable(context, uri, name, mime)
                    if (url != null) links.add(url)
                }

                // Build final message: original content + all Streamable links
                val nonVideoUploads = uploads.filter { it !in videoItems }
                val finalContent = listOf(content)
                    .plus(links)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")

                // Send message on main thread
                com.aliucord.Utils.mainThread {
                    if (nonVideoUploads.isEmpty()) {
                        // Just send the text+links message
                        StoreStream.getMessages().sendMessage(
                            channelId,
                            finalContent,
                            emptyList(),
                            null
                        )
                    } else {
                        // Re-invoke original with non-video files + updated content
                        param.args[1] = finalContent
                        param.args[2] = nonVideoUploads
                        // Call original manually
                        param.method.invoke(param.thisObject, *param.args)
                    }
                }
            }.start()
        }
    }

    override fun stop(context: Context) = patcher.unpatchAll()

    // ── Streamable upload ────────────────────────────────────────────────────

    private fun uploadToStreamable(
        context: Context,
        fileUri: String,
        fileName: String,
        mimeType: String
    ): String? {
        return try {
            val stream = context.contentResolver.openInputStream(
                android.net.Uri.parse(fileUri)
            ) ?: throw Exception("Cannot open input stream for $fileUri")

            val boundary = "----AliucordBoundary${System.currentTimeMillis()}"
            val url = URL("https://api.streamable.com/upload")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            // Add Basic auth here if you have a Streamable account:
            // val creds = android.util.Base64.encodeToString("email:pass".toByteArray(), android.util.Base64.NO_WRAP)
            // conn.setRequestProperty("Authorization", "Basic $creds")

            val out = DataOutputStream(conn.outputStream)
            out.writeBytes("--$boundary\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n")
            out.writeBytes("Content-Type: $mimeType\r\n\r\n")
            stream.copyTo(out)
            out.writeBytes("\r\n--$boundary--\r\n")
            out.flush()
            stream.close()

            val code = conn.responseCode
            if (code != 200) throw Exception("HTTP $code from Streamable")

            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            val shortcode = json.getString("shortcode")
            "https://streamable.com/$shortcode"
        } catch (e: Exception) {
            logger.error("Streamable upload failed", e)
            null
        }
    }

    // ── Reflection helpers (adapt to your Discord version's field names) ─────

    private fun getUri(item: Any?): String? = runCatching {
        item?.javaClass?.getDeclaredField("uri")
            ?.also { it.isAccessible = true }
            ?.get(item) as? String
    }.getOrNull()

    private fun getName(item: Any?): String? = runCatching {
        item?.javaClass?.getDeclaredField("filename")
            ?.also { it.isAccessible = true }
            ?.get(item) as? String
    }.getOrNull()

    private fun getMimeType(item: Any?): String? = runCatching {
        item?.javaClass?.getDeclaredField("mimeType")
            ?.also { it.isAccessible = true }
            ?.get(item) as? String
    }.getOrNull()
}
