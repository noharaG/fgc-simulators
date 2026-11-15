using FGC.Core;
using FGC.Sim.Shared;
using NUnit.Framework;

namespace FGC.Tests
{
    /// <summary>
    /// ECS用 FGC.Sim.Shared.DriveKinematics が、既存の FGC.Core.DifferentialDriveModel と
    /// **数値的に同値**であることを担保する回帰テスト（ECS移行で挙動が変わらないことの第一防衛線）。
    /// 同一入力列を両者へ流し、CurrentSpeed/CurrentOmega が一致することを確認する。
    /// </summary>
    public class DriveKinematicsEquivalenceTests
    {
        [Test]
        public void DriveKinematics_MatchesDifferentialDriveModel_OverInputSweep()
        {
            var spec = RobotSpec.Default;

            // 代表的な入力の組み合わせ（デッドゾーン境界・飽和・負値・微小値を含む）
            float[] inputs = { -1f, -0.7f, -0.3f, -0.05f, -0.02f, 0f, 0.02f, 0.05f, 0.3f, 0.7f, 1f, 1.5f };
            float[] dts = { 0.01f, 0.02f, 0.05f };

            foreach (float dt in dts)
            foreach (float fwd in inputs)
            foreach (float turn in inputs)
            {
                var model = new DifferentialDriveModel(spec);
                float ks = 0f, ko = 0f; // DriveKinematics の状態

                // 複数ステップ進めて加速度制限・収束も含めて一致を確認
                for (int step = 0; step < 30; step++)
                {
                    model.Step(new DriveCommand { Forward = fwd, Turn = turn }, dt);
                    DriveKinematics.Step(
                        spec.MaxSpeed, spec.MaxOmega, spec.MaxAccel, spec.MaxAlpha, spec.Deadzone,
                        fwd, turn, dt, ref ks, ref ko);

                    Assert.AreEqual(model.CurrentSpeed, ks, 1e-5f,
                        $"speed mismatch fwd={fwd} turn={turn} dt={dt} step={step}");
                    Assert.AreEqual(model.CurrentOmega, ko, 1e-5f,
                        $"omega mismatch fwd={fwd} turn={turn} dt={dt} step={step}");
                }

                // 左右輪速も一致
                var (l, r) = model.WheelSpeeds();
                DriveKinematics.WheelSpeeds(ks, ko, spec.WheelTrack, out float kl, out float kr);
                Assert.AreEqual(l, kl, 1e-5f, $"wheelL mismatch fwd={fwd} turn={turn} dt={dt}");
                Assert.AreEqual(r, kr, 1e-5f, $"wheelR mismatch fwd={fwd} turn={turn} dt={dt}");
            }
        }
    }
}
