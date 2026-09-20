package com.local.ktv

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.Equalizer
import android.util.Log
import kotlin.math.min
import kotlin.math.round

/**
 * 音频均衡器内核。
 *
 * 实现参考开源项目 **OpenEQ**（https://github.com/Turbofan3360/OpenEQ ，GPL-3.0）的
 * `audioprocessing/EQUtils.kt`：
 * - 直接使用 Android 系统自带的 `android.media.audiofx.Equalizer`，不引入第三方 DSP；
 * - **按设备真实支持的频段数与增益范围自适应**（很多设备宣称的频段数并不真正可用），
 *   频段标签生成规则与 OpenEQ 的 `eqFrequenciesToLabels()` 一致（60 / 230 / 910 / 3.6K …）。
 *
 * 与 OpenEQ 原版的差异（本 App 是播放器而不是独立均衡器）：
 * - OpenEQ 挂「全局混音（会话 0）」或自己生成的会话；本 App 需要作用于**自己的播放器**，
 *   因此默认把 EQ 挂到实际播放会话上：主播放器与内嵌副音轨取 IJKPlayer 的 AudioTrack 会话
 *   （`IjkMediaPlayer.getAudioSessionId()`，原生实现读的就是真实 AudioTrack 会话），
 *   外挂音轨取 `MediaPlayer.audioSessionId`。
 * - 会话随歌曲不断变化（每次 prepare 可能新建 AudioTrack），所以提供 [syncSessions]
 *   由界面层周期性对齐：新会话自动挂上，消失的会话延迟释放。
 * - 设备拒绝按会话挂载时，可退化为「全局混音」（会话 0）——代价是会影响本机所有声音，
 *   因此做成显式开关而不是静默回退。
 */
object KtvEqualizer {
    private const val TAG = "KtvEqualizer"

    /** dB → 毫贝(mB)，Android Equalizer 的增益单位。 */
    private const val DB_TO_MB = 100f

    /** 毫赫兹(mHz) → Hz，`getCenterFreq` 的单位。 */
    private const val MHZ_TO_HZ = 1000f

    /** 全局混音会话 id。 */
    const val GLOBAL_SESSION = 0

    /** 同时挂载的会话数上限（主播放器 + 副音轨 + 外挂音轨，正常 1~3 个）。 */
    private const val MAX_SESSIONS = 8

    /** 会话连续多少个同步周期未出现才释放（同步周期 500ms → 3s）。 */
    private const val STALE_TICKS = 6

    /** 探测不到设备能力时的兜底频段（常见 5 段）。 */
    private val FALLBACK_FREQUENCIES = listOf(60f, 230f, 910f, 3600f, 14000f)

    /** 设备均衡器能力。 */
    data class Capabilities(val frequencies: List<Float>, val minDb: Float, val maxDb: Float) {
        val bandCount: Int get() = frequencies.size
    }

    /**
     * 预设曲线：以「频段在可用范围内的相对位置 0..1」为横轴、以最大增益的比例 -1..1 为纵轴。
     * 这样同一套预设能自适应任意频段数的设备。
     */
    private val PRESETS: LinkedHashMap<String, List<Pair<Float, Float>>> = linkedMapOf(
        "标准" to listOf(0f to 0f),
        "流行" to listOf(0f to 0.35f, 0.25f to -0.15f, 0.6f to 0.1f, 1f to 0.35f),
        "抒情" to listOf(0f to -0.1f, 0.35f to 0.25f, 0.7f to 0.15f, 1f to -0.05f),
        "摇滚" to listOf(0f to 0.7f, 0.3f to -0.4f, 1f to 0.6f),
        "唱将" to listOf(0f to -0.2f, 0.5f to 0.55f, 0.8f to 0.3f, 1f to 0.05f),
    )

    /** 手动改过频段后使用的预设名。 */
    const val CUSTOM_PRESET = "自定义"

    @Volatile var enabled: Boolean = false
        private set

    @Volatile var globalMix: Boolean = false
        private set

    @Volatile var preset: String = "标准"
        private set

    private var levels: FloatArray = FloatArray(0)

    @Volatile private var cachedCaps: Capabilities? = null

    private class Entry(val effect: Equalizer) {
        var missingTicks = 0
    }

