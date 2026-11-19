package com.galaxy.steward.core.organize

import com.galaxy.steward.core.model.DirNode
import com.galaxy.steward.core.model.FileNode
import com.galaxy.steward.core.model.NodeFlags
import com.galaxy.steward.core.model.StorageTree
import com.galaxy.steward.core.model.Zone
import kotlin.math.ln

/** Where the model would file something, how sure it is (log-likelihood ratio, in nats), and why. */
data class LearnedHome(val folder: String, val score: Double, val because: List<String>, val examples: Int) {
    /** "shares "dsp", "presets" and .vdc with 12 items there" */
    val explanation: String
        get() = "shares ${because.take(3).joinToString(", ")} with " +
            if (examples == 1) "an item there" else "$examples items there"
}

/**
 * A small naive Bayes classifier trained on the phone's own folders, each time it is scanned: every folder you keep
 * things in is a class, and what already sits in it (names, extensions, kinds) is its training data. A loose file or a
 * stray folder then goes where things like it already are ("leakcanary-…" next to the other LeakCanary dumps, a
 * ViPER preset next to your other presets), with the words it matched as the reason.
 *
 * Scores are log-likelihood ratios against all folders together, so words that appear everywhere count for nothing.
 * A prior prefers shallow folders that aren't already crowded: fewer levels and smaller folders are quicker to list.
 * Nothing leaves the phone, and the model only suggests: rules and safety checks still decide.
 */
