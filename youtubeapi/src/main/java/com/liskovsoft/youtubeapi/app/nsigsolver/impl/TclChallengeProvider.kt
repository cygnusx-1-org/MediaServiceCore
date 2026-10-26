package com.liskovsoft.youtubeapi.app.nsigsolver.impl

import com.eclipsesource.v8.V8
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.youtubeapi.app.nsigsolver.common.CacheService
import com.liskovsoft.youtubeapi.app.nsigsolver.common.CachedData
import com.liskovsoft.youtubeapi.app.nsigsolver.common.loadScript
import com.liskovsoft.youtubeapi.app.nsigsolver.common.withLock
import java.security.MessageDigest

/**
 * Solves the n-parameter challenge for TCL-flavored players (tv-player-*-tcl.vflset), whose
 * n-function has a different call shape than the regular web/TV player and isn't recognized by
 * the generic (yt-dlp ejs) [V8ChallengeProvider].
 *
 * Unlike the regular player, the TCL n-function is invoked with a full URL object (it mutates the
 * "n" query param in place) rather than the bare n-param string — [solveN] hides that behind the
 * same bare-nParam contract the rest of the codebase uses, by wrapping/unwrapping a synthetic
 * placeholder URL.
 *
 * The runtime is kept between the solves with the player's n-function loaded, so a video only runs its own challenges. Its player
 * is replaced when the url changes (YouTube rotated the build), and the one preprocessed earlier is used only when the solver
 * scripts that made it are the ones of this version.
 */
internal object TclChallengeProvider {
    private val tag = TclChallengeProvider::class.simpleName
    private const val libPrefix = "nsigsolver/"
    private val libFilenames = listOf(
        "${libPrefix}polyfill.js",
        "${libPrefix}meriyah-6.1.4.min.js",
        "${libPrefix}astring-1.9.0.min.js",
        "${libPrefix}tcl.solver.js"
    )
    private val solverOutputType = object : TypeToken<SolverOutput>() {}.type
    private var v8Runtime: V8? = null
    /**
     * Of the player whose n-function the runtime has, null when it has none
     */
    private var loadedPlayerUrl: String? = null
    /**
     * Of the loaded player, preprocessed from its source. It's cached once it solves.
     */
    private var pendingPreprocessed: String? = null
    private val v8Lock = Any()
    private const val cacheSection = "tcl-challenge-solver"
    /**
     * Of the solver scripts: a player that other ones preprocessed (e.g. before the app was updated) is preprocessed again
     */
    private val solverVersion: String by lazy { sha1(loadSolverScripts()) }
    /**
     * Each solve makes a new n-function from the loaded player, like the solves that had a runtime of their own
     */
    private const val solveFunctionScript = """
        var tclSolveLoaded = function (challenges) {
            try {
                var nFunction = tclNFunctionFactory();
                var results = {};
                for (var i = 0; i < challenges.length; i++) {
                    results[challenges[i]] = nFunction(challenges[i]);
                }
                return { type: "result", results: results };
            } catch (error) {
                return { type: "error", error: error instanceof Error ? error.message + "\n" + error.stack : "" + error };
            }
        };
        "";
    """

    private data class SolverOutput(val type: String?, val results: Map<String, String>?, val error: String?, val preprocessed_player: String?)

    /**
     * Where the loaded player came from
     */
    private enum class PlayerSource { KEPT, CACHE, PLAYER_CODE }

    /**
     * @param playerUrl the TCL player's url (cache key)
     * @param playerCode the TCL player's JS source. Only read when its preprocessed n-function isn't loaded or cached.
     * @param nParams bare n-parameter values (as seen in a stream url's "n" query param)
     * @return map from each input nParam to its decoded value, or null for one that failed
     */
    fun solveN(playerUrl: String, playerCode: () -> String?, nParams: List<String>): Map<String, String?> {
        if (nParams.isEmpty()) {
            return emptyMap()
        }

        // n-params are opaque URL-safe tokens; wrap each in a throwaway url so the TCL
        // n-function (which expects a real URL object, not a bare string) has something valid
        // to parse. The wrapper's own shape doesn't matter beyond being a well-formed https url.
        val challengeByParam = nParams.distinct().associateWith { "https://www.youtube.com/videoplayback?n=$it" }

        val results = synchronized(v8Lock) {
            solveSync(playerUrl, playerCode, challengeByParam.values.toList())
        } ?: return nParams.associateWith { null }

        return nParams.associateWith { param -> results[challengeByParam[param]] }
    }

    /**
     * Loads the player's n-function ahead of the first solve (e.g. while the user browses)
     */
    fun prepare(playerUrl: String, playerCode: () -> String?) {
        synchronized(v8Lock) {
            solveSync(playerUrl, playerCode, emptyList())
        }
    }

