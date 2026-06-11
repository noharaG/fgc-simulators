using System.Collections.Generic;
using FGC.Ball;
using FGC.Core;
using FGC.Robot;
using Unity.Entities;
using UnityEngine;

namespace FGC.App
{
    /// <summary>
    /// 段階④: 試合の判定機構（Intake/Shoot/ShotTtl/Storage/Climb）を **ECS球に対して** 機能させる橋渡し層。
    ///
    /// 設計（計画書 段階④。最高リスクのJob系API＝ITriggerEventsJob/ICollisionEventsJob/CollisionWorld.OverlapAabb を避け、
    /// 段階②③で実証済みの「管理コードによる空間クエリ」流儀を踏襲）:
    ///  - 球の生成/消費/退避は EcsBallSim のプール操作API（球Entityの唯一所有者）に委譲。
    ///  - **ゲーム状態は GameObject 側の値を更新**: RobotActor.HeldBalls / RobotActor.ClimbZone /
    ///    WildfireManager の格納カウント（DepositToStorage）。→ ScoreBoard/HUD/MLは既存のまま動く（移植コスト最小）。
    ///  - 指令(Intake/Shoot)は EcsRobotSim が SetLastCommand で反映した RobotActor.LastCommand を参照（旧機構と同じ）。
    ///  - ロボ位置は EcsRobotSim が同期した GameObject Transform を使用（<1フレームの遅延は機構には無害）。
    ///
    /// 旧 GameObject 機構（RobotIntake/RobotShooter/RobotClimb/StorageTrigger）は ECS球を扱えない/二重発火するため、
    /// MatchDirector が ECS機構ON時に無効化する。本クラスが唯一の判定経路になる。
    /// </summary>
    public sealed class EcsMechanics : MonoBehaviour
    {
        // --- Intake（RobotIntake 正典値） ---
        private const int Capacity = FieldConstants.RobotBallCapacity;          // =10
        private const float IntakeInterval = 0.15f;                             // 1球あたり吸引間隔
        private const float IntakeRadius = 0.30f;                               // 口(前面幅0.5)の球吸引半径近似
        private static readonly Vector3 MouthOffset =
            new Vector3(0f, 0.10f, 0.25f + FieldConstants.IntakeFrontDepth * 0.5f);

        // --- Shoot（RobotShooter 正典値） ---
        private const float LaunchSpeed = 6.0f;
        private const float ElevationDeg = 25f;
        private static readonly float FireInterval = 1f / FieldConstants.ShootBps; // =0.2 (BPS5)
        private const float AimSpreadDeg = 4f;
        private const float MissSpreadDeg = 28f; // 成功判定に失敗した射出の散布（missRadius2.0m相当の外し方）
        private static readonly Vector3 MuzzleOffset = new Vector3(0f, 0.30f, 0.30f);
        private const float ShotTtl = 3.0f;

        // --- Storage（StorageTrigger 正典値） ---
        private const float DepositInterval = 0.1f;

        // --- Climb（RobotClimb 正典値） ---
        private const float ReleaseGrace = 1.0f;
        private static readonly float ClimbLow = FieldConstants.BraceLowEnd;    // 0.24
        private static readonly float ClimbHigh = FieldConstants.BraceHighEnd;  // 1.97
        private static readonly Collider[] ClimbHits = new Collider[8];

        /// <summary>1球格納するたびに発火（預けた/撃ったロボ, 格納種別）。ML報酬・演出用。</summary>
        public System.Action<RobotActor, StorageKind> OnDeposited;

        /// <summary>**射出（シュート）由来**の格納時のみ追加発火（OnDepositedと併発）。
        /// ML報酬で「持ち込み格納」より「射出」を優遇するボーナス用（2026-06-12 ユーザー要望）。</summary>
        public System.Action<RobotActor, StorageKind> OnShotDeposited;

        /// <summary>射出した球が**フィールド外に出た**時に発火（撃ったロボ）。場外ペナルティ用（2026-06-12 ユーザー要望）。</summary>
        public System.Action<RobotActor> OnShotOut;

        private struct Zone
        {
            public Vector3 Center;
            public Vector3 Half;
            public StorageKind Kind;
            public bool Restricted;
            public AllianceColor Alliance;
        }

        private sealed class Mech
        {
            public RobotActor Actor;
            public float IntakeCd, ShootCd, DepositCd, SinceTouch;
        }

