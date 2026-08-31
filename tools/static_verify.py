#!/usr/bin/env python3
"""
Static verification harness for the Quantum player.

The Android SDK, Gradle distribution, JDK and every Maven/Google-Maven artifact
are on hosts this sandbox cannot reach, so `./gradlew assembleDebug` cannot be
run here. This script is NOT a compiler and does not claim to be: it checks the
classes of defect that actually broke this project, using only the files in the
repository.

Checks:
  1. Every XML resource parses, and has no duplicate resource names.
  2. Every R.<type>.<name> referenced from Kotlin exists in res/.
  3. Every @type/name referenced from XML exists in res/.
  4. Every `package` declaration matches its directory.
  5. Braces / parens / brackets balance in every Kotlin file.
  6. No banned (invented) API is referenced anywhere.
  7. No duplicate top-level declarations inside a package.
  8. Room @Query column names exist as @ColumnInfo / @PrimaryKey names.
  9. Every AndroidManifest component class exists in the source tree.
 10. Every internal project type referenced in Kotlin is declared somewhere.
"""
import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "src", "main", "java")
RES = os.path.join(ROOT, "app", "src", "main", "res")
MANIFEST = os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml")
PKG_PREFIX = "com.quantum.player"

failures = []
checks_run = 0


def check(name, condition, detail=""):
    global checks_run
    checks_run += 1
    if not condition:
        failures.append(f"{name}: {detail}")
    return condition


def kotlin_files():
    out = []
    for base, _dirs, files in os.walk(os.path.join(ROOT, "app", "src")):
        for f in files:
            if f.endswith(".kt"):
                out.append(os.path.join(base, f))
    return sorted(out)


def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def strip_kotlin_noise(text):
    """Remove strings and comments so structural scans do not see their innards."""
    out = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if text.startswith('"""', i):
            end = text.find('"""', i + 3)
            i = n if end == -1 else end + 3
            out.append('""')
            continue
        if c == '"':
            i += 1
            while i < n and text[i] != '"':
                i += 2 if text[i] == "\\" else 1
            i += 1
            out.append('""')
            continue
        if c == "'":
            i += 1
            while i < n and text[i] != "'":
                i += 2 if text[i] == "\\" else 1
            i += 1
            out.append("''")
            continue
        if text.startswith("//", i):
            end = text.find("\n", i)
            i = n if end == -1 else end
            continue
        if text.startswith("/*", i):
            end = text.find("*/", i + 2)
            i = n if end == -1 else end + 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


# ---------------------------------------------------------------- 1. resources
resources = defaultdict(set)   # type -> names
xml_files = []
for base, _dirs, files in os.walk(RES):
    for f in sorted(files):
        if f.endswith(".xml"):
            xml_files.append(os.path.join(base, f))

for path in sorted(xml_files):
    rel = os.path.relpath(path, ROOT)
    try:
        tree = ET.parse(path)
    except ET.ParseError as exc:
        check("xml-parses", False, f"{rel}: {exc}")
        continue
    check("xml-parses", True)
    folder = os.path.basename(os.path.dirname(path))
    kind = folder.split("-")[0]
    if kind == "values":
        for node in tree.getroot():
            name = node.get("name")
            if not name:
                continue
            tag = node.tag
            if tag in ("string", "string-array", "plurals"):
                rtype = "string" if tag != "string-array" else "array"
                check("values-unique", name not in resources[rtype],
                      f"{rel}: duplicate <{tag} name=\"{name}\">")
                resources[rtype].add(name)
            elif tag == "color":
                resources["color"].add(name)
            elif tag in ("style",):
                resources["style"].add(name)
            elif tag in ("dimen",):
                resources["dimen"].add(name)
            elif tag in ("bool", "integer"):
                resources[tag].add(name)
    else:
        stem = os.path.splitext(os.path.basename(path))[0]
        if kind == "drawable":
            resources["drawable"].add(stem)
        elif kind == "mipmap":
            resources["mipmap"].add(stem)
        elif kind == "xml":
            resources["xml"].add(stem)
        elif kind in ("layout", "anim", "animator", "raw", "font", "menu", "navigation"):
            resources[kind].add(stem)

