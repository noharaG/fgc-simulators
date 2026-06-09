using System.Collections.Generic;
using FGC.Core;
using UnityEngine;

namespace FGC.Ball
{
    /// <summary>
    /// WILDFIRE 500球の生成・物理・描画・格納管理（PLAN.md §3.4）。
    /// 各球は Rigidbody+SphereCollider。描画は GPU Instancing（Graphics.DrawMeshInstanced）で軽量化。
    /// 試合開始時に EXTINGUISHER 下から放出。SUPPRESSION/EXTINGUISHER の格納トリガに入った球は
    /// 物理から外してプールへ退避しカウントする。
    /// </summary>
    public sealed class WildfireManager : MonoBehaviour
    {
        [Header("描画")]
        public Mesh ballMesh;            // 低ポリ球メッシュ
        public Material ballMaterial;    // GPU Instancing有効マテリアル

        [Header("放出口（EXTINGUISHER下）")]
        // 既定はEXTINGUISHER中央(STEP実測 z≈3.384)下部開口。シーン生成側で FieldLayout から上書きされる。
        public Vector3 emitOrigin = new Vector3(3.5f, 0.4f, 3.384f);
        [Tooltip("放出口の位置をシーン上のオブジェクトで指定（ゴールのスロットにこれをドラッグして合わせる）。未設定なら emitOrigin を使う。")]
        public Transform emitAnchor;

        /// <summary>実際の放出基点。emitAnchor があればその位置、無ければ emitOrigin。</summary>
        private Vector3 EmitPoint => emitAnchor != null ? emitAnchor.position : emitOrigin;

        [Header("放出の流し方（爆散防止：時間をかけて少数ずつ放出）")]
        [Tooltip("毎秒放出する球数。500球をこのレートで流す（例:50なら約10秒。ゆっくり流すほど積み上がらない）。")]
        public float pourRatePerSec = 50f;
        [Tooltip("放出口から押し出す向き（前方＝フィールド中央へ）。水平成分が転がり方向。zの符号が逆なら反転して調整。")]
        public Vector3 emitDirection = new Vector3(0f, -0.5f, 0.5f);
        [Tooltip("放出初速[m/s]。低速で前へ押し出し、中央に山を作る。大きいほど遠くまで広がる。")]
        public float pourSpeed = 0.3f;
        [Tooltip("放出の横ばらつき[m/s]（扇状の広がり）。小さいほど狭く溜まる。")]
        public float pourSpread = 0.1f;
        private bool _pouring;
        private float _pourCarry;

        private readonly List<Rigidbody> _live = new List<Rigidbody>(FieldConstants.BallCount);
        private readonly List<Transform> _liveT = new List<Transform>(FieldConstants.BallCount);
        private readonly Stack<GameObject> _stored = new Stack<GameObject>();

        // 格納カウント（スコア用）
        public int SuppressionRedStored { get; private set; }
        public int SuppressionBlueStored { get; private set; }
        public int ExtinguisherStored { get; private set; }

        private readonly List<Matrix4x4> _matrices = new List<Matrix4x4>(1023);

        /// <summary>500球を非アクティブで用意（PreMatch）。</summary>
        public void Prepare()
        {
            Clear();
            for (int i = 0; i < FieldConstants.BallCount; i++)
            {
                var go = CreateBall(i);
                go.SetActive(false);
                _stored.Push(go);
            }
        }

        /// <summary>エピソード/試合リセット：場の球を全てプールへ戻し、格納計数を0にする（球は破棄せず再利用）。</summary>
        public void ResetToPool()
        {
            for (int i = 0; i < _liveT.Count; i++)
            {
                var go = _liveT[i].gameObject;
                go.SetActive(false);
                _stored.Push(go);
            }
            _live.Clear();
            _liveT.Clear();
            _pouring = false;
            _pourCarry = 0f;
            SuppressionRedStored = SuppressionBlueStored = ExtinguisherStored = 0;
        }

