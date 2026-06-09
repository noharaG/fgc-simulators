using FGC.Core;
using NUnit.Framework;
using UnityEngine;

namespace FGC.Tests
{
    /// <summary>
    /// FieldLayout のNPC得点ゴール配置の回帰テスト。
    /// 赤/青ゴールの左右は過去に逆転バグがあった（HANDOFF: 「ゴールの左右が逆だった」を修正済）。
    /// NPC は `NpcGoal(alliance)` を狙うため、ここが逆だと敵ゴールへ運ぶ致命バグになる。固定する。
    /// `NpcGoal` は Vector2(x=フィールドx, y=フィールドz) を返す。
    /// </summary>
    public sealed class FieldLayoutTests
    {
        [Test]
        public void NpcGoal_RedIsRightOfBlue()
        {
            Vector2 red = FieldLayout.NpcGoal(AllianceColor.Red);
            Vector2 blue = FieldLayout.NpcGoal(AllianceColor.Blue);
            // 方針「赤=右(x大)/青=左(x小)」（PLAN.md・各ロボ初期位置に近い側）。
            Assert.Greater(red.x, blue.x, "赤ゴールは青ゴールより右(x大)であるべき（左右逆転バグの回帰防止）");
        }

        [Test]
        public void NpcGoal_WithinFieldBounds()
        {
            foreach (var a in new[] { AllianceColor.Red, AllianceColor.Blue })
            {
                Vector2 g = FieldLayout.NpcGoal(a);
                Assert.GreaterOrEqual(g.x, 0f);
                Assert.LessOrEqual(g.x, FieldConstants.FieldSizeX);
                Assert.GreaterOrEqual(g.y, 0f);
                Assert.LessOrEqual(g.y, FieldConstants.FieldSizeZ);
            }
        }

        [Test]
        public void NpcGoal_SymmetricAboutFieldCenterX()
        {
            Vector2 red = FieldLayout.NpcGoal(AllianceColor.Red);
            Vector2 blue = FieldLayout.NpcGoal(AllianceColor.Blue);
            float center = FieldConstants.FieldSizeX * 0.5f;
            // 赤青ゴールはフィールド中央x(3.5)に対し左右対称のはず（±0.1m許容）。
            Assert.AreEqual(red.x - center, center - blue.x, 0.1f, "赤青ゴールは中央xに対し左右対称であるべき");
        }
    }
}
