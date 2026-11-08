#!/usr/bin/env python3
"""从本地 Flipper-IRDB 克隆生成内置红外码表（assets/ir/ir-codes.json）。

只收 parsed 且协议在 IrProtocols.SUPPORTED 内的条目；按键名映射到规范名，
映射不到的转成 ASCII 自定义键。raw 文件（小米电视/盒子、格力/科龙/志高等空调）
因私有协议与转录风险暂不内置。
"""
import json, os, re

ROOT = os.path.expanduser("~/Desktop/code/openSource/Flipper-IRDB")
ASSET = os.path.join(os.path.dirname(__file__), "..", "app/src/main/assets/ir/ir-codes.json")

NAME_MAP = {
    "power": "power", "onoff": "power", "powa": "power",
    "mute": "mute",
    "volup": "vol_up", "volumeup": "vol_up", "voldn": "vol_down", "volumedown": "vol_down",
    "chup": "ch_up", "chnext": "ch_up", "channelup": "ch_up", "chplus": "ch_up",
    "chdown": "ch_down", "chprev": "ch_down", "channeldown": "ch_down", "chminus": "ch_down",
    "up": "up", "down": "down", "left": "left", "right": "right",
    "ok": "ok", "enter": "ok", "select": "ok",
    "menu": "menu", "settings": "menu", "option": "menu", "options": "menu",
    "back": "back", "return": "back",
    "exit": "exit",
    "input": "input", "source": "input", "tvsource": "input", "hdmi": "input", "antena": "input",
    "info": "info", "display": "info",
    "guide": "guide", "epg": "guide", "blueepg": "blue",
    "play": "play", "playpause": "play", "pauseplay": "play", "pause": "pause", "stop": "stop",
    "next": "next", "prev": "prev", "previous": "prev",
    "ff": "ff", "fastforward": "ff", "fastfo": "ff", "fwd": "ff",
    "rew": "rew", "rewind": "rew", "reverse": "rew", "fastba": "rew", "rev": "rew",
    "home": "home", "smarthub": "home",
    "sleep": "sleep",
    "red": "red", "green": "green", "yellow": "yellow", "blue": "blue",
    "0": "0", "1": "1", "2": "2", "3": "3", "4": "4",
    "5": "5", "6": "6", "7": "7", "8": "8", "9": "9",
}

PROTOCOL_MAP = {
    "NEC": "nec1", "NECext": "necx2", "NECx": "necx2", "NECx2": "necx2",
    "SIRC": "sony12", "SIRC15": "sony15", "SIRC20": "sony20",
    "RC5": "rc5", "RC5X": "rc5",
}

RAW_SPECIAL = {"temp+": "temp_up", "temp-": "temp_down", "temp up": "temp_up", "temp down": "temp_down"}

def norm_name(raw):
    special = RAW_SPECIAL.get(raw.lower().strip())
    if special:
        return special
    key = re.sub(r"[^a-z0-9]", "", raw.lower())
    return NAME_MAP.get(key) or re.sub(r"[^a-z0-9]+", "_", raw.lower()).strip("_")[:32] or "custom"

def parse_ir(path):
    entries, cur = [], {}
    for line in open(path, encoding="utf-8", errors="replace"):
        line = line.strip()
        if line.startswith("#") or not line:
            continue
        k, _, v = line.partition(":")
        k, v = k.strip().lower(), v.strip()
        if k == "name":
            if cur.get("name"): entries.append(cur)
            cur = {"name": v}
        elif k in ("type", "protocol"):
            cur[k] = v
        elif k in ("address", "command"):
            cur[k] = [int(x, 16) for x in re.split(r"[ ,]+", v) if x]
    if cur.get("name"): entries.append(cur)
    return entries

def collect(rel_paths, protocol, addr_bytes, carrier):
    """收集指定文件里协议+地址匹配的 parsed 按键 -> {canonical: function}。"""
    out, conflicts = {}, []
    for rel in rel_paths:
        path = os.path.join(ROOT, rel)
        if not os.path.exists(path):
            raise FileNotFoundError(path)
        for e in parse_ir(path):
            if e.get("type") != "parsed" or e.get("protocol") != protocol:
                continue
            if e.get("address", [])[:len(addr_bytes)] != addr_bytes:
                continue
            fn = e.get("command", [None])[0]
            if fn is None:
                continue
            name = norm_name(e["name"])
            if name in out:
                # 文件顺序即优先级：先到先得，冲突记录后跳过（不同代际遥控同址不同键）。
                if out[name] != fn:
                    conflicts.append(f"{name}:{out[name]}!={fn}")
                continue
            out[name] = fn
    return out, conflicts

