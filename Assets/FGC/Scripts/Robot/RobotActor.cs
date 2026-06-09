using FGC.Core;
using UnityEngine;

namespace FGC.Robot
{
    /// <summary>
    /// フル3D剛体ロボ（PLAN.md §3.3）。IRobotController から指令を受け、DifferentialDriveModel で
    /// 目標 v, ω を算出し、Rigidbody に速度制御として適用する。転倒・段差・登攀は剛体に委ねる。
    /// 6スロットに同じ RobotActor を置き、Controller を差し替えて Human/NPC/ML を実現する。
    /// </summary>
    [RequireComponent(typeof(Rigidbody))]
    public sealed class RobotActor : MonoBehaviour
    {
        [Header("構成")]
        public AllianceColor alliance = AllianceColor.Red;
        public int slotIndex = 0;

        public RobotSpec spec = RobotSpec.Default;

        /// <summary>差し替え可能な制御ソース。Human / RuleBasedNPC / MLAgent のいずれか。</summary>
        public IRobotController Controller { get; set; }

        /// <summary>試合フェーズ中のみ true。準備フェーズ／試合終了後は false で駆動・機構を停止し待機する。</summary>
        public bool Active { get; set; }

        private Rigidbody _rb;
        private DifferentialDriveModel _drive;

        // 観測に詰める外部参照（Sim層が注入）。
        public System.Func<RobotObservation> ObservationProvider;

        public ClimbZone ClimbZone { get; set; } = ClimbZone.None;
        public int HeldBalls { get; set; }

        /// <summary>直近フレームで Controller が返した指令（機構コンポーネントが参照）。</summary>
        public DriveCommand LastCommand { get; private set; } = DriveCommand.Zero;

        private void Awake()
        {
            EnsureInit();
        }

        /// <summary>_rb/_drive を初期化（未生成なら生成）。spec は構造体なので常に有効。
        /// Awake と FixedUpdate の両方から呼び、初期化順やAwakeの不完全実行に依存せず動かす。</summary>
        private void EnsureInit()
        {
            if (_rb == null) _rb = GetComponent<Rigidbody>();
            if (_rb != null)
            {
                _rb.mass = spec.Mass;
                _rb.constraints = RigidbodyConstraints.FreezeRotationX | RigidbodyConstraints.FreezeRotationZ;
                _rb.interpolation = RigidbodyInterpolation.Interpolate;
            }
            if (_drive == null) _drive = new DifferentialDriveModel(spec);
        }

        private void FixedUpdate()
        {
            if (_drive == null || _rb == null) EnsureInit();

            float dt = Time.fixedDeltaTime;

            DriveCommand cmd = DriveCommand.Zero;
            if (Active && Controller != null)
            {
                RobotObservation obs = ObservationProvider != null ? ObservationProvider() : BuildMinimalObservation();
                cmd = Controller.GetCommand(in obs, dt);
            }
            LastCommand = cmd;

            // 準備フェーズ(Active=false)は cmd=Zero で減速・待機。機構も止める。
            _drive.Step(cmd, dt);
            ApplyDrive(_drive.CurrentSpeed, _drive.CurrentOmega, dt);
            if (Active) HandleMechanisms(cmd);
        }

        /// <summary>目標前進速度・旋回角速度を剛体に適用（接地前提の速度制御）。</summary>
        private void ApplyDrive(float targetSpeed, float targetOmega, float dt)
        {
            // _drive.Step が既に MaxAccel/MaxAlpha でランプ済みの目標値。床の回転/並進摩擦に
            // 速度・角速度を食われないよう、毎フレーム「直接」セットする（アーケード式velocity制御）。
            // ※MoveTowards で実速度(av.y)から積み上げると、摩擦が毎ステップ0へ引き戻すため指令角速度に届かない。
            Vector3 forward = transform.forward;
            forward.y = 0f;
            forward.Normalize();

            Vector3 v = _rb.linearVelocity;
            Vector3 horiz = forward * targetSpeed;
            _rb.linearVelocity = new Vector3(horiz.x, v.y, horiz.z); // 水平のみ目標へ。Y(重力)は残す

            Vector3 av = _rb.angularVelocity;
            _rb.angularVelocity = new Vector3(av.x, targetOmega, av.z); // ヨーは目標を直接
        }

        private void HandleMechanisms(DriveCommand cmd)
        {
            // インテーク/シュート/登攀の具体機構は次段（トリガ/ジョイント）で実装。
            // 現状はフラグ受け取りのみ（Control/MLの差込口を確定させる目的）。
        }

        /// <summary>エピソード/試合リセット：位置・速度・保持・登攀・運動学を初期化（ML連続学習用）。</summary>
        public void ResetState(Vector3 position, Quaternion rotation)
        {
            transform.SetPositionAndRotation(position, rotation);
            if (_rb != null)
            {
                _rb.linearVelocity = Vector3.zero;
                _rb.angularVelocity = Vector3.zero;
            }
            _drive?.Reset();
            HeldBalls = 0;
            ClimbZone = ClimbZone.None;
            LastCommand = DriveCommand.Zero;
        }

        private RobotObservation BuildMinimalObservation()
        {
            return new RobotObservation
            {
                Alliance = alliance,
                SlotIndex = slotIndex,
                Position = transform.position,
                HeadingRad = transform.eulerAngles.y * Mathf.Deg2Rad,
                Velocity = _rb.linearVelocity,
                AngularVelocity = _rb.angularVelocity.y,
                HeldBalls = HeldBalls,
                ClimbZone = ClimbZone,
                HasNearestBall = false,
                NearestBall = transform.position
            };
        }
    }
}
