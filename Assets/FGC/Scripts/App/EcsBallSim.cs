using System.Collections.Generic;
using FGC.Core;
using FGC.Sim.ECS;
using Unity.Collections;
using Unity.Entities;
using Unity.Mathematics;
using Unity.Physics;
using Unity.Transforms;
using UnityEngine;
// UnityEngine と Unity.Physics で名前が衝突する型はエイリアスで明示する。
using PxCollider = Unity.Physics.Collider;
using PxMaterial = Unity.Physics.Material;
using PxSphere = Unity.Physics.SphereCollider;
using PxBox = Unity.Physics.BoxCollider;

namespace FGC.App
{
    /// <summary>
    /// 段階②: WILDFIRE 500球を **ECS Entity + Unity Physics(DOTS)** で回す（球同士の押し出し/詰まり/こぼれ/堆積を
    /// マルチコアで正確に）。物理シミュレーションは Unity Physics パッケージのSystem群(内部Burst)が担うため、
    /// このクラスは「Entity生成・放出オーケストレーション・描画ブリッジ」だけを行う（GameObject側）。
    ///
    /// 設計メモ:
    ///  - 球の生成/放出/プールを EntityManager で直接操作（ISystem特有の罠を避け低リスク化。将来System化可）。
    ///  - **ECS物理ワールドはGameObjectのMeshColliderと衝突しない**ため、球が落ちる床＋外周壁をECS静的剛体で用意。
    ///    （フィールド本体コライダのECS移行は段階④/⑧。ここでは球が乗る床だけ）。
    ///  - 描画は既存と同じ Graphics.DrawMeshInstanced（Entity の LocalTransform から行列を作る）。
    ///    entities.graphics は版不整合＋VR×SinglePassリスクのため不採用（HANDOFF参照）。
    ///  - 値は FieldConstants / FieldLayout をそのまま使用（移行による挙動変化ゼロ）。
    /// </summary>
    public sealed class EcsBallSim : MonoBehaviour
    {
        [Header("描画")]
        public Mesh ballMesh;
        public UnityEngine.Material ballMaterial;

        [Header("放出口・流し方（WildfireManager と同値）")]
        public Vector3 emitOrigin = new Vector3(3.5f, 0.15f, 0.45f);
        public float pourRatePerSec = 50f;
        public Vector3 emitDirection = new Vector3(0f, -0.5f, 0.5f);
        public float pourSpeed = 0.3f;
        public float pourSpread = 0.1f;

        private World _world;
        private EntityManager _em;
        private readonly List<Entity> _pool = new List<Entity>(FieldConstants.BallCount);    // Disabled（場外）
        private readonly List<Entity> _active = new List<Entity>(FieldConstants.BallCount);   // Enabled（場）
        private readonly List<BlobAssetReference<PxCollider>> _ownedColliders = new List<BlobAssetReference<PxCollider>>(8);
        private EntityQuery _renderQuery;
        private bool _spawned;
        private bool _pouring;
        private float _pourCarry;
        private int _toPour; // この放出サイクルで残り放出する球数（intakeでプールへ戻った球の再放出を防ぐ上限）
        private Unity.Mathematics.Random _rng = Unity.Mathematics.Random.CreateFromIndex(0xA1B2C3u);
        private readonly List<Matrix4x4> _matrices = new List<Matrix4x4>(1023);

        /// <summary>MatchDirector から呼ぶ：球メッシュ/マテリアル/放出点を受け取り、生成して放出を開始する。</summary>
        public void Begin(Mesh mesh, UnityEngine.Material material, Vector3 emit)
        {
            if (mesh != null) ballMesh = mesh;
            if (material != null) ballMaterial = material;
            emitOrigin = emit;
            EnsureRenderAssets();

            _world = World.DefaultGameObjectInjectionWorld;
            if (_world == null) { Debug.LogError("[FGC] ECS Default World が無い。Entitiesパッケージ未解決の可能性。"); return; }
            _em = _world.EntityManager;

            if (!_spawned) Spawn();
            EmitAll();
        }

