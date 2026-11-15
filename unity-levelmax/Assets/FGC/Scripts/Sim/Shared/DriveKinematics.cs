namespace FGC.Sim.Shared
{
    /// <summary>
    /// 差動2輪の運動学（純数学・Burst対応・エンジン非依存）。
    /// FGC.Core.DifferentialDriveModel と**完全に同一の数式**を、ECSのBurst Jobから呼べる形で再実装したもの。
    /// 数式の正典は DifferentialDriveModel（Java版 RobotPhysics.java 由来, PLAN.md §3.3）。
    /// 両者の同値は FGC.Tests の DriveKinematicsEquivalenceTests で担保する（ドリフト検出）。
    ///
    /// なぜ Core に置かず別実装か: 依存グラフは FGC.Core ← FGC.Sim.Shared ← FGC.Sim.ECS。
    /// Core が Shared を参照すると循環するため、Core(=既存テストの参照先)は不変のまま、
    /// ECS が使う Burst 安全な数学版をここに置く。UnityEngine(Mathf/Vector3)に依存しない。
    /// </summary>
    public static class DriveKinematics
    {
        public const float ExpoFactor = 0.6f; // DifferentialDriveModel と同値

        /// <summary>
        /// 入力指令(forward/turn, [-1,1])から目標 v, ω を dt 秒分更新する。
        /// currentSpeed/currentOmega を ref で受け、加速度/角加速度制限つきで近づける。
        /// DifferentialDriveModel.Step と同一手順: clamp → deadzone → expo → target → approach。
        /// </summary>
        public static void Step(
            float maxSpeed, float maxOmega, float maxAccel, float maxAlpha, float deadzone,
            float forwardInput, float turnInput, float dt,
            ref float currentSpeed, ref float currentOmega)
        {
            float fwd = Expo(ApplyDeadzone(Clamp01Signed(forwardInput), deadzone));
            float turn = Expo(ApplyDeadzone(Clamp01Signed(turnInput), deadzone));

            float targetSpeed = fwd * maxSpeed;
            float targetOmega = turn * maxOmega;

            currentSpeed = Approach(currentSpeed, targetSpeed, maxAccel, dt);
            currentOmega = Approach(currentOmega, targetOmega, maxAlpha, dt);
        }

        /// <summary>左右輪の周速 (left, right)。wheelTrack=左右輪間距離。</summary>
        public static void WheelSpeeds(float currentSpeed, float currentOmega, float wheelTrack,
            out float left, out float right)
        {
            float half = wheelTrack * 0.5f;
            left = currentSpeed - currentOmega * half;
            right = currentSpeed + currentOmega * half;
        }

        /// <summary>★2026-06-12: NPC/ML用の**直結版**（deadzone/expo無し・線形マッピング）。
        /// Step() の expo/deadzone は人間スティック用の入力整形で、Java正典でもNPC(NpcRobot.integrate)は
        /// 生の目標値を直接ランプしていた。Unity移植時に全コントローラへ整形が掛かったため
        /// 「指令0.7が実効0.49」「微小旋回がdeadzoneで消える」＝旋回が遅い症状の原因になっていた。</summary>
        public static void StepDirect(
            float maxSpeed, float maxOmega, float maxAccel, float maxAlpha,
            float forwardInput, float turnInput, float dt,
            ref float currentSpeed, ref float currentOmega)
        {
            float targetSpeed = Clamp01Signed(forwardInput) * maxSpeed;
            float targetOmega = Clamp01Signed(turnInput) * maxOmega;
            currentSpeed = Approach(currentSpeed, targetSpeed, maxAccel, dt);
            currentOmega = Approach(currentOmega, targetOmega, maxAlpha, dt);
        }

        /// <summary>★2026-06-12: 差動2輪の**車輪速度飽和**。前進+旋回の合算 |v ± ω·track/2| が
        /// 車輪上限(=maxSpeed)を超える場合、v・ω を同率で縮め曲率を保ったまま物理上限内へ収める。
        /// Java正典は車輪速度を統計用に逆算するだけで飽和させない（v満速+ω満速が同時に出る）が、
        /// 実機では不可能なためユーザー要望で追加（曲率保存スケーリング）。</summary>
        public static void SaturateToWheelLimit(ref float speed, ref float omega, float wheelTrack, float maxWheelSpeed)
        {
            float half = wheelTrack * 0.5f;
            float left = speed - omega * half;
            float right = speed + omega * half;
            float la = Abs(left), ra = Abs(right);
            float worst = la > ra ? la : ra;
            if (worst <= maxWheelSpeed || worst < 1e-6f) return;
            float k = maxWheelSpeed / worst;
            speed *= k;
            omega *= k;
        }

        private static float ApplyDeadzone(float v, float dz)
        {
            if (Abs(v) < dz) return 0f;
            float sign = Sign(v);
            return sign * (Abs(v) - dz) / (1f - dz);
        }

        // DifferentialDriveModel.Expo と同値: Mathf.Lerp(v, v*v*v, ExpoFactor)
        private static float Expo(float v) => Lerp(v, v * v * v, ExpoFactor);

        private static float Approach(float current, float target, float maxRate, float dt)
        {
            float maxStep = maxRate * dt;
            float diff = target - current;
            if (Abs(diff) <= maxStep) return target;
            return current + Sign(diff) * maxStep;
        }

        // --- Mathf 同値ヘルパ（Burst安全・UnityEngine非依存） ---
        private static float Abs(float v) => v < 0f ? -v : v;
        // UnityEngine.Mathf.Sign と同じく 0 は +1 を返す（math.sign の 0→0 とは異なる）
        private static float Sign(float v) => v >= 0f ? 1f : -1f;
        // UnityEngine.Mathf.Lerp は t を[0,1]にclampするが、ここでの t は定数0.6固定なので未clampでも同値
        private static float Lerp(float a, float b, float t) => a + (b - a) * t;

        private static float Clamp01Signed(float v)
        {
            if (v < -1f) return -1f;
            if (v > 1f) return 1f;
            return v;
        }
    }
}
