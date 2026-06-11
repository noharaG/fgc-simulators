using System.Collections.Generic;
using FGC.Core;
using FGC.Input;
using FGC.Match;
using FGC.Robot;
using FGC.Sim.ECS;
using FGC.Sim.Shared;
using Unity.Collections;
using Unity.Entities;
using Unity.Mathematics;
using Unity.Physics;
using Unity.Transforms;
using UnityEngine;
// UnityEngine と Unity.Physics の型名衝突をエイリアスで明示（EcsBallSim と同じ流儀）。
using PxCollider = Unity.Physics.Collider;
using PxMaterial = Unity.Physics.Material;
using PxBox = Unity.Physics.BoxCollider;

namespace FGC.App
{
    /// <summary>
    /// 段階③: 6ロボを **ECS Entity + Unity Physics(DOTS)** の動的剛体にして、段階②のECS球と
    /// **同じ物理ワールド**へ入れる。これで段階②で失われた「球↔ロボの押し出し/堆積干渉」が復活する。
    ///
    /// 設計（HANDOFF §新セッション「次の一手」/ 9.段階③ に準拠。EcsBallSim と同じ MonoBehaviour+EntityManager 流儀で低リスク化）:
    ///  - 各ロボ = Box(0.5×0.493×0.5)・質量13.5kg・**X/Z回転凍結**(InverseInertia.x/z=0=慣性テンソル無限大)・
    ///    **7mmグランドクリアランス**(box底面 y=0.007＝床から7mm浮かせ、≤7mmの微小干渉物を無視)。
    ///  - 駆動は **PhysicsVelocity 直書き**で旧 RobotActor.ApplyDrive と同値再現
    ///    （水平速度=前方×目標速、Y(重力)は保持、角速度=ヨーのみ目標を直接）。
    ///  - 指令: 人間スロット=GamepadInput、その他=既存の RuleBasedNpcController を**そのまま再利用**
    ///    （観測の最寄り球はECS球を直接クエリ）。運動学は FGC.Sim.Shared.DriveKinematics（Coreと同値担保済）。
    ///  - 描画/機構/観測/スコアは GameObject の RobotActor 側に残置（段階④まで非機能でOK）。
    ///    毎フレーム **ECS Entity → GameObject Transform** を同期し、可視メッシュ/HUD/マーカーが物理に追従する。
    ///    GameObject の Rigidbody は MatchDirector が kinematic 化＝二重駆動を回避（HANDOFFの「二重管理に注意」対策）。
    /// </summary>
    public sealed class EcsRobotSim : MonoBehaviour
    {
        // ロボ剛体の形状: 7mmクリアランスのため底面を y=0.007 に（box高さ0.486・中心y0.25）。
        private const float BoxFull = 0.5f;
        private const float GroundClearance = 0.007f;   // 床から7mm（HANDOFFのロボ要件）
        private const float BoxHeight = BoxFull - GroundClearance * 2f; // 0.486（上面は0.493）
        private const float BoxCenterY = 0.25f;         // entity原点(床y0)基準。底面=0.25-0.243=0.007

        /// <summary>1ロボの ECS/同期情報。GameObject側 RobotActor とペアで保持する。</summary>
        private sealed class Bot
        {
            public Entity Entity;
            public RobotActor Actor;       // 同期先(可視/機構/観測のGameObjectシェル)
            public AllianceColor Alliance;
            public int SlotIndex;
            public RobotSpec Spec;
            public float Speed;            // DriveKinematics の保持状態（目標前進速度）
            public float Omega;            // 同（目標角速度）
            public IRobotController Npc;   // 既存のルールベースNPCを再利用
            // 観測用の実速度（kinematic GameObject rb は速度0のため、ECS結果からここで供給する）。
            public Vector3 LinVel;         // ワールド線速度[m/s]（PhysicsVelocity.Linear）
            public float YawRate;          // ワールドyaw角速度[rad/s]（heading差分/dt・eulerAngles.y系）
            public float PrevYawDeg;       // 前フレームのyaw[deg]
            public bool HasPrevYaw;        // 初回フレーム判定
        }

        private World _world;
        private EntityManager _em;
        private readonly List<Bot> _bots = new List<Bot>(6);
        private readonly List<BlobAssetReference<PxCollider>> _ownedColliders = new List<BlobAssetReference<PxCollider>>(6);
        private EntityQuery _ballQuery;
        private MatchConfig _config;
        private bool _spawned;
        private bool _active;

