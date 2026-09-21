package uz.evakuatsiya.yordamchi

import android.graphics.PointF
import kotlin.math.sqrt

/**
 * Plan rasmi uchun yurish mumkin bo‘lgan yo‘lak tugunlari.
 * Keyingi bosqichda bu ma’lumot JSON orqali har bir qavat uchun yuklanadi.
 */
object IndoorRoutePlanner {
    private data class Node(val id: String, val point: PointF)

    private val nodes = listOf(
        Node("left-hall", PointF(17f, 52f)),
        Node("hall-1", PointF(30f, 52f)),
        Node("stair-door", PointF(42f, 42f)),
        Node("exit-main", PointF(42f, 21f)),
        Node("hall-2", PointF(55f, 52f)),
        Node("hall-3", PointF(70f, 52f)),
        Node("right-hall", PointF(83f, 52f))
    )

    private val edges = mapOf(
        "left-hall" to listOf("hall-1"),
        "hall-1" to listOf("left-hall", "stair-door"),
        "stair-door" to listOf("hall-1", "exit-main", "hall-2"),
        "exit-main" to listOf("stair-door"),
        "hall-2" to listOf("stair-door", "hall-3"),
        "hall-3" to listOf("hall-2", "right-hall"),
        "right-hall" to listOf("hall-3")
    )

    val defaultExit = PointF(42f, 21f)

    fun calculateRoute(start: PointF, exit: PointF = defaultExit): List<PointF> {
        val startNode = nodes.minBy { distance(it.point, start) }
        val exitNode = nodes.minBy { distance(it.point, exit) }
        val previous = mutableMapOf<String, String?>()
        val distances = nodes.associate { it.id to Float.POSITIVE_INFINITY }.toMutableMap()
        val pending = nodes.map { it.id }.toMutableSet()
        distances[startNode.id] = 0f

        while (pending.isNotEmpty()) {
            val current = pending.minByOrNull { distances[it] ?: Float.POSITIVE_INFINITY } ?: break
            pending.remove(current)
            if (current == exitNode.id) break
            for (next in edges[current].orEmpty()) {
                if (next !in pending) continue
                val currentPoint = nodes.first { it.id == current }.point
                val nextPoint = nodes.first { it.id == next }.point
                val candidate = (distances[current] ?: Float.POSITIVE_INFINITY) + distance(currentPoint, nextPoint)
                if (candidate < (distances[next] ?: Float.POSITIVE_INFINITY)) {
                    distances[next] = candidate
                    previous[next] = current
                }
            }
        }

        val ids = mutableListOf<String>()
        var cursor: String? = exitNode.id
        while (cursor != null) {
            ids += cursor
            cursor = previous[cursor]
        }
        ids.reverse()
        if (ids.firstOrNull() != startNode.id) return listOf(start, exit)

        return buildList {
            add(start)
            ids.forEach { id -> add(nodes.first { it.id == id }.point) }
            add(exit)
        }
    }

    private fun distance(a: PointF, b: PointF): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }
}
