#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
FG26_F STEP (AP242) -> フィールド幾何の高精度抽出 (v2)。
依存ライブラリ無し。Python 3.10+。

構造 (本ファイル実測で確定):
  - 各部品は SHAPE_REPRESENTATION('name',(#axisplacement),#ctx) を持ち、配置のみ。
  - 実 B-rep は SHAPE_REPRESENTATION_RELATIONSHIP(#leafSR, #ADVANCED_BREP_SHAPE_REPRESENTATION)
    で leafSR と同一座標系に連結 (変換なし)。
  - アセンブリは CONTEXT_DEPENDENT_SHAPE_REPRESENTATION(#rep_rel, #pds(NAUO)) で
    rep_rel = (REPRESENTATION_RELATIONSHIP(child_SR, parent_SR)
               REPRESENTATION_RELATIONSHIP_WITH_TRANSFORMATION(#ITEM_DEFINED_TRANSFORMATION)).
    ITEM_DEFINED_TRANSFORMATION(t1_in_child, t2_in_parent) -> child->parent 変換 = t2 . inv(t1).
  - NAUO がインスタンス名を保持。

出力: field_geometry_step.json / field_geometry_report.txt
STEP単位は mm。出力は m (×0.001)。座標系は CAD原点基準 (後段でfield原点へオフセット)。
"""
import re, sys, json, math, collections, os

STEP_PATH = sys.argv[1] if len(sys.argv) > 1 else r"C:\Users\genri\Downloads\FG26_F (1).step"
OUT_DIR   = sys.argv[2] if len(sys.argv) > 2 else r"C:\Users\genri\Desktop\FGCシミュレーターレベルマックス\Assets\FGC\Settings"
os.makedirs(OUT_DIR, exist_ok=True)

print("[1/6] reading...", flush=True)
raw = open(STEP_PATH, encoding="utf-8", errors="replace").read()
data = raw[raw.index("DATA;") + 5:]

print("[2/6] tokenizing...", flush=True)
ent = {}
for m in re.finditer(r"#(\d+)\s*=\s*(.*?);", data, re.S):
    ent[int(m.group(1))] = m.group(2).replace("\n", "").replace("\r", "")
print("   entities:", len(ent), flush=True)

ref_re = re.compile(r"#(\d+)")
def refs(i): return [int(x) for x in ref_re.findall(ent.get(i, ""))]
def typ(i):
    v = ent.get(i, "")
    return "_CPLX_" if v.lstrip().startswith("(") else v.split("(", 1)[0].strip()
def nums(s): return [float(x) for x in re.findall(r"[-+]?\d*\.?\d+(?:[eE][-+]?\d+)?", s)]

def cartesian(i):
    p = ent[i]; inner = p[p.index("(", p.index("(")+1):]
    n = nums(inner); return (n + [0,0,0])[:3]
def direction(i):
    p = ent[i]; inner = p[p.index("(", p.index("(")+1):]
    n = nums(inner); return (n + [0,0,0])[:3]

def ident(): return [1,0,0,0, 0,1,0,0, 0,0,1,0]
def normalize(v):
    n = math.sqrt(sum(c*c for c in v)); return [c/n for c in v] if n>1e-12 else [0,0,1]
def cross(a,b): return [a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]]
def a2p3d(i):
    rr = refs(i)
    loc = cartesian(rr[0]) if rr else [0,0,0]
    z = normalize(direction(rr[1]) if len(rr)>1 else [0,0,1])
    xr = direction(rr[2]) if len(rr)>2 else [1,0,0]
    d = sum(xr[k]*z[k] for k in range(3))
    x = normalize([xr[k]-d*z[k] for k in range(3)]); y = cross(z,x)
    return [x[0],y[0],z[0],loc[0], x[1],y[1],z[1],loc[1], x[2],y[2],z[2],loc[2]]
def mmul(A,B):
    R=[0]*12
    for r in range(3):
        for c in range(3): R[r*4+c]=sum(A[r*4+k]*B[k*4+c] for k in range(3))
        R[r*4+3]=sum(A[r*4+k]*B[k*4+3] for k in range(3))+A[r*4+3]
    return R
def minv(A):
    R=[A[0],A[4],A[8],0, A[1],A[5],A[9],0, A[2],A[6],A[10],0]; t=[A[3],A[7],A[11]]
    R[3]=-(R[0]*t[0]+R[1]*t[1]+R[2]*t[2]); R[7]=-(R[4]*t[0]+R[5]*t[1]+R[6]*t[2]); R[11]=-(R[8]*t[0]+R[9]*t[1]+R[10]*t[2])
    return R
def apply(A,p): return [A[0]*p[0]+A[1]*p[1]+A[2]*p[2]+A[3], A[4]*p[0]+A[5]*p[1]+A[6]*p[2]+A[7], A[8]*p[0]+A[9]*p[1]+A[10]*p[2]+A[11]]

print("[3/6] mapping SR names / geometry links...", flush=True)
sr_name = {}
for i,v in ent.items():
    if v.startswith("SHAPE_REPRESENTATION(") or v.startswith("ADVANCED_BREP_SHAPE_REPRESENTATION(") or v.startswith("MANIFOLD_SURFACE_SHAPE_REPRESENTATION("):
        m = re.match(r"[A-Z_]+\('([^']*)'", v)
        sr_name[i] = m.group(1) if m else ""

# SR -> geometry rep (ABSR/MSSR) via SHAPE_REPRESENTATION_RELATIONSHIP
sr_geom = {}
for i,v in ent.items():
    if v.startswith("SHAPE_REPRESENTATION_RELATIONSHIP"):
        rr = refs(i)
        if len(rr) < 2: continue
        a,b = rr[0], rr[1]
        ta,tb = typ(a),typ(b)
        if ta=="SHAPE_REPRESENTATION" and tb in ("ADVANCED_BREP_SHAPE_REPRESENTATION","MANIFOLD_SURFACE_SHAPE_REPRESENTATION"):
            sr_geom[a]=b
        elif tb=="SHAPE_REPRESENTATION" and ta in ("ADVANCED_BREP_SHAPE_REPRESENTATION","MANIFOLD_SURFACE_SHAPE_REPRESENTATION"):
            sr_geom[b]=a

# PDS(NAUO) -> nauo, nauo -> instance name
pds_nauo={}
for i,v in ent.items():
    if v.startswith("PRODUCT_DEFINITION_SHAPE"):
        for r in refs(i):
            if typ(r)=="NEXT_ASSEMBLY_USAGE_OCCURRENCE": pds_nauo[i]=r
nauo_name={}
for i,v in ent.items():
    if v.startswith("NEXT_ASSEMBLY_USAGE_OCCURRENCE"):
        m=re.match(r"NEXT_ASSEMBLY_USAGE_OCCURRENCE\('[^']*','([^']*)'",v)
        nauo_name[i]=m.group(1) if m else ""

print("[4/6] building SR assembly tree...", flush=True)
# CDSR -> (rep_rel, pds). rep_rel complex -> child_SR, parent_SR, IDT
edges = collections.defaultdict(list)  # parent_SR -> [(child_SR, M_rel, nauo_name)]
child_srs=set()
for i,v in ent.items():
    if not v.startswith("CONTEXT_DEPENDENT_SHAPE_REPRESENTATION"): continue
    rr=refs(i)
    if len(rr)<2: continue
    rep_rel,pds = rr[0],rr[1]
    nm = nauo_name.get(pds_nauo.get(pds,-1),"")
    rrefs=refs(rep_rel)
    srs=[r for r in rrefs if typ(r)=="SHAPE_REPRESENTATION"]
    idt=[r for r in rrefs if typ(r)=="ITEM_DEFINED_TRANSFORMATION"]
    if len(srs)<2 or not idt: continue
    child_sr,parent_sr = srs[0],srs[1]   # REPRESENTATION_RELATIONSHIP(child, parent)
    ir=refs(idt[0]); aps=[r for r in ir if typ(r)=="AXIS2_PLACEMENT_3D"]
    if len(aps)>=2: M=mmul(a2p3d(aps[1]), minv(a2p3d(aps[0])))
    elif len(aps)==1: M=a2p3d(aps[0])
    else: M=ident()
    edges[parent_sr].append((child_sr,M,nm))
    child_srs.add(child_sr)

roots=[s for s in edges if s not in child_srs]
print("   root SRs:", [(r,sr_name.get(r,"")) for r in roots], flush=True)

print("[5/6] local AABB (geometry point cloud) + DFS world...", flush=True)
local_cache={}
def geom_local_aabb(geomrep):
    if geomrep in local_cache: return local_cache[geomrep]
    mn=[math.inf]*3; mx=[-math.inf]*3; seen=set(); st=[geomrep]
    while st:
        x=st.pop()
        if x in seen: continue
        seen.add(x)
        if typ(x)=="CARTESIAN_POINT":
            p=cartesian(x)
            for k in range(3):
                if p[k]<mn[k]: mn[k]=p[k]
                if p[k]>mx[k]: mx[k]=p[k]
            continue
        for r in refs(x):
            if r not in seen: st.append(r)
    res=None if mn[0]==math.inf else (mn,mx)
    local_cache[geomrep]=res; return res

occ=[]
def corners(la,M):
    mn,mx=la; cs=[]
    for a in (mn[0],mx[0]):
        for b in (mn[1],mx[1]):
            for c in (mn[2],mx[2]): cs.append(apply(M,[a,b,c]))
    return cs

def dfs(sr,M,path,top,depth):
    g=sr_geom.get(sr)
    if g is not None:
        la=geom_local_aabb(g)
        if la:
            cs=corners(la,M)
            wmn=[min(c[k] for c in cs) for k in range(3)]
            wmx=[max(c[k] for c in cs) for k in range(3)]
            occ.append({"name":sr_name.get(sr,"") or (path.split("/")[-1] if path else ""),
                        "top":top,"path":path,"min":wmn,"max":wmx})
    for (csr,Mrel,nm) in edges.get(sr,[]):
        cname = nm or sr_name.get(csr,"")
        dfs(csr, mmul(M,Mrel), (path+"/"+cname) if path else cname, top if top else cname, depth+1)

for root in roots:
    dfs(root, ident(), sr_name.get(root,"ROOT"), None, 0)
print("   leaf geometry occurrences:", len(occ), flush=True)

print("[6/6] aggregate & write...", flush=True)
def M(v): return [round(c*0.001,5) for c in v]
agg=collections.defaultdict(lambda:([math.inf]*3,[-math.inf]*3,0))
for o in occ:
    k=o["top"] or o["name"]
    mn,mx,c=agg[k]
    agg[k]=([min(mn[i],o["min"][i]) for i in range(3)],[max(mx[i],o["max"][i]) for i in range(3)],c+1)
aggs=[]
for k,(mn,mx,c) in sorted(agg.items()):
    aggs.append({"name":k,"leaf_count":c,"min_m":M(mn),"max_m":M(mx),
                 "size_m":M([mx[i]-mn[i] for i in range(3)]),
                 "center_m":M([(mn[i]+mx[i])/2 for i in range(3)])})

gmn=[math.inf]*3; gmx=[-math.inf]*3
for o in occ:
    for i in range(3):
        gmn[i]=min(gmn[i],o["min"][i]); gmx[i]=max(gmx[i],o["max"][i])

out={"source":STEP_PATH,"units":"output=m (STEP mm x0.001)","entity_count":len(ent),
     "global_min_m":M(gmn),"global_max_m":M(gmx),"global_size_m":M([gmx[i]-gmn[i] for i in range(3)]),
     "roots":[sr_name.get(r,"") for r in roots],
     "aggregates":aggs,
     "occurrences":[{"name":o["name"],"top":o["top"],"path":o["path"],
                     "min_m":M(o["min"]),"max_m":M(o["max"]),
                     "size_m":M([o["max"][i]-o["min"][i] for i in range(3)]),
                     "center_m":M([(o["min"][i]+o["max"][i])/2 for i in range(3)])} for o in occ]}
json.dump(out,open(os.path.join(OUT_DIR,"field_geometry_step.json"),"w",encoding="utf-8"),ensure_ascii=False,indent=2)

L=[]
L.append("FG26_F STEP geometry extraction v2 (meters)")
L.append(f"entities={len(ent)}  leaf_occ={len(occ)}  roots={out['roots']}")
L.append(f"GLOBAL min={out['global_min_m']} max={out['global_max_m']} size={out['global_size_m']}")
L.append("")
L.append("=== top-level subassembly aggregates ===")
L.append(f"{'name':38s}{'cnt':>4}   {'center(x,y,z)':^26}{'size(x,y,z)':^26}")
for a in sorted(aggs,key=lambda a:-max(a['size_m'])):
    c=a['center_m']; s=a['size_m']
    L.append(f"{a['name'][:38]:38s}{a['leaf_count']:>4}   ({c[0]:7.3f},{c[1]:7.3f},{c[2]:7.3f})  ({s[0]:7.3f},{s[1]:7.3f},{s[2]:7.3f})")
open(os.path.join(OUT_DIR,"field_geometry_report.txt"),"w",encoding="utf-8").write("\n".join(L))
print("\n".join(L))
print("\nWROTE field_geometry_step.json /", "field_geometry_report.txt")
