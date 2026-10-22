package dev.obdmaru.recorder

/** One serial request at a time. Rates are targets, not guarantees. */
data class PollTask(val header: String, val command: String, val fast: Boolean,
                    var intervalMs: Long, var nextMs: Long = 0, val discovery: Boolean = false) {
    val key get() = "$header/$command"
}

class PollScheduler(private val fastBurst: Int) {
    private val tasks = linkedMapOf<String, PollTask>()
    private var fastRun = 0
    private var lastHeader = ""
    fun add(task: PollTask) { tasks.putIfAbsent(task.key, task) }
    fun size() = tasks.size
    fun remainingDiscovery() = tasks.values.count { it.discovery }
    fun next(now: Long): PollTask? {
        val ready = tasks.values.filter { it.nextMs <= now }
        val fast = ready.filter { it.fast }
        val background = ready.filter { !it.fast }
        val pool = if (background.isNotEmpty() && (fastRun >= fastBurst || fast.isEmpty())) background else fast.ifEmpty { background }
        val chosen = pool.maxByOrNull {
            // Prefer grouping close deadlines at the same ECU, without bypassing old work indefinitely.
            (now - it.nextMs).toDouble() + if (it.header == lastHeader) 100.0 else 0.0
        } ?: return null
        if (chosen.fast) fastRun++ else fastRun = 0
        lastHeader = chosen.header
        return chosen
    }
    fun complete(task: PollTask, now: Long, success: Boolean, allZero: Boolean) {
        if (task.discovery) { tasks.remove(task.key); return }
        val interval = if (task.fast) task.intervalMs else when {
            !success -> maxOf(task.intervalMs, 60000)
            allZero -> maxOf(task.intervalMs, 120000)
            else -> task.intervalMs
        }
        task.nextMs = now + interval
    }
}