        /// <summary>試合開始：放出を開始。実際の放出は Update の PourStep で時間をかけて少数ずつ行う
        /// （500球を同一点に同時生成すると重なり反発で爆散するため）。</summary>
        public void EmitAll()
        {
            _pouring = true;
            _pourCarry = 0f;
        }

        /// <summary>放出中なら、毎フレーム pourRatePerSec に従って少数ずつ放出口から球を流す。
        /// 同フレーム放出ぶんは縦に積んで重なりを避け、下〜やや外へ低速で出してフィールド中央に山を作る。</summary>
        private void PourStep(float dt)
        {
            if (!_pouring) return;
            _pourCarry += pourRatePerSec * dt;
            int n = Mathf.FloorToInt(_pourCarry);
            if (n <= 0) return;
            n = Mathf.Min(n, 4); // スロー時に一気に出して重さが暴走する(死のスパイラル)のを防ぐ
            _pourCarry -= n;

            for (int k = 0; k < n; k++)
            {
                if (_stored.Count == 0) { _pouring = false; break; }
                var go = _stored.Pop();
                // 縦積みせず、放出口の水平な小円内に出す（高く積み上げない）。低レートなので重なりは僅少。
                Vector2 disc = Random.insideUnitCircle * (FieldConstants.EmitRadius * 0.4f);
                go.transform.position = EmitPoint + new Vector3(disc.x, 0f, disc.y);
                go.SetActive(true);
                var rb = go.GetComponent<Rigidbody>();
                rb.linearVelocity = PourVelocity();
                rb.angularVelocity = Vector3.zero;
                _live.Add(rb);
                _liveT.Add(go.transform);
            }
        }

        /// <summary>毎フレーム：GPU Instancingで描画（1023個ずつバッチ）。</summary>
        private void Update()
        {
            PourStep(Time.deltaTime); // 放出中なら時間をかけて少数ずつ流す

            if (ballMesh == null || ballMaterial == null) return;
            float scale = FieldConstants.BallRadius * 2f;
            var s = new Vector3(scale, scale, scale);

            _matrices.Clear();
            for (int i = 0; i < _liveT.Count; i++)
            {
                _matrices.Add(Matrix4x4.TRS(_liveT[i].position, Quaternion.identity, s));
                if (_matrices.Count == 1023)
                {
                    Graphics.DrawMeshInstanced(ballMesh, 0, ballMaterial, _matrices);
                    _matrices.Clear();
                }
            }
            if (_matrices.Count > 0)
                Graphics.DrawMeshInstanced(ballMesh, 0, ballMaterial, _matrices);
        }

        /// <summary>指定位置に最も近い「場に出ている」球の位置を返す（観測・NPC追従用）。</summary>
        public bool TryGetNearestBall(Vector3 from, out Vector3 pos, float maxRange = float.MaxValue)
        {
            pos = from;
            float best = maxRange;
            bool found = false;
            for (int i = 0; i < _liveT.Count; i++)
            {
                float d = Vector3.Distance(from, _liveT[i].position);
                if (d < best) { best = d; pos = _liveT[i].position; found = true; }
            }
            return found;
        }

        public int LiveCount => _liveT.Count;

        /// <summary>インテークが場の球を回収：物理から外しプールへ（スコアは加算しない）。成功でtrue。</summary>
        public bool CollectBall(GameObject ball)
        {
            int idx = _liveT.FindIndex(t => t.gameObject == ball);
            if (idx < 0) return false; // 場に出ている球でなければ無視（フィールド/ロボ等を弾く）
            _live.RemoveAt(idx);
            _liveT.RemoveAt(idx);
            ball.SetActive(false);
            _stored.Push(ball);
            return true;
        }

