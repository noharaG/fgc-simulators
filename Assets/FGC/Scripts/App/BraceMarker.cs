using UnityEngine;

namespace FGC.App
{
    /// <summary>
    /// 登攀対象の BRACE（斜めの鋼管）であることを示すマーカー。
    /// 実CAD FBX の部品名は「mesh#」「MAXTube…(REV-…)」等で "brace" を含まないため、
    /// 名前ではこの部材を特定できない。`MatchDirector.TagBraces` が形状（細長い・斜め・
    /// 登攀高さ帯を跨ぐ）から該当コライダを判定し、このコンポーネントを付与する。
    /// 登攀判定（`EcsMechanics`・`RobotClimb`）はこのマーカーでBRACEを認識する。
    /// </summary>
    public sealed class BraceMarker : MonoBehaviour { }
}