def check(protocol, device, subdevice, commands):
    """按 IrProtocols 的约束做范围校验。"""
    for name, fn in commands.items():
        if protocol in ("nec1",):
            assert 0 <= device <= 255 and 0 <= fn <= 255, (protocol, device, fn, name)
        elif protocol in ("necx2",):
            assert 0 <= device <= 255 and 0 <= subdevice <= 255 and 0 <= fn <= 255, (protocol, fn, name)
        elif protocol in ("sony12",):
            assert 0 <= fn <= 127 and 0 <= device <= 31, (protocol, fn, name)
        elif protocol == "rc5":
            assert 0 <= device <= 31 and 0 <= fn <= 127, (protocol, fn, name)

def device(protocol, dev_id, aliases, device_no, subdevice_no, carrier, source, commands):
    return {
        "id": dev_id, "aliases": aliases, "protocol": protocol,
        "device": device_no, "subdevice": subdevice_no, "carrier_hz": carrier,
        "source": source, "commands": dict(sorted(commands.items(), key=lambda kv: availability(kv[0]))),
    }

AVAIL = ["power", "power_on", "power_off", "vol_up", "vol_down", "mute", "ch_up", "ch_down",
         "up", "down", "left", "right", "ok", "back", "exit", "menu", "home", "input",
         "info", "guide", "play", "pause", "stop", "next", "prev", "rew", "ff", "sleep"]
def availability(name):
    try: return AVAIL.index(name)
    except ValueError: return len(AVAIL)

def build():
    brands = {}

    # 海尔：电视（NEC/4）+ 空调（NEC/32，部分型号）
    haier_tv, c = collect(["TVs/Haier/Haier_L42C1180.ir"], "NEC", [4], 38000)
    print("haier 冲突:", c)
    brands["haier"] = {"id": "haier", "name": "Haier", "aliases": ["haier", "海尔"], "devices": [
        device("nec1", "tv", ["tv", "电视", "电视机", "television"], 4, -1, 38000,
               "TVs/Haier/Haier_L42C1180.ir", haier_tv),
    ]}

    # 东芝：老遥控 NEC/64 + FireTV 世代 NECext 02 7D
    tosh_tv, c1 = collect(["TVs/Toshiba/Toshiba CT 8560.ir",
                           "TVs/Toshiba/Toshiba_CT-32F2.ir", "TVs/Toshiba/Toshiba_32AV502U.ir"], "NEC", [64], 38000)
    tosh_smart, c2 = collect(["TVs/Toshiba/Toshiba_50C350LC.ir", "TVs/Toshiba/Toshiba_43LF421U21.ir",
                              "TVs/Toshiba/Toshiba_FireTV.ir", "TVs/Toshiba/toshiba_firetv_v2.ir"], "NECext", [2, 125], 38000)
    print("toshiba 冲突:", c1, c2)
    brands["toshiba"] = {"id": "toshiba", "name": "Toshiba", "aliases": ["toshiba", "东芝"], "devices": [
        device("nec1", "tv", ["tv", "电视", "电视机", "television"], 64, -1, 38000,
               "TVs/Toshiba/Toshiba CT 8560.ir + Toshiba_CT-32F2.ir + Toshiba_32AV502U.ir", tosh_tv),
        device("necx2", "smart_tv", ["smarttv", "firetv", "智能电视"], 2, 125, 38000,
               "TVs/Toshiba/Toshiba_50C350LC.ir + Toshiba_43LF421U21.ir", tosh_smart),
    ]}

    # TCL：Roku TV 世代 NECext EA C7（32S327/50S423；RCA 协议机型不收）
    tcl_tv, c = collect(["TVs/TCL/TCL_32S327.ir", "TVs/TCL/TCL_50S423.ir"], "NECext", [234, 199], 38000)
    print("tcl 冲突:", c)
    brands["tcl"] = {"id": "tcl", "name": "TCL", "aliases": ["tcl", "汤姆逊"], "devices": [
        device("necx2", "tv", ["tv", "电视", "电视机", "television"], 234, 199, 38000,
               "TVs/TCL/TCL_32S327.ir + TCL_50S423.ir（Roku TV 世代；RCA 协议机型未收录）", tcl_tv),
    ]}

    # 美的：部分分体空调遥控为 NECext 01 FF（parsed）
    midea_ac, c = collect(["ACs/Midea/Midea_MP12SVKBA3RCM.ir", "ACs/Midea/Midea_WWK08CR81N.ir"], "NECext", [1, 255], 38000)
    print("midea 冲突:", c)
    brands["midea"] = {"id": "midea", "name": "Midea", "aliases": ["midea", "美的"], "devices": [
        device("necx2", "ac", ["ac", "空调", "airconditioner"], 1, 255, 38000,
               "ACs/Midea/Midea_MP12SVKBA3RCM.ir + Midea_WWK08CR81N.ir（多数美的空调遥控为 raw，仅此两款 parsed）", midea_ac),
    ]}

    # 万播投影：NEC/59
    wanbo, c = collect(["Projectors/Wanbo/Wanbo_T6_Max.ir"], "NEC", [59], 38000)
    print("wanbo 冲突:", c)
    brands["wanbo"] = {"id": "wanbo", "name": "Wanbo", "aliases": ["wanbo", "万播"], "devices": [
        device("nec1", "projector", ["projector", "投影", "投影仪"], 59, -1, 38000,
               "Projectors/Wanbo/Wanbo_T6_Max.ir", wanbo),
    ]}

    # 海信：并入本地两个文件的补充键（home/字幕/频道列表/文本/Netflix 等）
    hisense_extra, c = collect(["TVs/Hisense/Hisense_32A4HAU.ir", "TVs/Hisense/Hisense_55E7KQ.ir"],
                               "NECext", [0, 191], 38000)
    print("hisense 冲突:", c)
    brands["_hisense_extra"] = hisense_extra

    return brands, brands.pop("_hisense_extra")

