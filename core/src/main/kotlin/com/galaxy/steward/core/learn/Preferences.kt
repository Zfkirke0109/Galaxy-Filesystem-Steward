package com.galaxy.steward.core.learn

import com.galaxy.steward.core.DAY_MS
import com.galaxy.steward.core.MIB
import com.galaxy.steward.core.model.FileKind
import com.galaxy.steward.core.plan.DuplicateGroup
import com.galaxy.steward.core.plan.FolderDuplicateGroup
import com.galaxy.steward.core.plan.FolderMerge
import com.galaxy.steward.core.plan.JunkItem
import com.galaxy.steward.core.plan.OptimizeItem
import com.galaxy.steward.core.plan.OrganizeMove
import com.galaxy.steward.core.plan.PlanItem
import com.galaxy.steward.core.termux.TermuxItem
import java.io.File
import java.io.IOException
import kotlin.math.exp
import kotlin.random.Random

/** One suggestion you were shown, described by [features], and whether you ran it. */
data class Decision(val time: Long, val runId: String, val features: List<String>, val chosen: Boolean, val undone: Boolean = false)

/** What your past choices say about a suggestion: tick it ([select]) or leave it, with the probability behind that. */
data class LearnedChoice(val select: Boolean, val probability: Double, val decisions: Int) {
    val note: String get() = if (select) "You usually run these" else "You usually leave these"
}

/**
 * Describes a suggestion for the preference model with a few words: its kind, where it is (top folder and the two
 * levels below), its extension, where it would go, how big and how old it is, and whether the rules tick it. Pairs
 * with the kind ("junk:INSTALLED_APKS|top:MT2") let it learn "installers in MT2, yes; in Download, no".
 */
object PreferenceFeatures {
    fun of(item: PlanItem, root: String, now: Long): List<String> {
        val kind: String
        val path: String
        var dest: String? = null
        var bytes = item.reclaimBytes
        var mtime = -1L
        var directory = false
        when (item) {
            is JunkItem -> {
                kind = "junk:${item.category.name}"
                path = item.path
                mtime = item.mtime
                directory = item.isDirectory
            }
            is OrganizeMove -> {
                kind = "organize:" + if (item.reason.startsWith("Learned")) "learned" else "rule"
                path = item.source
                dest = item.destinationFolder
                bytes = item.bytes
                directory = item.isDirectory
            }
            is OptimizeItem -> {
                kind = "optimize:${item.kind.name}"
                path = item.path
                directory = true
            }
            is DuplicateGroup -> {
                kind = "dup"
                path = item.removals.firstOrNull()?.path ?: item.keeper.path
                dest = item.keeper.path.substringBeforeLast('/')
                mtime = item.keeper.mtime
            }
            is FolderDuplicateGroup -> {
                kind = "folderdup"
                path = item.removals.firstOrNull()?.path ?: item.keeper.path
                dest = item.keeper.path
                directory = true
            }
            is FolderMerge -> {
                kind = "merge"
                path = item.source
                dest = item.target
                directory = true
            }
        }
        // Stored space-separated, so no feature may hold a space (or a tab): "My Folder" is learned as "My_Folder".
        val rel = path.removePrefix("$root/").replace(WHITESPACE, "_")
        val parts = rel.split('/')
        val top = parts.first()
        val two = parts.take(2).joinToString("/")
        val three = parts.take(3).joinToString("/")
        return buildList {
            add("c:$kind")
            add("top:$top")
            add("c|top:$kind|$top")
            add("c|p2:$kind|$two")
            if (parts.size > 3) add("c|p3:$kind|$three")
            if (!directory) FileKind.extensionOf(rel.substringAfterLast('/')).takeIf { it.isNotEmpty() }?.let { ext ->
                add("ext:$ext")
                add("c|ext:$kind|$ext")
            }
            dest?.removePrefix("$root/")?.replace(WHITESPACE, "_")?.split('/')?.take(2)?.joinToString("/")?.let { d ->
                add("dest:$d")
                add("c|dest:$kind|$d")
            }
            add("size:" + sizeBucket(bytes))
            if (mtime > 0) {
                val days = (now - mtime) / DAY_MS
                add("age:" + when {
                    days < 7 -> "week"
                    days < 30 -> "month"
                    days < 180 -> "half-year"
                    else -> "older"
                })
            }
            add("def:${item.defaultSelected}")
        }
    }

    /**
     * A Termux clean-up suggestion: its target ("termux:decompiled"), where it is (the folder in the home or $PREFIX,
     * or in a distribution, and the level below), how big it is and whether the rules tick it.
     */
    fun ofTermux(item: TermuxItem, home: String, prefix: String): List<String> {
        val kind = "termux:${item.targetId}"
        val rel = when {
            item.path.startsWith("$home/") -> "~/" + item.path.removePrefix("$home/")
            item.path.startsWith("$prefix/") -> "\$PREFIX/" + item.path.removePrefix("$prefix/")
            else -> item.path.trimStart('/')
        }.replace(WHITESPACE, "_")
        // "~/work", not "~": the folder in the home (or $PREFIX) is what a top folder is in shared storage.
        val parts = rel.split('/')
        val depth = if (parts.first() == "~" || parts.first() == "\$PREFIX") 2 else 1
        val top = parts.take(depth).joinToString("/")
        return buildList {
            add("c:$kind")
            add("top:$top")
            add("c|top:$kind|$top")
            if (parts.size > depth) add("c|p2:$kind|${parts.take(depth + 1).joinToString("/")}")
            add("size:" + sizeBucket(item.bytes))
            add("def:${item.defaultSelected}")
        }
    }

