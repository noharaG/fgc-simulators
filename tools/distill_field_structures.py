#!/usr/bin/env python3
"""
field_geometry_cadquery.json（387部品の精密AABB）を、主要構造物ごとに集約して
FieldLayout/FieldBuilder に使える「構造物単位の footprint（中心XZ・サイズ・高さ）」へ蒸留する。

グループ判定は部品名トークンで行う:
  RISER / GUARD_RAIL / *_ALLIANCE_TAPE / *_FIELD_TAPE
  FG26_F_02_CENTER_GOAL      → EXTINGUISHER（中央・上辺）
  FG26_F_03_SIDE_GOAL_RED/BLUE → SUPPRESSION 赤/青
  FG26_F_05_HUMAN_PLAYER_STATION _RED/_BLUE → FIRE SHIELD 赤/青
  GAMEPIECE A                → 球（CADサンプル位置）
出力: Assets/FGC/Settings/field_structures.json（構造物→{center_xz, size, y_min, y_max}）
"""
import json
import os

HERE = os.path.dirname(__file__)
IN_JSON = os.path.join(HERE, "..", "Assets", "FGC", "Settings", "field_geometry_cadquery.json")
OUT_JSON = os.path.join(HERE, "..", "Assets", "FGC", "Settings", "field_structures.json")


def group_of(name):
    n = name.upper()
    if "CENTER_GOAL" in n: return "EXTINGUISHER"
    if "SIDE_GOAL_RED" in n: return "SUPPRESSION_RED"
    if "SIDE_GOAL_BLUE" in n: return "SUPPRESSION_BLUE"
    if "HUMAN_PLAYER_STATION _RED" in n or "HUMAN_PLAYER_STATION_RED" in n: return "FIRE_SHIELD_RED"
    if "HUMAN_PLAYER_STATION_BLUE" in n or "HUMAN_PLAYER_STATION _BLUE" in n: return "FIRE_SHIELD_BLUE"
    if "GUARD_RAIL" in n: return "GUARD_RAIL"
    if n.endswith("/RISER") or n == "FG26_F/RISER": return "RISER"
    if "RED_ALLIANCE_TAPE" in n: return "ALLIANCE_TAPE_RED"
    if "BLUE_ALLIANCE_TAPE" in n: return "ALLIANCE_TAPE_BLUE"
    if "RED_FIELD_TAPE" in n: return "FIELD_TAPE_RED"
    if "BLUE_FIELD_TAPE" in n: return "FIELD_TAPE_BLUE"
    if "GAMEPIECE" in n: return "GAMEPIECE"
    return None  # ネジ等の細部は無視


def main():
    with open(IN_JSON, encoding="utf-8") as f:
        data = json.load(f)

    groups = {}
    for p in data["parts"]:
        g = group_of(p["name"])
        if g is None:
            continue
        mn = p["aabb_m"]["min"]; mx = p["aabb_m"]["max"]
        if g not in groups:
            groups[g] = [list(mn), list(mx), 0]
        acc = groups[g]
        for i in range(3):
            acc[0][i] = min(acc[0][i], mn[i])
            acc[1][i] = max(acc[1][i], mx[i])
        acc[2] += 1

    out = {}
    for g, (mn, mx, cnt) in groups.items():
        out[g] = {
            "center_xz": [round((mn[0] + mx[0]) / 2, 3), round((mn[2] + mx[2]) / 2, 3)],
            "size_xyz": [round(mx[0] - mn[0], 3), round(mx[1] - mn[1], 3), round(mx[2] - mn[2], 3)],
            "y_min": round(mn[1], 3), "y_max": round(mx[1], 3),
            "x_range": [round(mn[0], 3), round(mx[0], 3)],
            "z_range": [round(mn[2], 3), round(mx[2], 3)],
            "part_count": cnt,
        }

    with open(OUT_JSON, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)

    # コンソール要約
    print(f"overall field AABB(m): {data.get('overall_aabb')}")
    for g in sorted(out):
        s = out[g]
        print(f"{g:18s} center_xz={s['center_xz']}  size={s['size_xyz']}  y=[{s['y_min']},{s['y_max']}]  parts={s['part_count']}")
    print("出力:", OUT_JSON)


if __name__ == "__main__":
    main()
