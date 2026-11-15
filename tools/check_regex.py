"""Android compiles Kotlin Regex with ICU, which is stricter than the JVM (e.g. an unescaped `}` is an error).
Extracts every Regex("...") literal from the sources and prints them one per line for tools/icu_regex_check."""
import re, sys, pathlib
for f in pathlib.Path(sys.argv[1]).rglob("*.kt"):
    src = f.read_text(encoding="utf-8")
    for m in re.finditer(r'Regex\(\s*"((?:[^"\\]|\\.)*)"(?:\s*\+\s*"((?:[^"\\]|\\.)*)")?', src):
        s = m.group(1) + (m.group(2) or "")
        print(s.replace("\\\\", "\0").replace("\\$", "$").replace("\0", "\\"))
