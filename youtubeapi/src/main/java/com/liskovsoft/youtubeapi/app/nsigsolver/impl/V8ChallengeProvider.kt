package com.liskovsoft.youtubeapi.app.nsigsolver.impl

import android.os.SystemClock
import com.eclipsesource.v8.V8
import com.eclipsesource.v8.V8ScriptExecutionException
import com.eclipsesource.v8.V8Value
import com.google.gson.Gson
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.sharedutils.rx.RxHelper
import com.liskovsoft.youtubeapi.app.nsigsolver.common.CachedData
import com.liskovsoft.youtubeapi.app.nsigsolver.common.loadScript
import com.liskovsoft.youtubeapi.app.nsigsolver.common.withLock
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.JsChallengeProviderError
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.JsChallengeRequest
import com.liskovsoft.youtubeapi.app.nsigsolver.runtime.JsRuntimeChalBaseJCP
import com.liskovsoft.youtubeapi.app.nsigsolver.runtime.Script
import com.liskovsoft.youtubeapi.app.nsigsolver.runtime.ScriptSource
import com.liskovsoft.youtubeapi.app.nsigsolver.runtime.ScriptType
import com.liskovsoft.youtubeapi.app.nsigsolver.runtime.ScriptVariant
import com.liskovsoft.youtubeapi.app.nsigsolver.runtime.SolverOutput
import com.liskovsoft.youtubeapi.app.playerdata.PlayerDownload
import com.liskovsoft.youtubeapi.videoinfo.V2.FetchStages
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The web player's n/sig solver (yt-dlp ejs). The runtime is kept between the solves, with the solver's libraries and the player's
 * preprocessed one loaded, as TclChallengeProvider does: made and let go for each solve, with them loaded again each time, a decipher
 * took 1.9-2.1 s on a Mi Box, about 35 times in a few minutes, against 67-175 ms for TV's (97Z1YWD3). Its player is replaced when the
 * url changes, the runtime of the one before let go, and the runtime unused for a while is let go too (see releaseLater).
 * The runtime is locked to a thread for each use only (see withLock), under v8Lock: solves come from any thread (a fetch's, the
 * warm-up's, the subtitles').
 */
internal object V8ChallengeProvider: JsRuntimeChalBaseJCP() {
    private val tag = V8ChallengeProvider::class.simpleName
    private val v8NpmLibFilename = listOf("${libPrefix}polyfill.js", "${libPrefix}meriyah-6.1.4.min.js", "${libPrefix}astring-1.9.0.min.js")
    @Volatile
    private var v8Runtime: V8? = null
    /**
     * Of the player whose solvers the runtime has, null when it has none. Under v8Lock.
     */
    private var loadedPlayerUrl: String? = null
    private val v8Lock = Any()
    /**
     * When a solve last ran, in the time of SystemClock#elapsedRealtime. Under v8Lock.
     */
    private var lastUsedMs = 0L
    /**
     * Let go when no solve ran this long (see releaseLater)
     */
    private const val RELEASE_IDLE_MS = 30 * 60 * 1_000L
    private val isReleaseScheduled = AtomicBoolean()
    /**
     * A web decipher's stages, in the fetch that waits for it (see FetchStages), as the TV player's load has (see TclChallengeProvider):
     * the runtime made with the solver's libraries, the player's source preprocessed by them (its download is a stage of its own, see
     * PlayerDownload), its solvers made from the preprocessed one, and the challenges solved by them. A decipher with a solve only went
     * by the runtime kept.
     */
    const val STAGE_V8_START = TclChallengeProvider.STAGE_V8_START
    const val STAGE_PREPROCESS = TclChallengeProvider.STAGE_PREPROCESS
    const val STAGE_PLAYER_LOAD = "player load"
    const val STAGE_SOLVE = "solve"
    /**
     * Where the player loaded was preprocessed: before (the cache), or just now
     */
    const val LOADED_FROM_CACHE = TclChallengeProvider.LOADED_FROM_CACHE
    const val LOADED_FROM_PREPROCESSED = TclChallengeProvider.LOADED_FROM_PREPROCESSED
    private const val FAILED = "failed"
    /**
     * Of the runtime: where a player's source, and its preprocessed one, are put for a script, not in it (see runV8With)
     */
    private const val SOURCE_GLOBAL = "webPlayerSource"
    private const val PREPROCESSED_GLOBAL = "webPreprocessedPlayer"
    /**
     * The player's solvers are made from the loaded one for each solve, as each solve's own runtime made them (see jsc, main), and its
     * requests answered as jsc answers them
     */
    private const val solveFunctionScript = """
        var webSolveLoaded = function (requests) {
            var solvers = { n: null, sig: null };
            webSolverFactory(solvers);
            var responses = requests.map(function (request) {
                if (request.type !== 'n' && request.type !== 'sig') {
                    return { type: 'error', error: 'Unknown request type: ' + request.type };
                }
                var solver = solvers[request.type];
                if (!solver) {
                    return { type: 'error', error: 'Failed to extract ' + request.type + ' function' };
                }
                try {
                    var data = {};
                    for (var i = 0; i < request.challenges.length; i++) {
                        data[request.challenges[i]] = solver(request.challenges[i]);
                    }
                    return { type: 'result', data: data };
                } catch (error) {
                    return { type: 'error', error: error instanceof Error ? error.message + '\n' + error.stack : '' + error };
                }
            });
            return { type: 'result', responses: responses };
        };
        "";
    """