class FilingModel private constructor(
    private val homes: List<Home>,
    private val background: Counts,
    private val vocabulary: Int,
) {
    private class Counts {
        val features = HashMap<String, Double>()
        var total = 0.0
        var items = 0

        fun add(f: Map<String, Double>) {
            for ((k, w) in f) features[k] = (features[k] ?: 0.0) + w
            total += f.values.sum()
            items++
        }
    }

    /**
     * [counts] is what the folder holds, with what you moved there yourself counted [YOURS_WEIGHT] times ([weights]);
     * [plain] counts everything once, for the phone-wide background.
     */
    private class Home(val dir: DirNode, val rel: String, val prior: Double) {
        val counts = Counts()
        val plain = Counts()
        val members = HashMap<String, Map<String, Double>>()
        val weights = HashMap<String, Double>()

        fun add(path: String, features: Map<String, Double>, weight: Double) {
            plain.add(features)
            counts.add(if (weight == 1.0) features else features.mapValues { it.value * weight })
            members[path] = features
            if (weight != 1.0) weights[path] = weight
        }
    }

    val size: Int get() = homes.size

    /** Homes by folder, and by path for "every home inside this folder": looked up for every item planned. */
    private val byDir = java.util.IdentityHashMap<DirNode, Home>().apply { homes.forEach { put(it.dir, it) } }
    private val byPath = java.util.TreeMap<String, Home>().apply { homes.forEach { put(it.dir.path, it) } }

    /** The homes at [path] or inside it. */
    private fun homesAtOrInside(path: String): List<Home> =
        listOfNotNull(byPath[path]) + byPath.subMap("$path/", "$path/\uffff").values

    /** What [node] itself added to the counts: it is a member only of its own folder's home, if that is one. */
    private fun ownEntry(node: Any, self: String): Map<String, Double>? = when (node) {
        is FileNode -> byDir[node.dir]?.members?.get(self)
        is DirNode -> node.parent?.let { byDir[it] }?.members?.get(self)
        else -> null
    }

    /**
     * The best home for [node], or null when no folder is clearly better than the rest. When [node] already sits in one
     * of the homes, it is taken out of that home's counts first (leave-one-out), so it can't vote for where it is.
     *
     * Each home is scored once, from counts looked up once: this used to look through every home again for every home
     * it scored, and 800 downloads against 1,500 homes took 13 s (FilingModelTest).
     */
    fun homeFor(node: Any): LearnedHome? {
        val (features, self) = when (node) {
            is FileNode -> featuresOf(node) to node.path
            is DirNode -> featuresOf(node) to node.path
            else -> return null
        }
        if (features.isEmpty()) return null
        // A folder is never its own home, nor one inside itself; and what it holds is no evidence about where it goes.
        val inside = if (node is DirNode) homesAtOrInside(self) else emptyList()
        val skip = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Home, Boolean>()).apply { addAll(inside) }
        val excluded = Counts().apply { inside.forEach { h -> add(h.plain.features); items += h.plain.items - 1 } }
        val counted = ownEntry(node, self)
        var best: Home? = null
        var bestScore = Double.NEGATIVE_INFINITY
        var bestReasons: List<Pair<String, Double>> = emptyList()
        var runnerUp = Double.NEGATIVE_INFINITY
        for (h in homes) {
            if (h in skip) continue
            val (score, reasons) = evidence(h, features, self, counted, excluded) ?: continue
            if (score > bestScore) {
                runnerUp = bestScore
                best = h
                bestScore = score
                bestReasons = reasons
            } else if (score > runnerUp) {
                runnerUp = score
            }
        }
        val home = best ?: return null
        if (bestScore < MIN_SCORE || bestScore - runnerUp < MIN_MARGIN) return null
        return LearnedHome(home.rel, bestScore, bestReasons.sortedByDescending { it.second }.map { (f, _) -> label(f) }, examples(home, bestReasons, self))
    }

    /** How well [node] fits the folder it is in (its own entry left out): 0 when nothing there is like it. */
    fun fitWhereItIs(node: DirNode): Double {
        val parent = node.parent ?: return 0.0
        val home = byDir[parent] ?: return 0.0
        val self = node.path
        val excluded = Counts().apply { homesAtOrInside(self).forEach { add(it.plain.features) } }
        return evidence(home, featuresOf(node), self, home.members[self], excluded)?.first ?: 0.0
    }

    /** Items in [h] other than [self] that share something with it: "with 12 items there". */
    private fun examples(h: Home, reasons: List<Pair<String, Double>>, self: String): Int =
        h.members.count { (path, m) -> path != self && reasons.any { (f, _) -> m.containsKey(f) } }

    /**
     * Evidence that [features] belong in [h]: for each word or extension things in [h] share with them, how much more
     * common it is there than on the whole phone (a log ratio, with the home's counts smoothed towards the phone's).
     * Words nothing there shares count for nothing, but at least a third of what describes the item must match.
     * [counted] is the item's own entry in its folder's home, if any ([ownEntry]). Every reason is a word or extension
     * another item in [h] has, so there is always an example for it.
     */
    private fun evidence(
        h: Home,
        features: Map<String, Double>,
        self: String,
        counted: Map<String, Double>?,
        excluded: Counts? = null,
    ): Pair<Double, List<Pair<String, Double>>>? {
        // What the item itself added to the counts: to its home's (more when you moved it there yourself), and to the phone's.
        val own = h.members[self]
        val ownWeight = h.weights[self] ?: 1.0
        val sum = features.values.sum()
        val homeTotal = h.counts.total - (own?.values?.sum() ?: 0.0) * ownWeight
        val allTotal = background.total - (counted?.values?.sum() ?: 0.0) - (excluded?.total ?: 0.0)
        var score = h.prior
        var matched = 0.0
        val reasons = ArrayList<Pair<String, Double>>()
        for ((f, w) in features) {
            val inHome = (h.counts.features[f] ?: 0.0) - (own?.get(f) ?: 0.0) * ownWeight
            if (inHome < 0.99) continue
            val inAll = (background.features[f] ?: 0.0) - (counted?.get(f) ?: 0.0) - (excluded?.features?.get(f) ?: 0.0)
            val pAll = (inAll + ALPHA) / (allTotal + ALPHA * vocabulary)
            val pHome = (inHome + MU * pAll) / (homeTotal + MU)
            val lr = ln(pHome / pAll)
            score += w * lr
            matched += w
            if (lr > 0) reasons += f to w * lr
        }
        if (reasons.isEmpty() || matched < sum / 3) return null
        return score to reasons
    }

    companion object {
        private const val ALPHA = 0.5

        /** How strongly a home's counts lean on the phone's (Dirichlet smoothing): small homes aren't trusted blindly. */
        private const val MU = 10.0

        /** Suggestions need this much evidence (summed log ratios) ... */
        const val MIN_SCORE = 3.0

        /** ... and to beat the next best folder by this much. */
        const val MIN_MARGIN = 1.0

        private val STOP = setOf(
            "the", "and", "for", "new", "copy", "final", "file", "files", "folder", "data", "misc", "other", "stuff", "tmp",
            "temp", "untitled", "document", "documents", "download", "downloads", "img", "image", "images", "vid", "video",
            "export", "exports", "backup", "old", "com", "org", "net", "www", "http", "https", "android", "version",
        )
        private val CAMEL = Regex("(?<=[a-z])(?=[A-Z])|(?<=[A-Za-z])(?=[0-9])|(?<=[0-9])(?=[A-Za-z])")
        private val SPLIT = Regex("[^A-Za-z0-9]+")
        private val DATE_BUCKET = Regex("""^\d{4}(-\d{2})?$""")

        /** Name words of 3 letters or more, lower case, no numbers or filler words. */
        fun tokens(name: String): List<String> =
            name.split(SPLIT).flatMap { it.split(CAMEL) }.map { it.lowercase() }
                .filter { it.length >= 3 && it.any(Char::isLetter) && it !in STOP && !it.all(Char::isDigit) }
                .distinct()

        private fun label(feature: String): String = when {
            feature.startsWith("t:") -> "\"${feature.substring(2)}\""
            feature.startsWith("e:") -> "." + feature.substring(2)
            else -> feature
        }

        /** A file: the words of its name, and its extension (counted double: it says what the file is). */
        fun featuresOf(file: FileNode): Map<String, Double> = buildMap {
            for (t in tokens(file.name.substringBeforeLast('.', file.name))) put("t:$t", 1.0)
            file.extension.takeIf { it.isNotEmpty() && it.length <= 8 }?.let { put("e:$it", 2.0) }
        }

        /** A folder: the words of its name (a folder's name says more than a file's), and its three commonest extensions. */
        fun featuresOf(dir: DirNode): Map<String, Double> = buildMap {
            for (t in tokens(dir.name)) put("t:$t", 1.5)
            val byExt = HashMap<String, Int>()
            var seen = 0
            dir.walkFiles { f ->
                if (seen++ < 5000 && f.extension.isNotEmpty() && f.extension.length <= 8) byExt[f.extension] = (byExt[f.extension] ?: 0) + 1
            }
            byExt.entries.sortedByDescending { it.value }.take(3).forEach { put("e:${it.key}", 1.0) }
        }

        /** A folder things are filed in: your own or media, shallow, not a project, a date bucket or the inbox. */
        private fun isHome(d: DirNode): Boolean {
            if (d.isRoot || d.hidden || d.depth > 4) return false
            if (d.zone != Zone.USER_MANAGED && d.zone != Zone.MEDIA_LIBRARY) return false
            val rel = d.relPath
            if (rel == "Download" || rel.startsWith("Download/") || rel == "Documents" || rel.startsWith("Documents/Inbox-Review")) return false
            // The camera and the screenshot tool write to DCIM themselves; nothing downloaded belongs among their shots.
            if (rel == "DCIM" || rel.startsWith("DCIM/")) return false
            if (DATE_BUCKET.matches(d.name) || d.insideFlagged(NodeFlags.CODE_TREE or NodeFlags.PROJECT_ROOT)) return false
            return d.files.count { !it.hidden } + d.dirs.count { !it.hidden } >= 2
        }

        /**
         * Something you moved into a folder yourself since the last scan counts this many times in that folder (once in
         * the phone-wide background), so what it shares with the folder stands out more.
         */
        const val YOURS_WEIGHT = 3.0

        /** [yours]: paths of files and folders you moved yourself since the last scan ([com.galaxy.steward.core.learn.ScanMemory]). */
        fun train(tree: StorageTree, crowded: Int = 1000, yours: Set<String> = emptySet()): FilingModel {
            val homes = ArrayList<Home>()
            tree.root.walkDirs { d ->
                if (!isHome(d)) return@walkDirs
                val direct = d.files.size + d.dirs.size
                val prior = -0.25 * maxOf(0, d.depth - 2) - if (direct > crowded) 2.0 else 0.0
                val home = Home(d, d.relPath, prior)
                for (f in d.files) if (!f.hidden) home.add(f.path, featuresOf(f), if (f.path in yours) YOURS_WEIGHT else 1.0)
                for (c in d.dirs) if (!c.hidden) home.add(c.path, featuresOf(c), if (c.path in yours) YOURS_WEIGHT else 1.0)
                homes += home
            }
            val background = Counts()
            val vocabulary = HashSet<String>()
            for (h in homes) {
                for ((k, w) in h.plain.features) {
                    background.features[k] = (background.features[k] ?: 0.0) + w
                    vocabulary += k
                }
                background.total += h.plain.total
                background.items += h.plain.items
            }
            return FilingModel(homes, background, maxOf(1, vocabulary.size))
        }
    }
}
