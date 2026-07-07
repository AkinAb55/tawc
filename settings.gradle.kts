pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "tawc"
include(":app")

// Termux's terminal-emulator + terminal-view (Apache-2.0) provide the
// in-app terminal widget, and :termux-extrakeys compiles termux's
// GPLv3 extra-keys row from the same checkout (license notes in
// termux-extrakeys/build.gradle.kts and notes/terminal.md).
// They're wired in as included projects straight from the vendored
// checkout, so the dep must exist at settings-evaluation time — clone/
// verify it here instead of in a build task. Pin lives in deps/deps.list.
// providers.exec (not ProcessBuilder) so the configuration cache works:
// Gradle records the output as a configuration input and re-runs the
// script even on cache-hit builds, keeping pin verification on every build.
run {
    val ensure = providers.exec {
        workingDir(rootDir)
        commandLine("scripts/ensure-deps.sh", "termux-app")
        isIgnoreExitValue = true
    }
    val output = ensure.standardOutput.asText.get() + ensure.standardError.asText.get()
    check(ensure.result.get().exitValue == 0) {
        "scripts/ensure-deps.sh termux-app failed:\n$output"
    }
}
include(":terminal-emulator")
project(":terminal-emulator").projectDir = file("deps/termux-app/terminal-emulator")
include(":terminal-view")
project(":terminal-view").projectDir = file("deps/termux-app/terminal-view")
include(":termux-extrakeys")