    private val effects = LinkedHashMap<Int, Entry>()

    // ─── 设备能力 ───────────────────────────────────────

    fun presetNames(): List<String> = PRESETS.keys.toList()

    /**
     * 探测设备支持的频段与增益范围（结果缓存；失败则返回常见 5 段兜底）。
     */
    fun capabilities(context: Context): Capabilities {
        cachedCaps?.let { return it }
        synchronized(this) {
            cachedCaps?.let { return it }
            val probeSession = runCatching {
                (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                    .generateAudioSessionId()
            }.getOrDefault(GLOBAL_SESSION)
            val caps = probe(probeSession) ?: probe(GLOBAL_SESSION)
                ?: Capabilities(FALLBACK_FREQUENCIES, -15f, 15f)
            cachedCaps = caps
            ensureLevelsFor(caps.bandCount)
            Log.i(
                TAG,
                "capabilities bands=${caps.bandCount} range=${caps.minDb}..${caps.maxDb} " +
                    "freq=${caps.frequencies}",
            )
            return caps
        }
    }

    private fun probe(session: Int): Capabilities? = runCatching {
        val eq = Equalizer(0, session)
        try {
            val bandCount = eq.numberOfBands.toInt()
            if (bandCount <= 0) return@runCatching null
            val frequencies = (0 until bandCount).map { eq.getCenterFreq(it.toShort()) / MHZ_TO_HZ }
            val range = eq.bandLevelRange
            Capabilities(frequencies, range[0] / DB_TO_MB, range[1] / DB_TO_MB)
        } finally {
            runCatching { eq.release() }
        }
    }.getOrNull()

    /** 本机是否允许挂「全局混音」（会话 0）。 */
    fun globalMixSupported(): Boolean = runCatching {
        val eq = Equalizer(0, GLOBAL_SESSION)
        runCatching { eq.release() }
        true
    }.getOrDefault(false)

    /** 频段中心频率的短标签，规则同 OpenEQ：1kHz 以下直接显示，以上显示 `3.6K` 形式。 */
    fun bandLabels(caps: Capabilities): List<String> = caps.frequencies.map { freq ->
        val text = if (freq < 1000f) {
            freq.toString()
        } else {
            (freq / 1000f).toString() + "K"
        }
        text.dropLastWhile { it == '0' }.dropLastWhile { it == '.' }.ifEmpty { "0" }
    }

    // ─── 状态读写 ───────────────────────────────────────

    fun levelsSnapshot(): FloatArray = levels.copyOf()

    fun setEnabled(value: Boolean) {
        enabled = value
        effects.values.forEach { entry -> runCatching { entry.effect.enabled = value } }
        Log.i(TAG, "enabled=$value sessions=${effects.size}")
    }

    /** 切换全局混音（会话 0）。切换会重建所有效果实例。 */
    fun setGlobalMix(value: Boolean) {
        if (globalMix == value) return
        globalMix = value
        releaseAll()
        Log.i(TAG, "globalMix=$value")
    }

    fun setLevels(newLevels: FloatArray) {
        levels = newLevels.copyOf()
        effects.values.forEach { applyTo(it.effect) }
    }

    /** 恢复当前目标频段为全 0（不平坦化）。 */
    fun resetLevels() {
        preset = "标准"
        setLevels(FloatArray(levels.size))
    }

    fun applyPreset(name: String) {
        val shape = PRESETS[name] ?: return
        preset = name
        setLevels(shapeToLevels(shape, capabilitiesIfKnown()))
    }

    /** 用户手动调整了某个频段：预设切换为「自定义」（不改变频段值）。 */
    fun markCustomPreset() {
        preset = CUSTOM_PRESET
        effects.values.forEach { applyTo(it.effect) }
    }

    private fun capabilitiesIfKnown(): Capabilities =
        cachedCaps ?: Capabilities(FALLBACK_FREQUENCIES, -15f, 15f)

    /** 把 0..1 的预设曲线插值到当前设备的频段上。 */
    private fun shapeToLevels(shape: List<Pair<Float, Float>>, caps: Capabilities): FloatArray {
        val bandCount = caps.bandCount.coerceAtLeast(1)
        val maxGain = caps.maxDb.coerceAtLeast(0f)
        val minGain = caps.minDb.coerceAtMost(0f)
        if (shape.size == 1) return FloatArray(bandCount) { 0f }
        return FloatArray(bandCount) { index ->
            val position = if (bandCount == 1) 0f else index.toFloat() / (bandCount - 1)
            var upper = 1
            while (upper < shape.size - 1 && shape[upper].first < position) upper++
            val (leftPos, leftValue) = shape[upper - 1]
            val (rightPos, rightValue) = shape[upper]
            val ratio = if (rightPos - leftPos <= 0f) 0f else (position - leftPos) / (rightPos - leftPos)
            val value = leftValue + (rightValue - leftValue) * ratio.coerceIn(0f, 1f)
            (if (value >= 0f) value * maxGain else value * -minGain)
        }
    }

    /**
     * 载入已保存的设置（App 启动时调用一次）。
     */
    fun restore(enabled: Boolean, presetName: String, savedLevels: FloatArray, useGlobalMix: Boolean) {
        globalMix = useGlobalMix
        this.enabled = enabled
        if (savedLevels.isNotEmpty()) {
            levels = savedLevels.copyOf()
        }
        preset = presetName.ifBlank { "标准" }
    }

    /**
     * 载入设置后把频段对齐到设备实际频段数。
     *
     * 预设（非自定义）直接按预设曲线在本机频段上重新生成，避免把「在别的设备上算好的
     * 频段值」硬套到本机；自定义则保留用户保存的原始数值。
     */
    fun alignToDevice(caps: Capabilities) {
        cachedCaps = caps
        ensureLevelsFor(caps.bandCount)
        if (preset != CUSTOM_PRESET) {
            applyPreset(preset)
        }
    }

    private fun ensureLevelsFor(bandCount: Int) {
        val target = bandCount.coerceAtLeast(1)
        if (levels.size == target) return
        val old = levels
        levels = FloatArray(target) { index -> old.getOrElse(index) { 0f } }
    }

    // ─── 会话挂载 ───────────────────────────────────────

    /**
     * 对齐当前存活的播放会话。
     *
     * @param liveSessions 主播放器 / 内嵌副音轨 / 外挂音轨当前的音频会话 id（无效的传 0）
     */
    fun syncSessions(liveSessions: List<Int>) {
        val targets: Set<Int> = if (globalMix) {
            setOf(GLOBAL_SESSION)
        } else {
            liveSessions.filter { it > 0 }.toSet()
        }
        val iterator = effects.entries.iterator()
        while (iterator.hasNext()) {
            val (session, entry) = iterator.next()
            if (targets.contains(session)) {
                entry.missingTicks = 0
                continue
            }
            entry.missingTicks++
            if (entry.missingTicks >= STALE_TICKS) {
                runCatching { entry.effect.release() }
                iterator.remove()
                Log.i(TAG, "released stale session=$session")
            }
        }
        targets.forEach { attach(it) }
    }

    private fun attach(session: Int) {
        val existing = effects[session]
        if (existing != null) {
            applyTo(existing.effect)
            return
        }
        if (effects.size >= MAX_SESSIONS) {
            Log.w(TAG, "skip session=$session: too many effects")
            return
        }
        runCatching {
            val eq = Equalizer(0, session)
            ensureLevelsFor(eq.numberOfBands.toInt())
            eq.enabled = enabled
            applyTo(eq)
            effects[session] = Entry(eq)
            Log.i(TAG, "attached session=$session bands=${eq.numberOfBands} enabled=$enabled")
        }.onFailure { Log.w(TAG, "attach session=$session failed: ${it.message}") }
    }

    private fun applyTo(eq: Equalizer) {
        runCatching {
            val loopLimit = min(levels.size, eq.numberOfBands.toInt())
            for (index in 0 until loopLimit) {
                eq.setBandLevel(
                    index.toShort(),
                    round(levels[index] * DB_TO_MB).toInt().toShort(),
                )
            }
        }.onFailure { Log.w(TAG, "apply levels failed: ${it.message}") }
    }

    fun releaseAll() {
        effects.values.forEach { runCatching { it.effect.release() } }
        effects.clear()
    }

    /** 已挂载的会话数（供日志/诊断）。 */
    fun attachedSessionCount(): Int = effects.size
}
