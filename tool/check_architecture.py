#!/usr/bin/env python3
"""Check module direction, dependency declarations, and presentation I/O boundaries."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
errors = []
modules = {p.parent.relative_to(ROOT).as_posix().replace("/", "."): p.parent
           for parent in ("apps", "core", "features")
           for p in (ROOT / parent).rglob("build.gradle.kts")}
graph = {}
for name, folder in modules.items():
    build = (folder / "build.gradle.kts").read_text()
    dependencies = re.findall(r"(?:implementation|api)\(projects\.([\w.]+)\)", build)
    graph[name] = dependencies
    if re.search(r"project\(\s*[\"']", build):
        errors.append(f"{name}: use type-safe projects accessors")
    if re.search(r"(?:implementation|api)\(\s*[\"'][^\n]+:[^\n]+:[^\n]+", build):
        errors.append(f"{name}: external versions belong in libs.versions.toml")
    for dependency in dependencies:
        if dependency not in modules:
            errors.append(f"{name}: unknown module {dependency}")
        if name.endswith(".domain") and not (dependency.endswith(".domain") or dependency == "core.common"):
            errors.append(f"{name}: domain cannot depend on {dependency}")
        if name.endswith(".presentation") and (dependency.endswith(".data") or dependency in {"core.network", "core.storage", "core.datastore", "core.database"}):
            errors.append(f"{name}: presentation cannot depend on {dependency}")
        if name.startswith("features.") and dependency.startswith("features.") and name.split(".")[1] != dependency.split(".")[1]:
            errors.append(f"{name}: features must not depend on each other ({dependency})")
        if name.endswith(".data") and dependency.endswith(".presentation"):
            errors.append(f"{name}: data cannot depend on presentation")
    for source in folder.glob("src/*Main/kotlin/**/*.kt"):
        text = source.read_text()
        imports = re.findall(r"^import\s+([^\s;]+)", text, re.M)
        path = str(source.relative_to(ROOT))
        if name.endswith(".domain") or name == "core.common":
            forbidden = ("android.", "androidx.", "io.ktor.", "org.koin.", "platform.", "kotlinx.serialization.", "com.squareup.wire.")
            if any(i.startswith(forbidden) for i in imports):
                errors.append(f"{path}: framework dependency in domain")
        if name.endswith(".presentation") and any(i.startswith(("io.ktor.", "platform.", "java.io.", "android.content.")) for i in imports):
            errors.append(f"{path}: direct I/O or platform dependency in presentation")
        if "/datasources/" in path and any(".repositories." in i or ".usecases." in i for i in imports):
            errors.append(f"{path}: datasource depends on an orchestration layer")
        if "/repositories/" in path and any(".usecases." in i or ".presentation." in i for i in imports):
            errors.append(f"{path}: repository depends on an upper layer")
        if source.name.endswith("ViewModel.kt") and any(
            ".data." in i or ".repositories." in i or i.startswith("androidx.navigation.") for i in imports
        ):
            errors.append(f"{path}: ViewModels coordinate use cases only")
        if "/pages/" in path or "/widgets/" in path:
            if any(word in text for word in ("LaunchedEffect(", "rememberCoroutineScope(", "mutableStateOf(", "koinInject", "koinViewModel")):
                errors.append(f"{path}: state/effects/DI belong in ViewModels or navigation composition")

def visit(name, trail):
    if name in trail:
        errors.append("Dependency cycle: " + " -> ".join([*trail, name]))
        return
    for dependency in graph.get(name, []):
        visit(dependency, [*trail, name])

for name in graph:
    visit(name, [])
if errors:
    print("\n".join(errors), file=sys.stderr)
    sys.exit(1)
print(f"Architecture checks passed for {len(modules)} modules.")
