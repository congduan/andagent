#!/usr/bin/env python3
"""扫描 Flipper-IRDB .ir 文件：统计 parsed 协议/地址与 raw 段，辅助挑选内置码表数据。"""
import os, sys, json, re
from collections import defaultdict

ROOT = os.path.expanduser("~/Desktop/code/openSource/Flipper-IRDB")
TARGETS = sys.argv[1:] or [
    "TVs/Haier", "TVs/Xiaomi", "TVs/TCL", "TVs/Hisense", "TVs/Toshiba",
    "ACs/Chigo", "ACs/GREE", "ACs/Haier", "ACs/Hisense", "ACs/Kelon",
    "ACs/Midea", "ACs/TCL", "ACs/Xiaomi",
    "Streaming_Devices/Xiaomi", "Projectors/Wanbo",
]

def parse_ir(path):
    entries, cur = [], {}
    raw = None
    for line in open(path, encoding="utf-8", errors="replace"):
        line = line.strip()
        if line.startswith("#") or not line:
            continue
        k, _, v = line.partition(":")
        k, v = k.strip().lower(), v.strip()
        if k == "name":
            if cur.get("name"): entries.append(cur)
            cur = {"name": v}
        elif k == "type":
            cur["type"] = v
        elif k == "protocol":
            cur["protocol"] = v
        elif k == "address":
            cur["address"] = v
        elif k == "command":
            cur["command"] = v
        elif k == "frequency":
            cur["frequency"] = v
        elif k == "data":
            cur.setdefault("raw_frames", 0)
            cur["raw_frames"] += 1
            cur["raw_items"] = cur.get("raw_items", 0) + len(v.split())
            cur["raw_head"] = cur.get("raw_head") or v.split()[:8]
    if cur.get("name"): entries.append(cur)
    return entries

def parse_hex(b32):
    """'04 00 00 00' -> [4,0,0,0]"""
    parts = [x for x in re.split(r"[ ,]+", b32 or "") if x]
    return [int(p, 16) for p in parts]

for target in TARGETS:
    d = os.path.join(ROOT, target)
    print(f"\n== {target} ==")
    if not os.path.isdir(d):
        print("  (不存在)"); continue
    for fn in sorted(os.listdir(d)):
        if not fn.lower().endswith(".ir"): continue
        path = os.path.join(d, fn)
        try: entries = parse_ir(path)
        except Exception as e:
            print(f"  {fn}: 解析失败 {e}"); continue
        parsed = defaultdict(list)
        raw = []
        for e in entries:
            if e.get("type") == "parsed":
                a, c = parse_hex(e.get("address")), parse_hex(e.get("command"))
                key = (e.get("protocol"), tuple(a), tuple(c))
                parsed[key].append(e["name"])
            else:
                raw.append((e["name"], e.get("frequency"), e.get("raw_frames"), e.get("raw_items")))
        if parsed:
            print(f"  {fn}: parsed {sum(len(v) for v in parsed.values())} 键")
            for (proto, a, c), names in sorted(parsed.items()):
                print(f"     {proto} addr={list(a)} cmd={list(c)} -> {names[:6]}{'...' if len(names)>6 else ''}")
        if raw:
            print(f"  {fn}: raw {len(raw)} 段")
            for name, freq, frames, items in raw[:6]:
                print(f"     {name} f={freq} 帧×{frames} 项/帧≈{items//max(frames,1)}")
            if len(raw) > 6: print(f"     ...共 {len(raw)} 段")
