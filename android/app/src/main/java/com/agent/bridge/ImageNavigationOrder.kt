package com.agent.bridge

import kotlin.random.Random

/** Keeps the browser order available while the viewer follows a shuffled permutation. */
internal class ImageNavigationOrder {
    private var original: List<String> = emptyList()
    var paths: List<String> = emptyList()
        private set
    var shuffled: Boolean = false
        private set

    fun reset(paths: List<String>) {
        original = paths.toList()
        this.paths = original
        shuffled = false
    }

    fun toggle(current: String, random: Random = Random.Default) {
        if (paths.size < 2 || current !in paths) return
        shuffled = !shuffled
        paths = if (shuffled) listOf(current) + original.filterNot { it == current }.shuffled(random)
            else original
    }

    fun remove(path: String) {
        original = original.filterNot { it == path }
        paths = paths.filterNot { it == path }
    }
}
