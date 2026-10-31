// Opt-in, for machines where Maven Central answers HTTP 429 (shared egress addresses, CI containers): puts a mirror of
// Central in front of Central for this build, with Central kept as the fallback. Off unless GALAXY_STEWARD_CENTRAL_MIRROR
// names an https URL, and only used when passed to Gradle:
//
//   GALAXY_STEWARD_CENTRAL_MIRROR=https://maven-central.storage-download.googleapis.com/maven2/ \
//     ./gradlew --init-script gradle/init/central-mirror.gradle.kts :core:test
//
// Google's mirror of Central serves the same artifacts as Central. Only point it at a mirror you trust as much as Central:
// this build pins no artifact checksums (README.md, Build from source).
val mirror = System.getenv("GALAXY_STEWARD_CENTRAL_MIRROR")?.trim().orEmpty()
if (mirror.isNotEmpty()) {
    require(mirror.startsWith("https://")) { "GALAXY_STEWARD_CENTRAL_MIRROR must be an https URL, not '$mirror'" }
    fun RepositoryHandler.preferMirror() {
        val repo = maven(mirror) { name = "CentralMirror" }
        remove(repo)
        addFirst(repo)
    }
    settingsEvaluated {
        pluginManagement.repositories.preferMirror()
        dependencyResolutionManagement.repositories.preferMirror()
    }
}
