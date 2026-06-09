using UnityEditor;

namespace FGC.Editor
{
    /// <summary>
    /// Assets/Models/Field 配下の3Dモデル(FBX)の取り込み設定を自動調整する。
    /// 実行時に MeshCollider を割り当てるためメッシュを Read/Write 有効化し、
    /// 焼き込まれたマテリアル色を活かすためマテリアルを取り込む。
    /// ※設定変更は (再)インポート時に反映される。既存FBXは右クリック→Reimportで適用。
    /// </summary>
    public sealed class FieldModelPostprocessor : AssetPostprocessor
    {
        private bool IsFieldModel()
            => assetPath.Replace("\\", "/").Contains("Assets/Models/Field/");

        private void OnPreprocessModel()
        {
            if (!IsFieldModel()) return;
            var mi = (ModelImporter)assetImporter;
            mi.isReadable = true;                                   // 実行時 MeshCollider 用
            mi.meshCompression = ModelImporterMeshCompression.Off;  // 衝突精度のため非圧縮
            mi.importNormals = ModelImporterNormals.Import;         // Blenderの外向き法線を尊重
            mi.materialImportMode = ModelImporterMaterialImportMode.ImportStandard; // 焼き込み色を取り込む
        }
    }
}
