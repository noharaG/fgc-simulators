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
    }
}
