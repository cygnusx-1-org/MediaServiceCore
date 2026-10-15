package com.liskovsoft.youtubeapi.aislist

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper
import com.liskovsoft.mediaserviceinterfaces.data.AiSListData
import com.liskovsoft.mediaserviceinterfaces.data.AiSListReport
import com.liskovsoft.sharedutils.helpers.FileHelpers
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Call
import java.io.File
import java.util.Locale

internal object AiSListService {
    private val TAG = AiSListService::class.java.simpleName
    private const val DIR_NAME = "aislist"
    private const val BLOCKLIST_FILE = "blocklist.txt"
    private const val WARNLIST_FILE = "warnlist.txt"
    private const val REFRESH_PERIOD_MS = 4 * 60 * 60 * 1_000L // 4 hours
    private const val MAX_RESPONSE_BYTES = 5 * 1024 * 1024 // 5 MB
    private const val MAX_MESSAGE_BYTES = 64 * 1024
    private const val MAX_MESSAGE_LENGTH = 200 // a toast
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
    private val mAiSListApi = RetrofitHelper.create(AiSListApi::class.java)

    /**
     * Returns the cached lists, downloading fresh ones when the cache is older than [REFRESH_PERIOD_MS].<br/>
     * A failed download keeps the cached copy. Must be called off the main thread.
     */
    @JvmStatic
    fun getData(): AiSListData {
        val blocklistFile = getFile(BLOCKLIST_FILE)
        val warnlistFile = getFile(WARNLIST_FILE)

        if (blocklistFile != null && warnlistFile != null) {
            refreshIfStale(mAiSListApi.getBlocklist(), blocklistFile)
            refreshIfStale(mAiSListApi.getWarnlist(), warnlistFile)
        }

        val blocklist = parse(blocklistFile)
        val warnlist = parse(warnlistFile)
        val updatedTimeMs = maxOf(blocklistFile?.lastModified() ?: 0, warnlistFile?.lastModified() ?: 0)

        return object : AiSListData {
            override fun getBlocklist(): Set<String> = blocklist
            override fun getWarnlist(): Set<String> = warnlist
            override fun getUpdatedTimeMs(): Long = updatedTimeMs
        }
    }

    /**
     * Sends the channel to AiSList's review as the AiBlock extension does: the video is the evidence.<br/>
     * Must be called off the main thread.
     * @return the answer, with the status 0 when the server wasn't reached
     */
    @JvmStatic
    fun report(channelHandle: String, videoUrl: String, username: String): AiSListReport {
        val call = mAiSListApi.reportChannel(createReportJson(channelHandle, videoUrl, username).toRequestBody(JSON_TYPE))

        val response = try {
            call?.let { RetrofitHelper.getResponse(it) }
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Can't report $channelHandle: ${e.message}")
            null
        } ?: return createReport(0, null)

        // The message comes with the error codes too, e.g. "Channel already submitted"
        val message = (if (response.isSuccessful) response.body() else response.errorBody())?.use {
            if (it.contentLength() > MAX_MESSAGE_BYTES) null else parseMessage(it.string())
        }

        return createReport(response.code(), message)
    }

    /**
     * The fields the AiBlock extension sends
     */
    @JvmStatic
    fun createReportJson(channelHandle: String, videoUrl: String, username: String): String {
        return GsonBuilder().disableHtmlEscaping().create().toJson(linkedMapOf("channel_handle" to channelHandle, "video_url" to videoUrl, "username" to username))
    }

    /**
     * @return the "message" of the answer, null when there's none
     */
    @JvmStatic
    fun parseMessage(content: String?): String? {
        if (content.isNullOrBlank()) {
            return null
        }

        val message = try {
            val json = JsonParser().parse(content)
            val field = if (json.isJsonObject) json.asJsonObject.get("message") else null
            if (field != null && field.isJsonPrimitive) field.asString else null
        } catch (e: RuntimeException) { // not JSON, e.g. the error page of a proxy
            null
        }

        return message?.trim()?.take(MAX_MESSAGE_LENGTH)?.ifEmpty { null }
    }

    private fun createReport(status: Int, message: String?): AiSListReport {
        return object : AiSListReport {
            override fun isAccepted(): Boolean = status in 200..299
            override fun getStatus(): Int = status
            override fun getMessage(): String? = message
        }
    }

    /**
     * One handle per line. Lines starting with "!" are comments.
     */
    @JvmStatic
    fun parse(content: String?): Set<String> {
        if (content.isNullOrEmpty()) {
            return emptySet()
        }

        val result = HashSet<String>()

        content.lineSequence()
            .map { it.trim() }
            .filter { it.length > 1 && it.startsWith("@") } // UC channel ids aren't used by the list
            .forEach { result.add(it.lowercase(Locale.ROOT)) }

        return result
    }

    private fun parse(file: File?): Set<String> {
        return if (file?.exists() == true) parse(FileHelpers.getFileContents(file)) else emptySet()
    }

    private fun refreshIfStale(call: Call<ResponseBody?>?, file: File) {
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < REFRESH_PERIOD_MS) {
            return
        }

        if (call == null) {
            return
        }

        val body = try {
            RetrofitHelper.get(call)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Can't download ${file.name}: ${e.message}")
            null
        } ?: return

        val content = body.use {
            if (it.contentLength() > MAX_RESPONSE_BYTES) null else it.string()
        }

        if (content == null || content.length > MAX_RESPONSE_BYTES || parse(content).isEmpty()) {
            Log.e(TAG, "Unexpected ${file.name} content")
            return
        }

        // Write to a temp file first so an interrupted write never replaces a good copy
        val tempFile = File(file.parentFile, "${file.name}.tmp")
        FileHelpers.stringToFile(content, tempFile)

        if (!tempFile.renameTo(file)) {
            FileHelpers.delete(tempFile)
        }
    }

    private fun getFile(name: String): File? {
        val context = GlobalPreferences.context() ?: return null
        val dir = File(FileHelpers.getFilesDir(context), DIR_NAME)

        if (!dir.exists() && !dir.mkdirs()) {
            return null
        }

        return File(dir, name)
    }
}
