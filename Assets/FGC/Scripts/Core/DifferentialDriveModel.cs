using UnityEngine;

namespace FGC.Core
{
    /// <summary>
    /// 差動2輪の運動学モデル。Java版 RobotPhysics.java の式を移植（PLAN.md §3.3）。
    /// 「車輪が路面に与える目標 v, ω」を算出する純ロジック。剛体への力適用は Sim層が担う。
    /// デッドゾーン → expoカーブ → 目標速度 → 加速度制限（アクティブブレーキ含む）。
    /// </summary>
    public sealed class DifferentialDriveModel
    {
        private readonly RobotSpec _spec;
        public float CurrentSpeed { get; private set; }   // m/s（前進＋）
        public float CurrentOmega { get; private set; }   // rad/s（旋回）

        private const float ExpoFactor = 0.6f; // expoカーブ強度（0=線形,1=3乗）

        public DifferentialDriveModel(RobotSpec spec)
        {
            _spec = spec;
        }

        public void Reset()
        {
            CurrentSpeed = 0f;
            CurrentOmega = 0f;
        }

        /// <summary>入力指令から目標 v, ω を更新。dt秒進めた値を返す。</summary>
        public void Step(DriveCommand cmd, float dt)
        {
            cmd = cmd.Clamped();
            float fwd = Expo(ApplyDeadzone(cmd.Forward, _spec.Deadzone));
            float turn = Expo(ApplyDeadzone(cmd.Turn, _spec.Deadzone));

            float targetSpeed = fwd * _spec.MaxSpeed;
            float targetOmega = turn * _spec.MaxOmega;

            CurrentSpeed = Approach(CurrentSpeed, targetSpeed, _spec.MaxAccel, dt);
            CurrentOmega = Approach(CurrentOmega, targetOmega, _spec.MaxAlpha, dt);
        }

        /// <summary>左右輪の周速 (left, right) を返す。Sim層で接地速度制御に使う。</summary>
        public (float left, float right) WheelSpeeds()
        {
            float half = _spec.WheelTrack * 0.5f;
            return (CurrentSpeed - CurrentOmega * half, CurrentSpeed + CurrentOmega * half);
        }

        private static float ApplyDeadzone(float v, float dz)
        {
            if (Mathf.Abs(v) < dz) return 0f;
            // デッドゾーン外を [0,1] に再正規化（符号維持）
            float sign = Mathf.Sign(v);
            return sign * (Mathf.Abs(v) - dz) / (1f - dz);
        }

        private static float Expo(float v)
        {
            // アクティブブレーキ的な操作感：小入力を緩く、大入力を素直に
            return Mathf.Lerp(v, v * v * v, ExpoFactor);
        }

        /// <summary>current を target に最大変化率 maxRate で近づける（加速度/角加速度制限）。</summary>
        private static float Approach(float current, float target, float maxRate, float dt)
        {
            float maxStep = maxRate * dt;
            float diff = target - current;
            if (Mathf.Abs(diff) <= maxStep) return target;
            return current + Mathf.Sign(diff) * maxStep;
        }
    }
}