check("resources-have-strings", len(resources["string"]) > 0, "no <string> resources found")
check("resources-have-launcher", "ic_launcher" in resources["mipmap"],
      "no ic_launcher mipmap")
check("resources-have-notification-icon", "ic_stat_playback" in resources["drawable"],
      "notification icon missing")

# ------------------------------------------------- 2. R.<type>.<name> in Kotlin
for path in kotlin_files():
    rel = os.path.relpath(path, ROOT)
    text = strip_kotlin_noise(read(path))
    for rtype, name in re.findall(r"(?<![\w.])R\.([a-zA-Z_]+)\.([a-zA-Z0-9_]+)", text):
        pool = resources.get(rtype)
        check(f"R.{rtype}.{name}", pool is not None and name in pool,
              f"{rel}: R.{rtype}.{name} is not defined in res/")

# ------------------------------------------------------ 3. @type/name in XML
for path in xml_files:
    rel = os.path.relpath(path, ROOT)
    try:
        raw = read(path)
    except OSError:
        continue
    for rtype, name in re.findall(r'"@(?:\+?)([a-zA-Z_]+)/([a-zA-Z0-9_.]+)"', raw):
        if rtype in ("id", "android", "style"):
            continue
        # @style references may be framework styles.
        pool = resources.get(rtype)
        check(f"@{rtype}/{name}", pool is not None and name in pool,
              f"{rel}: @{rtype}/{name} is not defined in res/")

# ------------------------------------------------------- 4. package vs folder
for path in kotlin_files():
    rel = os.path.relpath(path, ROOT)
    text = read(path)
    m = re.search(r"^\s*package\s+([\w.]+)", text, re.MULTILINE)
    check("package-declared", m is not None, f"{rel}: no package declaration")
    if not m:
        continue
    declared = m.group(1)
    directory = os.path.dirname(path).split(os.sep + "java" + os.sep)[-1].replace(os.sep, ".")
    check("package-matches-dir", declared == directory,
          f"{rel}: package {declared} but directory {directory}")

# -------------------------------------------------------- 5. delimiter balance
PAIRS = {"{": "}", "(": ")", "[": "]"}
for path in kotlin_files():
    rel = os.path.relpath(path, ROOT)
    text = strip_kotlin_noise(read(path))
    stack = []
    line = 1
    balanced = True
    for ch in text:
        if ch == "\n":
            line += 1
        elif ch in PAIRS:
            stack.append((ch, line))
        elif ch in PAIRS.values():
            if not stack or PAIRS[stack[-1][0]] != ch:
                balanced = False
                break
            stack.pop()
    check("delimiters-balanced", balanced and not stack,
          f"{rel}: unbalanced at line {line}" + (f", unclosed {stack[-3:]}" if stack else ""))

# ------------------------------------------------------------- 6. banned APIs
BANNED = {
    r"toStringAsFixed": "JavaScript API, not Kotlin",
    r"toStringToFixed": "JavaScript API, not Kotlin",
    r"\.rootPath\b": "File has no rootPath member",
    r"\.parentPath\b": "File has no parentPath member",
    r"android\.content\.ApplicationContext": "no such class",
    r"androidx\.compose\.ui\.file": "no such package",
    r"PointerInputModifier": "internal Compose type",
    r"CardElevation\.Level": "not a Compose API",
    r"Icons\.False\.": "not a Compose API",
    r"MediaSessionCompat\.FLAG_ENABLE_KEY_CONTROL": "no such constant",
    r"exitPictureInPictureMode\(\)": "no such Activity method",
    r"setPictureInPictureRotation": "no such API",
    r"setMediaDescription": "no such PiP API",
    r"ComponentActivityCallback": "no such class",
    r"setCallbackProxy": "no such Window method",
    r"androidx\.lifecycle\.OnGoingNotification": "no such class",
    r"SliderStyle": "not a Material 3 type",
    r"MaterialTheme\.menu": "not a Material 3 API",
    r"typography\.h6\b": "Material 2 name; this project uses Material 3",
    r"typography\.body1\b": "Material 2 name; this project uses Material 3",
    r"Room\.deleteDatabase": "no such static; use Context.deleteDatabase",
    r"\bimport java\.lang\.Long\b": "boxed Java type imported into Kotlin",
    r"^\s*def\s": "not Kotlin syntax",
    r"addTextOutput": "does not exist in Media3 1.3.1",
    r"Cue\.startTimeMs|Cue\.startTimeUs": "Cue has no timing fields in 1.3.1",
    r"media3-exoplayer-ijk": "artifact does not exist",
    r"accompanist": "artifact does not exist",
    r"java\.util\.JsonObject": "no such class; org.json.JSONObject is the Android one",
    r"\.appendLine\(\"\"\)": "empty appendLine",
    r"Dispatchers\.Default\.launch": "Dispatchers is not a CoroutineScope",
    r"runBlocking": None,  # reported separately, only banned in suspend bodies
}