        /// <summary>MatchDirector から呼ぶ：GameObjectロボ群に対応するECS剛体を生成し、同一物理ワールドへ入れる。</summary>
        public void Begin(IReadOnlyList<RobotActor> robots, MatchConfig config)
        {
            _config = config;
            _world = World.DefaultGameObjectInjectionWorld;
            if (_world == null) { Debug.LogError("[FGC] ECS Default World が無い。Entitiesパッケージ未解決の可能性。"); return; }
            _em = _world.EntityManager;

            if (!_spawned) Spawn(robots);
        }

        /// <summary>試合フェーズ開始/終了でロボ駆動をON/OFF（OFF中は水平静止して待機）。</summary>
        public void SetActive(bool active) => _active = active;

        /// <summary>環境リセット：各ロボを開始位置・静止へ戻す（autoRestart/再戦用）。</summary>
        public void ResetPositions()
        {
            if (!_spawned) return;
            foreach (var b in _bots)
            {
                Vector3 p = FieldLayout.RobotStart(b.SlotIndex, b.Alliance);
                quaternion q = ToQuaternion(FieldLayout.RobotStartRotation(b.Alliance));
                _em.SetComponentData(b.Entity, new LocalTransform
                {
                    Position = new float3(p.x, 0f, p.z),
                    Rotation = q,
                    Scale = 1f
                });
                _em.SetComponentData(b.Entity, new PhysicsVelocity());
                b.Speed = 0f;
                b.Omega = 0f;
            }
        }

        private void Spawn(IReadOnlyList<RobotActor> robots)
        {
            _ballQuery = _em.CreateEntityQuery(
                ComponentType.ReadOnly<BallTag>(), ComponentType.ReadOnly<LocalTransform>());

            for (int i = 0; i < robots.Count; i++)
            {
                var actor = robots[i];
                var spec = actor.spec;

                // ロボの共有コライダ（7mmクリアランス付きBox）。摩擦/反発は控えめ（速度直書きが支配的）。
                var boxCol = PxBox.Create(
                    new BoxGeometry
                    {
                        Center = new float3(0f, BoxCenterY, 0f),
                        Orientation = quaternion.identity,
                        Size = new float3(BoxFull, BoxHeight, BoxFull),
                        BevelRadius = 0.01f
                    },
                    CollisionFilter.Default,
                    new PxMaterial { Friction = 0.4f, Restitution = 0.0f });
                _ownedColliders.Add(boxCol);

                // X/Z回転を凍結（慣性テンソル無限大＝InverseInertia.x/z=0）。ヨー(y)のみ回せる＝転倒防止。
                var mass = PhysicsMass.CreateDynamic(boxCol.Value.MassProperties, spec.Mass);
                mass.InverseInertia = new float3(0f, mass.InverseInertia.y, 0f);

                Vector3 p = FieldLayout.RobotStart(actor.slotIndex, actor.alliance);
                quaternion q = ToQuaternion(FieldLayout.RobotStartRotation(actor.alliance));

                Entity e = _em.CreateEntity();
                _em.AddComponentData(e, new SimRobotTag());
                _em.AddComponentData(e, new LocalTransform { Position = new float3(p.x, 0f, p.z), Rotation = q, Scale = 1f });
                _em.AddComponentData(e, new PhysicsCollider { Value = boxCol });
                _em.AddComponentData(e, mass);
                _em.AddComponentData(e, new PhysicsVelocity());
                _em.AddComponentData(e, new PhysicsDamping { Linear = 0.05f, Angular = 0.05f });
                _em.AddComponentData(e, new PhysicsGravityFactor { Value = 1f });
                _em.AddSharedComponent(e, new PhysicsWorldIndex(0));

                _bots.Add(new Bot
                {
                    Entity = e,
                    Actor = actor,
                    Alliance = actor.alliance,
                    SlotIndex = actor.slotIndex,
                    Spec = spec,
                    Npc = new RuleBasedNpcController()
                });
            }

            _spawned = true;
            Debug.Log($"[FGC] ECSロボを生成: {_bots.Count}機（球と同一ワールドの動的剛体／7mmクリアランス／X-Z回転凍結）。");
        }