        /// <summary>環境リセット：場の球を全てプールへ戻し、再放出する（autoRestart/再戦用）。</summary>
        public void ResetAndPour()
        {
            if (!_spawned) { EmitAll(); return; }
            for (int i = 0; i < _active.Count; i++)
            {
                _em.SetEnabled(_active[i], false);
                _pool.Add(_active[i]);
            }
            _active.Clear();
            _pourCarry = 0f;
            EmitAll();
        }

        private void EmitAll()
        {
            _pouring = true;
            _pourCarry = 0f;
            _toPour = FieldConstants.BallCount; // 1サイクルでちょうど500球放出したら止める
        }

        // ===== 段階④: 機構（EcsMechanics）から呼ぶ球プール操作 =====
        // EcsBallSim が球Entity/プールの唯一の所有者なので、消費/放出/退避/位置取得をここに集約する。
        // すべてメインスレッド(FixedUpdate)から呼ばれる前提＝_pool/_active の同時変更は無い。

        /// <summary>指定位置の半径内にある「場の球」を1個探してプールへ退避する（intake/飛来捕捉）。成功でtrue。</summary>
        public bool TryConsumeBallNear(Vector3 worldPos, float radius, out Vector3 ballPos)
        {
            ballPos = worldPos;
            if (!_spawned) return false;
            float3 p = (float3)worldPos;
            float best = radius * radius;
            int bestIdx = -1;
            for (int i = 0; i < _active.Count; i++)
            {
                float3 bp = _em.GetComponentData<LocalTransform>(_active[i]).Position;
                float d2 = math.distancesq(bp, p);
                if (d2 <= best) { best = d2; bestIdx = i; }
            }
            if (bestIdx < 0) return false;
            Entity e = _active[bestIdx];
            ballPos = (Vector3)_em.GetComponentData<LocalTransform>(e).Position;
            _active.RemoveAt(bestIdx);
            _em.SetEnabled(e, false);
            _pool.Add(e);
            return true;
        }

        /// <summary>プールから1球を取り出し pos に出して velocity を与え場に戻す（シュート射出）。戻り値=放った球(無ければEntity.Null)。</summary>
        public Entity LaunchBall(Vector3 pos, Vector3 velocity)
        {
            if (!_spawned || _pool.Count == 0) return Entity.Null;
            Entity e = _pool[_pool.Count - 1];
            _pool.RemoveAt(_pool.Count - 1);
            _em.SetComponentData(e, LocalTransform.FromPosition((float3)pos));
            _em.SetComponentData(e, new PhysicsVelocity { Linear = (float3)velocity, Angular = float3.zero });
            _em.SetEnabled(e, true);
            _active.Add(e);
            return e;
        }

        /// <summary>指定球をプールへ戻す（飛来球のttl切れ・格納時）。</summary>
        public void DespawnToPool(Entity e)
        {
            if (!_spawned) return;
            int idx = _active.IndexOf(e);
            if (idx < 0) return; // 既にプール内 or 未知
            _active.RemoveAt(idx);
            _em.SetEnabled(e, false);
            _pool.Add(e);
        }

        /// <summary>球Entityの現在位置（飛来球の得点判定用）。Entityが無効ならfalse。</summary>
        public bool TryGetBallPosition(Entity e, out Vector3 pos)
        {
            pos = Vector3.zero;
            if (!_spawned || !_em.Exists(e)) return false;
            pos = (Vector3)_em.GetComponentData<LocalTransform>(e).Position;
            return true;
        }

        private void EnsureRenderAssets()
        {
            if (ballMesh == null) ballMesh = Resources.GetBuiltinResource<Mesh>("Sphere.fbx");
            if (ballMaterial == null) ballMaterial = MaterialUtil.Colored(new Color(0.95f, 0.55f, 0.15f));
        }

