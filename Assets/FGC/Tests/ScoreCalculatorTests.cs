using System.Collections.Generic;
using FGC.Core;
using NUnit.Framework;

namespace FGC.Tests
{
    /// <summary>
    /// ルール正典のスコア計算（Core ScoreCalculator）の EditMode テスト。
    /// 朝のコンパイル後、Test Runner で緑になればルール実装が壊れていないことを保証する。
    /// </summary>
    public class ScoreCalculatorTests
    {
        private static RobotClimbState Climb(ClimbZone z, bool support = false)
            => new RobotClimbState { Zone = z, SupportsPartner = support };

        [Test]
        public void ClimbIncrement_PerZone()
        {
            Assert.AreEqual(0.00f, ScoreCalculator.ClimbIncrement(ClimbZone.None), 1e-5f);
            Assert.AreEqual(0.05f, ScoreCalculator.ClimbIncrement(ClimbZone.Contact), 1e-5f);
            Assert.AreEqual(0.10f, ScoreCalculator.ClimbIncrement(ClimbZone.Zone1), 1e-5f);
            Assert.AreEqual(0.20f, ScoreCalculator.ClimbIncrement(ClimbZone.Zone2), 1e-5f);
            Assert.AreEqual(0.30f, ScoreCalculator.ClimbIncrement(ClimbZone.Zone3), 1e-5f);
        }

        [Test]
        public void ClimbMultiplier_SumsIncrementsPlusOne()
        {
            var robots = new List<RobotClimbState> { Climb(ClimbZone.Zone3), Climb(ClimbZone.Zone1), Climb(ClimbZone.None) };
            // 1 + 0.30 + 0.10 + 0 = 1.40
            Assert.AreEqual(1.40f, ScoreCalculator.ClimbMultiplier(robots), 1e-5f);
        }

        [Test]
        public void PartnerClimb_25PerSupportedRobot()
        {
            var robots = new List<RobotClimbState> { Climb(ClimbZone.Zone3, true), Climb(ClimbZone.Zone2, true), Climb(ClimbZone.None) };
            Assert.AreEqual(50, ScoreCalculator.PartnerClimb(robots));
        }

        [Test]
        public void Coopertition_Thresholds()
        {
            Assert.AreEqual(0, ScoreCalculator.Coopertition(3));
            Assert.AreEqual(10, ScoreCalculator.Coopertition(4));
            Assert.AreEqual(25, ScoreCalculator.Coopertition(5));
            Assert.AreEqual(40, ScoreCalculator.Coopertition(6));
            Assert.AreEqual(40, ScoreCalculator.Coopertition(7)); // 上限維持
        }

        [Test]
        public void CountZone3_AcrossBothAlliances()
        {
            var red = new List<RobotClimbState> { Climb(ClimbZone.Zone3), Climb(ClimbZone.Zone3), Climb(ClimbZone.Zone2) };
            var blue = new List<RobotClimbState> { Climb(ClimbZone.Zone3), Climb(ClimbZone.None), Climb(ClimbZone.Zone1) };
            Assert.AreEqual(3, ScoreCalculator.CountZone3(red, blue));
        }

        [Test]
        public void RegionalScore_CeilOfSuppressionTimesMultiplier_PlusTerms()
        {
            // SUPPRESSION=10球, CLIMB倍率= 1 + 0.30 = 1.30 → ceil(10*1.30)=13
            // PARTNER_CLIMB= 25*1=25, EXTINGUISHER=4, COOP=10 → 合計 52
            var robots = new List<RobotClimbState> { Climb(ClimbZone.Zone3, true) };
            var region = new RegionScoreInput { SuppressionBalls = 10, Robots = robots };
            int score = ScoreCalculator.RegionalScore(region, extinguisherBallsGlobal: 4, coopertitionGlobal: 10);
            Assert.AreEqual(13 + 25 + 4 + 10, score);
        }

        [Test]
        public void RegionalScore_RoundsUp()
        {
            // SUPPRESSION=3, 倍率=1.10 → 3*1.10=3.3 → ceil=4
            var robots = new List<RobotClimbState> { Climb(ClimbZone.Zone1) };
            var region = new RegionScoreInput { SuppressionBalls = 3, Robots = robots };
            int score = ScoreCalculator.RegionalScore(region, 0, 0);
            Assert.AreEqual(4, score);
        }
    }
}