        private void FixedUpdate()
        {
            if (!_spawned) return;
            float dt = Time.fixedDeltaTime;

            // 全ロボ共通: 場のECS球(Disabled除外＝未格納球のみ)の位置を1回取得して使い回す。
            NativeArray<LocalTransform> balls = _ballQuery.ToComponentDataArray<LocalTransform>(Allocator.Temp);
            int humanSlot = _config != null ? _config.HumanSlotIndex() : -1;

            foreach (var b in _bots)
            {
                LocalTransform lt = _em.GetComponentData<LocalTransform>(b.Entity);
                float3 fwd = math.mul(lt.Rotation, new float3(0f, 0f, 1f));
                fwd.y = 0f;
                fwd = math.lengthsq(fwd) > 1e-6f ? math.normalize(fwd) : new float3(0f, 0f, 1f);
                float heading = math.atan2(fwd.x, fwd.z); // Unity forward=(sinθ,0,cosθ) と同系

                // 指令の取得: 人間スロット=Gamepad / その他=**RobotActorに割り当てられたController**（ML/NPC）。
                // ★重要バグ修正(2026-06-10): 従来は常に内部生成の b.Npc を使っており、MatchDirector が割り当てた
                // MLAgentController の行動が物理・機構に一切届かず「行動→報酬」の因果が切断されていた
                // （run9/run10の2000万stepが無意味化した根本原因）。Controller優先・b.Npcは保険のフォールバック。
                DriveCommand cmd = DriveCommand.Zero;
                bool isHuman = (b.SlotIndex == humanSlot)
                    && b.Alliance == (_config != null ? _config.PlayerAlliance : b.Alliance);
                if (_active)
                {
                    if (isHuman)
                    {
                        cmd = GamepadInput.Read();
                    }
                    else
                    {
                        var ctrl = (b.Actor != null && b.Actor.Controller != null) ? b.Actor.Controller : b.Npc;
                        // 観測は MatchDirector.BuildObservation(敵/味方/残時間込み)を優先。無ければECSローカル版。
                        RobotObservation obs = (b.Actor != null && b.Actor.ObservationProvider != null)
                            ? b.Actor.ObservationProvider()
                            : BuildObservation(b, lt.Position, heading, balls);
                        cmd = ctrl.GetCommand(in obs, dt);
                    }
                }
                // 機構(EcsMechanics)が参照する LastCommand を更新（ExternallyDriven中は本体が更新しないため）。
                if (b.Actor != null) b.Actor.SetLastCommand(cmd);

                // 運動学（加速度/角加速度ランプ）。
                // ★2026-06-12 足回り修正:
                //  - 人間入力のみ expo/deadzone 整形(Step)。NPC/MLは直結(StepDirect)＝Java正典のNPC経路と同じ。
                //    （従来は全員に整形が掛かり「指令0.7→実効0.49」「微小旋回がdeadzoneで消滅」＝旋回が遅かった）
                //  - 前進+旋回の合算が車輪上限を超えないよう飽和（曲率保存スケーリング・実機準拠）。
                if (isHuman)
                    DriveKinematics.Step(
                        b.Spec.MaxSpeed, b.Spec.MaxOmega, b.Spec.MaxAccel, b.Spec.MaxAlpha, b.Spec.Deadzone,
                        cmd.Forward, cmd.Turn, dt, ref b.Speed, ref b.Omega);
                else
                    DriveKinematics.StepDirect(
                        b.Spec.MaxSpeed, b.Spec.MaxOmega, b.Spec.MaxAccel, b.Spec.MaxAlpha,
                        cmd.Forward, cmd.Turn, dt, ref b.Speed, ref b.Omega);
                DriveKinematics.SaturateToWheelLimit(ref b.Speed, ref b.Omega, b.Spec.WheelTrack, b.Spec.MaxSpeed);

                // PhysicsVelocity 直書き（旧 ApplyDrive と同値）: 水平=前方×目標速、Y(重力)は保持、角速度=ヨーのみ。
                PhysicsVelocity pv = _em.GetComponentData<PhysicsVelocity>(b.Entity);
                float3 horiz = fwd * b.Speed;
                pv.Linear = new float3(horiz.x, pv.Linear.y, horiz.z);
                pv.Angular = new float3(0f, b.Omega, 0f);
                _em.SetComponentData(b.Entity, pv);
            }

            balls.Dispose();
        }