        private void Spawn()
        {
            // 球の共有コライダ（カーペット想定の反発/摩擦＝Java値踏襲）
            var ballCol = PxSphere.Create(
                new SphereGeometry { Center = float3.zero, Radius = FieldConstants.BallRadius },
                CollisionFilter.Default,
                new PxMaterial { Friction = FieldConstants.BallFriction, Restitution = FieldConstants.BallRestitution });
            _ownedColliders.Add(ballCol);

            float3 emit = emitOrigin;
            for (int i = 0; i < FieldConstants.BallCount; i++)
            {
                Entity e = _em.CreateEntity();
                _em.AddComponentData(e, new BallTag());
                _em.AddComponentData(e, LocalTransform.FromPosition(emit));
                _em.AddComponentData(e, new PhysicsCollider { Value = ballCol });
                _em.AddComponentData(e, PhysicsMass.CreateDynamic(ballCol.Value.MassProperties, FieldConstants.BallMass));
                _em.AddComponentData(e, new PhysicsVelocity());
                _em.AddComponentData(e, new PhysicsDamping { Linear = 0.2f, Angular = 0.5f });
                _em.AddComponentData(e, new PhysicsGravityFactor { Value = 1f });
                _em.AddSharedComponent(e, new PhysicsWorldIndex(0));
                _em.SetEnabled(e, false); // プールへ（Disabled＝物理/描画から外れる）
                _pool.Add(e);
            }

            // ECS静的剛体: 床(7x7, 天面 y=0) ＋ 外周4壁（球が落ちる先。GameObject床とは別ワールドなので必須）
            CreateStaticBox(new float3(FieldConstants.FieldSizeX * 0.5f, -0.02f, FieldConstants.FieldSizeZ * 0.5f),
                            new float3(FieldConstants.FieldSizeX, 0.04f, FieldConstants.FieldSizeZ));
            float sx = FieldConstants.FieldSizeX, sz = FieldConstants.FieldSizeZ, h = 0.5f, t = 0.1f;
            CreateStaticBox(new float3(sx * 0.5f, h * 0.5f, 0f), new float3(sx, h, t));
            CreateStaticBox(new float3(sx * 0.5f, h * 0.5f, sz), new float3(sx, h, t));
            CreateStaticBox(new float3(0f, h * 0.5f, sz * 0.5f), new float3(t, h, sz));
            CreateStaticBox(new float3(sx, h * 0.5f, sz * 0.5f), new float3(t, h, sz));

            _renderQuery = _em.CreateEntityQuery(ComponentType.ReadOnly<BallTag>(), ComponentType.ReadOnly<LocalTransform>());
            _spawned = true;
            Debug.Log($"[FGC] ECS球を生成: {FieldConstants.BallCount}球(プール) + 静的床/壁5。Unity Physicsで物理を回します。");
        }

        private void CreateStaticBox(float3 center, float3 size) => CreateStaticBox(center, size, quaternion.identity);

        private void CreateStaticBox(float3 center, float3 size, quaternion orientation)
        {
            var col = PxBox.Create(
                new BoxGeometry { Center = float3.zero, Orientation = orientation, Size = size, BevelRadius = 0f },
                CollisionFilter.Default);
            _ownedColliders.Add(col);
            Entity e = _em.CreateEntity();
            _em.AddComponentData(e, new SimStaticColliderTag());
            _em.AddComponentData(e, LocalTransform.FromPosition(center));
            _em.AddComponentData(e, new PhysicsCollider { Value = col });
            _em.AddSharedComponent(e, new PhysicsWorldIndex(0)); // PhysicsVelocity無し＝静的剛体
        }

        /// <summary>段階④補完: 可視フィールド(FBX)のGameObjectコライダの**ワールドAABB**から、ECS静的ボックスを1個作る。
        /// MatchDirector が各フィールドコライダの bounds を渡す。ECSワールド座標＝Unityワールド座標なので**見た目と同位置**で衝突する。
        /// Spawn 後（_em確定後）に呼ぶこと。</summary>
        public void AddStaticBox(Vector3 center, Vector3 size)
        {
            if (!_spawned) return;
            CreateStaticBox((float3)center, (float3)size);
        }

