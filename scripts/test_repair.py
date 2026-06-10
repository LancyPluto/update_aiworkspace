import json

title = json.load(open("scripts/_api_wf2.json", encoding="utf-8"))[1]["data"]["title"]
lines = [repr(title), str([hex(ord(c)) for c in title])]
try:
    repaired = title.encode("latin-1").decode("utf-8")
    lines.append("latin1 repair: " + repaired)
except Exception as exc:
    lines.append("latin1 repair failed: " + str(exc))

try:
    repaired2 = bytes(ord(c) & 0xFF for c in title).decode("utf-8")
    lines.append("mask repair: " + repaired2)
except Exception as exc:
    lines.append("mask repair failed: " + str(exc))

open("scripts/_repair_test.txt", "w", encoding="utf-8").write("\n".join(lines))
