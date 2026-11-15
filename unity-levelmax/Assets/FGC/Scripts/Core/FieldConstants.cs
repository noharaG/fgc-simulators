namespace FGC.Core
{
    /// <summary>
    /// FGC2026 "Igniting Innovation" のルール由来寸法（HANDOFF.md §6 / PLAN.md §3.5）。
    /// 単位はすべて SI（m, kg, s）。Unity座標は (x=x_field, y=height, z=y_field)、原点=フィールド左下隅。
    /// 既存Java版（参照専用）の数値は RobotSpec 側に保持。ここはフィールド/ルール定数のみ。
    /// </summary>
    public static class FieldConstants
    {
        // --- フィールド全体 ---
        public const float FieldSizeX = 7.0f;       // m（左右）
        public const float FieldSizeZ = 7.0f;       // m（奥行き）
        public const float RiserTopIsFloor = 0.0f;  // RISER天面=床=y0
        public const float GroundRaise = 0.70f;     // 地上からの嵩上げ70cm（観客床基準）
        public const float GuardrailHeight = 0.20f; // ガードレール高20cm

        // --- WILDFIRE（球） ---
        public const float BallRadius = 0.05f;      // 直径100mm
        public const float BallMass = 0.062f;       // 62g
        public const int BallCount = 500;
        public const float BallRestitution = 0.2f;
        public const float BallFriction = 0.4f;

        // --- 試合時間 ---
        public const float MatchDuration = 150.0f;  // 2:30
        public const float SettleTime = 5.0f;       // 終了5秒の静定判定（G21）

        // --- SUPPRESSION UNIT ---
        public const float SuppressionHeight = 2.01f;
        public const float SuppressionCanopyBottom = 1.65f;
        public const float SuppressionOverhang = 0.40f;

        // --- EXTINGUISHER（中央・試合開始時に500球放出） ---
        public const float ExtinguisherBackHeight = 2.01f;
        public const float ExtinguisherOpening = 1.66f;
        public const float ExtinguisherLedge = 0.40f;
        public const float ExtinguisherLowerOpening = 0.28f; // 下部開口（放出口）

        // --- BRACE（登攀対象の鋼管） ---
        public const float BraceLength = 6.4f;
        public const float BraceLowEnd = 0.24f;     // 低端24cm
        public const float BraceHighEnd = 1.97f;    // 高端197cm
        public const float BraceSag = 0.10f;        // 中央たわみ100mm
        public const float BraceRadius = 0.024f;    // φ半径約2.4cm

        // --- ゾーン / ステーション ---
        public const float RegionalZoneLength = 4.10f; // 410cm（ガードレール沿い）
        public const float RegionalZoneWidth = 0.50f;  // 50cm
        public const float AllianceStationOuter = 1.50f; // ALLIANCE STATION 外側150cm

        // --- 球放出（EXTINGUISHER下、Java値踏襲） ---
        public const float EmitSpeedMin = 1.0f;
        public const float EmitSpeedMax = 3.0f;
        public const float EmitRadius = 0.25f;

        // --- ロボ機構パラメータ（RobotConfig.java 正典より） ---
        public const int RobotBallCapacity = 10;       // intakeCapacity 回収上限
        public const float IntakeFrontDepth = 0.08f;   // 前面辺からの回収判定奥行き
        public const float IntakeFrontWidth = 0.50f;   // 前面辺の横幅
        public const float ShootRange = 2.0f;          // 射程
        public const float ShootAngleThreshold = 0.2618f; // 正対角度閾値（15°）
        public const float ShootSuccessProb = 0.7f;    // 射出成功確率
        public const float ShootBps = 5.0f;            // 秒間射出数 → fireInterval = 1/Bps
        public const float ShootMissRadius = 2.0f;     // 失敗時ばらつき半径
    }
}
