package com.liskovsoft.youtubeapi.aislist

import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper
import com.liskovsoft.mediaserviceinterfaces.data.AiSListData
import com.liskovsoft.sharedutils.helpers.FileHelpers
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
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
