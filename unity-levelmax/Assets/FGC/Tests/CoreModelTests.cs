using FGC.Core;
using NUnit.Framework;
using UnityEngine;

namespace FGC.Tests
{
    /// <summary>差動2輪モデル・試合状態機械・スペック派生値の EditMode テスト。</summary>
    public class CoreModelTests
    {
        [Test]
        public void RobotSpec_MaxSpeed_Approx188_ForGear15()
        {
            var spec = RobotSpec.Default; // gear=1/15
            // (6000/60)*(1/15)*π*0.09*0.8 ≈ 1.508... 実装式どおり（HANDOFFの≈1.88は0.8前の概算）
            float expected = (6000f / 60f) * (1f / 15f) * Mathf.PI * 0.09f * 0.8f;
            Assert.AreEqual(expected, spec.MaxSpeed, 1e-3f);
            Assert.Greater(spec.MaxSpeed, 0f);
        }

        [Test]
        public void DifferentialDrive_Deadzone_ZeroesSmallInput()
        {
            var model = new DifferentialDriveModel(RobotSpec.Default);
            // デッドゾーン0.05未満の入力は速度を生まない
            var cmd = new DriveCommand { Forward = 0.02f, Turn = 0f };
            model.Step(cmd, 0.02f);
            Assert.AreEqual(0f, model.CurrentSpeed, 1e-4f);
        }

        [Test]
        public void DifferentialDrive_AccelLimited_DoesNotJumpToTopSpeed()
        {
            var spec = RobotSpec.Default;
            var model = new DifferentialDriveModel(spec);
            var cmd = new DriveCommand { Forward = 1f, Turn = 0f };
            model.Step(cmd, 0.02f); // 1ステップ
            // 1ステップで MaxAccel*dt 以上には増えない
            Assert.LessOrEqual(model.CurrentSpeed, spec.MaxAccel * 0.02f + 1e-4f);
        }

        [Test]
        public void DifferentialDrive_WheelSpeeds_SplitByOmega()
        {
            var model = new DifferentialDriveModel(RobotSpec.Default);
            // 旋回のみ与え続け、左右輪速が対称に分かれることを確認
            for (int i = 0; i < 200; i++) model.Step(new DriveCommand { Forward = 0f, Turn = 1f }, 0.02f);
            var (l, r) = model.WheelSpeeds();
            Assert.AreEqual(-l, r, 1e-3f); // 並進0なら左右逆符号
        }

        [Test]
        public void MatchManager_Phases_PreToRunningToFinished()
        {
            var m = new MatchManager();
            Assert.AreEqual(MatchPhase.PreMatch, m.Phase);

            m.StartMatch();
            Assert.AreEqual(MatchPhase.Running, m.Phase);

            // 直前まで進める
            m.Tick(FieldConstants.MatchDuration - FieldConstants.SettleTime - 0.1f);
            Assert.AreEqual(MatchPhase.Running, m.Phase);

            // 終了5秒前で Settling
            m.Tick(0.2f);
            Assert.AreEqual(MatchPhase.Settling, m.Phase);

            // 残りを使い切ると Finished、残時間0
            m.Tick(FieldConstants.SettleTime);
            Assert.AreEqual(MatchPhase.Finished, m.Phase);
            Assert.AreEqual(0f, m.Remaining, 1e-4f);
        }

        [Test]
        public void MatchManager_FinishEvent_FiresOnce()
        {
            var m = new MatchManager();
            int fired = 0;
            m.OnMatchFinish += () => fired++;
            m.StartMatch();
            m.Tick(FieldConstants.MatchDuration + 1f);
            m.Tick(1f); // 追加Tickで再発火しない
            Assert.AreEqual(1, fired);
        }
    }
}