for path in kotlin_files():
    rel = os.path.relpath(path, ROOT)
    text = strip_kotlin_noise(read(path))
    for line_no, line in enumerate(text.splitlines(), 1):
        for pattern, reason in BANNED.items():
            if reason is None:
                continue
            if re.search(pattern, line):
                check(f"banned-api {pattern}", False, f"{rel}:{line_no} {reason}")

# runBlocking inside a suspend function is a real deadlock risk.
for path in kotlin_files():
    rel = os.path.relpath(path, ROOT)
    if os.sep + "test" + os.sep in path or os.sep + "androidTest" + os.sep in path:
        continue
    text = read(path)
    if "runBlocking" in text and re.search(r"\bsuspend fun\b", text):
        for line_no, line in enumerate(text.splitlines(), 1):
            if "runBlocking" in line and "Application" not in rel:
                check("no-runBlocking-in-suspend", False, f"{rel}:{line_no}")

# ------------------------------------------------ 7. duplicate top-level decls
DECL = re.compile(
    r"^(?:@[\w.]+(?:\([^)]*\))?\s*)*(?:public |internal |private |abstract |open |sealed |data |enum |annotation |value )*\s*"
    r"(class|interface|object|enum class|data class|sealed class)\s+([A-Za-z_]\w*)",
    re.MULTILINE,
)
by_package = defaultdict(list)
for path in kotlin_files():
    text = strip_kotlin_noise(read(path))
    m = re.search(r"^\s*package\s+([\w.]+)", text, re.MULTILINE)
    if not m:
        continue
    for _kw, name in DECL.findall(text):
        by_package[(m.group(1), name)].append(os.path.relpath(path, ROOT))

for (pkg, name), paths in sorted(by_package.items()):
    if len(paths) > 1:
        check("no-duplicate-declarations", False,
              f"{pkg}.{name} declared in {paths}")
    else:
        check("no-duplicate-declarations", True)

# --------------------------------------------------------- 8. Room SQL columns
ENTITY_DIR = os.path.join(SRC, PKG_PREFIX.replace(".", os.sep), "database")
declared_columns = set()
declared_tables = set()
for path in kotlin_files():
    if os.path.dirname(path) != ENTITY_DIR:
        continue
    text = read(path)
    for m in re.finditer(r'@ColumnInfo\(\s*name\s*=\s*"([^"]+)"', text):
        declared_columns.add(m.group(1))
    for m in re.finditer(r'@Entity\(\s*tableName\s*=\s*"([^"]+)"', text):
        declared_tables.add(m.group(1))

for path in kotlin_files():
    if os.path.dirname(path) != ENTITY_DIR:
        continue
    rel = os.path.relpath(path, ROOT)
    text = read(path)
    for query in re.findall(r'@Query\(\s*"""?(.*?)"\s*\)', text, re.DOTALL):
        for m in re.finditer(r'\b(?:SELECT|UPDATE|WHERE|SET|ORDER BY|ON|AND|OR|,)\s+([a-z_][a-z0-9_]*)\s*(?:=|,|\bFROM\b|\bWHERE\b|$)', query, re.IGNORECASE):
            col = m.group(1)
            if col.lower() in ("select", "from", "where", "set", "and", "or", "by",
                               "exists", "count", "avg", "sum", "max", "min", "as",
                               "order", "desc", "asc", "not", "null", "limit", "on",
                               "delete", "insert", "into", "values", "update", "group",
                               "distinct", "in", "like", "is", "case", "when", "then",
                               "else", "end", "join", "left", "inner", "outer"):
                continue
            check(f"column {col} declared", col in declared_columns,
                  f"{rel}: @Query references column '{col}' with no @ColumnInfo")

