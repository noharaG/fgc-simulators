using FGC.Core;
using UnityEngine;

namespace FGC.Robot
{
    /// <summary>
    /// ルールベースNPC（PLAN.md §3.2）。既存Java版 Behaviors.java の操舵則を移植：
    ///   steerTowards（P制御 kP=6.0・最大±3.0rad/s）で目標方位へ旋回し、最寄りボールへ向かう。
    /// 簡易ステートマシン: SeekBall（最寄り球へ）→ 近接でIntake → 保持中はGoalへ → 近接でShoot。
    /// ML版に差し替え可能なよう IRobotController として実装。指令は正規化 [-1,1] に変換して返す。
    /// </summary>
    public sealed class RuleBasedNpcController : IRobotController
    {
        private const float KP = 6.0f;
        private const float MaxOmega = 3.0f;     // Java版の操舵上限
        private const float CruiseForward = 0.7f; // 巡航前進フラクション
        private const float IntakeRange = 0.35f;  // この距離で回収
        private const int Capacity = FieldConstants.RobotBallCapacity; // 保持上限（=10, 正典）
        // 射撃ゲートは正典値を使用: 射程 FieldConstants.ShootRange(2.0)・正対 ShootAngleThreshold(15°)

        public DriveCommand GetCommand(in RobotObservation obs, float dt)
        {
            Vector2 self = new Vector2(obs.Position.x, obs.Position.z);
            float heading = obs.HeadingRad;

            // スコア対象ゴール＝自リージョンのSUPPRESSION（FieldLayoutに集約）
            Vector2 goalTarget = FieldLayout.NpcGoal(obs.Alliance);

            bool holdingFull = obs.HeldBalls >= Capacity;

            // 目標点の決定：満杯ならゴール、それ以外は最寄り球（無ければゴールへ向かい待機）
            Vector2 target;
            bool wantIntake = false, wantShoot = false;

            if (holdingFull)
            {
                target = goalTarget;
                // 射撃判断は下で方位誤差も見て決める（射程内＋正対15°以内）
            }
            else if (obs.HasNearestBall)
            {
                target = new Vector2(obs.NearestBall.x, obs.NearestBall.z);
                if (Vector2.Distance(self, target) < IntakeRange) wantIntake = true;
            }
            else
            {
                // 球が見えない：ゴール側で待機巡回
                target = goalTarget;
            }

            Vector2 dir = target - self;
            float dist = dir.magnitude;
            if (dist < 1e-4f)
                return new DriveCommand { Intake = wantIntake, Shoot = wantShoot };

            // Unity の eulerAngles.y(θ) は forward=(sinθ,0,cosθ)。目標へ向く角は atan2(Δx, Δz)。
            // dir=(Δx, Δz) なので atan2(dir.x, dir.y)。obs.HeadingRad(=eulerAngles.y) と同系で収束する。
            float targetHeading = Mathf.Atan2(dir.x, dir.y);
            float headingErr = Mathf.Abs(NormalizeAngle(heading - targetHeading));
            float omega = SteerTowards(heading, targetHeading);

            // 射撃ゲート（正典）: 満杯保持・射程内・ゴールへ正対(15°以内) のとき発射
            if (holdingFull
                && dist <= FieldConstants.ShootRange
                && headingErr <= FieldConstants.ShootAngleThreshold)
            {
                wantShoot = true;
            }

            // 向きが大きくずれている間は減速して旋回優先（Java版踏襲: >0.6radで0.3倍）
            float fwd = CruiseForward;
            if (headingErr > 0.6f) fwd *= 0.3f;
            // ほぼ到達したら前進を緩める
            if (dist < IntakeRange) fwd *= 0.4f;

            return new DriveCommand
            {
                Forward = fwd,
                Turn = Mathf.Clamp(omega / FieldNpcMaxOmega(), -1f, 1f),
                Intake = wantIntake,
                Shoot = wantShoot,
                Climb = false
            };
        }

        // omega を正規化Turnへ変換する基準（ロボのMaxOmega=4rad/s想定）。
        private static float FieldNpcMaxOmega() => 4.0f;

        /// <summary>角度偏差をP制御で旋回角速度へ（Java版 steerTowards 移植・最大±3rad/s）。</summary>
        private static float SteerTowards(float currentHeading, float targetHeading)
        {
            float diff = NormalizeAngle(targetHeading - currentHeading);
            float omega = diff * KP;
            return Mathf.Clamp(omega, -MaxOmega, MaxOmega);
        }

        private static float NormalizeAngle(float a)
        {
            while (a > Mathf.PI) a -= 2f * Mathf.PI;
            while (a < -Mathf.PI) a += 2f * Mathf.PI;
            return a;
        }
    }
}