    private fun sizeBucket(bytes: Long) = when {
        bytes < MIB -> "s"
        bytes < 16 * MIB -> "m"
        bytes < 256 * MIB -> "l"
        else -> "xl"
    }

    private val WHITESPACE = Regex("\\s")

    /** The features that name one place and kind precisely; a choice counts as evidence only through these. */
    fun specific(features: List<String>): List<String> = features.filter { it.startsWith("c|") }
}

/**
 * Logistic regression over [PreferenceFeatures], trained on your own decisions each time it is loaded (a few thousand
 * lines, milliseconds). It only ever changes which suggestions start ticked, and only with enough decisions like it
 * behind it; every rule and safety check still applies to what runs.
 */
class PreferenceModel private constructor(
    private val weights: Map<String, Double>,
    private val bias: Double,
    private val support: Map<String, Int>,
    val decisions: Int,
) {
    fun probability(features: List<String>): Double = sigmoid(bias + features.sumOf { weights[it] ?: 0.0 })

    /** How many past decisions shared this suggestion's most specific place-and-kind feature. */
    fun supportOf(features: List<String>): Int = PreferenceFeatures.specific(features).maxOfOrNull { support[it] ?: 0 } ?: 0

    /** A confident, well-supported choice for [item], or null to keep the rule's default. */
    fun choiceFor(item: PlanItem, root: String, now: Long): LearnedChoice? {
        // Merging folders is always your call.
        if (item is FolderMerge) return null
        return choiceFor(PreferenceFeatures.of(item, root, now), item.defaultSelected)
    }

    /** A confident, well-supported choice for a suggestion described by [features], or null to keep [defaultSelected]. */
    fun choiceFor(features: List<String>, defaultSelected: Boolean): LearnedChoice? {
        val n = supportOf(features)
        if (n < MIN_SUPPORT) return null
        val p = probability(features)
        return when {
            p >= SELECT_AT && !defaultSelected -> LearnedChoice(true, p, n)
            p <= 1 - SELECT_AT && defaultSelected -> LearnedChoice(false, p, n)
            else -> null
        }
    }

    companion object {
        /** At least this many past decisions like it before a suggestion's default changes. */
        const val MIN_SUPPORT = 5

        const val SELECT_AT = 0.85

        private fun sigmoid(x: Double) = 1.0 / (1.0 + exp(-x))

        fun train(decisions: List<Decision>, epochs: Int = 12, rate: Double = 0.15, l2: Double = 1e-3): PreferenceModel {
            val weights = HashMap<String, Double>()
            var bias = 0.0
            val support = HashMap<String, Int>()
            for (d in decisions) for (f in PreferenceFeatures.specific(d.features)) support[f] = (support[f] ?: 0) + 1
            val random = Random(1)
            val order = decisions.indices.toMutableList()
            repeat(epochs) {
                order.shuffle(random)
                for (i in order) {
                    val d = decisions[i]
                    // Undoing a run is the strongest "no": it counts twice.
                    val label = if (d.chosen && !d.undone) 1.0 else 0.0
                    val weight = if (d.undone) 2.0 else 1.0
                    val p = sigmoid(bias + d.features.sumOf { weights[it] ?: 0.0 })
                    val g = (label - p) * weight * rate
                    bias += g
                    for (f in d.features) {
                        val w = weights[f] ?: 0.0
                        weights[f] = w + g - rate * l2 * w
                    }
                }
            }
            return PreferenceModel(weights, bias, support, decisions.size)
        }
    }
}

/**
 * Your decisions, one line each, in the app's private folder: which suggestions you ran and which you left, and runs you
 * undid. Nothing is sent anywhere. Only the latest [MAX_LINES] are kept.
 */
class DecisionLog(private val file: File) {
    @Synchronized
    fun record(runId: String, offered: List<PlanItem>, chosen: Set<String>, root: String, now: Long) =
        recordFeatures(runId, offered.map { PreferenceFeatures.of(it, root, now) to (it.id in chosen) }, now)

    /** One line per suggestion: its features, and whether it was run. */
    @Synchronized
    fun recordFeatures(runId: String, offered: List<Pair<List<String>, Boolean>>, now: Long) {
        if (offered.isEmpty()) return
        append(
            offered.map { (features, chosen) ->
                "D\t$now\t$runId\t${if (chosen) 1 else 0}\t" + features.joinToString(" ") { it.replace(' ', '_').replace('\t', '_') }
            },
        )
    }

    @Synchronized
    fun undone(runId: String, now: Long) = append(listOf("U\t$now\t$runId"))

    @Synchronized
    fun load(): List<Decision> {
        val lines = read()
        val undone = lines.filter { it.startsWith("U\t") }.mapNotNull { it.split('\t').getOrNull(2) }.toSet()
        return lines.filter { it.startsWith("D\t") }.mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 5) return@mapNotNull null
            Decision(p[1].toLongOrNull() ?: 0, p[2], p[4].split(' ').filter { it.isNotEmpty() }, p[3] == "1", p[2] in undone && p[3] == "1")
        }
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun read(): List<String> = try {
        if (file.isFile) file.readLines() else emptyList()
    } catch (_: IOException) {
        emptyList()
    }

    private fun append(lines: List<String>) {
        try {
            file.parentFile?.mkdirs()
            file.appendText(lines.joinToString("\n", postfix = "\n"))
            val all = read()
            if (all.size > MAX_LINES + MAX_LINES / 4) file.writeText(all.takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
        } catch (_: IOException) {
            // Learning is a convenience: a full disk must not fail the run it describes.
        }
    }

    companion object {
        const val MAX_LINES = 20_000
    }
}