# ------------------------------------------------------- 9. manifest classes
manifest = read(MANIFEST)
app_pkg = re.search(r'<manifest[^>]*package="([^"]+)"', manifest)
for cls in re.findall(r'android:name="([\w.]+)"', manifest):
    if not cls.startswith(PKG_PREFIX):
        continue
    expected = os.path.join(SRC, cls.replace(".", os.sep) + ".kt")
    exists = os.path.exists(expected)
    if not exists:
        # The class may live in a file with a different name.
        exists = any(
            re.search(r"\b(?:class|interface|object)\s+" + re.escape(cls.split(".")[-1]) + r"\b",
                      strip_kotlin_noise(read(p)))
            for p in kotlin_files()
        )
    check(f"manifest class {cls}", exists, f"declared in AndroidManifest.xml but not found in sources")

# ------------------------------------------- 10. internal project types exist
declared_names = set()
for path in kotlin_files():
    text = strip_kotlin_noise(read(path))
    for _kw, name in DECL.findall(text):
        declared_names.add(name)
    for m in re.finditer(r"^\s*(?:public |internal |private |inline |suspend )*fun\s+(?:<[^>]*>\s*)?([A-Za-z_]\w*)", text, re.MULTILINE):
        declared_names.add(m.group(1))

project_imports = defaultdict(set)
for path in kotlin_files():
    text = read(path)
    for imp in re.findall(r"^\s*import\s+(com\.quantum\.player\.[\w.]+)", text, re.MULTILINE):
        project_imports[imp.split(".")[-1]].add(os.path.relpath(path, ROOT))

for name, users in sorted(project_imports.items()):
    if name == "R":
        continue  # generated by the Android Gradle Plugin
    check(f"project type {name} exists", name in declared_names,
          f"imported by {sorted(users)} but never declared")


# ------------------------------------------- 11. PlaybackEngine contract check
# The UI must only touch members the abstraction actually declares. This is the
# project's central architectural promise, so it is checked explicitly.
ENGINE = os.path.join(SRC, PKG_PREFIX.replace(".", os.sep), "core", "PlaybackEngine.kt")
engine_text = strip_kotlin_noise(read(ENGINE))
engine_members = set()
for m in re.finditer(
    r"^\s*(?:suspend\s+)?(?:val|var)\s+([A-Za-z_]\w*)",
    engine_text, re.MULTILINE):
    engine_members.add(m.group(1))
for m in re.finditer(
    r"^\s*(?:suspend\s+)?fun\s+([A-Za-z_]\w*)",
    engine_text, re.MULTILINE):
    engine_members.add(m.group(1))
# Members inherited from nothing else; anything the UI may legitimately call.
check("engine-has-core-api", {"play", "pause", "stop", "release", "seekTo", "stateFlow",
                              "positionFlow", "errorFlow", "cuesFlow"} <= engine_members,
      f"PlaybackEngine is missing {sorted({'play','pause','stop','release','seekTo','stateFlow','positionFlow','errorFlow','cuesFlow'} - engine_members)}")

for path in kotlin_files():
    if os.sep + "test" + os.sep in path:
        continue
    rel = os.path.relpath(path, ROOT)
    text = strip_kotlin_noise(read(path))
    # Only files whose `engine` identifier is statically the abstraction are
    # held to it. QuantumApplication holds the concrete PlaybackManager and is
    # allowed to call lifecycle-only members such as shutdown().
    typed_as_engine = re.search(r"\bengine\s*:\s*PlaybackEngine\b", text)
    if not typed_as_engine:
        continue
    for member in set(re.findall(r"\bengine\.([A-Za-z_]\w*)", text)):
        check(f"engine.{member} declared", member in engine_members,
              f"{rel}: uses engine.{member}, which PlaybackEngine does not declare")