    override fun iterScriptSources(): Sequence<Pair<ScriptSource, (ScriptType) -> Script?>> = sequence {
        for ((source, func) in super.iterScriptSources()) {
            if (source == ScriptSource.WEB || source == ScriptSource.BUILTIN)
                yield(Pair(ScriptSource.BUILTIN, ::v8NpmSource))
            yield(Pair(source, func))
        }
    }

    private fun v8NpmSource(scriptType: ScriptType): Script? {
        if (scriptType != ScriptType.LIB)
            return null
        // V8-specific lib scripts that uses Deno NPM imports
        val code = loadScript(v8NpmLibFilename, "Failed to read v8 challenge solver lib script")
        return Script(scriptType, ScriptVariant.V8_NPM, ScriptSource.BUILTIN, scriptVersion, code)
    }

    override fun solvePlayer(playerUrl: String, requests: List<JsChallengeRequest>): SolverOutput {
        val output = synchronized(v8Lock) {
            try {
                loadPlayer(playerUrl)
                FetchStages.time(STAGE_SOLVE, null) { solveLoaded(requests) }
            } catch (e: Exception) {
                // Left in any state: made again by the next solve
                disposeRuntime()

                if (e is V8ScriptExecutionException && e.message?.contains("Invalid or unexpected token") == true)
                    ie.cache.clear(cacheSection) // cached data broken?

                throw e as? JsChallengeProviderError ?: JsChallengeProviderError("V8 runtime error: ${e.message}", e)
            } finally {
                lastUsedMs = SystemClock.elapsedRealtime()
            }
        }

        releaseLater()

        return output
    }

    /**
     * The player's solvers are loaded from where they are already: the runtime, else the cache, else its source, preprocessed now.
     * Another player's runtime is let go first, all it holds with it. Throws when it can't be.
     */
    private fun loadPlayer(playerUrl: String) {
        if (v8Runtime != null && loadedPlayerUrl == playerUrl) {
            return
        }

        disposeRuntime()
        initRuntime()

        val cached = ie.cache.load(cacheSection, getCacheKey(playerUrl))?.code

        if (cached != null) {
            if (timeLoad(LOADED_FROM_CACHE) { loadSolvers(cached) }) {
                loadedPlayerUrl = playerUrl
                return
            }

            // It doesn't load (e.g. it's broken)
            ie.cache.clear(cacheSection)
        }

        val source = PlayerDownload.time { getPlayer(playerUrl) } ?: throw JsChallengeProviderError("No player source: $playerUrl")
        val preprocessed = preprocess(source)

        if (!timeLoad(LOADED_FROM_PREPROCESSED) { loadSolvers(preprocessed) }) {
            throw JsChallengeProviderError("Cannot load the preprocessed player: $playerUrl")
        }

        ie.cache.store(cacheSection, getCacheKey(playerUrl), CachedData(preprocessed))
        loadedPlayerUrl = playerUrl
    }

    /**
     * The source goes in, and the preprocessed player comes out, as globals of the runtime, not in the script or its output (see
     * runV8With). In a function: nothing else of the run stays in the runtime's globals, holding on to the source.
     */
    private fun preprocess(source: String): String {
        val span = FetchStages.begin(STAGE_PREPROCESS)
        var preprocessed: String? = null

        try {
            parseOutput(runV8With(SOURCE_GLOBAL, source, """
                var $PREPROCESSED_GLOBAL;
                (function () {
                    var output = jsc({ type: "player", player: $SOURCE_GLOBAL, requests: [], output_preprocessed: true });
                    $PREPROCESSED_GLOBAL = output.preprocessed_player;
                    delete output.preprocessed_player;
                    return JSON.stringify(output);
                })();
            """))
            preprocessed = takeGlobalString(PREPROCESSED_GLOBAL)
        } finally {
            span.end(if (preprocessed != null) "${preprocessed.length / 1024} KB" else FAILED)
        }

        return preprocessed ?: throw JsChallengeProviderError("The solver gave no preprocessed player")
    }

