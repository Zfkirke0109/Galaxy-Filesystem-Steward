package com.galaxy.steward.diagnostics

/**
 * Leaves other Android users out of a device log: Secure Folder (user 150 on Samsung phones), a work profile, Dual
 * Messenger. What those keep private stays private in an export meant for fixing this app. Their apps' own lines are
 * known by uid (the column `logcat -v uid` adds), and the system's lines about them by the user they name: "u150a295",
 * "(u150)", "userId=150", "u=150", "am_proc_start: [150,…".
 */
class OtherProfiles(private val myUser: Int) {
    /** Lines left out so far. */
    var dropped = 0
        private set

    fun keep(line: String): Boolean {
        if (isOther(line)) {
            dropped++
            return false
        }
        return true
    }

    private fun isOther(line: String): Boolean {
        // "09-24 09:00:17.658 15010235:12247 12247 I Tag: …": the uid ends at the first ':' after the time.
        if (line.length > 20 && line[2] == '-' && line[5] == ' ' && line[8] == ':') {
            val colon = line.indexOf(':', 19)
            if (colon in 20..40) {
                val uid = line.substring(19, colon).trim()
                val user = uid.toLongOrNull()?.let { (it / PER_USER_RANGE).toInt() } ?: NAMED_UID.find(uid)?.groupValues?.get(1)?.toIntOrNull()
                if (user != null && user != myUser) return true
            }
            // Events that name the user first ("am_proc_start: [150,12247,…"), or a uid second ("am_pss: [pid,15010235,…").
            USER_EVENT.find(line)?.let { if (it.groupValues[1].toInt() != myUser) return true }
            UID_EVENT.find(line)?.let { if ((it.groupValues[1].toLong() / PER_USER_RANGE).toInt() != myUser) return true }
        }
        for (m in MENTION.findAll(line)) {
            val user = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toIntOrNull() ?: continue
            if (user != myUser) return true
        }
        return false
    }

    companion object {
        private const val PER_USER_RANGE = 100_000L
        private val NAMED_UID = Regex("""^u(\d+)_""")
        private val USER_EVENT = Regex(
            """ [IWEDV] (?:am_(?:proc_start|proc_bound|proc_died|kill|crash|anr|wtf)|ssm_user_[a-z_]+|wm_add_to_stopping|""" +
                """wm_set_resumed_activity|wm_new_intent|wm_(?:create|restart|pause|resume|finish|destroy|stop|idle|relaunch|""" +
                """relaunch_resume|failed_to_pause)_activity)\s*: \[(\d+)[,\]]""",
        )
        private val UID_EVENT = Regex(""" [IWEDV] am_(?:pss|cpu)\s*: \[\d+,(\d+),""")
        private val MENTION = Regex(
            """\bu(\d+)_?[as]\d+|\(u(\d+)\)|\buserId[=: ](\d+)\b|\buser (\d+)\b|\bu=(\d+)\b|\{[0-9a-f]+ u(\d+) """,
        )
    }
}