# --------------------------------------- 12. named arguments match declarations
# Collect every top-level / member function's parameter names, then verify that
# every named argument used at a call site actually exists. This catches the
# signature drift that is most likely when several files are rewritten together.
def parameter_names(text, fun_name):
    idx = text.find("fun " + fun_name + "(")
    if idx < 0:
        return None
    open_paren = text.index("(", idx)
    depth = 0
    end = -1
    for k in range(open_paren, len(text)):
        ch = text[k]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
            if ch == ")" and depth == 0:
                end = k
                break
    if end < 0:
        return None
    body = text[open_paren + 1:end]
    parts, depth, cur = [], 0, ""
    for ch in body:
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append(cur)
            cur = ""
        else:
            cur += ch
    parts.append(cur)
    return {p.strip().split(":")[0].split("=")[0].strip() for p in parts if p.strip()}

function_params = {}
for path in kotlin_files():
    text = strip_kotlin_noise(read(path))
    for m in re.finditer(r"\bfun\s+(?:<[^>]*>\s*)?([A-Z][A-Za-z0-9_]*)\s*\(", text):
        names = parameter_names(text, m.group(1))
        if names is not None:
            function_params.setdefault(m.group(1), set()).update(names)

for path in kotlin_files():
    rel = os.path.relpath(path, ROOT)
    text = strip_kotlin_noise(read(path))
    for call in re.finditer(r"\b([A-Z][A-Za-z0-9_]*)\s*\(", text):
        fn = call.group(1)
        if fn not in function_params:
            continue
        start = text.index("(", call.start())
        depth = 0
        end = -1
        for k in range(start, len(text)):
            ch = text[k]
            if ch in "([{":
                depth += 1
            elif ch in ")]}":
                depth -= 1
                if ch == ")" and depth == 0:
                    end = k
                    break
        if end < 0:
            continue
        body = text[start + 1:end]
        # Named arguments look like `name =` at argument depth.
        parts, depth, cur = [], 0, ""
        for ch in body:
            if ch in "([{":
                depth += 1
            elif ch in ")]}":
                depth -= 1
            if ch == "," and depth == 0:
                parts.append(cur)
                cur = ""
            else:
                cur += ch
        parts.append(cur)
        for part in parts:
            m = re.match(r"\s*([A-Za-z_]\w*)\s*=(?!=)", part)
            if not m:
                continue
            check(f"argument {fn}({m.group(1)})", m.group(1) in function_params[fn],
                  f"{rel}: {fn}() called with unknown named argument '{m.group(1)}'")


# ------------------------------------- 13. the UI layer must not see Media3
# The architecture's central promise: only `core` may know about the backend.
LAYERED = ("ui", "browser", "pip", "player", "subtitles", "playback", "model",
           "database", "silence", "error")
for path in kotlin_files():
    rel = os.path.relpath(path, ROOT)
    parts = rel.replace("\\", "/").split("/")
    if "com/quantum/player" not in rel:
        continue
    pkg = parts[parts.index("player") + 1]
    if pkg not in LAYERED:
        continue
    text = read(path)
    for line_no, line in enumerate(text.splitlines(), 1):
        stripped = line.strip()
        if stripped.startswith("//") or stripped.startswith("*"):
            continue
        check("ui-layer-free-of-media3", "import androidx.media3" not in line,
              f"{rel}:{line_no} imports androidx.media3 into the {pkg} layer")

# ---------------------------------------------------------------------- report
print("=" * 72)
print("Quantum static verification")
print("=" * 72)
print(f"Kotlin sources scanned : {len(kotlin_files())}")
print(f"XML resources scanned  : {len(xml_files)}")
print(f"String resources       : {len(resources['string'])}")
print(f"Checks executed        : {checks_run}")
print(f"Failures               : {len(failures)}")
if failures:
    print("-" * 72)
    for f in failures:
        print("FAIL  " + f)
    print("-" * 72)
    sys.exit(1)
print("All static checks passed.")