    /**
     * The player's solvers made from its preprocessed one, in a fetch's stages: from the cache, or just preprocessed
     */
    private fun timeLoad(source: String, load: () -> Boolean): Boolean {
        val span = FetchStages.begin(STAGE_PLAYER_LOAD)
        var isLoaded = false

        try {
            isLoaded = load()
        } finally {
            span.end(if (isLoaded) source else "$source, $FAILED")
        }

        return isLoaded
    }

    /**
     * Compiled once, run by each solve (see solveFunctionScript)
     */
    private fun loadSolvers(preprocessed: String): Boolean {
        try {
            runV8With(PREPROCESSED_GLOBAL, preprocessed,
                "var webSolverFactory = new Function('_result', $PREPROCESSED_GLOBAL);\n$solveFunctionScript")
        } catch (e: Exception) {
            Log.e(tag, "Cannot load the web player's solvers: ${e.message}")
            return false
        }

        return true
    }

    private fun solveLoaded(requests: List<JsChallengeRequest>): SolverOutput {
        return parseOutput(runV8("JSON.stringify(webSolveLoaded(${Gson().toJson(toJsonRequests(requests))}));"))
    }

    private fun runV8(stdin: String): String {
        val runtime = v8Runtime ?: throw JsChallengeProviderError("V8 runtime not initialized yet")
        return runtime.withLock {
            it.executeStringScript(stdin) ?: throw JsChallengeProviderError("V8 runtime error: empty response")
        }
    }

    /**
     * The script, with a text of a few MB (a player's source, or its preprocessed one) in the runtime's global, let go after. Put in
     * the script as a JSON literal, it was copied twice on the Java heap (escaped, then into the script) and parsed by V8 as a literal.
     */
    private fun runV8With(global: String, value: String, stdin: String): String {
        val runtime = v8Runtime ?: throw JsChallengeProviderError("V8 runtime not initialized yet")
        return runtime.withLock {
            try {
                it.add(global, value)
                it.executeStringScript(stdin) ?: throw JsChallengeProviderError("V8 runtime error: empty response")
            } finally {
                it.addUndefined(global)
            }
        }
    }

    /**
     * The text a script left in the runtime's global, let go of there. Null when it isn't one (e.g. the solver gave none).
     */
    private fun takeGlobalString(global: String): String? {
        val runtime = v8Runtime ?: return null
        return runtime.withLock {
            try {
                if (it.getType(global) == V8Value.STRING) it.getString(global) else null
            } finally {
                it.addUndefined(global)
            }
        }
    }

    private fun initRuntime() {
        if (v8Runtime != null)
            return

        FetchStages.time(STAGE_V8_START, null) {
            // Read before the runtime is made: it's never left locked to this thread (see withLock)
            val stdin = constructCommonStdin()
            v8Runtime = V8.createV8Runtime()
            runV8(stdin)
        }
    }

    /**
     * Under v8Lock. From any thread: the runtime is locked to this one for it (see withLock).
     */
    private fun disposeRuntime() {
        loadedPlayerUrl = null
        val runtime = v8Runtime ?: return
        v8Runtime = null

        // NOTE: getting lock fixes "Invalid V8 thread access: the locker has been released!"
        runtime.withLock {
            it.release(false)
        }
    }

    /**
     * The runtime no solve used for RELEASE_IDLE_MS is let go: what it holds (the solver's libraries and a web player, MBs of native
     * heap) isn't kept on a low-RAM box that plays by the TV clients, which may have used it for a player check (see
     * WebPlayerDataExtractor#checkSigData) and no more. One check at a time, on a thread of its own.
     */
    private fun releaseLater() {
        if (!isReleaseScheduled.compareAndSet(false, true)) {
            return
        }

        // The delay ends on a computation thread, which isn't for work that waits
        RxHelper.runAsync({ RxHelper.runAsync { releaseIfIdle() } }, RELEASE_IDLE_MS)
    }

    private fun releaseIfIdle() {
        try {
            synchronized(v8Lock) {
                if (SystemClock.elapsedRealtime() - lastUsedMs >= RELEASE_IDLE_MS) {
                    disposeRuntime()
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Can't let go of the V8 runtime: ${e.message}")
        } finally {
            isReleaseScheduled.set(false)
        }

        // Used meanwhile: checked again later
        if (v8Runtime != null) {
            releaseLater()
        }
    }
}
