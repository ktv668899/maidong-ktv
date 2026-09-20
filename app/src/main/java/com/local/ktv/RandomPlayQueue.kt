package com.local.ktv

/**
 * 公放随机清单（伪随机）。
 *
 * 原来的「随机续播」是每首播完现随机挑一首，容易在短时间内重复播到同一首歌。
 * 这里改成一次性生成一份**打乱顺序的临时清单**，按顺序往下播：
 * - 一轮之内**不重复**播放同一首歌（[playedIds] 记录本轮已播）；
 * - 每首**播放完毕 +1**（[playCounts] 计数，同时用于观测与诊断）；
 * - 每次取下一首都会重新比对曲库，**自动吸纳新增的本地歌曲**（[refresh]），
 *   被删除的歌自动从清单里剔除；
 * - 一轮播完后自动开启新一轮（重新打乱），并尽量不把刚播完的那首放在开头。
 *
 * 三条硬性约束（与需求一致）：
 * 1. **不显示**：清单只在内存中，界面上不展示；
 * 2. **不关联点歌清单**：与 `MainActivity.orderQueue` 完全独立，随机歌曲不写入已点列表，
 *    它是纯粹的「公放清单」；
 * 3. **App 退出即清理**：纯内存态，随进程结束消失；退出路径也会主动调用 [clear]。
 */
object RandomPlayQueue {
    private val pending = ArrayDeque<Song>()

    /** 本轮已播（用于去重）。 */
    private val playedIds = LinkedHashSet<String>()

    /** 每首播放完毕的次数（+1）。 */
    private val playCounts = HashMap<String, Int>()

    /** 曾经入过清单的曲目 id，用于识别「新增歌曲」。 */
    private val knownIds = LinkedHashSet<String>()

    /** 最近一首由本清单开始播放的曲目 id（用于判断播完的是不是公放清单里的歌）。 */
    @Volatile private var lastStartedId: String = ""

    private val random = java.util.Random()

    /** 当前是第几轮（从 1 开始）。 */
    var cycle: Int = 0
        private set

    /** 清单里还剩几首。 */
    val remaining: Int get() = pending.size

    /** 本轮已播几首。 */
    val playedInCycle: Int get() = playedIds.size

    /** 清空清单（App 退出时调用）。 */
    fun clear() {
        pending.clear()
        playedIds.clear()
        playCounts.clear()
        knownIds.clear()
        lastStartedId = ""
        cycle = 0
    }

    /** 某首在本会话内播放完毕的次数。 */
    fun playCount(id: String): Int = playCounts[id] ?: 0

    /** 这首是不是当前正在播的公放清单曲目。 */
    fun isCurrentTrack(song: Song): Boolean = KtvStore.stableId(song) == lastStartedId

    /** 开始播放某首随机曲目时登记（立即计入本轮已播，保证被打断也不会重复）。 */
    fun markStarted(song: Song) {
        val id = KtvStore.stableId(song)
        lastStartedId = id
        playedIds.add(id)
    }

    /** 某首随机曲目播放完毕：计数 +1。 */
    fun markFinished(song: Song): Int {
        val id = KtvStore.stableId(song)
        lastStartedId = ""
        val next = (playCounts[id] ?: 0) + 1
        playCounts[id] = next
        return next
    }

    /**
     * 取下一首。
     *
     * @param candidates 当前可播的本地曲目（每次实时传入，从而自动吸纳新增歌曲）
     * @param avoidId 尽量避开的曲目 id（通常是刚刚播完的那首）
     * @return 下一首；曲库为空时返回 null
     */
    fun next(candidates: List<Song>, avoidId: String? = null): Song? {
        refresh(candidates)
        val available = LinkedHashMap<String, Song>()
        candidates.forEach { available[KtvStore.stableId(it)] = it }

        // 取清单里还没播过的第一首。
        while (pending.isNotEmpty()) {
            val song = pending.removeFirst()
            val id = KtvStore.stableId(song)
            if (available.containsKey(id) && !playedIds.contains(id)) return song
        }

        // 清单取空（本轮全部播完 / 都被删除）→ 开启新一轮。
        if (available.isEmpty()) return null
        startNewCycle(available, avoidId)
        while (pending.isNotEmpty()) {
            val song = pending.removeFirst()
            val id = KtvStore.stableId(song)
            if (available.containsKey(id) && !playedIds.contains(id)) return song
        }
        // 极端兜底：曲库只有一首且被要求避开时仍要能播。
        return available.values.firstOrNull()
    }

    /**
     * 与当前曲库对齐：剔除已不存在的曲目，并把新出现的曲目随机插入清单。
     */
    private fun refresh(candidates: List<Song>) {
        val currentIds = candidates.map { KtvStore.stableId(it) }.toSet()

        if (knownIds.isEmpty() && pending.isEmpty()) {
            // 第一次使用：整库打乱作为第一轮清单。
            startNewCycle(candidates.associateBy { KtvStore.stableId(it) }, null)
            return
        }

        // 1) 剔除曲库里已不存在的曲目。
        pending.removeAll { !currentIds.contains(KtvStore.stableId(it)) }
        playedIds.removeAll { !currentIds.contains(it) }
        knownIds.retainAll(currentIds)

        // 2) 新增歌曲：随机插入清单，避免总是排在最后。
        val added = candidates.filter { knownIds.add(KtvStore.stableId(it)) }
        if (added.isEmpty()) return
        added.shuffled(random).forEach { song ->
            val index = if (pending.isEmpty()) 0 else random.nextInt(pending.size + 1)
            pending.add(index, song)
        }
    }

    /** 开启新一轮：重新打乱整库，尽量把 [avoidId] 放到最后。 */
    private fun startNewCycle(candidates: Map<String, Song>, avoidId: String?) {
        cycle++
        playedIds.clear()
        pending.clear()
        val shuffled = candidates.values.shuffled(random).toMutableList()
        if (avoidId != null && shuffled.size > 1) {
            val head = shuffled.firstOrNull { KtvStore.stableId(it) == avoidId }
            if (head != null) {
                shuffled.remove(head)
                shuffled.add(head)
            }
        }
        pending.addAll(shuffled)
        knownIds.clear()
        knownIds.addAll(candidates.keys)
    }
}
