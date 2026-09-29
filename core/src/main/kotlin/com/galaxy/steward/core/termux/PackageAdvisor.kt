package com.galaxy.steward.core.termux

import com.galaxy.steward.core.DAY_MS
import com.galaxy.steward.core.ageText
import com.galaxy.steward.core.humanBytes
import com.galaxy.steward.core.plural

/** Why a package is suggested for removal, most telling first. */
enum class PackageAdvice(val title: String) {
    SAME_JOB("Does the same job"),
    UNUSED("Not run in months"),
    NEVER_RUN("Never run"),
    NOTHING_USES("Nothing uses it"),
}

/**
 * A package you installed that you may not need: [why] in words, and what apt's autoremove would take with it (the
 * dependencies nothing else needs), which the uninstall dialog shows again from apt's own dry run.
 */
data class PackageSuggestion(
    val pkg: TermuxPackage,
    val advice: PackageAdvice,
    val why: String,
    val alsoGoes: List<String>,
    val alsoFrees: Long,
) {
    val total: Long get() = pkg.bytes + alsoFrees
}

/** When the shell history starts ([since], ms, 0 when it keeps no dates) and how many commands it holds. */
data class HistorySpan(val since: Long, val commands: Int)

/**
 * Smart removal for Termux packages. It only suggests what you installed yourself, that nothing installed needs, that
 * Termux doesn't need, and that nothing kept runs with (Layla's relays, Boot scripts, services, what runs now). Among
 * those: an older one of two that do the same job (two JDKs, Node.js and Node.js LTS, two browsers or VS Codes), one not
 * run in [UNUSED_DAYS] days, one never run in the [NEVER_RUN_DAYS]+ days since it was installed (only when the shell
 * history reaches back that far), and libraries installed by hand that nothing uses. Every suggestion still goes
 * through apt's dry run before anything is removed.
 */
object PackageAdvisor {
    const val UNUSED_DAYS = 90
    const val NEVER_RUN_DAYS = 30
    private const val MIN_LIBRARY_BYTES = 1L shl 20

    /** Package families whose members do one job; the one used last stays. */
    private val FAMILIES = listOf(
        Regex("""^openjdk-\d+$""") to "Java",
        Regex("""^nodejs(-lts)?$""") to "Node.js",
        Regex("""^(chromium|firefox|epiphany|falkon|midori|netsurf)$""") to "browser",
        Regex("""^(code-oss|code-server|vscodium)$""") to "VS Code",
        Regex("""^(neovim|neovim-nightly)$""") to "Neovim",
        Regex("""^lua5[1-4]$""") to "Lua",
        Regex("""^(llvm|clang)-?\d+$""") to "LLVM",
        Regex("""^(python2|python)$""") to "Python",
        Regex("""^(php|php7|php8)$""") to "PHP",
    )

    fun suggest(packages: List<TermuxPackage>, history: HistorySpan, now: Long = System.currentTimeMillis()): List<PackageSuggestion> {
        val installed = packages.associateBy { it.name }
        val neededBy = HashMap<String, MutableSet<String>>()
        packages.forEach { p -> p.depends.forEach { d -> if (d in installed && d != p.name) neededBy.getOrPut(d) { HashSet() } += p.name } }
        fun free(p: TermuxPackage) = p.manual && !p.protected && p.keptBy.isEmpty() && neededBy[p.name].isNullOrEmpty()

        val out = LinkedHashMap<String, PackageSuggestion>()
        fun add(p: TermuxPackage, advice: PackageAdvice, why: String) {
            if (p.name in out) return
            val (goes, bytes) = alsoGoes(p, installed, neededBy)
            out[p.name] = PackageSuggestion(p, advice, why, goes, bytes)
        }

        // Two that do the same job: the one run last (or else installed last) stays.
        for ((pattern, job) in FAMILIES) {
            val members = packages.filter { pattern.matches(it.name) && it.manual }
            if (members.size < 2) continue
            val keep = members.maxWith(compareBy<TermuxPackage> { it.lastUsed }.thenBy { it.uses }.thenBy { it.installed })
            members.filter { it !== keep && free(it) }.forEach { p ->
                add(p, PackageAdvice.SAME_JOB, "${keep.name} is also installed as your $job" + when {
                    keep.lastUsed > p.lastUsed -> ", and you ran it more recently"
                    else -> ""
                })
            }
        }
        for (p in packages) {
            if (!free(p) || p.commands.isEmpty()) continue
            if (p.lastUsed > 0 && now - p.lastUsed > UNUSED_DAYS * DAY_MS) {
                add(p, PackageAdvice.UNUSED, "Last run ${ageText(p.lastUsed, now)}")
            } else if (p.lastUsed == 0L && p.uses == 0 && p.installed > 0 && now - p.installed > NEVER_RUN_DAYS * DAY_MS &&
                history.since in 1..p.installed
            ) {
                // History that reaches back to before the install makes "never" a fact, for your shell at least.
                add(p, PackageAdvice.NEVER_RUN, "Installed ${ageText(p.installed, now)} and never run from your shell since (a script or another program may still call it)")
            }
        }
        for (p in packages) {
            if (!free(p) || p.commands.isNotEmpty() || p.bytes < MIN_LIBRARY_BYTES) continue
            add(p, PackageAdvice.NOTHING_USES, "No commands, and nothing installed needs it: left over from something removed, or installed by hand")
        }
        return out.values.sortedByDescending { it.total }
    }

    /**
     * What apt's autoremove would take along with [p]: dependencies you didn't install yourself that only [p], or other
     * packages going with it, need. Protected and kept ones always stay.
     */
    fun alsoGoes(p: TermuxPackage, installed: Map<String, TermuxPackage>, neededBy: Map<String, Set<String>>): Pair<List<String>, Long> {
        val going = linkedSetOf(p.name)
        var grew = true
        while (grew) {
            grew = false
            val next = going.flatMap { installed[it]?.depends.orEmpty() }.toSet() - going
            for (d in next) {
                val dep = installed[d] ?: continue
                if (dep.manual || dep.protected || dep.keptBy.isNotEmpty()) continue
                if (neededBy[d].orEmpty().all { it in going }) {
                    going += d
                    grew = true
                }
            }
        }
        val deps = (going - p.name).mapNotNull { installed[it] }
        return deps.map { it.name }.sorted() to deps.sumOf { it.bytes }
    }

    /** "3 suggestions could free 1.2 GiB" style summary line. */
    fun summary(list: List<PackageSuggestion>): String =
        "${list.size.plural("suggestion")} could free ${list.sumOf { it.total }.humanBytes()} with what goes along"
}
