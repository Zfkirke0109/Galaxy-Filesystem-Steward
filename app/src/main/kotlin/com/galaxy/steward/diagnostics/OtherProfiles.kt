package com.galaxy.steward.diagnostics

/**
 * Leaves other Android users out of a device log: Secure Folder (user 150 on Samsung phones), a work profile, Dual
 * Messenger (user 95). What those keep private stays private in an export meant for fixing this app.
 *
 * Their apps' own lines are known by uid, the column `logcat -v uid` adds after the time. Android 17 prints it as
 * "15010274   904   926 D Tag:", older versions as "15010235:12247 12247 D Tag:"; both are read. The system's lines
 * about them are known by the user they name ("u150a295", "(u150)", "userId = 150", "user:150", "/u150",
 * "am_proc_start: [150,…") or by one of their uids ("WorkSource{15010267", "AppUid(15010133)", "am_uid_running:
 * 15010309"). A bare number counts as a uid only for a user known to be on the phone, so sizes and ids that merely
 * look like one stay. When in doubt a line is left out: a missing line costs less than a leaked one.
 */
class OtherProfiles(private val myUser: Int, knownUsers: Set<Int> = SAMSUNG_PROFILES) {
    /** Lines left out so far. */
    var dropped = 0
        private set

    /** Other users seen or assumed so far: their bare uids are recognised from then on. */
    private val others = HashSet(knownUsers - myUser)

    fun keep(line: String): Boolean {
        if (isOther(line)) {
            dropped++
            return false
        }
        return true
    }

    private fun other(user: Int): Boolean {
        if (user == myUser) return false
        others += user
        return true
    }

    private fun isOther(line: String): Boolean {
        // Cheap looks first: a device log has hundreds of thousands of lines, and most name nobody.
        if (line.length > 20 && line[2] == '-' && line[5] == ' ' && line[8] == ':' && line[14] == '.') {
            headUser(line)?.let { if (other(it)) return true }
            if (line.contains(" am_") || line.contains(" wm_") || line.contains(" ssm_user_")) {
                // Events that name the user first ("am_proc_start: [150,12247,…"), or a uid second ("am_pss: [pid,15010235,…").
                USER_EVENT.find(line)?.let { if (other(it.groupValues[1].toInt())) return true }
                UID_EVENT.find(line)?.let { if (other((it.groupValues[1].toLong() / PER_USER_RANGE).toInt())) return true }
            }
        }
        if (namesAUser(line)) {
            for (m in MENTION.findAll(line)) {
                val user = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toIntOrNull() ?: continue
                if (other(user)) return true
            }
            // "u150" on its own only for a user known to be here: "kworker/u16:8" and "c2::u32" aren't users.
            for (m in USER_TOKEN.findAll(line)) {
                val user = m.groupValues[1].toInt()
                if (user != myUser && user in others) return true
            }
        }
        if (line.contains("uid", ignoreCase = true)) {
            for (m in UID_MENTION.findAll(line)) {
                if (other((m.groupValues[1].toLong() / PER_USER_RANGE).toInt())) return true
            }
        }
        // A bare uid, only for a user known to be here, so sizes and ids that merely look like one stay.
        if (others.isNotEmpty() && bareUid(line)) return true
        return false
    }

    /** The user of the uid column after the time: a number ("15010274", "1000:") or a name ("u150_a295"; "root" is no one's). */
    private fun headUser(line: String): Int? {
        var i = 18
        while (i < line.length && line[i] == ' ') i++
        val start = i
        while (i < line.length && (line[i].isLetterOrDigit() || line[i] == '_')) i++
        if (i == start || i >= line.length || (line[i] != ' ' && line[i] != ':')) return null
        val uid = line.substring(start, i)
        uid.toLongOrNull()?.let { return (it / PER_USER_RANGE).toInt() }
        if (uid.length > 2 && uid[0] == 'u' && uid[1].isDigit()) return NAMED_UID.find(uid)?.groupValues?.get(1)?.toIntOrNull()
        return null
    }

    /** A number of 6 to 8 digits on its own (not part of a longer one or a decimal) that is an app's uid of another user. */
    private fun bareUid(line: String): Boolean {
        var i = 19
        val n = line.length
        while (i < n) {
            if (!line[i].isDigit()) {
                i++
                continue
            }
            val start = i
            var value = 0L
            while (i < n && line[i].isDigit()) {
                if (i - start < 9) value = value * 10 + (line[i] - '0')
                i++
            }
            val len = i - start
            if (len !in 6..8 || line[start - 1] == '.' || (i < n && line[i] == '.')) continue
            val app = (value % PER_USER_RANGE).toInt()
            if ((value / PER_USER_RANGE).toInt() in others && (app in 1_000..29_999 || app in 90_000..99_999)) return true
        }
        return false
    }

    /** "u" before a digit or "=", or "user": the only ways [MENTION] and [USER_TOKEN] can match. */
    private fun namesAUser(line: String): Boolean {
        var i = line.indexOf('u')
        while (i >= 0 && i + 1 < line.length) {
            val next = line[i + 1]
            if (next.isDigit() || next == '=' || line.startsWith("ser", i + 1)) return true
            i = line.indexOf('u', i + 1)
        }
        return line.contains("User")
    }

    companion object {
        private const val PER_USER_RANGE = 100_000L

        /** Dual Messenger and Secure Folder: Samsung gives them these user ids. */
        val SAMSUNG_PROFILES = setOf(95, 150)

        private val NAMED_UID = Regex("""^u(\d+)_""")
        private val USER_EVENT = Regex(
            """ [IWEDV] (?:am_(?:proc_start|proc_bound|proc_died|kill|crash|anr|wtf)|ssm_user_[a-z_]+|wm_add_to_stopping|""" +
                """wm_set_resumed_activity|wm_new_intent|wm_(?:create|restart|pause|resume|finish|destroy|stop|idle|relaunch|""" +
                """relaunch_resume|failed_to_pause)_activity)\s*: \[(\d+)[,\]]""",
        )
        private val UID_EVENT = Regex(""" [IWEDV] am_(?:pss|cpu)\s*: \[\d+,(\d+),""")

        /** A user named outright: "u150a295", "u150_i3", "(u150)", "userId = 150", "user:150", "{4ab5a9f u150 ". */
        private val MENTION = Regex(
            """\bu(\d+)_?[asi]\d+|\(u(\d+)\)|\b(?:userId|user_id|userHandle|callingUserId|user)\s*[=:]?\s*(\d{1,3})\b|""" +
                """\bu=(\d+)\b|\{[0-9a-f]+ u(\d+) """,
        )

        /** A uid named outright: "uid = 15001000", "UID 15010211", "AppUid(15010133)", "uids={15020268}". */
        private val UID_MENTION = Regex("""(?i)\b(?:calling|app|source)?uids?\s*[=:({]*\s*(\d{6,8})\b""")
        private val USER_TOKEN = Regex("""\bu(\d{1,3})\b""")
    }
}