        /// <summary>シュート：プールから1球を pos に出し velocity を与えて場に戻す。出した球を返す（無ければnull）。</summary>
        public GameObject LaunchBall(Vector3 pos, Vector3 velocity)
        {
            if (_stored.Count == 0) return null;
            var go = _stored.Pop();
            go.transform.position = pos;
            go.SetActive(true);
            var rb = go.GetComponent<Rigidbody>();
            rb.linearVelocity = velocity;
            rb.angularVelocity = Vector3.zero;
            _live.Add(rb);
            _liveT.Add(go.transform);
            return go;
        }

        /// <summary>ロボ保持球を格納先へ預ける：個数分カウント加算（球は既にプール内）。</summary>
        public void DepositToStorage(StorageKind kind, int count)
        {
            if (count <= 0) return;
            switch (kind)
            {
                case StorageKind.SuppressionRed: SuppressionRedStored += count; break;
                case StorageKind.SuppressionBlue: SuppressionBlueStored += count; break;
                case StorageKind.Extinguisher: ExtinguisherStored += count; break;
            }
        }

        /// <summary>格納トリガから呼ぶ：球を物理から外しカウント。</summary>
        public void StoreBall(GameObject ball, StorageKind kind)
        {
            int idx = _liveT.FindIndex(t => t.gameObject == ball);
            if (idx >= 0) { _live.RemoveAt(idx); _liveT.RemoveAt(idx); }
            ball.SetActive(false);
            _stored.Push(ball);

            switch (kind)
            {
                case StorageKind.SuppressionRed: SuppressionRedStored++; break;
                case StorageKind.SuppressionBlue: SuppressionBlueStored++; break;
                case StorageKind.Extinguisher: ExtinguisherStored++; break;
            }
        }

        private GameObject CreateBall(int index)
        {
            var go = new GameObject($"Ball_{index}");
            go.transform.SetParent(transform, false);
            var sc = go.AddComponent<SphereCollider>();
            sc.radius = FieldConstants.BallRadius;
            var pm = new PhysicsMaterial("Wildfire")
            {
                bounciness = FieldConstants.BallRestitution,
                dynamicFriction = FieldConstants.BallFriction,
                staticFriction = FieldConstants.BallFriction
            };
            sc.material = pm;
            var rb = go.AddComponent<Rigidbody>();
            rb.mass = FieldConstants.BallMass;
            rb.collisionDetectionMode = CollisionDetectionMode.ContinuousDynamic;
            rb.sleepThreshold = 0.02f; // 静止球は早めにsleep
            rb.linearDamping = 0.2f;   // 床を滑り続けない
            rb.angularDamping = 0.5f;  // 転がり抵抗（広がりすぎ防止）
            rb.maxDepenetrationVelocity = 1f; // 重なり解消の弾き出しを抑える（放出時の急加速防止）
            return go;
        }

        private static Vector3 RandomInDisc(float r)
        {
            var c = Random.insideUnitCircle * r;
            return new Vector3(c.x, 0f, c.y);
        }

        /// <summary>放出口から前方へ低速で押し出す速度。横ばらつきで扇状に広げ、中央に山を作る。</summary>
        private Vector3 PourVelocity()
        {
            Vector3 fwd = emitDirection.sqrMagnitude > 1e-6f ? emitDirection.normalized : Vector3.forward;
            // 前方水平に直交する横方向（XZ平面）へばらつかせる
            Vector3 flat = new Vector3(fwd.x, 0f, fwd.z);
            if (flat.sqrMagnitude < 1e-6f) flat = Vector3.forward;
            Vector3 side = Vector3.Cross(Vector3.up, flat.normalized);
            return fwd * pourSpeed + side * Random.Range(-pourSpread, pourSpread);
        }

        private void Clear()
        {
            foreach (var t in _liveT) if (t) Destroy(t.gameObject);
            while (_stored.Count > 0) { var g = _stored.Pop(); if (g) Destroy(g); }
            _live.Clear(); _liveT.Clear();
            SuppressionRedStored = SuppressionBlueStored = ExtinguisherStored = 0;
        }
    }

    public enum StorageKind { SuppressionRed, SuppressionBlue, Extinguisher }
}
