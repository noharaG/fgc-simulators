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
        private static readonly Vector3 MuzzleOffset = new Vector3(0f, 0.30f, 0.30f);
        private const float ShotTtl = 3.0f;

        // --- Storage（StorageTrigger 正典値） ---
        private const float DepositInterval = 0.1f;

        // --- Climb（RobotClimb 正典値） ---
        private const float ReleaseGrace = 1.0f;
        private static readonly float ClimbLow = FieldConstants.BraceLowEnd;    // 0.24
        private static readonly float ClimbHigh = FieldConstants.BraceHighEnd;  // 1.97
        private static readonly Collider[] ClimbHits = new Collider[8];

        /// <summary>1球格納するたびに発火（預けた/撃ったロボ, 格納種別）。ML報酬・演出用（現状ML OFF）。</summary>
        public System.Action<RobotActor, StorageKind> OnDeposited;

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
                    for (int z = 0; z < _zones.Count; z++)
                    {
                        var zone = _zones[z];
                        if (!Inside(bp, zone.Center, zone.Half)) continue;
                        // SUPPRESSION は撃った主の同盟一致のみ得点（報酬帰属を維持）。
                        if (zone.Restricted && (s.Shooter == null || s.Shooter.alliance != zone.Alliance)) continue;
                        _balls.DespawnToPool(s.Ball);
                        _wildfire.DepositToStorage(zone.Kind, 1);
                        if (s.Shooter != null) OnDeposited?.Invoke(s.Shooter, zone.Kind);
                        remove = true;
                        break;
                    }
                }
                else remove = true; // 球が無効（既に退避済み等）

                if (!remove && s.Ttl <= 0f) { _balls.DespawnToPool(s.Ball); remove = true; }

                if (remove) _shots.RemoveAt(i);
                else _shots[i] = s;
            }
        }

        /// <summary>RobotShooter.Fire 同値：前方＋仰角＋小ばらつきで ECS球を放ち、飛来球として登録。</summary>
        private void FireShot(RobotActor a, Transform t)
        {
            Vector3 fwd = t.forward; fwd.y = 0f; fwd.Normalize();
            Vector3 axis = t.right;
            Vector3 dir = Quaternion.AngleAxis(-ElevationDeg, axis) * fwd; // 上向きへ傾ける
            float yaw = Random.Range(-AimSpreadDeg, AimSpreadDeg) * 0.5f;
            float pitch = Random.Range(-AimSpreadDeg, AimSpreadDeg) * 0.5f;
            dir = Quaternion.AngleAxis(yaw, Vector3.up) * Quaternion.AngleAxis(pitch, axis) * dir;

            Vector3 muzzle = t.TransformPoint(MuzzleOffset);
            Entity ball = _balls.LaunchBall(muzzle, dir * LaunchSpeed);
            if (ball != Entity.Null)
            {
                a.HeldBalls--;
                _shots.Add(new Shot { Ball = ball, Shooter = a, Ttl = ShotTtl });
            }
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
