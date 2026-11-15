using UnityEngine;

namespace FGC.Core
{
    /// <summary>
    /// ロボのスペック値。既存Java版 RobotConfig.java（参照専用）から移植（HANDOFF.md §6 / PLAN.md §3.3）。
    /// 差動2輪の運動学パラメータを派生プロパティで提供する。
    /// </summary>
    [System.Serializable]
    public struct RobotSpec
    {
        public float BodySize;        // 機体0.5m角
        public float Mass;            // 13.5kg
        public float WheelDiameter;   // 0.09m
        public float NoLoadRpm;       // 6000rpm
        public float GearRatio;       // 1/12, 1/15, 1/20 のいずれか（= 出力/入力。例 1/15）
        public float StallTorque;     // Tstall=0.105 N·m（モータ）
        public float Efficiency;      // η=0.85
        public float MaxOmega;        // 4 rad/s
        public float MaxAlpha;        // 12 rad/s^2
        public float Deadzone;        // 0.05
        public float WheelTrack;      // 左右輪間距離（差動2輪）。機体幅相当。

        /// <summary>既定スペック（gear=1/15）。Java版の標準構成。</summary>
        public static RobotSpec Default => new RobotSpec
        {
            BodySize = 0.5f,
            Mass = 13.5f,
            WheelDiameter = 0.09f,
            NoLoadRpm = 6000f,
            GearRatio = 1f / 15f,
            StallTorque = 0.105f,
            Efficiency = 0.85f,
            MaxOmega = 4f,
            MaxAlpha = 12f,
            Deadzone = 0.05f,
            WheelTrack = 0.5f
        };

        /// <summary>最高速 = (rpm/60)·gearRatio·π·D·0.8。1/15で≈1.88m/s。</summary>
        public float MaxSpeed =>
            (NoLoadRpm / 60f) * GearRatio * Mathf.PI * WheelDiameter * 0.8f;

        /// <summary>最大加速 = 2·Tstall·(1/gr)·η / (D/2) / mass。</summary>
        public float MaxAccel =>
            2f * StallTorque * (1f / GearRatio) * Efficiency / (WheelDiameter / 2f) / Mass;
    }
}
