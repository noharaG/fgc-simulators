using FGC.Core;
using UnityEngine;

namespace FGC.Robot
{
    /// <summary>
    /// ルールベースNPC（PLAN.md §3.2）。**Java競技NPC(CompetitionManager)の差動2輪操舵則を忠実移植**：
    ///   heading=純目標70%＋移動方向(回避込)30%ブレンド → 旋回はPD(kP=3.5/kD=1.0)・5°デッドバンド・±4rad/s、
    ///   前進は45°ゲート内で正対度×距離比。近接ロボ回避(1.0m)＋壁回避(0.4m)も Behaviors.java から移植。
    /// 簡易ステートマシン: SeekBall（最寄り球へ）→ 近接でIntake → 保持中はGoalへ → 射程内＆正対でShoot。
    /// ML版に差し替え可能なよう IRobotController として実装。指令は正規化 [-1,1] に変換して返す。
    /// </summary>
    public sealed class RuleBasedNpcController : IRobotController
    {
        // Java競技NPC(CompetitionManager)の差動2輪操舵則を忠実移植（練習Behaviorsのkp=6ではなく競技用PD）。
        private const float KpOmega = 3.5f;        // 旋回P（DIFF_KP_OMEGA）
        private const float KdOmega = 1.0f;        // 旋回D（DIFF_KD_OMEGA・errorDot≈-角速度で減衰）
        private const float AngleDeadband = 0.087f; // 旋回デッドバンド5°（首振り防止・DIFF_ANGLE_DEADBAND）
        private const float ForwardAngle = Mathf.PI / 4f; // 前進開始角度差45°（DIFF_FORWARD_ANGLE）
        private const float HeadPureBlend = 0.7f;  // heading=純目標70%＋移動方向(回避込)30%（DIFF_HEAD_PURE_BLEND）
        private const float MaxOmega = 4.0f;       // 競技は config.maxOmega(=4.0) でクランプ
        private const float RobotSpecMaxSpeed = 1.51f; // 最高速[m/s]（RobotConfig 1/15ギア・×0.8込み・正典）。前進の距離比正規化に使用
        private const float IntakeRange = 0.35f;  // この距離で回収
        private const int Capacity = FieldConstants.RobotBallCapacity; // 保持上限（=10, 正典）
        // Java版 Behaviors.java の回避則（NPCの動きの忠実反映）:
        private const float AvoidRadius = 1.0f;    // seekBallAvoidPlayer: この距離内の他ロボへ回避ベクトルをブレンド
        private const float AvoidWeight = 1.5f;    // 回避ベクトルの強さ（Java同値）
        private const float WallAvoidLimit = 0.4f; // isNearWall: 進行方向の壁がこの距離内なら前進を止め旋回で回避
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

            Vector2 toTarget = target - self;
            float dist = toTarget.magnitude;            // 目標(球/ゴール)までの距離。intake/shoot判定はこれを使う
            if (dist < 1e-4f)
                return new DriveCommand { Intake = wantIntake, Shoot = wantShoot };

            // Java seekBallAvoidPlayer 移植: 目標方向に、近接する他ロボ(敵/味方)からの回避ベクトルをブレンド。
            // これにより球やゴールへ向かいつつ、近くのロボを避けて回り込む（Java NPCの動きを忠実反映）。
            // heading目標: 純目標方向(70%)＋近接ロボ回避を加えた移動方向(30%)をブレンド（Java競技NPC DIFF_HEAD_PURE_BLEND）。
            Vector2 pureDir = toTarget / dist;
            Vector2 moveDir = pureDir;
            moveDir = BlendAvoidance(moveDir, self, obs.HasOpponent, obs.NearestOpponent);
            moveDir = BlendAvoidance(moveDir, self, obs.HasTeammate, obs.NearestTeammate);
            Vector2 steerDir = HeadPureBlend * pureDir + (1f - HeadPureBlend) * moveDir;
            if (steerDir.sqrMagnitude > 1e-12f) steerDir.Normalize(); else steerDir = pureDir;

