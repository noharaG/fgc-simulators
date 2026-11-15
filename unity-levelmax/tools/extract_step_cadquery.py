#!/usr/bin/env python3
"""
STEP → フィールド高精度ジオメトリ抽出（案A：OpenCASCADE/cadquery の XCAF を使用）。

HANDOFF §7 の通り、自前点群パーサ(extract_step_geometry.py v2)は曲面(球/管)の半径や
アセンブリ変換で寸法を取り違える問題があった。本スクリプトは OCP(OpenCASCADE) の XCAF で
ラベル付きソリッドごとに「正確なBBox（曲面対応の AddOptimal）＋累積配置」を取得する。

前提: Python 3.13 ＋ `pip install --user cadquery`（cadquery-ocp 7.8.x の cp313 wheel が存在）。
入力: C:\\Users\\genri\\Downloads\\FG26_F (1).step（44MB, AP242, 読取専用）
出力: Assets/FGC/Settings/field_geometry_cadquery.json（部品ごとの世界AABB[m]）＋ レポート

注意: STEP は通常 mm。本スクリプトは出力時に /1000 で m に変換する（要・実測で確認）。
      フィールド原点・向きの最終マッピングは出力AABBを見て field_obstacles.json と突き合わせる。
"""
import json
import os
import sys

STEP_PATH = r"C:\Users\genri\Downloads\FG26_F (1).step"
OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "Assets", "FGC", "Settings")
OUT_JSON = os.path.join(OUT_DIR, "field_geometry_cadquery.json")
OUT_REPORT = os.path.join(OUT_DIR, "field_geometry_cadquery_report.txt")
MM_TO_M = 1.0 / 1000.0


def load_doc(path):
    from OCP.STEPCAFControl import STEPCAFControl_Reader
    from OCP.TDocStd import TDocStd_Document
    from OCP.XCAFApp import XCAFApp_Application
    from OCP.TCollection import TCollection_ExtendedString
    from OCP.IFSelect import IFSelect_ReturnStatus

    app = XCAFApp_Application.GetApplication_s()
    doc = TDocStd_Document(TCollection_ExtendedString("MDTV-XCAF"))
    app.NewDocument(TCollection_ExtendedString("MDTV-XCAF"), doc)

    reader = STEPCAFControl_Reader()
    status = reader.ReadFile(path)
    if status != IFSelect_ReturnStatus.IFSelect_RetDone:
        raise RuntimeError(f"STEP 読込失敗: status={status}")
    reader.SetNameMode(True)
    reader.Transfer(doc)
    return doc


def label_name(label):
    from OCP.TDataStd import TDataStd_Name
    from OCP.TDF import TDF_Label  # noqa: F401
    attr = TDataStd_Name()
    if label.FindAttribute(TDataStd_Name.GetID_s(), attr):
        return attr.Get().ToExtString()
    return "?"


def shape_bbox_world(shape):
    """配置適用済み shape の最適AABB（曲面対応）を返す (xmin..zmax) in mm。"""
    from OCP.Bnd import Bnd_Box
    from OCP.BRepBndLib import BRepBndLib
    box = Bnd_Box()
    # AddOptimal: 曲面(球/円柱)も正しい外接箱になる
    BRepBndLib.AddOptimal_s(shape, box, True, False)
    if box.IsVoid():
        return None
    xmin, ymin, zmin, xmax, ymax, zmax = box.Get()
    return (xmin, ymin, zmin, xmax, ymax, zmax)