    /**
     * Once more when it fails, from the player's source: the player loaded may be stale (kept from a solve before, or
     * preprocessed earlier)
     */
    private fun solveSync(playerUrl: String, playerCode: () -> String?, challenges: List<String>): Map<String, String>? {
        try {
            val source = loadPlayer(playerUrl, playerCode) ?: return null

            solveLoaded(challenges)?.let { return it.also { storePreprocessed(playerUrl) } }

            invalidate()

            if (source == PlayerSource.PLAYER_CODE) {
                return null
            }

            loadPlayer(playerUrl, playerCode) ?: return null

            solveLoaded(challenges)?.let { return it.also { storePreprocessed(playerUrl) } }
        } catch (e: Exception) {
            Log.e(tag, "TCL n-function solve failed: ${e.message}")
        }

        invalidate()

        return null
    }

    /**
     * The player is loaded from where it is already: the runtime, else the cache, else its source. Null when it can't be.
     */
    private fun loadPlayer(playerUrl: String, playerCode: () -> String?): PlayerSource? {
        if (v8Runtime != null && loadedPlayerUrl == playerUrl) {
            return PlayerSource.KEPT
        }

        disposeRuntime()

        val cached = CacheService.load(cacheSection, getCacheKey(playerUrl))?.takeIf { it.version == solverVersion }?.code

        if (cached != null) {
            if (loadNFunction(playerUrl, cached)) {
                return PlayerSource.CACHE
            }

            // It doesn't load (e.g. it's broken)
            CacheService.clear(cacheSection)
        }

        val preprocessed = preprocess(playerCode) ?: return null

        if (!loadNFunction(playerUrl, preprocessed)) {
            return null
        }

        pendingPreprocessed = preprocessed

        return PlayerSource.PLAYER_CODE
    }

    private fun preprocess(playerCode: () -> String?): String? {
        val code = playerCode() ?: return null.also { Log.e(tag, "No TCL player source to solve with") }

        val request = mapOf(
            "type" to "player",
            "player" to code,
            "challenges" to emptyList<String>(),
            "output_preprocessed" to true
        )

        initRuntime()

        return runSolver("JSON.stringify(tclSolver(${Gson().toJson(request)}));")?.preprocessed_player
    }

    private fun loadNFunction(playerUrl: String, preprocessed: String): Boolean {
        try {
            initRuntime()
            runV8("var tclNFunctionFactory = new Function(${Gson().toJson(preprocessed)});\n$solveFunctionScript")
        } catch (e: Exception) {
            Log.e(tag, "Cannot load the TCL n-function: ${e.message}")
            disposeRuntime()
            return false
        }

        loadedPlayerUrl = playerUrl

        return true
    }

    private fun solveLoaded(challenges: List<String>): Map<String, String>? {
        return runSolver("JSON.stringify(tclSolveLoaded(${Gson().toJson(challenges)}));")?.let { it.results ?: emptyMap() }
    }

    /**
     * Null when the solver fails
     */
    private fun runSolver(stdin: String): SolverOutput? {
        val stdout = try {
            runV8(stdin)
        } catch (e: Exception) {
            Log.e(tag, "TCL n-function solve failed: ${e.message}")
            return null
        }

        val output: SolverOutput = try {
            Gson().fromJson(stdout, solverOutputType)
        } catch (e: JsonSyntaxException) {
            Log.e(tag, "Cannot parse TCL solver output: ${e.message}")
            return null
        }

        if (output.type == "error") {
            Log.e(tag, "TCL solver error: ${output.error}")
            return null
        }

        return output
    }

    private fun storePreprocessed(playerUrl: String) {
        val preprocessed = pendingPreprocessed ?: return
        pendingPreprocessed = null
        CacheService.store(cacheSection, getCacheKey(playerUrl), CachedData(preprocessed, solverVersion))
    }

    /**
     * After a failure: the runtime and the cached player may be stale (e.g. player build rotated)
     */
    private fun invalidate() {
        disposeRuntime()
        pendingPreprocessed = null
        CacheService.clear(cacheSection)
    }

    private fun getCacheKey(playerUrl: String) = "player:$playerUrl"

    private fun runV8(stdin: String): String {
        val runtime = v8Runtime ?: throw IllegalStateException("V8 runtime not initialized yet")
        return runtime.withLock {
            it.executeStringScript(stdin) ?: throw IllegalStateException("V8 runtime error: empty response")
        }
    }

    private fun initRuntime() {
        if (v8Runtime != null) {
            return
        }
        // Read before the runtime is made: it's never left locked to this thread (see withLock)
        val stdin = constructCommonStdin()
        v8Runtime = V8.createV8Runtime()
        runV8(stdin)
    }

    private fun constructCommonStdin(): String {
        return """
        ${loadSolverScripts()}
        "";
        """
    }

    private fun loadSolverScripts() = loadScript(libFilenames, "Failed to read TCL challenge solver script")

    private fun disposeRuntime() {
        loadedPlayerUrl = null
        val runtime = v8Runtime ?: return
        v8Runtime = null
        runtime.withLock {
            it.release(false)
        }
    }

    private fun sha1(text: String): String {
        return MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
