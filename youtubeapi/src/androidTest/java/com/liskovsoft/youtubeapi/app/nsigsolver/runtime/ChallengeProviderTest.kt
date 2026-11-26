package com.liskovsoft.youtubeapi.app.nsigsolver.runtime

import androidx.test.platform.app.InstrumentationRegistry
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.app.nsigsolver.impl.V8ChallengeProvider
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.ChallengeInput
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.ChallengeOutput
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.JsChallengeProviderResponse
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.JsChallengeRequest
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.JsChallengeResponse
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.JsChallengeType
import com.liskovsoft.youtubeapi.videoinfo.V2.FetchStages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

internal class ChallengeProviderTest {
    private val tests = listOf(
        JsChallengeRequest(
            JsChallengeType.N, ChallengeInput(
                "https://www.youtube.com/s/player/3d3ba064/player_ias_tce.vflset/en_US/base.js", listOf(
                    "ZdZIqFPQK-Ty8wId",
                    "4GMrWHyKI5cEvhDO",
                )
            )
        ),
        JsChallengeRequest(
            JsChallengeType.SIG, ChallengeInput(
                "https://www.youtube.com/s/player/3d3ba064/player_ias_tce.vflset/en_US/base.js", listOf(
                    "gN7a-hudCuAuPH6fByOk1_GNXN0yNMHShjZXS2VOgsEItAJz0tipeavEOmNdYN-wUtcEqD3bCXjc0iyKfAyZxCBGgIARwsSdQfJ2CJtt",
                )
            )
        ),
        JsChallengeRequest(
            JsChallengeType.N, ChallengeInput(
                "https://www.youtube.com/s/player/5ec65609/player_ias_tce.vflset/en_US/base.js", listOf(
                    "0eRGgQWJGfT5rFHFj",
                )
            )
        ),
        JsChallengeRequest(
            JsChallengeType.SIG, ChallengeInput(
                "https://www.youtube.com/s/player/5ec65609/player_ias_tce.vflset/en_US/base.js", listOf(
                    "AAJAJfQdSswRQIhAMG5SN7-cAFChdrE7tLA6grH0rTMICA1mmDc0HoXgW3CAiAQQ4=CspfaF_vt82XH5yewvqcuEkvzeTsbRuHssRMyJQ=I",
                )
            )
        ),
        JsChallengeRequest(
            JsChallengeType.N, ChallengeInput(
                "https://www.youtube.com/s/player/6742b2b9/player_ias_tce.vflset/en_US/base.js", listOf(
                    "_HPB-7GFg1VTkn9u",
                    "K1t_fcB6phzuq2SF",
                )
            )
        ),
        JsChallengeRequest(
            JsChallengeType.SIG, ChallengeInput(
                "https://www.youtube.com/s/player/6742b2b9/player_ias_tce.vflset/en_US/base.js", listOf(
                    "MMGZJMUucirzS_SnrSPYsc85CJNnTUi6GgR5NKn-znQEICACojE8MHS6S7uYq4TGjQX_D4aPk99hNU6wbTvorvVVMgIARwsSdQfJAA",
                )
            )
        ),
    )

    private val responses: List<JsChallengeProviderResponse> by lazy {
        val responses = mutableListOf<JsChallengeProviderResponse>()
        for ((test, results) in tests.zip(listOf(
            listOf("qmtUsIz04xxiNW", "N9gmEX7YhKTSmw"),
            listOf("ttJC2JfQdSswRAIgGBCxZyAfKyi0cjXCb3gqEctUw-NYdNmOEvaepit0zJAtIEsgOV2SXZjhSHMNy0NXNG_1kNyBf6HPuAuCduh-a7O"),
            listOf("4SvMpDQH-vBJCw"),
            listOf("AJfQdSswRQIhAMG5SN7-cAFChdrE7tLA6grI0rTMICA1mmDc0HoXgW3CAiAQQ4HCspfaF_vt82XH5yewvqcuEkvzeTsbRuHssRMyJQ=="),
            listOf("qUAsPryAO_ByYg", "Y7PcOt3VE62mog"),
            listOf("AJfQdSswRAIgMVVvrovTbw6UNh99kPa4D_XQjGT4qYu7S6SHM8EjoCACIEQnz-nKN5RgG6iUTnNJC58csYPSrnS_SzricuUMJZGM"),
        ))) {
            responses.add(JsChallengeProviderResponse(test, JsChallengeResponse(
                test.type, ChallengeOutput(test.input.challenges.zip(results).toMap())
            )))
        }
        responses
    }

    @Before
    fun setUp() {
        GlobalPreferences.instance(InstrumentationRegistry.getInstrumentation().context)
    }

    @Test
    fun testNonEmptyResponse() {
        val actual = V8ChallengeProvider.bulkSolve(tests).toList()
        assertEquals(responses, actual)
    }

    @Test
    fun testSingleNRequest() {
        val result = V8ChallengeProvider.bulkSolve(listOf(tests.first())).toList()
        assertTrue("not empty", result.isNotEmpty())
    }

    /**
     * 97Z1YWD3: about 35 web deciphers took 1.9-2.1 s each, a runtime made with the solver's libraries and the player loaded for every
     * one. The runtime is kept with the player loaded: a solve of the same player runs only its challenges.
     */
    @Test
    fun aSecondSolveOfThePlayerRunsOnlyItsChallenges() {
        val player = tests.take(2)
        solveTimed("solve1", player)

        val stages = solveTimed("solve2", player)

        assertEquals(responses.take(2), V8ChallengeProvider.bulkSolve(player).toList())
        assertEquals(listOf(V8ChallengeProvider.STAGE_SOLVE), stages)
    }

    /**
     * The runtime of the player before is let go: another one's solve makes it again, and loads its player
     */
    @Test
    fun anotherPlayerIsLoadedInANewRuntime() {
        solveTimed("player1", tests.take(2))

        val stages = solveTimed("player2", tests.drop(2).take(2))

        assertTrue(stages.toString(), stages.containsAll(listOf(V8ChallengeProvider.STAGE_V8_START, V8ChallengeProvider.STAGE_PLAYER_LOAD,
                V8ChallengeProvider.STAGE_SOLVE)))
        assertFalse(stages.toString(), solveTimed("player2again", tests.drop(2).take(2)).contains(V8ChallengeProvider.STAGE_V8_START))
    }

    /**
     * The names of the stages the solve ran, as a fetch of the video records them
     */
    private fun solveTimed(videoId: String, requests: List<JsChallengeRequest>): List<String> {
        FetchStages.open(videoId)

        try {
            V8ChallengeProvider.bulkSolve(requests).toList()
        } finally {
            FetchStages.close()
        }

        return FetchStages.take(videoId).map { it.name }.filter { it != "fetch" }
    }
}