        private struct Shot
        {
            public Entity Ball;
            public RobotActor Shooter;
            public float Ttl;
        }

        private EcsBallSim _balls;
        private WildfireManager _wildfire;
        private readonly List<Mech> _mechs = new List<Mech>(6);
        private readonly List<Zone> _zones = new List<Zone>(3);
        private readonly List<Shot> _shots = new List<Shot>(64);
        private bool _active;

        /// <summary>MatchDirector から呼ぶ：球プール・ロボ・カウンタ参照を受け取り、格納ゾーンを構築する。</summary>
        public void Begin(EcsBallSim balls, IReadOnlyList<RobotActor> robots, WildfireManager wildfire)
        {
            _balls = balls;
            _wildfire = wildfire;

            _mechs.Clear();
            foreach (var a in robots) _mechs.Add(new Mech { Actor = a });

            // 格納ゾーン（CreateStorageZones と同じ FieldLayout 由来。AABB判定で使う）。
            _zones.Clear();
            _zones.Add(new Zone
            {
                Center = FieldLayout.SuppressionBlueCenter, Half = FieldLayout.SuppressionZoneSize * 0.5f,
                Kind = StorageKind.SuppressionBlue, Restricted = true, Alliance = AllianceColor.Blue
            });
            _zones.Add(new Zone
            {
                Center = FieldLayout.SuppressionRedCenter, Half = FieldLayout.SuppressionZoneSize * 0.5f,
                Kind = StorageKind.SuppressionRed, Restricted = true, Alliance = AllianceColor.Red
            });
            _zones.Add(new Zone
            {
                Center = FieldLayout.ExtinguisherCenter, Half = FieldLayout.ExtinguisherZoneSize * 0.5f,
                Kind = StorageKind.Extinguisher, Restricted = false, Alliance = AllianceColor.Red
            });
        }

        public void SetActive(bool active) => _active = active;

        /// <summary>環境リセット：飛来球の追跡とクールダウンを破棄（HeldBalls等は RobotActor.ResetState 側で0化）。</summary>
        public void ResetState()
        {
            _shots.Clear();
            foreach (var m in _mechs) { m.IntakeCd = m.ShootCd = m.DepositCd = 0f; m.SinceTouch = ReleaseGrace; }
        }

        private void FixedUpdate()
        {
            if (!_active || _balls == null || _wildfire == null) return;
            float dt = Time.fixedDeltaTime;

            ProcessShots(dt);

            for (int i = 0; i < _mechs.Count; i++)
            {
                var m = _mechs[i];
                var a = m.Actor;
                if (a == null) continue;
                if (m.IntakeCd > 0f) m.IntakeCd -= dt;
                if (m.ShootCd > 0f) m.ShootCd -= dt;
                if (m.DepositCd > 0f) m.DepositCd -= dt;

                DriveCommand cmd = a.LastCommand;
                Transform t = a.transform;

                // インテーク：前方の口の範囲に居る場の球を1個回収（容量・クールダウン律速）。
                if (cmd.Intake && a.HeldBalls < Capacity && m.IntakeCd <= 0f)
                {
                    Vector3 mouth = t.TransformPoint(MouthOffset);
                    if (_balls.TryConsumeBallNear(mouth, IntakeRadius, out _))
                    {
                        a.HeldBalls++;
                        m.IntakeCd = IntakeInterval;
                    }
                }

                // シュート：保持球を前方＋仰角に射出し、飛来球として追跡（ゴール到達で得点化）。
                if (cmd.Shoot && a.HeldBalls > 0 && m.ShootCd <= 0f)
                {
                    FireShot(a, t);
                    m.ShootCd = FireInterval;
                }

                // 登攀：BRACE近接＋高さで ClimbZone を更新（差動2輪では実登攀しないので実質Contact/None）。
                UpdateClimb(m, t, dt);

                // 格納（保持球）：自/全同盟ゾーン内なら1球ずつ預けてカウント加算。
                if (a.HeldBalls > 0 && m.DepositCd <= 0f)
                {
                    for (int z = 0; z < _zones.Count; z++)
                    {
                        var zone = _zones[z];
                        if (!Inside(t.position, zone.Center, zone.Half)) continue;
                        if (zone.Restricted && a.alliance != zone.Alliance) continue;
                        a.HeldBalls--;
                        _wildfire.DepositToStorage(zone.Kind, 1);
                        m.DepositCd = DepositInterval;
                        OnDeposited?.Invoke(a, zone.Kind);
                        break;
                    }
                }
            }
        }

