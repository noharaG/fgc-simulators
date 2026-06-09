using FGC.Core;
using UnityEngine;

namespace FGC.App
{
    /// <summary>
    /// フィールドFBXの当たり判定生成（共有ロジック）。
    ///  ・床: 安定重視の薄い Box Collider（500球が乗る面はBoxの方がジッタが出ない）。
    ///  ・構造物: 各メッシュに凹 MeshCollider を付け、ゴール/壁/ブレース/球放出部を形状忠実に。
    ///    最大辺が minSize 未満の微小部品（ボルト/ベアリング等）は除外。
    /// エディタ焼き込み（FieldSceneBuilder, 1回クックしてシーン保存）と
    /// 実行時フォールバック（MatchDirector）の両方から呼ぶ。
    /// ※MeshCollider は FBX メッシュが Read/Write 有効である必要（FieldModelPostprocessor で自動設定）。
    /// </summary>
    public static class FieldColliders
    {
        public static void Build(Transform fieldRoot, GameObject model, float minSize)
        {
            // 床（薄いBox, y=0面, 7x7）
            var floor = new GameObject("FloorCollider");
            floor.transform.SetParent(fieldRoot, false);
            floor.transform.localPosition = new Vector3(
                FieldConstants.FieldSizeX * 0.5f, -0.02f, FieldConstants.FieldSizeZ * 0.5f);
            var fb = floor.AddComponent<BoxCollider>();
            fb.size = new Vector3(FieldConstants.FieldSizeX, 0.04f, FieldConstants.FieldSizeZ);

            int added = 0, skipped = 0;
            var filters = model.GetComponentsInChildren<MeshFilter>();
            foreach (var mf in filters)
            {
                var mesh = mf.sharedMesh;
                if (mesh == null) { skipped++; continue; }
                Vector3 s = mesh.bounds.size, ls = mf.transform.lossyScale;
                float maxDim = Mathf.Max(s.x * Mathf.Abs(ls.x),
                                         Mathf.Max(s.y * Mathf.Abs(ls.y), s.z * Mathf.Abs(ls.z)));
                if (maxDim < minSize) { skipped++; continue; } // 微小ハードウェア除外
                var mc = mf.gameObject.AddComponent<MeshCollider>();
                mc.sharedMesh = mesh; // convex=false（静的フィールドなので凹形状OK）
                added++;
            }
            Debug.Log($"[FGC] フィールドコライダ生成: MeshCollider={added} / 除外={skipped}（閾値{minSize}m）");
        }
    }
}
