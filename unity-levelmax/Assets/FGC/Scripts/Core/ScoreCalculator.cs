using System;
using System.Collections.Generic;

namespace FGC.Core
{
    /// <summary>ロボ1台分の登攀状態。CLIMB倍率・PARTNER_CLIMB・COOPERTITION に使う。</summary>
    public struct RobotClimbState
    {
        public ClimbZone Zone;        // 到達ゾーン
        public bool SupportsPartner;  // 仲間ロボを支持しているか（PARTNER_CLIMB対象）
    }

    /// <summary>1リージョン（同盟）のスコア入力。</summary>
    public struct RegionScoreInput
    {
        public int SuppressionBalls;            // 該当REGIONALのSUPPRESSIONに格納された球数（1点/球）
        public IReadOnlyList<RobotClimbState> Robots; // このリージョンのロボ（通常3台）
    }

    /// <summary>
    /// FGC2026 スコア計算（ルール準拠・PLAN.md §3.6 / HANDOFF.md §6）。純ロジック・テスト可能。
    /// REGIONAL = ceil(SUPPRESSION点 × CLIMB倍率) + PARTNER_CLIMB + EXTINGUISHER点 + COOPERTITION
    /// 端数は最終的に切り上げ。EXTINGUISHER点/COOPERTITION は GLOBAL 値を各リージョンに加算する解釈。
    /// ※ GLOBAL項のリージョン配分は朝にGame Manualで要確認（下記 TODO）。
    /// </summary>
    public static class ScoreCalculator
    {
        // CLIMB倍率の各ゾーン増分
        public const float ClimbContact = 0.05f;
        public const float ClimbZone1 = 0.10f;
        public const float ClimbZone2 = 0.20f;
        public const float ClimbZone3 = 0.30f;

        public const int PartnerClimbPerRobot = 25;

        /// <summary>各ロボの登攀ゾーンに対応する CLIMB 倍率増分。</summary>
        public static float ClimbIncrement(ClimbZone zone)
        {
            switch (zone)
            {
                case ClimbZone.Contact: return ClimbContact;
                case ClimbZone.Zone1: return ClimbZone1;
                case ClimbZone.Zone2: return ClimbZone2;
                case ClimbZone.Zone3: return ClimbZone3;
                default: return 0f;
            }
        }

        /// <summary>CLIMB倍率 = 1 + Σ各ロボ増分。</summary>
        public static float ClimbMultiplier(IReadOnlyList<RobotClimbState> robots)
        {
            float sum = 0f;
            for (int i = 0; i < robots.Count; i++)
                sum += ClimbIncrement(robots[i].Zone);
            return 1f + sum;
        }

        /// <summary>PARTNER_CLIMB = 25 × 支持された仲間ロボ数。</summary>
        public static int PartnerClimb(IReadOnlyList<RobotClimbState> robots)
        {
            int supported = 0;
            for (int i = 0; i < robots.Count; i++)
                if (robots[i].SupportsPartner) supported++;
            return PartnerClimbPerRobot * supported;
        }

        /// <summary>COOPERTITION：全6チームでZ3に到達した台数に応じた GLOBAL 加点（4台=10/5台=25/6台=40）。</summary>
        public static int Coopertition(int robotsInZone3Global)
        {
            if (robotsInZone3Global >= 6) return 40;
            if (robotsInZone3Global == 5) return 25;
            if (robotsInZone3Global == 4) return 10;
            return 0;
        }

        /// <summary>全6台（赤3＋青3）でZ3到達数を数える補助。</summary>
        public static int CountZone3(IReadOnlyList<RobotClimbState> red, IReadOnlyList<RobotClimbState> blue)
        {
            int n = 0;
            for (int i = 0; i < red.Count; i++) if (red[i].Zone == ClimbZone.Zone3) n++;
            for (int i = 0; i < blue.Count; i++) if (blue[i].Zone == ClimbZone.Zone3) n++;
            return n;
        }

        /// <summary>
        /// 1リージョンのREGIONALスコア。
        /// extinguisherBallsGlobal / coopertitionGlobal は GLOBAL値（各リージョンに加算）。
        /// </summary>
        public static int RegionalScore(RegionScoreInput region, int extinguisherBallsGlobal, int coopertitionGlobal)
        {
            float suppression = region.SuppressionBalls; // 1点/球
            float multiplier = ClimbMultiplier(region.Robots);
            int climbScaled = (int)Math.Ceiling(suppression * multiplier); // 端数切り上げ
            int partner = PartnerClimb(region.Robots);
            return climbScaled + partner + extinguisherBallsGlobal + coopertitionGlobal;
        }
    }
}