        /// <summary>飛来シュート球の寿命管理＋ゴール得点化（StorageTrigger の経路A＋ShotBall.ttl 相当）。</summary>
        private void ProcessShots(float dt)
        {
            for (int i = _shots.Count - 1; i >= 0; i--)
            {
                var s = _shots[i];
                s.Ttl -= dt;
                bool remove = false;

                if (_balls.TryGetBallPosition(s.Ball, out Vector3 bp))
                {
                    // ★場外判定(2026-06-12 ユーザー要望): シュートがフィールド外へ出たら球は消滅し、
                    // 撃ったロボへペナルティ（OnShotOut→RewardShaper）。乱射でボールを場外へ捨てる行動を抑止。
                    bool outOfField = bp.x < -0.05f || bp.x > FieldConstants.FieldSizeX + 0.05f
                                   || bp.z < -0.05f || bp.z > FieldConstants.FieldSizeZ + 0.05f
                                   || bp.y < -0.5f; // 床抜け(場外落下)
                    if (outOfField)
                    {
                        _balls.DespawnToPool(s.Ball);
                        if (s.Shooter != null) OnShotOut?.Invoke(s.Shooter);
                        remove = true;
                    }
                    else
                    {
                        for (int z = 0; z < _zones.Count; z++)
                        {
                            var zone = _zones[z];
                            if (!Inside(bp, zone.Center, zone.Half)) continue;
                            // SUPPRESSION は撃った主の同盟一致のみ得点（報酬帰属を維持）。
                            if (zone.Restricted && (s.Shooter == null || s.Shooter.alliance != zone.Alliance)) continue;
                            _balls.DespawnToPool(s.Ball);
                            _wildfire.DepositToStorage(zone.Kind, 1);
                            if (s.Shooter != null)
                            {
                                OnDeposited?.Invoke(s.Shooter, zone.Kind);
                                OnShotDeposited?.Invoke(s.Shooter, zone.Kind); // 射出由来＝ボーナス対象
                            }
                            remove = true;
                            break;
                        }
                    }
                }
                else remove = true; // 球が無効（既に退避済み等）

                // ★変更(2026-06-12): TTL切れ＝「外れて場内に落ちた」球は**場に残す**（従来は消滅＝供給が漏れていた）。
                // 追跡だけ終了し、床の球として再回収可能にする（現実準拠）。場外は上の判定で消滅+ペナルティ済み。
                if (!remove && s.Ttl <= 0f) remove = true;

                if (remove) _shots.RemoveAt(i);
                else _shots[i] = s;
            }
        }

        /// <summary>RobotShooter.Fire 相当。★Java版移植(2026-06-12): **距離・角度・接触・混雑で成功率が低下**
        /// (CompetitionManager.java の prob 計算と同係数)。成功→従来の小ばらつき(±4°)、
        /// 失敗→大ばらつき(±MissSpreadDeg＋速度ジッタ＝missRadius2.0相当の散布)で外れる。</summary>
        private void FireShot(RobotActor a, Transform t)
        {
            float prob = ComputeShotSuccessProb(a, t);
            bool success = Random.value < prob;

            Vector3 fwd = t.forward; fwd.y = 0f; fwd.Normalize();
            Vector3 axis = t.right;
            Vector3 dir = Quaternion.AngleAxis(-ElevationDeg, axis) * fwd; // 上向きへ傾ける
            float spread = success ? AimSpreadDeg : MissSpreadDeg;
            float yaw = Random.Range(-spread, spread) * 0.5f;
            float pitch = Random.Range(-spread, spread) * 0.5f;
            dir = Quaternion.AngleAxis(yaw, Vector3.up) * Quaternion.AngleAxis(pitch, axis) * dir;
            float speed = success ? LaunchSpeed : LaunchSpeed * Random.Range(0.65f, 1.25f); // 失敗は吹かし/失速も

            Vector3 muzzle = t.TransformPoint(MuzzleOffset);
            Entity ball = _balls.LaunchBall(muzzle, dir * speed);
            if (ball != Entity.Null)
            {
                a.HeldBalls--;
                _shots.Add(new Shot { Ball = ball, Shooter = a, Ttl = ShotTtl });
            }
        }