        /// <summary>ECS球を直接クエリして最寄り球を求め、NPCに渡す観測を組む（HeldBalls等の機構値はGameObject側を流用）。</summary>
        private RobotObservation BuildObservation(Bot b, float3 pos, float heading, NativeArray<LocalTransform> balls)
        {
            float3 nearest = pos;
            bool hasBall = false;
            float best = float.MaxValue;
            for (int i = 0; i < balls.Length; i++)
            {
                float3 bp = balls[i].Position;
                float dx = bp.x - pos.x, dz = bp.z - pos.z;
                float d2 = dx * dx + dz * dz;
                if (d2 < best) { best = d2; nearest = bp; hasBall = true; }
            }

            return new RobotObservation
            {
                Alliance = b.Alliance,
                SlotIndex = b.SlotIndex,
                Position = new Vector3(pos.x, pos.y, pos.z),
                HeadingRad = heading,
                Velocity = Vector3.zero,
                AngularVelocity = b.Omega,
                HeldBalls = b.Actor != null ? b.Actor.HeldBalls : 0,
                ClimbZone = b.Actor != null ? b.Actor.ClimbZone : ClimbZone.None,
                HasNearestBall = hasBall,
                NearestBall = new Vector3(nearest.x, nearest.y, nearest.z)
            };
        }

        /// <summary>ECS物理の結果を可視GameObject(RobotActor)へ反映（位置・ヨー）。機構/観測/HUDがこれに追従。</summary>
        private void LateUpdate()
        {
            if (!_spawned) return;
            float dt = Time.deltaTime;
            foreach (var b in _bots)
            {
                if (b.Actor == null) continue;
                LocalTransform lt = _em.GetComponentData<LocalTransform>(b.Entity);
                var rot = new Quaternion(lt.Rotation.value.x, lt.Rotation.value.y, lt.Rotation.value.z, lt.Rotation.value.w);
                b.Actor.transform.SetPositionAndRotation(
                    new Vector3(lt.Position.x, lt.Position.y, lt.Position.z), rot);

                // 観測用の実速度を供給（kinematic rb は0のまま＝NPCのD項/ML観測が死ぬのを防ぐ）。
                // 線速度=ECSのワールド速度をそのまま、yaw角速度=eulerAngles.y の差分/dt（heading と同一系＝符号一致）。
                if (_em.HasComponent<PhysicsVelocity>(b.Entity))
                {
                    var pv = _em.GetComponentData<PhysicsVelocity>(b.Entity);
                    b.LinVel = new Vector3(pv.Linear.x, pv.Linear.y, pv.Linear.z);
                }
                float yawDeg = rot.eulerAngles.y;
                b.YawRate = (b.HasPrevYaw && dt > 1e-6f)
                    ? Mathf.DeltaAngle(b.PrevYawDeg, yawDeg) * Mathf.Deg2Rad / dt
                    : 0f;
                b.PrevYawDeg = yawDeg;
                b.HasPrevYaw = true;
            }
        }

        /// <summary>観測用: ECS剛体の実速度を返す（kinematicなGameObject rb は速度0のため）。
        /// MatchDirector.BuildObservation がECSロボ稼働時にこれを優先使用する。線速度=world[m/s]、yawRate=world[rad/s]。</summary>
        public bool TryGetVelocity(RobotActor actor, out Vector3 linVel, out float yawRate)
        {
            for (int i = 0; i < _bots.Count; i++)
            {
                if (_bots[i].Actor == actor)
                {
                    linVel = _bots[i].LinVel;
                    yawRate = _bots[i].YawRate;
                    return _bots[i].HasPrevYaw; // 初回フレームは未確定（false）
                }
            }
            linVel = Vector3.zero; yawRate = 0f;
            return false;
        }

        private static quaternion ToQuaternion(Quaternion r) => new quaternion(r.x, r.y, r.z, r.w);

        private void OnDestroy()
        {
            // 再生停止/ドメインリロード時は ECS World が先に破棄され、クエリも自動Disposeされる。
            // その後に手動Disposeするとhashmap二重解放でNREになるため、World生存時のみ破棄する。
            if (_spawned && _world != null && _world.IsCreated) _ballQuery.Dispose();
            for (int i = 0; i < _ownedColliders.Count; i++)
                if (_ownedColliders[i].IsCreated) _ownedColliders[i].Dispose();
            _ownedColliders.Clear();
        }
    }
}
