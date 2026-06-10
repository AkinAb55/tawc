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

// Termux's terminal-emulator + terminal-view (Apache-2.0; the GPL parts
// of termux-app are not included) provide the in-app terminal widget.
// They're wired in as included projects straight from the vendored
// checkout, so the dep must exist at settings-evaluation time — clone/
// verify it here instead of in a build task. Pin lives in deps/deps.list.
run {
    val proc = ProcessBuilder("scripts/ensure-deps.sh", "termux-app")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val output = proc.inputStream.readBytes().decodeToString()
    check(proc.waitFor() == 0) { "scripts/ensure-deps.sh termux-app failed:\n$output" }
}
include(":terminal-emulator")
project(":terminal-emulator").projectDir = file("deps/termux-app/terminal-emulator")
include(":terminal-view")
project(":terminal-view").projectDir = file("deps/termux-app/terminal-view")