        /// <summary>段階④補完(斜め部材用): 任意の向きを持つ静的ボックス(OBB)を1個作る。
        /// ブレース等の**斜めパイプ**は軸並行AABBだと巨大な箱になりフィールドを塞ぐため、
        /// MatchDirector が実メッシュからタイトなOBB(中心/サイズ/向き)を算出して渡す。見た目と同位置・同傾きで衝突する。</summary>
        public void AddStaticBoxOriented(Vector3 center, Vector3 size, Quaternion orientation)
        {
            if (!_spawned) return;
            CreateStaticBox((float3)center, (float3)size, (quaternion)orientation);
        }

        private void FixedUpdate()
        {
            if (!_pouring || !_spawned) return;
            _pourCarry += pourRatePerSec * Time.fixedDeltaTime;
            int n = Mathf.Min(Mathf.FloorToInt(_pourCarry), 4); // 一気放出による死のスパイラル防止
            if (n <= 0) return;
            _pourCarry -= n;

            for (int k = 0; k < n; k++)
            {
                if (_toPour <= 0 || _pool.Count == 0) { _pouring = false; break; }
                Entity e = _pool[_pool.Count - 1];
                _pool.RemoveAt(_pool.Count - 1);

                float3 pos = (float3)emitOrigin + RandomDisc(FieldConstants.EmitRadius * 0.4f);
                _em.SetComponentData(e, LocalTransform.FromPosition(pos));
                _em.SetComponentData(e, new PhysicsVelocity { Linear = PourVelocity(), Angular = float3.zero });
                _em.SetEnabled(e, true);
                _active.Add(e);
                _toPour--;
            }
        }

        private void LateUpdate()
        {
            if (!_spawned || ballMesh == null || ballMaterial == null) return;
            var arr = _renderQuery.ToComponentDataArray<LocalTransform>(Allocator.Temp);
            float s = FieldConstants.BallRadius * 2f;
            var scale = new Vector3(s, s, s);
            _matrices.Clear();
            for (int i = 0; i < arr.Length; i++)
            {
                _matrices.Add(Matrix4x4.TRS((Vector3)arr[i].Position, Quaternion.identity, scale));
                if (_matrices.Count == 1023)
                {
                    Graphics.DrawMeshInstanced(ballMesh, 0, ballMaterial, _matrices);
                    _matrices.Clear();
                }
            }
            if (_matrices.Count > 0)
                Graphics.DrawMeshInstanced(ballMesh, 0, ballMaterial, _matrices);
            arr.Dispose();
        }

        private float3 RandomDisc(float r)
        {
            float2 dir = _rng.NextFloat2Direction();
            float rad = _rng.NextFloat(0f, r);
            return new float3(dir.x * rad, 0f, dir.y * rad);
        }

        /// <summary>放出口から前方へ低速で押し出す速度（WildfireManager.PourVelocity と同等）。</summary>
        private float3 PourVelocity()
        {
            float3 fwd = math.lengthsq((float3)emitDirection) > 1e-6f
                ? math.normalize((float3)emitDirection) : new float3(0f, 0f, 1f);
            float3 flat = new float3(fwd.x, 0f, fwd.z);
            if (math.lengthsq(flat) < 1e-6f) flat = new float3(0f, 0f, 1f);
            float3 side = math.cross(new float3(0f, 1f, 0f), math.normalize(flat));
            return fwd * pourSpeed + side * _rng.NextFloat(-pourSpread, pourSpread);
        }

        private void OnDestroy()
        {
            // 再生停止/ドメインリロード時は ECS World が先に破棄され、クエリも自動Disposeされる。
            // その後に手動Disposeするとhashmap二重解放でNREになるため、World生存時のみ破棄する。
            if (_spawned && _world != null && _world.IsCreated) _renderQuery.Dispose();
            for (int i = 0; i < _ownedColliders.Count; i++)
                if (_ownedColliders[i].IsCreated) _ownedColliders[i].Dispose();
            _ownedColliders.Clear();
        }
    }
}
