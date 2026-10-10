plugins {
    id("starter.quality")
    id("starter.web.toolchain")
}

tasks.register<Exec>("architectureCheck") {
    group = "verification"
    commandLine("python3", "tool/check_architecture.py")
}

tasks.register<Exec>("distributionCheck") {
    group = "verification"
    commandLine(
        "python3",
        "-m",
        "unittest",
        "discover",
        "-s",
        "tool/distribution",
        "-p",
        "test_*.py",
    )
}

tasks.named("check") {
    dependsOn("architectureCheck")
    dependsOn("distributionCheck")
    dependsOn("formatCheck")
    dependsOn(
        subprojects
            .filter { it.buildFile.exists() && it.path != ":apps:android" }
            .map { "${it.path}:desktopTest" }
    )
}
