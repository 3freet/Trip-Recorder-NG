"""Appends English/Arabic pairs to assets/ar.tsv (keys and values are escaped: backslash, newline, tab)."""
import os
TSV = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "assets", "ar.tsv")

def esc(s):
    return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")

def add(pairs):
    existing = set()
    if os.path.exists(TSV):
        for line in open(TSV, encoding="utf-8"):
            if "\t" in line and not line.startswith("#"):
                existing.add(line.split("\t", 1)[0])
    with open(TSV, "a", encoding="utf-8") as f:
        for en, ar in pairs:
            if esc(en) in existing:
                print("already there:", en[:50]); continue
            f.write(esc(en) + "\t" + esc(ar) + "\n")
            existing.add(esc(en))
