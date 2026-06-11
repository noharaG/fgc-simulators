using UnityEngine;

namespace FGC.Core
{
    /// <summary>
    /// IRobotController に渡す観測。Human/RuleBasedNPC/MLAgent が共通で使う。
    /// 位置・速度はUnityワールド座標（XZ平面、Yは高さ）。ML観測ベクトルの素にもなる。
    /// </summary>
    public struct RobotObservation
    {
        public AllianceColor Alliance;
        public int SlotIndex;          // 0..5（赤0-2 / 青3-5）

        public Vector3 Position;       // 自ロボ位置
        public float HeadingRad;       // 自ロボの向き（XZ平面のyaw）
        public Vector3 Velocity;       // 自ロボ速度
        public float AngularVelocity;  // 旋回角速度

        public int HeldBalls;          // インテーク保持数
        public ClimbZone ClimbZone;    // 現在の登攀ゾーン

        public float MatchTimeRemaining; // 残時間[s]
        public int OwnRegionSuppression; // 自リージョンSUPPRESSION格納数
        public int GlobalExtinguisher;   // EXTINGUISHER格納数（GLOBAL）

        // 近傍球・相手位置はSim層で必要数だけ詰める（ML観測用の配列はController側で構築）。
        public Vector3 NearestBall;      // 最も近い未格納球の位置（無ければPosition）
        public bool HasNearestBall;

        // 近傍ロボ（3v3戦術学習用・PLAN §3.8）。位置はワールド座標、無ければ Has=false。
        public Vector3 NearestOpponent;
        public bool HasOpponent;
        public Vector3 NearestTeammate;
        public bool HasTeammate;

        // ===== 全能観測（2026-06-12 ユーザー要望: 「味方の意図」と「ボールの位置」を全能で） =====
        // 近傍2〜5番目の球（ワールド座標。BallsValid 未満のスロットのみ有効、無効時は Position と同値）
        public Vector3 Ball2, Ball3, Ball4, Ball5;
        public int BallsValid;        // 有効な近傍球の数（0〜5。1番目は HasNearestBall/NearestBall）
        public int FieldBallCount;    // 場に残っている球の総数（供給の枯渇を知覚できる）

        // 味方2台の「意図」: 位置＋速度（進行方向＝何をしに行くか）＋保持数（満載→ゴールへ/空→回収へ）
        public Vector3 Mate1Pos, Mate1Vel; public int Mate1Held; public bool HasMate1;
        public Vector3 Mate2Pos, Mate2Vel; public int Mate2Held; public bool HasMate2;

        /// <summary>全500球の密度グリッド（7×7マス・各マスの球数。ワールド固定・全ロボ共有参照）。
        /// 「ボール500個すべての位置を常に把握」のための学習可能表現（2026-06-12 ユーザー要望）。null可。</summary>
        public float[] BallGrid;

        // 敵3台の「見える情報」: 位置と向きのみ（2026-06-12 ユーザー要望の情報非対称）。
        // 選手は敵の今の位置・向きは見えるが「どのボールを狙うか」等の意図は読めない＝速度/保持数は与えない。
        public Vector3 Opp1Pos; public float Opp1HeadingRad; public bool HasOpp1;
        public Vector3 Opp2Pos; public float Opp2HeadingRad; public bool HasOpp2;
        public Vector3 Opp3Pos; public float Opp3HeadingRad; public bool HasOpp3;
    }
}