def main():
    new_brands, hisense_extra = build()
    asset = json.load(open(ASSET, encoding="utf-8"))
    by_id = {b["id"]: b for b in asset["brands"]}

    # 海信补充键：只加内置没有的
    hisense = by_id["hisense"]["devices"][0]
    added = []
    for name, fn in hisense_extra.items():
        if name not in hisense["commands"]:
            hisense["commands"][name] = fn
            added.append(name)
    hisense["commands"] = dict(sorted(hisense["commands"].items(), key=lambda kv: availability(kv[0])))
    if added and "补充键来自" not in hisense["source"]:
        hisense["source"] += "；补充键来自 TVs/Hisense/Hisense_32A4HAU.ir + Hisense_55E7KQ.ir（同地址 00 BF）: " + ",".join(added)

    for bid, brand in new_brands.items():
        if bid in by_id:
            by_id[bid]["devices"] = brand["devices"]  # 幂等：重复生成时整体替换
        else:
            asset["brands"].append(brand)

    asset["brands"].sort(key=lambda b: b["id"])
    total = sum(len(d["commands"]) for b in asset["brands"] for d in b["devices"])
    asset["note"] = ("条目只存协议参数（protocol/device/subdevice/function），由 IrProtocols 现场编译成波形；"
        "数据取自 Flipper-IRDB（CC0，无署名义务）的 parsed .ir 文件，来源见 brands[].source。"
        f"当前 {len(asset['brands'])} 个品牌 {sum(len(b['devices']) for b in asset['brands'])} 个设备 {total} 键。"
        "raw 私有协议（小米电视/盒子、格力/科龙/志高/TCL 空调等）与未实现协议（RC6/RCA/Kaseikyo）暂不收录；"
        "同型号遥控器可能有差异，用 ir_code_save 覆盖。")
    with open(ASSET, "w", encoding="utf-8") as f:
        json.dump(asset, f, ensure_ascii=False, indent=2)
        f.write("\n")
    for b in asset["brands"]:
        for d in b["devices"]:
            print(f"{b['id']}/{d['id']}: {len(d['commands'])} 键 [{d['protocol']} dev={d['device']} sub={d['subdevice']}]")

if __name__ == "__main__":
    main()
