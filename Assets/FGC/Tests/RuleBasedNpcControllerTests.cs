using FGC.Core;
using FGC.Robot;
using NUnit.Framework;
using UnityEngine;

namespace FGC.Tests
{
    /// <summary>
    /// RuleBasedNpcController（Java競技NPCの操舵則移植）の決定論的検証。
    /// エージェントはUnity Playを実行できないため、操舵・前進ゲート・デッドバンド・回収・壁回避を
    /// EditMode単体テストで担保する。座標系: forward=(sinθ,cosθ)、self/ball は (x,z) 平面。
    /// </summary>
    public sealed class RuleBasedNpcControllerTests
    {
        private const float Dt = 0.02f;

        private static RobotObservation Obs(Vector3 pos, float headingRad, Vector3 ball)
        {
            return new RobotObservation
            {
                Alliance = AllianceColor.Red,
                Position = pos,
                HeadingRad = headingRad,
                AngularVelocity = 0f,
                HeldBalls = 0,                 // 満杯でない＝最寄り球を追う（ゴール依存を避ける）
                HasNearestBall = true,
                NearestBall = ball,
                HasOpponent = false,
                HasTeammate = false,
            };
        }

        [Test]
        public void BallStraightAhead_DrivesForward_NoTurn()
        {
            var c = new RuleBasedNpcController();
            // 原点で+Z向き、球は真正面(+Z)1.5m先。
            var cmd = c.GetCommand(Obs(new Vector3(3.5f, 0f, 3.5f), 0f, new Vector3(3.5f, 0f, 5.0f)), Dt);
            Assert.Greater(cmd.Forward, 0.5f, "真正面の球へは前進するはず");
            Assert.Less(Mathf.Abs(cmd.Turn), 0.01f, "正対しているので旋回しないはず");
        }

        [Test]
        public void BallToTheRight_TurnsAndHoldsForwardUntilAligned()
        {
            var c = new RuleBasedNpcController();
            // +Z向きで球は真右(+X)。90°ずれ＝前進ゲート(45°)外なので前進せず旋回優先。
            var cmd = c.GetCommand(Obs(new Vector3(3.5f, 0f, 3.5f), 0f, new Vector3(5.0f, 0f, 3.5f)), Dt);
            Assert.Greater(cmd.Turn, 0.5f, "右の球へ向けて旋回するはず");
            Assert.AreEqual(0f, cmd.Forward, 1e-4f, "45°以上ずれている間は前進しないはず");
        }

        [Test]
        public void WithinDeadband_NoTurn()
        {
            var c = new RuleBasedNpcController();
            // 約3°ずれ（デッドバンド5°内）→ 旋回0、前進あり。
            float a = 3f * Mathf.Deg2Rad;
            var ball = new Vector3(3.5f + Mathf.Sin(a), 0f, 3.5f + Mathf.Cos(a));
            var cmd = c.GetCommand(Obs(new Vector3(3.5f, 0f, 3.5f), 0f, ball), Dt);
            Assert.AreEqual(0f, cmd.Turn, 1e-4f, "5°デッドバンド内は旋回しないはず");
            Assert.Greater(cmd.Forward, 0f, "ほぼ正対なので前進するはず");
        }

        [Test]
        public void BallWithinIntakeRange_RequestsIntake()
        {
            var c = new RuleBasedNpcController();
            // 0.2m先（IntakeRange 0.35m 内）→ Intake要求。
            var cmd = c.GetCommand(Obs(new Vector3(3.5f, 0f, 3.5f), 0f, new Vector3(3.5f, 0f, 3.7f)), Dt);
            Assert.IsTrue(cmd.Intake, "回収距離内ではIntakeを要求するはず");
        }

        [Test]
        public void DTerm_DampsTurnWhenAlreadyRotating()
        {
            var c = new RuleBasedNpcController();
            // 球は真右(err≈+π/2)。角速度0なら omega=clamp(π/2*3.5)=4 → Turn=1。
            var still = Obs(new Vector3(3.5f, 0f, 3.5f), 0f, new Vector3(5.0f, 0f, 3.5f));
            float turnStill = c.GetCommand(still, Dt).Turn;
            // 既に目標方向へ高速旋回中(+4rad/s)なら PD の D項(-Kd*角速度)で旋回指令が減衰するはず。
            var spinning = still;
            spinning.AngularVelocity = 4f;
            float turnSpin = c.GetCommand(spinning, Dt).Turn;
            Assert.Less(turnSpin, turnStill, "既に旋回中はD項で旋回指令が減衰するはず（オーバーシュート抑制）");
        }

        [Test]
        public void NearWallFacingIt_StopsForward()
        {
            var c = new RuleBasedNpcController();
            // 西壁(x=0)から0.2m、西向き(-X)。正面に球を置き正対させても、壁が近いので前進は止まるはず。
            var cmd = c.GetCommand(Obs(new Vector3(0.2f, 0f, 3.5f), -Mathf.PI / 2f, new Vector3(-0.5f, 0f, 3.5f)), Dt);
            Assert.AreEqual(0f, cmd.Forward, 1e-4f, "進行方向の壁が近いと前進を止めるはず");
        }
    }
}
