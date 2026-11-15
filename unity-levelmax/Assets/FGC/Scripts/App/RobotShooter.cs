using FGC.Ball;
using FGC.Core;
using FGC.Robot;
using UnityEngine;

namespace FGC.App
{
    /// <summary>
    /// シュート（射出）機構（PLAN.md §3.3・次フェーズ③）。Shoot 指令が有効な間、発射レートに従って
    /// 保持球を1個ずつ、機体前方＋仰角方向へ Rigidbody 放出する（白ゴール/格納口へ入れる）。
    /// 物理放出は WildfireManager.LaunchBall に委譲（球はプールから1個復活して場へ戻る）。
    /// </summary>
    [RequireComponent(typeof(RobotActor))]
    public sealed class RobotShooter : MonoBehaviour
    {
        public WildfireManager wildfire;
        public float launchSpeed = 6.0f;       // 射出初速[m/s]
        public float elevationDeg = 25f;       // 仰角[deg]
        public float fireInterval = 1f / FieldConstants.ShootBps; // 発射間隔[s]（=0.2, shootBPS5正典）
        public float aimSpreadDeg = 4f;        // 狙いの小ばらつき（左右仰角）。命中可否は3D物理で決まる。
        public Vector3 muzzleOffset = new Vector3(0f, 0.30f, 0.30f); // 前方やや上

        // 設計メモ: 既存Java版の shootSuccessProb(0.7)/shootMissRadius(2.0) は2D抽象の「命中サイコロ」。
        // 本3D版では球を実際に飛ばし、SUPPRESSION/EXTINGUISHER の格納トリガで成否が物理的に決まる（PLAN§3.4）。
        // よって確率は使わず、代わりに aimSpreadDeg の小ばらつきで人/機構の狙いの不完全さを表現する。

        private RobotActor _actor;
        private float _cooldown;

        private void Awake() => _actor = GetComponent<RobotActor>();

        private void Update()
        {
            if (_cooldown > 0f) { _cooldown -= Time.deltaTime; return; }
            if (wildfire == null) return;
            if (!_actor.LastCommand.Shoot) return;
            if (_actor.HeldBalls <= 0) return;

            Fire();
            _cooldown = fireInterval;
        }

        private void Fire()
        {
            // 前方（XZ）に仰角を加えた射出方向
            Vector3 fwd = transform.forward; fwd.y = 0f; fwd.Normalize();
            Vector3 axis = transform.right;
            Vector3 dir = Quaternion.AngleAxis(-elevationDeg, axis) * fwd; // 上向きに傾ける

            // 狙いの小ばらつき（左右ヨー・上下ピッチに ±aimSpread/2）。命中可否は格納トリガ＝物理で決まる。
            float yaw = Random.Range(-aimSpreadDeg, aimSpreadDeg) * 0.5f;
            float pitch = Random.Range(-aimSpreadDeg, aimSpreadDeg) * 0.5f;
            dir = Quaternion.AngleAxis(yaw, Vector3.up) * Quaternion.AngleAxis(pitch, axis) * dir;

            Vector3 muzzle = transform.TransformPoint(muzzleOffset);
            var ball = wildfire.LaunchBall(muzzle, dir * launchSpeed);
            if (ball != null)
            {
                _actor.HeldBalls--;
                // 撃った主と有効期限を付与（ゴール到達で StorageTrigger が得点化＋報酬帰属）
                var shot = ball.GetComponent<ShotBall>();
                if (shot == null) shot = ball.AddComponent<ShotBall>();
                shot.Arm(_actor, 3.0f);
            }
        }
    }
}
