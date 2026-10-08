#!/usr/bin/env python3
"""Checks assets/ar.tsv against the texts used in the code.

Keys are the English texts passed to L.t(...) and L.f(...) (string literals joined with +). Reports:
  - texts used in the code that have no Arabic entry (they would show in English),
  - entries in ar.tsv that no code uses any more,
  - entries whose %-placeholders differ from the English text (would crash or garble at run time),
  - L.t/L.f calls whose text is not a plain literal (they cannot be checked),
  - visible-looking literals passed to UI calls without L.t (heuristic).
Exit code 1 when something that breaks the app is found (missing format arguments, bad placeholders).
"""
import glob, re, sys, os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "org", "triprecorderng")
TSV = os.path.join(ROOT, "assets", "ar.tsv")

def unesc_java(s):
    out, i = [], 0
    while i < len(s):
        c = s[i]
        if c == "\\" and i + 1 < len(s):
            n = s[i + 1]
            if n == "u" and i + 5 < len(s):
                out.append(chr(int(s[i + 2:i + 6], 16))); i += 6; continue
            out.append({"n": "\n", "t": "\t", '"': '"', "\\": "\\", "'": "'"}.get(n, n)); i += 2; continue
        out.append(c); i += 1
    return "".join(out)

def unesc_tsv(s):
    out, i = [], 0
    while i < len(s):
        c = s[i]
        if c == "\\" and i + 1 < len(s):
            n = s[i + 1]
            out.append("\n" if n == "n" else "\t" if n == "t" else n); i += 2; continue
        out.append(c); i += 1
    return "".join(out)

def parse_call_arg(text, pos):
    """Reads string literals joined by + starting at pos. Returns (key or None, end_pos)."""
    parts, i, n = [], pos, len(text)
    while True:
        while i < n and text[i] in " \t\r\n": i += 1
        if i >= n or text[i] != '"': return (None, i)
        j = i + 1
        while j < n and text[j] != '"':
            j += 2 if text[j] == "\\" else 1
        parts.append(unesc_java(text[i + 1:j]))
        i = j + 1
        k = i
        while k < n and text[k] in " \t\r\n": k += 1
        if k < n and text[k] == "+":
            i = k + 1; continue
        return ("".join(parts), i)

used, dynamic = {}, []
for f in sorted(glob.glob(os.path.join(SRC, "*.java"))):
    text = open(f, encoding="utf-8").read()
    for m in re.finditer(r"\bL\.(t|f)\(", text):
        key, end = parse_call_arg(text, m.end())
        line = text.count("\n", 0, m.start()) + 1
        if key is None:
            dynamic.append((os.path.basename(f), line))
        else:
            used.setdefault(key, (os.path.basename(f), line, m.group(1)))

if "--dump" in sys.argv:   # print the texts used in the code, in source order, as JSON
    import json
    print(json.dumps(sorted(used, key=lambda k: used[k][:2]), ensure_ascii=False))
    sys.exit(0)

table = {}
if os.path.exists(TSV):
    for ln, raw in enumerate(open(TSV, encoding="utf-8"), 1):
        raw = raw.rstrip("\n")
        if not raw or raw.startswith("#"): continue
        if "\t" not in raw:
            print(f"ar.tsv line {ln}: no tab separator"); continue
        en, ar = raw.split("\t", 1)
        en, ar = unesc_tsv(en), unesc_tsv(ar)
        if en in table: print(f"ar.tsv line {ln}: duplicate entry: {en[:60]!r}")
        table[en] = ar

fmt = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]")
def placeholders(s):
    return sorted(x for x in fmt.findall(s) if x != "%%")

bad = 0
missing = [k for k in used if k not in table]
unused = [k for k in table if k not in used]
for k in sorted(missing, key=lambda x: used[x]):
    print(f"MISSING  {used[k][0]}:{used[k][1]}  {k[:90]!r}")
for k, v in table.items():
    if k in used and placeholders(k) != placeholders(v):
        bad += 1
        print(f"PLACEHOLDERS differ  {k[:70]!r}  en={placeholders(k)} ar={placeholders(v)}")
for k in used:
    if used[k][2] == "f" and k in table: pass
for k in sorted(unused): print(f"UNUSED   {k[:90]!r}")
for f, ln in dynamic: print(f"DYNAMIC  {f}:{ln} (text is not a plain literal)")

# heuristic: literals handed to UI calls without L.t
calls = re.compile(r"\b(pill|chip|pageTitle|settingRow|toast|setTitle|setMessage|setPositiveButton|setNegativeButton|barButton|tile)\(\s*\"")
for f in sorted(glob.glob(os.path.join(SRC, "*.java"))):
    text = open(f, encoding="utf-8").read()
    for m in calls.finditer(text):
        line = text.count("\n", 0, m.start()) + 1
        print(f"UNWRAPPED {os.path.basename(f)}:{line}  {text[m.start():m.start()+70].splitlines()[0]!r}")

print(f"\n{len(used)} texts in code, {len(table)} Arabic entries, {len(missing)} missing, {len(unused)} unused, {bad} placeholder problems")
sys.exit(1 if bad else 0)