        /// <summary>射出の成功率（Java CompetitionManager の prob 計算を忠実移植・2026-06-12）。
        /// base=ShootSuccessProb(0.7) ×
        ///   距離ペナルティ: 1-0.5·(min(1, 距離/射程2.0))² （近距離1.0→射程端0.5）
        ///   角度ペナルティ: max(0, cos(自陣ゴール方向との角度差)) （正面1→真横0→後方0）
        ///   接触ペナルティ: 0.6m以内の他ロボ1台ごとに ×0.7 （押し合い中は当たらない）
        ///   混雑ペナルティ: ゴール1.2m以内の他ロボ1台ごとに ×0.85
        /// Javaの遮蔽ペナルティ(射線ブロック×0.25)は、ECSではゴール構造物の物理壁が球を実際に弾くため省略。
        /// Java同様 base×0.3 を下限、base を上限にクランプ。</summary>
        private float ComputeShotSuccessProb(RobotActor a, Transform t)
        {
            float baseProb = FieldConstants.ShootSuccessProb;
            Vector2 goal = FieldLayout.NpcGoal(a.alliance);
            Vector3 pos = t.position;
            float dx = goal.x - pos.x, dz = goal.y - pos.z;
            float dist = Mathf.Sqrt(dx * dx + dz * dz);

            float ratio = Mathf.Min(1f, dist / FieldConstants.ShootRange);
            float distPen = 1f - 0.5f * ratio * ratio;

            float aimDeg = Mathf.Atan2(dx, dz) * Mathf.Rad2Deg;          // forward=(sinθ,cosθ)系
            float errRad = Mathf.Abs(Mathf.DeltaAngle(t.eulerAngles.y, aimDeg)) * Mathf.Deg2Rad;
            float anglePen = Mathf.Max(0f, Mathf.Cos(errRad));

            float contactPen = 1f, crowdPen = 1f;
            foreach (var m in _mechs)
            {
                var o = m.Actor;
                if (o == null || o == a) continue;
                Vector3 op = o.transform.position;
                if (Vector3.Distance(op, pos) < 0.6f) contactPen *= 0.7f;
                float gdx = op.x - goal.x, gdz = op.z - goal.y;
                if (Mathf.Sqrt(gdx * gdx + gdz * gdz) < 1.2f) crowdPen *= 0.85f;
            }

            float prob = baseProb * distPen * anglePen * contactPen * crowdPen;
            return Mathf.Clamp(prob, baseProb * 0.3f, baseProb);
        }

        /// <summary>RobotClimb 同値：BRACE("brace*")近接で接触判定、機体高さでゾーン化、非接触は猶予後 None。</summary>
        private void UpdateClimb(Mech m, Transform t, float dt)
        {
            Vector3 center = t.position + Vector3.up * 0.25f;
            int n = Physics.OverlapBoxNonAlloc(center, new Vector3(0.3f, 0.3f, 0.3f), ClimbHits, t.rotation);
            bool touching = false;
            for (int i = 0; i < n; i++)
            {
                var c = ClimbHits[i];
                // BRACE判定: 実FBXは "brace" 名でないため BraceMarker(形状判定で付与)を優先。旧プリミティブ名も後方互換で許容。
                if (c != null && (c.GetComponentInParent<BraceMarker>() != null || c.gameObject.name.StartsWith("brace"))) { touching = true; break; }
            }

            if (touching)
            {
                m.SinceTouch = 0f;
                m.Actor.ClimbZone = ZoneForHeight(t.position.y);
            }
            else
            {
                m.SinceTouch += dt;
                if (m.SinceTouch >= ReleaseGrace) m.Actor.ClimbZone = ClimbZone.None;
            }
        }

        private static ClimbZone ZoneForHeight(float y)
        {
            float band = (ClimbHigh - ClimbLow) / 3f;
            if (y < ClimbLow) return ClimbZone.Contact;
            if (y < ClimbLow + band) return ClimbZone.Zone1;
            if (y < ClimbLow + 2f * band) return ClimbZone.Zone2;
            return ClimbZone.Zone3;
        }

        private static bool Inside(Vector3 p, Vector3 center, Vector3 half)
            => Mathf.Abs(p.x - center.x) <= half.x
            && Mathf.Abs(p.y - center.y) <= half.y
            && Mathf.Abs(p.z - center.z) <= half.z;
    }
}
