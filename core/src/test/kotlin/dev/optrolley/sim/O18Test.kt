package dev.optrolley.sim

import dev.optrolley.core.Routers
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** O18 — it gets better — for every router built in, over the reference suite. */
class O18Test {
    @TestFactory
    fun `O18 it gets better`(): List<DynamicTest> = Routers.all.list.map { router ->
        DynamicTest.dynamicTest("O18 router ${router.id}") {
            val failures = O18.check(ReferenceSuite.score(router.id))
            assertTrue(failures.isEmpty(), "router ${router.id} fails O18:\n" + failures.joinToString("\n"))
        }
    }
}

class SuiteTest {
    private val baselines = ReferenceSuite.baselines

    @Test
    fun `the suite has a scenario per noise source, one clean and one with everything`() {
        val noises = ReferenceSuite.scenarios.map { it.noise }
        assertTrue(Noise() in noises)
        val sources = listOf<(Noise) -> Boolean>(
            { it.batch > 0 }, { it.late > 0 }, { it.reverse > 0 }, { it.rearrangeAt != null }, { it.absent > 0 },
        )
        for (on in sources) assertTrue(noises.any { n -> on(n) && sources.count { it(n) } == 1 })
        assertTrue(noises.any { n -> sources.all { it(n) } })
        assertEquals(ReferenceSuite.scenarios.size * ReferenceSuite.shops.size * ReferenceSuite.seeds.size * ReferenceSuite.TRIPS, baselines.size)
    }

    @Test
    fun `paper is a coin toss and the floor leaves room to learn`() {
        val paper = O18.meanNormalised(baselines) { it.paper }
        assertTrue(paper in 0.45..0.55, "paper $paper")
        for ((n, at) in baselines.groupBy { it.n }) {
            val floor = O18.meanNormalised(at) { it.floor }
            assertTrue(floor < 0.5 * O18.meanNormalised(at) { it.paper }, "trip $n: floor $floor")
        }
    }

    @Test
    fun `paper itself is no better than paper, and fails the margin`() {
        val failures = O18.check(baselines.map { it.copy(presented = it.paper) })
        assertEquals(2, failures.size, failures.toString())
        assertTrue(failures[0].startsWith("trip 5:") && failures[1].startsWith("trip 5 after"))
    }

    @Test
    fun `the report is deterministic`() {
        val paper = Result.success(baselines.map { it.copy(presented = it.paper) })
        val error = Result.failure<List<WalkScore>>(NotImplementedError("x"))
        val results = mapOf("paper" to paper, "broken" to error)
        val report = SimReport.render(results)
        assertEquals(report, SimReport.render(results))
        assertTrue("## Router `paper`" in report && "O18: **fails**" in report && "Not scored" in report)
    }

    /** Writes `core/build/sim-report.md`; fails, after writing, if any router could not be scored. */
    @Test
    fun `write the simulation report`() {
        val results = Routers.all.list.associate { it.id to runCatching { ReferenceSuite.score(it.id) } }
        File("build").mkdirs()
        File("build/sim-report.md").writeText(SimReport.render(results))
        results.values.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
    }
}
