using FGC.Ball;
using FGC.Core;
using FGC.Robot;
using UnityEngine;

namespace FGC.App
{
    /// <summary>
    /// ロボのインテーク（吸引）機構（PLAN.md §3.3 機構・次フェーズ①）。
    /// ロボ前方に isTrigger のコライダを置き、Intake 指令が有効な間、範囲内の「場に出ている球」を
    /// 容量上限まで回収（HeldBalls++・WildfireManager から退避）。吸引にはクールダウンを設ける。
    /// Robot 層は Ball 層に依存させたくないため、本コンポーネントは App 層に置き両者を結線する。
    /// </summary>
    [RequireComponent(typeof(RobotActor))]
    public sealed class RobotIntake : MonoBehaviour
    {
        public WildfireManager wildfire;
        public int capacity = FieldConstants.RobotBallCapacity; // 保持上限（=10, RobotConfig.java正典）
        public float intakeInterval = 0.15f; // 1球あたりの吸引間隔[s]
        // 口の寸法は正典の前面幅0.50・奥行0.08を反映（高さは球が入る程度）
        public Vector3 mouthOffset = new Vector3(0f, 0.10f, 0.25f + FieldConstants.IntakeFrontDepth * 0.5f);
        public Vector3 mouthSize = new Vector3(FieldConstants.IntakeFrontWidth, 0.20f, FieldConstants.IntakeFrontDepth + 0.12f);

        private RobotActor _actor;
        private float _cooldown;
        private GameObject _trigger;

        private void Awake()
        {
            _actor = GetComponent<RobotActor>();
            CreateTrigger();
        }

        private void CreateTrigger()
        {
            // IntakeMouth はロボ(Rigidbody)の子＝複合コライダ。Unity仕様によりトリガイベントは
            // Rigidbody所有者（=このRobotIntakeのGameObject）に送られるため、OnTriggerStayは本クラスで受ける。
            _trigger = new GameObject("IntakeMouth");
            _trigger.transform.SetParent(transform, false);
            _trigger.transform.localPosition = mouthOffset;
            var box = _trigger.AddComponent<BoxCollider>();
            box.isTrigger = true;
            box.size = mouthSize;
        }

        private void Update()
        {
            if (_cooldown > 0f) _cooldown -= Time.deltaTime;
        }

        // 複合コライダ（子のIntakeMouthトリガ）の重なりは親=この GameObject に通知される。
        private void OnTriggerStay(Collider other)
        {
            TryIntake(other.gameObject);
        }

        /// <summary>トリガ内に球が居る間に呼ばれる。条件を満たせば1球回収。</summary>
        private void TryIntake(GameObject candidate)
        {
            if (wildfire == null) return;
            if (!_actor.LastCommand.Intake) return;     // Intake指令が無ければ吸わない
            if (_actor.HeldBalls >= capacity) return;    // 満杯
            if (_cooldown > 0f) return;                  // クールダウン中

            if (wildfire.CollectBall(candidate))         // 場の球なら回収成功
            {
                _actor.HeldBalls++;
                _cooldown = intakeInterval;
            }
        }
    }
}
