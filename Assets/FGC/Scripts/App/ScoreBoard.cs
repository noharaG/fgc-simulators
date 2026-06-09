using System.Collections.Generic;
using FGC.Ball;
using FGC.Core;
using FGC.Robot;
using UnityEngine;

namespace FGC.App
{
    /// <summary>
    /// ライブスコア集計（PLAN.md §3.6）。WildfireManager の格納数とロボの登攀状態から、
    /// Core の ScoreCalculator で REGIONAL スコアを算出する。HUD・ML報酬・終了時確定で使う。
    /// </summary>
    public sealed class ScoreBoard : MonoBehaviour
    {
        public WildfireManager wildfire;
        public MatchDirector director;

        public int RedRegional { get; private set; }
        public int BlueRegional { get; private set; }
        public int CoopertitionGlobal { get; private set; }

        private readonly List<RobotClimbState> _red = new List<RobotClimbState>(3);
        private readonly List<RobotClimbState> _blue = new List<RobotClimbState>(3);

        /// <summary>現在の状態からスコアを再計算（毎フレーム or 必要時に呼ぶ）。</summary>
        public void Recalculate()
        {
            if (wildfire == null || director == null) return;

            _red.Clear(); _blue.Clear();
            foreach (var actor in director.Robots)
            {
                var cs = new RobotClimbState
                {
                    Zone = actor.ClimbZone,
                    SupportsPartner = false // 仲間支持判定は将来（PARTNER_CLIMB）
                };
                if (actor.alliance == AllianceColor.Red) _red.Add(cs); else _blue.Add(cs);
            }

            int z3 = ScoreCalculator.CountZone3(_red, _blue);
            CoopertitionGlobal = ScoreCalculator.Coopertition(z3);

            int ext = wildfire.ExtinguisherStored;

            RedRegional = ScoreCalculator.RegionalScore(
                new RegionScoreInput { SuppressionBalls = wildfire.SuppressionRedStored, Robots = _red },
                ext, CoopertitionGlobal);

            BlueRegional = ScoreCalculator.RegionalScore(
                new RegionScoreInput { SuppressionBalls = wildfire.SuppressionBlueStored, Robots = _blue },
                ext, CoopertitionGlobal);
        }

        private void Update()
        {
            Recalculate();
        }
    }
}
