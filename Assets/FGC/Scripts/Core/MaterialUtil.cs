using UnityEngine;
using UnityEngine.Rendering;

namespace FGC.Core
{
    /// <summary>
    /// レンダーパイプライン非依存に色付きマテリアルを作る共通ユーティリティ。
    /// CreatePrimitive の既定マテリアルは URP 環境でマゼンタ表示になるため、生成物には本ユーティリティで
    /// 明示的に色を割り当てる。URP/Lit → Standard → Unlit/Color の順に利用可能なシェーダを探す。
    /// </summary>
    public static class MaterialUtil
    {
        private static Shader FindShader()
        {
            // アクティブなレンダーパイプライン(URP等)の既定シェーダを最優先で使う。
            // Shader.Find は実行時にURP/Litを見つけられずStandard(=URPでマゼンタ)に落ちることがあるため、
            // パイプラインの defaultShader を直接取得するのが確実。
            var rp = GraphicsSettings.currentRenderPipeline;
            if (rp != null && rp.defaultShader != null) return rp.defaultShader;
            // ここに来る＝SRPアセット未割当でBuilt-inパイプラインが稼働中。
            // この状態でURP/Litを掴むとBuilt-in上では非互換でマゼンタになるため、必ずStandardを優先する。
            // （URPアセットをGraphics/Quality Settingsに割り当てれば上のrp.defaultShader側=URP/Litが使われる）
            Shader sh = Shader.Find("Standard");
            if (sh == null) sh = Shader.Find("Universal Render Pipeline/Lit");
            if (sh == null) sh = Shader.Find("Unlit/Color");
            return sh;
        }

        /// <summary>指定色のマテリアルを生成（_BaseColor/_Color 両対応・GPU Instancing有効）。</summary>
        public static Material Colored(Color color) => Colored(color, false);

        /// <summary>
        /// 指定色のマテリアルを生成。doubleSided=true でカリングOff（両面表示）。
        /// 自前生成メッシュ（角柱押出）は巻き方向次第で面が裏返り不可視になり得るため、構造体は両面で確実に見せる。
        /// </summary>
        public static Material Colored(Color color, bool doubleSided)
        {
            var mat = new Material(FindShader()) { enableInstancing = true };
            mat.color = color;
            if (mat.HasProperty("_BaseColor")) mat.SetColor("_BaseColor", color);
            if (doubleSided && mat.HasProperty("_Cull"))
                mat.SetFloat("_Cull", 0f); // 0=Off（URP/Lit・Standardの一部が対応）
            return mat;
        }

        // よく使う色（フィールド/ロボの視認性向上用）
        public static Material Floor() => Colored(new Color(0.30f, 0.32f, 0.35f));
        public static Material Rail() => Colored(new Color(0.85f, 0.78f, 0.20f));
        public static Material Structure() => Colored(new Color(0.70f, 0.70f, 0.72f), doubleSided: true);
        public static Material Brace() => Colored(new Color(0.55f, 0.57f, 0.60f));
        public static Material RedTape() => Colored(new Color(0.85f, 0.25f, 0.25f));
        public static Material BlueTape() => Colored(new Color(0.25f, 0.45f, 0.90f));
        public static Material Extinguisher() => Colored(new Color(0.80f, 0.45f, 0.20f));
    }
}