            // Unity の eulerAngles.y(θ) は forward=(sinθ,0,cosθ)。目標へ向く角は atan2(Δx, Δz)。
            float targetHeading = Mathf.Atan2(steerDir.x, steerDir.y);
            float err = NormalizeAngle(targetHeading - heading);
            float absErr = Mathf.Abs(err);

            // 旋回: デッドバンド5°内は停止（首振り防止）、外はPD制御（errorDot≈-現在角速度で減衰）。±MaxOmegaでクランプ。
            float omega = 0f;
            if (absErr >= AngleDeadband)
                omega = Mathf.Clamp(err * KpOmega + (-obs.AngularVelocity) * KdOmega, -MaxOmega, MaxOmega);

            // 射撃ゲート（正典）: 満杯保持・射程内・ゴールへ正対(15°以内) のとき発射
            if (holdingFull
                && dist <= FieldConstants.ShootRange
                && absErr <= FieldConstants.ShootAngleThreshold)
            {
                wantShoot = true;
            }

            // 前進: 45°ゲート内で「正対度(1-|err|/45°)」×「距離比 min(1, dist*3/最高速)」。正対＆遠いほど速い（Java競技NPC同値）。
            float fwd = 0f;
            if (absErr < ForwardAngle && dist > 0.05f)
            {
                float alignFactor = 1f - absErr / ForwardAngle;
                float distFactor = Mathf.Min(1f, dist * 3f / RobotSpecMaxSpeed);
                fwd = distFactor * alignFactor;
            }
            // Java isNearWall 移植: 進行方向の壁が近ければ前進を止める（旋回は継続＝壁から向きを変えて回避）
            if (IsNearWall(self, heading, WallAvoidLimit)) fwd = 0f;

            return new DriveCommand
            {
                Forward = fwd,
                Turn = Mathf.Clamp(omega / MaxOmega, -1f, 1f),
                Intake = wantIntake,
                Shoot = wantShoot,
                Climb = false
            };
        }

        /// <summary>Java seekBallAvoidPlayer 移植: 近接する他ロボから離れる回避ベクトルを進行方向にブレンド。
        /// AvoidRadius(1.0m)内のとき、近いほど強く(重み×1.5)離れる方向を足して正規化する。</summary>
        private static Vector2 BlendAvoidance(Vector2 dir, Vector2 self, bool has, Vector3 other)
        {
            if (!has) return dir;
            Vector2 o = new Vector2(other.x, other.z);
            float d = Vector2.Distance(self, o);
            if (d >= AvoidRadius || d < 1e-6f) return dir;
            Vector2 away = (self - o) / d;
            float w = (AvoidRadius - d) / AvoidRadius;   // 0〜1（近いほど大）
            Vector2 blended = dir + away * (w * AvoidWeight);
            return blended.sqrMagnitude > 1e-12f ? blended.normalized : dir;
        }

        /// <summary>Java isNearWall 移植: 進行方向(forward=(sinθ,cosθ))のフィールド外壁までの距離が limit 未満か。
        /// 7m四方の各辺(x=0/x=7/z=0/z=7)へのレイ距離の最小値で判定する。self=(x,z)。</summary>
        private static bool IsNearWall(Vector2 self, float heading, float limit)
        {
            float fx = Mathf.Sin(heading), fz = Mathf.Cos(heading);
            float sx = FieldConstants.FieldSizeX, sz = FieldConstants.FieldSizeZ;
            float dW = fx < 0f ? self.x / -fx : float.MaxValue;        // x=0（西）
            float dE = fx > 0f ? (sx - self.x) / fx : float.MaxValue;  // x=7（東）
            float dS = fz < 0f ? self.y / -fz : float.MaxValue;        // z=0（南）
            float dN = fz > 0f ? (sz - self.y) / fz : float.MaxValue;  // z=7（北）
            float min = Mathf.Min(Mathf.Min(dW, dE), Mathf.Min(dS, dN));
            return min < limit;
        }

        private static float NormalizeAngle(float a)
        {
            while (a > Mathf.PI) a -= 2f * Mathf.PI;
            while (a < -Mathf.PI) a += 2f * Mathf.PI;
            return a;
        }
    }
}