def walk(shape_tool, label, loc_parent, depth, out, name_path):
    """アセンブリを再帰し、葉(単純形状)ごとに世界AABBを記録。"""
    from OCP.XCAFDoc import XCAFDoc_ShapeTool
    from OCP.TDF import TDF_LabelSequence
    from OCP.BRepBuilderAPI import BRepBuilderAPI_Transform

    name = label_name(label)
    full = name_path + "/" + name if name_path else name

    if shape_tool.IsAssembly_s(label):
        comps = TDF_LabelSequence()
        shape_tool.GetComponents_s(label, comps)
        for i in range(1, comps.Length() + 1):
            comp = comps.Value(i)
            # コンポーネントの配置
            comp_loc = shape_tool.GetLocation_s(comp)
            new_loc = loc_parent.Multiplied(comp_loc) if comp_loc else loc_parent
            # 参照先（実体）ラベルへ
            ref = comp
            refd = shape_tool.GetReferredShape_s(comp, ref) if hasattr(shape_tool, "GetReferredShape_s") else None
            target = ref if refd else comp
            walk(shape_tool, target, new_loc, depth + 1, out, full)
    else:
        shape = shape_tool.GetShape_s(label)
        if shape is None:
            return
        moved = shape.Moved(loc_parent) if loc_parent else shape
        bb = shape_bbox_world(moved)
        if bb is None:
            return
        xmin, ymin, zmin, xmax, ymax, zmax = [v * MM_TO_M for v in bb]
        out.append({
            "name": full,
            "aabb_m": {
                "min": [round(xmin, 4), round(ymin, 4), round(zmin, 4)],
                "max": [round(xmax, 4), round(ymax, 4), round(zmax, 4)],
                "size": [round(xmax - xmin, 4), round(ymax - ymin, 4), round(zmax - zmin, 4)],
            },
        })


def main():
    if not os.path.exists(STEP_PATH):
        print("STEP が見つかりません:", STEP_PATH); sys.exit(1)
    print("読込中:", STEP_PATH)
    doc = load_doc(STEP_PATH)

    from OCP.XCAFDoc import XCAFDoc_DocumentTool
    from OCP.TDF import TDF_LabelSequence
    from OCP.TopLoc import TopLoc_Location

    shape_tool = XCAFDoc_DocumentTool.ShapeTool_s(doc.Main())
    roots = TDF_LabelSequence()
    shape_tool.GetFreeShapes(roots)
    print("free shapes:", roots.Length())

    out = []
    identity = TopLoc_Location()
    for i in range(1, roots.Length() + 1):
        walk(shape_tool, roots.Value(i), identity, 0, out, "")

    # 全体バウンド
    if out:
        xs = [p["aabb_m"]["min"][0] for p in out] + [p["aabb_m"]["max"][0] for p in out]
        ys = [p["aabb_m"]["min"][1] for p in out] + [p["aabb_m"]["max"][1] for p in out]
        zs = [p["aabb_m"]["min"][2] for p in out] + [p["aabb_m"]["max"][2] for p in out]
        overall = {"min": [min(xs), min(ys), min(zs)], "max": [max(xs), max(ys), max(zs)]}
    else:
        overall = None

    os.makedirs(OUT_DIR, exist_ok=True)
    with open(OUT_JSON, "w", encoding="utf-8") as f:
        json.dump({"unit": "m", "overall_aabb": overall, "parts": out}, f, ensure_ascii=False, indent=2)

    with open(OUT_REPORT, "w", encoding="utf-8") as f:
        f.write(f"parts: {len(out)}\n")
        if overall:
            sx = overall['max'][0]-overall['min'][0]
            sy = overall['max'][1]-overall['min'][1]
            sz = overall['max'][2]-overall['min'][2]
            f.write(f"overall size [m]: {sx:.3f} x {sy:.3f} x {sz:.3f}  (期待: 約7 x 約2 x 約7 のいずれかの並び)\n")
        for p in sorted(out, key=lambda q: -max(q['aabb_m']['size'])):
            s = p['aabb_m']['size']
            f.write(f"  {p['name'][:70]:70s}  size={s[0]:.3f} x {s[1]:.3f} x {s[2]:.3f}\n")

    print("出力:", OUT_JSON)
    print("レポート:", OUT_REPORT)


if __name__ == "__main__":
    main()
