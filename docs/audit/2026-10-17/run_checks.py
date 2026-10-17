"""Offline audit reproductions against current sources. No server/device calls.

Requires locally cached Kotlin 2.1.20, coroutines 1.8.1, OkHttp 4.12.0 jars
and Java (JAVA_HOME or Android Studio's bundled JBR on macOS).
FIXED means the audited regression was prevented; this is not device validation.
"""
import ast
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
backend = ROOT / "backend/jarvis-server/app"
tree = ast.parse((backend / "routers/assistant.py").read_text())
fn = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == "apply_values")
scope = {"FIELDS": {"meeting": ("meetings", {"title", "summary", "location"})}, "embed_text": lambda _: [0.0]}
exec(compile(ast.Module(body=[fn], type_ignores=[]), "apply_values audit", "exec"), scope)


class Cursor:
    def __init__(self):
        self.sql = []

    def execute(self, sql, params):
        self.sql.append(sql)

    def fetchone(self):
        return {"id": "dummy-id", "title": "new", "summary": "corrected", "location": "office",
                "raw_transcript": "", "metadata": {}, "person_ids": [], "started_at": "2026-10-17"}


projection = ast.parse((backend / "meeting_projection.py").read_text())
selected = [n for n in projection.body if isinstance(n, ast.FunctionDef)]
from typing import Optional, List
scope.update({"Optional": Optional, "List": List, "Jsonb": lambda x: x})
exec(compile(ast.Module(body=selected, type_ignores=[]), "projection audit", "exec"), scope)
cursor = Cursor()
scope["apply_values"](cursor, "meeting", "dummy-id", {"title": "new", "summary": "corrected", "location": "office"})
assert len(cursor.sql) == 3 and cursor.sql[0].startswith("UPDATE meetings SET")
assert any("UPDATE memories" in sql for sql in cursor.sql)
print("FIXED: meeting correction updates derived-memory SQL (stub cursor, not DB integration)", flush=True)

cache = Path.home() / ".gradle/caches/modules-2/files-2.1"
dependencies = [
    "org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.20",
    "org.jetbrains.kotlin/kotlin-stdlib/2.1.20",
    "org.jetbrains.kotlin/kotlin-script-runtime/2.1.20",
    "org.jetbrains.kotlin/kotlin-reflect/1.6.10",
    "org.jetbrains.intellij.deps/trove4j/1.0.20200330",
    "org.jetbrains/annotations/13.0",
    "org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.1",
    "com.squareup.okhttp3/okhttp/4.12.0",
    "com.squareup.okio/okio-jvm/3.6.0",
    "org.json/json/20240303",
]
jars = []
for dependency in dependencies:
    found = list((cache / dependency).glob("*/*.jar"))
    if len(found) != 1:
        raise RuntimeError(f"Missing or ambiguous cached dependency: {dependency}")
    jars.extend(found)
classpath = os.pathsep.join(map(str, jars))
java = Path(os.environ.get("JAVA_HOME", "/Applications/Android Studio.app/Contents/jbr/Contents/Home")) / "bin/java"
sources = ROOT / "frontend/samples/CameraAccessAndroid/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/tasks"
with tempfile.TemporaryDirectory(prefix="jarvis-audit-") as output:
    subprocess.run([str(java), "-cp", classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                    "-no-stdlib", "-no-reflect", "-classpath", classpath, "-d", output,
                    *[str(sources / name) for name in ("TaskManager.kt", "TaskHttp.kt", "DurableRequests.kt", "TaskResults.kt")],
                    str(Path(__file__).with_name("AuditChecks.kt"))], check=True)
    subprocess.run([str(java), "-cp", output + os.pathsep + classpath,
                    "com.meta.wearable.dat.externalsampleapps.cameraaccess.tasks.AuditChecksKt"], check=True)
