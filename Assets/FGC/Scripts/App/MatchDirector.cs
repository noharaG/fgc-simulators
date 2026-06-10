using System.Collections.Generic;
using FGC.Ball;
using FGC.Core;
using FGC.Match;
using FGC.Robot;
using FGC.UI;
using FGC.VR;
using UnityEngine;

namespace FGC.App
{
    /// <summary>
    /// 試合の統合オーケストレータ（PLAN.md §3 全体の結線）。
    /// フィールド構築 → 6ロボ生成＆コントローラ割当 → 観測配線 → VR配置 → 試合進行（MatchManager）。
    /// SetupMenu の OnStartRequested で開始（球放出＋タイマー始動）。Core/Sim/Control/VR を1点で束ねる。
    /// </summary>
    public sealed class MatchDirector : MonoBehaviour
    {
        [Header("依存（シーン内参照。未設定なら生成）")]
        public WildfireManager wildfire;
        public VrPlayerRig playerRig;

        [Header("フィールド外観（STEP由来 FBX：実形状・実色）")]
        [Tooltip("Onshape→Blender 由来の field FBX。FieldSceneBuilder が割り当てる。")]
        public GameObject fieldModelPrefab;
        [Tooltip("CAD原点→シム座標へのオフセット。RISER中心→フィールド0..7・RISER天面=床y=0。")]
        public Vector3 fieldOffset = new Vector3(3.5f, -0.7f, 3.5f);
        [Tooltip("取り込み向き補正（Unity FBX軸変換のズレを目視で詰める用）。")]
        public Vector3 fieldEuler = Vector3.zero;
        [Tooltip("スケール補正（通常1。取り込み単位がずれている時のみ調整）。")]
        public float fieldScale = 1f;

        [Header("フィールド当たり判定")]
        [Tooltip("構造物に MeshCollider（凹・形状忠実）を自動付与する。ゴール/壁/ブレース/球放出部の精度用。")]
        public bool buildFieldColliders = true;
        [Tooltip("この最大辺[m]未満の小部品（ボルト/ベアリング等）にはコライダを付けない。")]
        public float colliderMinSize = 0.06f;
        [Tooltip("構造物の精密MeshCollider(1612個・非Convex)を実行時もそのまま使う（既定ON＝形状忠実）。" +
                 "OFFにすると重いMeshColliderを無効化し、軽量な床＋外周壁のBoxのみで物理を回す（500球で重い場合の保険）。")]
        public bool useHeavyFieldColliders = true;

        [Tooltip("球を ECS(Unity Physics/DOTS) で回す（段階②）。OFFで従来のGameObject球(WildfireManager)。" +
                 "ON時はWildfireManagerは球を生成せず、EcsBallSimがECS球＋専用の床/壁を生成して物理を回す。")]
        public bool useEcsBalls = true;
        private EcsBallSim _ecsBalls;

        [Tooltip("可視フィールドのコライダ(FBXのMeshCollider群)のワールドAABBからECS静的ボックスを複製し、" +
                 "ロボ/球が見えている構造物と同位置で衝突するようにする（段階④補完）。巨大/微小/極薄は除外。" +
                 "OFFでロボ/球はECS床/壁＋球とのみ衝突＝構造物すり抜け（段階③の状態）。useEcsBalls がONのときのみ意味を持つ。")]
        public bool useEcsStructureColliders = true;

        [Tooltip("ロボを ECS(Unity Physics/DOTS) の動的剛体で回す（段階③）。ON時は球と同一物理ワールドに入り" +
                 "球↔ロボ干渉が復活する。GameObjectロボはkinematic化され可視/機構/観測のシェルとして残る（EcsRobotSimがTransform同期）。" +
                 "既定ON。OFFで段階②の状態（GameObjectロボ＋ECS球）へ即ロールバック。useEcsBalls がONのときのみ意味を持つ。")]
        public bool useEcsRobots = true;
        private EcsRobotSim _ecsRobots;

        [Tooltip("判定機構(Intake/Shoot/Storage/Climb)を ECS球に対して機能させる（段階④）。ON時は旧GameObject機構" +
                 "(RobotIntake/RobotShooter/RobotClimb/StorageTrigger)を無効化し、EcsMechanics が唯一の判定経路になる。" +
                 "既定ON。useEcsRobots＆useEcsBalls がONのときのみ意味を持つ。OFFで段階③の状態（機構非機能）へ。")]
        public bool useEcsMechanics = true;
        private EcsMechanics _ecsMechanics;
        private bool EcsActive => useEcsBalls && useEcsRobots; // ECSロボ稼働中か
        private bool EcsMechActive => EcsActive && useEcsMechanics;

        [Tooltip("試合タイマー/フェーズを ECS の MatchSystem＋MatchStateDataシングルトンで回す（段階⑤）。" +
                 "スコアは ScoreBoard が算出し ScoreData シングルトンへ公開、HUDはシングルトンを読む。" +
                 "OFFで旧 FGC.Core.MatchManager 駆動へ即ロールバック。")]
        public bool useEcsMatch = true;
        private bool _ecsFinishHandled; // ECS試合のFinished検出を一度だけOnFinishへ流すためのエッジ管理

        public MatchConfig config = MatchConfig.Default(AllianceColor.Red, MatchMode.ThreeVsThree);
        [Tooltip("学習用: 全6スロットをMLエージェントに強制する（Build Training Sceneが立てる）。" +
                 "MatchConfig.Slotsはreadonlyでシリアライズされず、エディタ時のSetAllKindがPlay開始で失われるため、" +
                 "boolフラグ(シリアライズ可)経由でランタイムに全ML化する。")]
        public bool forceMlAgents = false;

        [Tooltip("試合終了で自動的にリセット→再開する（ML学習で連続エピソードを回す用。VR対戦ではfalse）")]
        public bool autoRestart = false;

        [Tooltip("Start時にSetupMenuを介さず即試合開始する（学習用シーン。VR対戦ではfalse=メニュー待ち）")]
        public bool autoBegin = false;

        private readonly MatchManager _match = new MatchManager();
        private readonly List<RobotActor> _robots = new List<RobotActor>(6);

        public MatchManager Match => _match;
        public IReadOnlyList<RobotActor> Robots => _robots;

        // ===== HUD表示用の現在値（段階⑤: ECS時はシングルトン、OFF時はMatchManager/ScoreBoard。毎Updateで更新）=====
        private float _hudRemaining = FieldConstants.MatchDuration;
        private MatchPhase _hudPhase = MatchPhase.PreMatch;
        private int _hudRed, _hudBlue, _hudCoop;
        public float HudRemaining => _hudRemaining;
        public MatchPhase HudPhase => _hudPhase;
        public int HudRed => _hudRed;
        public int HudBlue => _hudBlue;
        public int HudCoop => _hudCoop;

        public ScoreBoard Score { get; private set; }
        private readonly List<StorageTrigger> _storageTriggers = new List<StorageTrigger>(3);

        private void Start()
        {
            // 床/球のめり込みを抑えつつ、500球での重さを避ける程度にソルバ反復を控えめに上げる。
            // （20は重すぎて全球アクティブ時にフレーム崩壊→試合が止まる原因になっていた）
            Physics.defaultSolverIterations = 8;
            Physics.defaultSolverVelocityIterations = 2;

            // 学習用: 全ML化をランタイムで強制（readonly Slots がPlay開始で既定に戻る問題への対処）。
            // SpawnRobots が config.Slots を読む前に適用する。
            if (forceMlAgents)
            {
                if (config.Slots.Count == 0) config.RebuildSlots();
                config.SetAllKind(ControllerKind.MLAgent);
                Debug.Log($"[FGC] 学習用: 全{config.Slots.Count}スロットをMLエージェントに強制しました(forceMlAgents)。");
            }

            BuildField();
            SpawnRobots();
            // 準備フェーズ：試合開始前にフィールドへ球を撒いておく（本来の挙動）。ロボは Active=false で待機。
            if (useEcsBalls) StartEcsBalls();
            else if (wildfire != null) { wildfire.Prepare(); wildfire.EmitAll(); }
            // 段階③: ロボをECS剛体化（球と同一ワールド）。ECS球ON時のみ意味を持つ。
            if (EcsActive) StartEcsRobots();
            CreateStorageZones();
            CreateScoreBoard();
            // 段階④: 判定機構をECS球に対して機能させる。旧GameObject機構/ゾーンを無効化してから起動。
            if (EcsMechActive) StartEcsMechanics();
#if FGC_MLAGENTS
            CreateRewardShaper();
#endif
            if (playerRig != null) AllianceStationPlacer.Place(playerRig, config);

            _match.OnMatchFinish += OnFinish;

            // 「試合開始」ボタンの結線を実行時に張る。エディタ生成時のAction購読はPlay(ドメインリロード)で
            // 消えるため、ここで繋ぎ直すことでボタンから BeginMatch が確実に呼ばれる。
            var menu = FindFirstObjectByType<SetupMenu>();
            if (menu != null)
            {
                menu.OnStartRequested -= BeginMatch;
                menu.OnStartRequested += BeginMatch;
            }

            if (autoBegin) BeginMatch(config); // 学習用: メニューを介さず即開始
        }

        private void OnFinish()
        {
            if (Score != null) Score.Recalculate();
            Debug.Log($"[FGC] 試合終了 RED={Score?.RedRegional} BLUE={Score?.BlueRegional}");

            foreach (var actor in _robots) actor.Active = false; // 終了：ロボ停止
            if (_ecsRobots != null) _ecsRobots.SetActive(false); // 段階③: ECSロボ駆動を停止（待機）
            if (_ecsMechanics != null) _ecsMechanics.SetActive(false); // 段階④: 判定機構を停止

#if FGC_MLAGENTS
            // 勝敗報酬（self_play用）＋ML エピソード終了通知
            int red = Score != null ? Score.RedRegional : 0;
            int blue = Score != null ? Score.BlueRegional : 0;
            foreach (var actor in _robots)
            {
                var ml = actor.GetComponent<FGC.ML.MLAgentController>();
                if (ml == null) continue;

                int own = actor.alliance == AllianceColor.Red ? red : blue;
                int opp = actor.alliance == AllianceColor.Red ? blue : red;
                float outcome = own > opp ? 1f : (own < opp ? -1f : 0f);
                ml.AddShapedReward(outcome);
                ml.FinishEpisode();
            }
#endif

            // 学習モード: 環境をリセットして次の試合を即開始（連続エピソード）
            if (autoRestart)
            {
                ResetMatch();
                BeginMatch(config);
            }
        }

        /// <summary>環境を初期状態へ戻す（タイマー・球・ロボ位置/保持/登攀）。autoRestartや手動再戦で使う。</summary>
        public void ResetMatch()
        {
            if (useEcsMatch) { _ecsFinishHandled = false; EcsMatchBridge.ResetMatch(); } // 段階⑤: ECSタイマーを準備へ
            else _match.Reset();
            // 準備フェーズへ戻す：球を撒き直し、ロボは待機(Active=false)。
            if (useEcsBalls)
            {
                if (_ecsBalls != null) _ecsBalls.ResetAndPour();
                if (wildfire != null) wildfire.ResetToPool(); // 格納カウントを0へ（GO球リストは空＝無害）
            }
            else if (wildfire != null) { wildfire.ResetToPool(); wildfire.EmitAll(); }

            foreach (var actor in _robots)
            {
                Vector3 pos = FieldLayout.RobotStart(actor.slotIndex, actor.alliance);
                actor.ResetState(pos, FieldLayout.RobotStartRotation(actor.alliance));
                actor.Active = false;
            }
            // 段階③: ECSロボも開始位置・静止へ戻し待機（GameObject側 ResetState はkinematicなので位置だけ整う）。
            if (_ecsRobots != null) { _ecsRobots.ResetPositions(); _ecsRobots.SetActive(false); }
            // 段階④: 飛来球追跡/クールダウンを破棄して待機。
            if (_ecsMechanics != null) { _ecsMechanics.ResetState(); _ecsMechanics.SetActive(false); }
#if FGC_MLAGENTS
            // ML報酬のエピソード状態（登攀の最高到達・保持球トラッキング）をクリア。
            if (_rewardShaper != null) _rewardShaper.OnMatchReset();
#endif
        }

        /// <summary>段階②: ECS球シム(EcsBallSim)を用意して放出を開始する。放出点/メッシュ/色は
        /// WildfireManager の設定（emitAnchor優先）を流用し、見た目・位置を従来と一致させる。</summary>
        private void StartEcsBalls()
        {
            _ecsBalls = GetComponent<EcsBallSim>();
            if (_ecsBalls == null) _ecsBalls = gameObject.AddComponent<EcsBallSim>();

            Mesh mesh = wildfire != null ? wildfire.ballMesh : null;
            Material mat = wildfire != null ? wildfire.ballMaterial : null;
            Vector3 emit = new Vector3(3.5f, 0.15f, 0.45f);
            if (wildfire != null)
                emit = wildfire.emitAnchor != null ? wildfire.emitAnchor.position : wildfire.emitOrigin;

            _ecsBalls.Begin(mesh, mat, emit);
            if (useEcsStructureColliders) BuildEcsFieldColliders();
        }

        /// <summary>段階④補完: 可視フィールド(FBX等)のGameObjectコライダの**ワールドAABB**を ECS静的ボックスとして複製し、
        /// ロボ/球が**見えている構造物と同位置で**衝突するようにする。位置を推測せず実コライダに合わせるので確実。
        /// 巨大(中央トラス/床/長い外周＝塞ぎ過ぎ)・微小(ボルト等)・極薄(床/テープ)・トリガは除外。床/外周はECS側で別途生成済み。</summary>
        private void BuildEcsFieldColliders()
        {
            var field = transform.Find("Field");
            if (field == null || _ecsBalls == null) return;

            int made = 0, oriented = 0;
            foreach (var col in field.GetComponentsInChildren<Collider>())
            {
                if (col == null || !col.enabled || col.isTrigger) continue;
                Bounds b = col.bounds;                       // ワールド空間AABB（ECS=Unity同座標なので見た目と一致）
                Vector3 s = b.size;
                float maxDim = Mathf.Max(s.x, Mathf.Max(s.y, s.z));
                if (maxDim < 0.30f) continue;                // 微小部品（ボルト/継手）は無視
                if (s.y < 0.05f) continue;                   // 極薄（床/テープ/床面）は無視
                if (maxDim > 3.0f)
                {
                    // フィールド横断級。AABBだと塞ぎ過ぎなので原則除外だが、**ブレースの斜めパイプ**や
                    // トラス管/レールのような「細長い部材」は実メッシュからタイトなOBBを作って当たり判定を復活させる
                    // （対角6.4mのブレースは斜めゆえAABBが巨大化して従来は除外＝すり抜けていた）。
                    if (TryMakeThinOrientedBox(col, out var oc, out var os, out var orot))
                    {
                        _ecsBalls.AddStaticBoxOriented(oc, os, orot);
                        made++; oriented++;
                        if (made >= 2000) break;
                    }
                    continue;                                // 太い塊（ゴール背板/床/広い面）は従来通り除外
                }
                _ecsBalls.AddStaticBox(b.center, s);
                made++;
                if (made >= 2000) break;                     // 安全上限
            }
            Debug.Log($"[FGC] 可視フィールドコライダから ECS静的ボックスを {made} 個生成（うち斜め部材OBB {oriented} 個。見た目と同位置で衝突）。");
        }

        /// <summary>断面がこの値未満なら「細長部材(パイプ/管/レール)」とみなしOBB化する閾値[m]。
        /// ブレースは2x1inch管(断面≈0.05m)なので余裕で該当。ゴール背板/床/広い面(断面大)は除外＝塞ぎ過ぎ防止。</summary>
        private const float ThinMemberMaxCross = 0.5f;

        /// <summary>巨大除外対象の MeshCollider の実メッシュ頂点から、長軸に沿ったタイトなOBB(中心/サイズ/向き)を算出する。
        /// 斜めに baked された頂点でも長軸を2点法で求めるので、ブレースの斜めパイプを正しく細い箱で覆える。
        /// 細長部材(断面 &lt; ThinMemberMaxCross)のみ true。太い塊は false（従来通り除外）。</summary>
        private static bool TryMakeThinOrientedBox(Collider col, out Vector3 center, out Vector3 size, out Quaternion rot)
        {
            center = default; size = default; rot = Quaternion.identity;
            var mc = col as MeshCollider;
            if (mc == null || mc.sharedMesh == null || !mc.sharedMesh.isReadable) return false;

            var verts = mc.sharedMesh.vertices;
            if (verts.Length < 2) return false;
            var l2w = col.transform.localToWorldMatrix;

            // 1) 長軸を2点法で推定: centroid→最遠A→最遠B。a-b が部材の長手方向。
            Vector3 c0 = Vector3.zero;
            for (int i = 0; i < verts.Length; i++) c0 += l2w.MultiplyPoint3x4(verts[i]);
            c0 /= verts.Length;
            Vector3 a = c0; float da = -1f;
            for (int i = 0; i < verts.Length; i++) { var w = l2w.MultiplyPoint3x4(verts[i]); float d = (w - c0).sqrMagnitude; if (d > da) { da = d; a = w; } }
            Vector3 b = a; float db = -1f;
            for (int i = 0; i < verts.Length; i++) { var w = l2w.MultiplyPoint3x4(verts[i]); float d = (w - a).sqrMagnitude; if (d > db) { db = d; b = w; } }

            Vector3 fwd = b - a;
            if (fwd.sqrMagnitude < 1e-6f) return false;
            fwd.Normalize();
            Vector3 up0 = Mathf.Abs(fwd.y) < 0.9f ? Vector3.up : Vector3.right;
            Vector3 right = Vector3.Normalize(Vector3.Cross(up0, fwd));
            Vector3 up = Vector3.Cross(fwd, right);          // 既に正規直交

            // 2) 直交3軸(right/up/fwd)へ全頂点を射影してタイトな範囲を取る。
            float minR = float.MaxValue, maxR = -float.MaxValue, minU = float.MaxValue, maxU = -float.MaxValue, minF = float.MaxValue, maxF = -float.MaxValue;
            for (int i = 0; i < verts.Length; i++)
            {
                Vector3 w = l2w.MultiplyPoint3x4(verts[i]) - a;
                float pr = Vector3.Dot(w, right), pu = Vector3.Dot(w, up), pf = Vector3.Dot(w, fwd);
                if (pr < minR) minR = pr; if (pr > maxR) maxR = pr;
                if (pu < minU) minU = pu; if (pu > maxU) maxU = pu;
                if (pf < minF) minF = pf; if (pf > maxF) maxF = pf;
            }
            float wR = maxR - minR, wU = maxU - minU, wF = maxF - minF;

            // 細長部材のみOBB化（断面が小さい）。太い塊(断面大=面/ブロック)はOBBでも塞ぐので除外維持。
            if (Mathf.Max(wR, wU) > ThinMemberMaxCross) return false;

            center = a + right * ((minR + maxR) * 0.5f) + up * ((minU + maxU) * 0.5f) + fwd * ((minF + maxF) * 0.5f);
            size = new Vector3(Mathf.Max(wR, 0.02f), Mathf.Max(wU, 0.02f), Mathf.Max(wF, 0.02f)); // localX=right, Y=up, Z=fwd(長手)
            rot = Quaternion.LookRotation(fwd, up);          // localZ→fwd, localY→up
            return true;
        }

        /// <summary>段階③: ロボをECS動的剛体へ載せ替える。GameObjectロボは kinematic 化＋ExternallyDriven にして
        /// 二重駆動を避け、EcsRobotSim が物理駆動＋Transform同期を担う（可視/機構/観測のシェルは残る）。</summary>
        private void StartEcsRobots()
        {
            _ecsRobots = GetComponent<EcsRobotSim>();
            if (_ecsRobots == null) _ecsRobots = gameObject.AddComponent<EcsRobotSim>();

            foreach (var actor in _robots)
            {
                actor.ExternallyDriven = true;
                var rb = actor.GetComponent<Rigidbody>();
                if (rb != null) rb.isKinematic = true; // GameObject物理を止め、ECSの結果に追従させる
            }

            _ecsRobots.Begin(_robots, config);
        }

        /// <summary>段階④: ECS判定機構を起動。旧GameObject機構(各ロボのRobotIntake/Shooter/Climb)と
        /// 旧StorageTriggerは ECS球を扱えない/二重発火するため無効化し、EcsMechanics を唯一の判定経路にする。</summary>
        private void StartEcsMechanics()
        {
            // 旧機構コンポーネントを無効化（特に RobotShooter は wildfire.LaunchBall でGameObject球を撒いてしまう）。
            foreach (var actor in _robots)
            {
                DisableIfPresent(actor.GetComponent<RobotIntake>());
                DisableIfPresent(actor.GetComponent<RobotShooter>());
                DisableIfPresent(actor.GetComponent<RobotClimb>());
            }
            // 旧GameObject格納ゾーン（PhysXトリガ）を無効化＝ECS機構が同等のAABB判定で代替。
            foreach (var st in _storageTriggers) if (st != null) st.enabled = false;

            _ecsMechanics = GetComponent<EcsMechanics>();
            if (_ecsMechanics == null) _ecsMechanics = gameObject.AddComponent<EcsMechanics>();
            _ecsMechanics.Begin(_ecsBalls, _robots, wildfire);
        }

        private static void DisableIfPresent(MonoBehaviour mb) { if (mb != null) mb.enabled = false; }

#if FGC_MLAGENTS
        private RewardShaper _rewardShaper;        // ResetMatch でエピソード状態をクリアするため保持

        private void CreateRewardShaper()
        {
            var go = new GameObject("RewardShaper");
            go.transform.SetParent(transform, false);
            var shaper = go.AddComponent<RewardShaper>();
            shaper.director = this;
            shaper.Bind(_storageTriggers);          // 旧GameObject機構（useEcsMechanics OFF時に発火）
            shaper.BindEcs(_ecsMechanics);          // ECS機構（既定ON時に発火）＝ECS学習で主報酬を確実化
            _rewardShaper = shaper;
        }
#endif

        /// <summary>格納ゾーン（SUPPRESSION赤/青・EXTINGUISHER）を生成。座標は暫定・外観図と要照合。</summary>
        private void CreateStorageZones()
        {
            if (wildfire == null) return;
            var root = new GameObject("StorageZones").transform;
            root.SetParent(transform, false);
            _storageTriggers.Clear();

            // SUPPRESSION（リージョン得点・同盟一致のみ）: 座標は FieldLayout に集約
            _storageTriggers.Add(StorageTrigger.Create(root, "Suppression_Blue",
                FieldLayout.SuppressionBlueCenter, FieldLayout.SuppressionZoneSize,
                wildfire, FGC.Ball.StorageKind.SuppressionBlue, true, AllianceColor.Blue));
            _storageTriggers.Add(StorageTrigger.Create(root, "Suppression_Red",
                FieldLayout.SuppressionRedCenter, FieldLayout.SuppressionZoneSize,
                wildfire, FGC.Ball.StorageKind.SuppressionRed, true, AllianceColor.Red));

            // EXTINGUISHER（GLOBAL・全同盟可）: 上辺中央
            _storageTriggers.Add(StorageTrigger.Create(root, "Extinguisher",
                FieldLayout.ExtinguisherCenter, FieldLayout.ExtinguisherZoneSize,
                wildfire, FGC.Ball.StorageKind.Extinguisher, false, AllianceColor.Red));
        }

        private void CreateScoreBoard()
        {
            var go = new GameObject("ScoreBoard");
            go.transform.SetParent(transform, false);
            Score = go.AddComponent<ScoreBoard>();
            Score.wildfire = wildfire;
            Score.director = this;
        }

        private void Update()
        {
            if (useEcsMatch)
            {
                // タイマー進行は MatchSystem(ECS) が担当。ここでは状態を読み、Finishedエッジで一度だけ OnFinish。
                if (EcsMatchBridge.TryGetState(out var st))
                {
                    _hudRemaining = st.Remaining;
                    _hudPhase = (MatchPhase)st.Phase;
                    if (st.Phase == (int)MatchPhase.Finished && !_ecsFinishHandled)
                    {
                        _ecsFinishHandled = true;
                        OnFinish();
                    }
                }
                if (Score != null)
                {
                    _hudRed = Score.RedRegional; _hudBlue = Score.BlueRegional; _hudCoop = Score.CoopertitionGlobal;
                    EcsMatchBridge.PublishScore(_hudRed, _hudBlue, _hudCoop); // ScoreData シングルトンへ公開
                }
            }
            else
            {
                _match.Tick(Time.deltaTime);
                _hudRemaining = _match.Remaining;
                _hudPhase = _match.Phase;
                if (Score != null) { _hudRed = Score.RedRegional; _hudBlue = Score.BlueRegional; _hudCoop = Score.CoopertitionGlobal; }
            }
        }

        /// <summary>SetupMenu から呼ぶ：試合開始（球放出＋タイマー）。</summary>
        public void BeginMatch(MatchConfig cfg)
        {
            config = cfg;
            ReassignControllers();
            // 試合フェーズ開始：球は準備フェーズで撒き済み。ここではロボを始動させタイマー開始。
            foreach (var actor in _robots) actor.Active = true;
            if (_ecsRobots != null) _ecsRobots.SetActive(true); // 段階③: ECSロボ駆動を始動
            if (_ecsMechanics != null) _ecsMechanics.SetActive(true); // 段階④: ECS判定機構を始動
            if (useEcsMatch) { _ecsFinishHandled = false; EcsMatchBridge.StartMatch(); } // 段階⑤: ECSタイマー始動
            else _match.StartMatch();
        }

        private void BuildField()
        {
            // エディタ(FGC > Build Field Scene)でフィールド＋コライダを焼き込み済みなら、
            // 実行時の再生成・MeshCollider再クックをしない（Play開始のフリーズ回避）。
            var bakedField = transform.Find("Field");
            if (bakedField != null) { ConfigureFieldColliders(bakedField); TagBraces(bakedField); return; }

            // フォールバック: シーンに焼き込みが無い場合のみ実行時生成（fieldModelPrefab 指定時）。
            var fieldRoot = new GameObject("Field").transform;
            fieldRoot.SetParent(transform, false);

            if (fieldModelPrefab != null)
            {
                var model = Instantiate(fieldModelPrefab, fieldRoot);
                model.name = "FieldModel";
                model.transform.localPosition = fieldOffset;
                if (fieldEuler != Vector3.zero)
                    model.transform.localRotation = Quaternion.Euler(fieldEuler) * model.transform.localRotation;
                if (!Mathf.Approximately(fieldScale, 1f))
                    model.transform.localScale = model.transform.localScale * fieldScale;

                if (buildFieldColliders) FieldColliders.Build(fieldRoot, model, colliderMinSize);
                TagBraces(fieldRoot);
            }
            else
            {
                Debug.LogWarning("[FGC] フィールドが見つかりません。FGC > Build Field Scene を実行してください。");
            }
        }

        /// <summary>登攀対象の BRACE（斜めの鋼管）を**形状から特定**して `BraceMarker` を付与する。
        /// 実FBX部品名は "brace" を含まない（mesh#/MAXTube…）ため、名前ではなく形状で判定する：
        /// 細長い（断面&lt;0.5m）・長い（長軸&gt;3m）・登攀高さ帯を斜めに跨ぐ（世界minY&lt;0.6 かつ maxY&gt;1.2）。
        /// これで対角の鋼管2本だけが該当し、横レール（低い）/ゴール壁（太い）/高所トラス（minY高）は除外される。
        /// 登攀判定（EcsMechanics/RobotClimb）はこのマーカーでBRACEを認識する。</summary>
        private void TagBraces(Transform field)
        {
            if (field == null) return;
            int tagged = 0;
            foreach (var col in field.GetComponentsInChildren<Collider>())
            {
                if (col == null || col.isTrigger) continue;
                if (col.GetComponent<BraceMarker>() != null) continue; // 二重付与防止
                // 安価なAABB事前フィルタ（頂点読取はここを通った候補のみ＝1612コライダの大半を弾く）
                Bounds b = col.bounds;
                Vector3 s = b.size;
                float maxDim = Mathf.Max(s.x, Mathf.Max(s.y, s.z));
                if (maxDim < 3.0f) continue;          // 長い部材のみ（ブレースは6.4m）
                if (b.min.y > 0.6f) continue;         // 床近くから始まる（高所トラスを除外）
                if (b.max.y < 1.2f) continue;         // 登攀帯まで上がる（低い横レールを除外）
                if (!IsThinElongated(col, out _, out _, out _)) continue; // 細長い鋼管のみ（太いゴール壁を除外）
                col.gameObject.AddComponent<BraceMarker>();
                tagged++;
            }
            // 実フィールドでは 8 個＝ブレース2基×各4管(2x1 MAXTube)。中央トラス斜材等の混入が無いことは
            // 各部材の位置(X≈4.88/2.12・Z≈3.7)・長さ(6.4m)・傾き(|軸Y|≈0.26)が2グループに揃うことで確認済(2026-06-09)。
            Debug.Log($"[FGC] BRACE(登攀対象)を {tagged} 個タグ付け（形状判定・名前非依存。実フィールドは2基×各4管=8想定）。");
        }

        /// <summary>コライダのメッシュ頂点から「細長い部材か」を判定（長軸&gt;3m かつ断面&lt;0.5m）。
        /// 斜めにbakeされた頂点でも長軸を2点法で求めるので向きに依らない。
        /// out: length=長軸長[m] / cross=断面の最大幅[m] / axis=長軸の単位ベクトル(world)。</summary>
        private static bool IsThinElongated(Collider col, out float length, out float cross, out Vector3 axis)
        {
            length = 0f; cross = 0f; axis = Vector3.forward;
            var mc = col as MeshCollider;
            if (mc == null || mc.sharedMesh == null || !mc.sharedMesh.isReadable) return false;
            var verts = mc.sharedMesh.vertices;
            if (verts.Length < 2) return false;
            var l2w = col.transform.localToWorldMatrix;

            Vector3 c0 = Vector3.zero;
            for (int i = 0; i < verts.Length; i++) c0 += l2w.MultiplyPoint3x4(verts[i]);
            c0 /= verts.Length;
            Vector3 a = c0; float da = -1f;
            for (int i = 0; i < verts.Length; i++) { var w = l2w.MultiplyPoint3x4(verts[i]); float d = (w - c0).sqrMagnitude; if (d > da) { da = d; a = w; } }
            Vector3 bb = a; float db = -1f;
            for (int i = 0; i < verts.Length; i++) { var w = l2w.MultiplyPoint3x4(verts[i]); float d = (w - a).sqrMagnitude; if (d > db) { db = d; bb = w; } }

            Vector3 fwd = bb - a;
            float len = fwd.magnitude;
            if (len < 3.0f) return false;                    // 長軸が短い＝ブレースでない
            fwd /= len;
            length = len; axis = fwd;
            Vector3 up0 = Mathf.Abs(fwd.y) < 0.9f ? Vector3.up : Vector3.right;
            Vector3 right = Vector3.Normalize(Vector3.Cross(up0, fwd));
            Vector3 up = Vector3.Cross(fwd, right);

            float minR = float.MaxValue, maxR = -float.MaxValue, minU = float.MaxValue, maxU = -float.MaxValue;
            for (int i = 0; i < verts.Length; i++)
            {
                Vector3 w = l2w.MultiplyPoint3x4(verts[i]) - a;
                float pr = Vector3.Dot(w, right), pu = Vector3.Dot(w, up);
                if (pr < minR) minR = pr; if (pr > maxR) maxR = pr;
                if (pu < minU) minU = pu; if (pu > maxU) maxU = pu;
            }
            cross = Mathf.Max(maxR - minR, maxU - minU);
            return cross <= 0.5f;                            // 断面が小さい＝細い鋼管
        }

        /// <summary>
        /// 焼き込み済みフィールドの当たり判定を実行時に最適化。
        /// 既定(useHeavyFieldColliders=false)では、500球には重すぎる構造MeshColliderを無効化し、
        /// 軽量な床Box＋外周壁Boxだけで物理を回す（フレーム崩壊＝試合が止まる問題の回避）。
        /// </summary>
        private void ConfigureFieldColliders(Transform field)
        {
            if (useHeavyFieldColliders) return; // 精密コライダをそのまま使う（球数を絞る等の上級用途）

            int disabled = 0;
            foreach (var mc in field.GetComponentsInChildren<MeshCollider>())
            {
                if (mc.enabled) { mc.enabled = false; disabled++; }
            }
            EnsurePerimeterWalls(field);
            EnsureFloor(field);
            Debug.Log($"[FGC] 重い構造MeshColliderを無効化({disabled}個) → 軽量Box(床+外周壁)で物理を回します。");
        }

        /// <summary>外周4辺の壁（Box）。球・ロボの場外流出を防ぐ。</summary>
        private static void EnsurePerimeterWalls(Transform field)
        {
            if (field.Find("RuntimeWalls") != null) return;
            var walls = new GameObject("RuntimeWalls").transform;
            walls.SetParent(field, false);
            float sx = FieldConstants.FieldSizeX, sz = FieldConstants.FieldSizeZ;
            float h = 0.5f, t = 0.1f; // 高さ50cm・厚10cm
            AddBox(walls, "Wall_S", new Vector3(sx * 0.5f, h * 0.5f, 0f), new Vector3(sx, h, t));
            AddBox(walls, "Wall_N", new Vector3(sx * 0.5f, h * 0.5f, sz), new Vector3(sx, h, t));
            AddBox(walls, "Wall_W", new Vector3(0f, h * 0.5f, sz * 0.5f), new Vector3(t, h, sz));
            AddBox(walls, "Wall_E", new Vector3(sx, h * 0.5f, sz * 0.5f), new Vector3(t, h, sz));
        }

        /// <summary>床Box（焼き込み済みに無ければ用意）。安定した接地面。</summary>
        private static void EnsureFloor(Transform field)
        {
            if (field.Find("FloorCollider") != null) return;
            AddBox(field, "FloorCollider",
                new Vector3(FieldConstants.FieldSizeX * 0.5f, -0.02f, FieldConstants.FieldSizeZ * 0.5f),
                new Vector3(FieldConstants.FieldSizeX, 0.04f, FieldConstants.FieldSizeZ));
        }

        private static void AddBox(Transform parent, string name, Vector3 localPos, Vector3 size)
        {
            var go = new GameObject(name);
            go.transform.SetParent(parent, false);
            go.transform.localPosition = localPos;
            go.AddComponent<BoxCollider>().size = size;
        }

        private void SpawnRobots()
        {
            _robots.Clear();
            for (int i = 0; i < config.Slots.Count; i++)
            {
                var slot = config.Slots[i];
                var actor = CreateRobot(slot.SlotIndex, slot.Alliance);
                _robots.Add(actor);
            }
            ReassignControllers();
            WireObservations();
        }

        private RobotActor CreateRobot(int slotIndex, AllianceColor alliance)
        {
            var go = new GameObject($"Robot_{slotIndex}_{alliance}");
            go.transform.SetParent(transform, false);
            go.transform.SetPositionAndRotation(
                FieldLayout.RobotStart(slotIndex, alliance),
                FieldLayout.RobotStartRotation(alliance));

            var col = go.AddComponent<BoxCollider>();
            col.size = new Vector3(0.5f, 0.5f, 0.5f);          // 500mm立方体
            col.center = new Vector3(0f, 0.25f, 0f);            // 床(y=0)に底面を接地
            go.AddComponent<Rigidbody>(); // RobotActor.Awake が質量/拘束を設定

            AddRobotVisual(go.transform, alliance); // 可視メッシュ（同盟色＋前方マーカー）

            var actor = go.AddComponent<RobotActor>();
            actor.alliance = alliance;
            actor.slotIndex = slotIndex;
            actor.spec = RobotSpec.Default;

            // インテーク機構を付与し WildfireManager に結線
            var intake = go.AddComponent<RobotIntake>();
            intake.wildfire = wildfire;
            // シュート機構を付与し結線
            var shooter = go.AddComponent<RobotShooter>();
            shooter.wildfire = wildfire;
            // 登攀判定（BRACE接触＋高さでClimbZone更新）
            go.AddComponent<RobotClimb>();
            return actor;
        }

        /// <summary>ロボの可視メッシュ（同盟色の本体＋前方マーカー）。衝突は親BoxColliderが担うのでコライダ無し。</summary>
        private static void AddRobotVisual(Transform parent, AllianceColor alliance)
        {
            Color baseColor = alliance == AllianceColor.Red
                ? new Color(0.85f, 0.25f, 0.25f) : new Color(0.25f, 0.45f, 0.9f);
            var mat = MaterialUtil.Colored(baseColor);

            // 本体
            var body = GameObject.CreatePrimitive(PrimitiveType.Cube);
            body.name = "Visual_Body";
            Object.Destroy(body.GetComponent<Collider>()); // 物理は親に任せる
            body.transform.SetParent(parent, false);
            body.transform.localPosition = new Vector3(0f, 0.25f, 0f);
            body.transform.localScale = new Vector3(0.5f, 0.5f, 0.5f);  // 500mm立方体
            body.GetComponent<MeshRenderer>().sharedMaterial = mat;

            // 前方マーカー（向きが分かるよう前面に白い小片）
            var nose = GameObject.CreatePrimitive(PrimitiveType.Cube);
            nose.name = "Visual_Front";
            Object.Destroy(nose.GetComponent<Collider>());
            nose.transform.SetParent(parent, false);
            nose.transform.localPosition = new Vector3(0f, 0.40f, 0.27f); // 0.5立方体の前面上部
            nose.transform.localScale = new Vector3(0.18f, 0.08f, 0.06f);
            nose.GetComponent<MeshRenderer>().sharedMaterial = MaterialUtil.Colored(Color.white);
        }

        /// <summary>MatchConfig の割当に従い各ロボへ Controller を設定（人間/NPC/ML）。</summary>
        private void ReassignControllers()
        {
            for (int i = 0; i < _robots.Count; i++)
            {
                var actor = _robots[i];
                var slot = FindSlot(actor.slotIndex);
                actor.Controller = MakeController(slot.Kind, actor);
                SetPlayerMarker(actor, slot.Kind == ControllerKind.Human); // 自ロボ強調（PLAN §3.7）
            }
        }

        /// <summary>人間操作ロボの頭上に浮遊マーカーを出す/消す（VRで自機を見失わないため）。</summary>
        private static void SetPlayerMarker(RobotActor actor, bool on)
        {
            var existing = actor.transform.Find("PlayerMarker");
            if (on && existing == null)
            {
                var m = GameObject.CreatePrimitive(PrimitiveType.Sphere);
                m.name = "PlayerMarker";
                Object.Destroy(m.GetComponent<Collider>());
                m.transform.SetParent(actor.transform, false);
                m.transform.localPosition = new Vector3(0f, 0.6f, 0f);
                m.transform.localScale = Vector3.one * 0.18f;
                m.GetComponent<MeshRenderer>().sharedMaterial = MaterialUtil.Colored(new Color(0.2f, 1f, 0.35f));
            }
            else if (!on && existing != null)
            {
                Object.Destroy(existing.gameObject);
            }
        }

        private IRobotController MakeController(ControllerKind kind, RobotActor actor)
        {
            switch (kind)
            {
                case ControllerKind.Human: return new HumanController();
#if FGC_MLAGENTS
                case ControllerKind.MLAgent:
                    // ML はMonoBehaviour(Agent)。既存が無ければ付与し、観測供給を配線。
                    var ml = actor.GetComponent<FGC.ML.MLAgentController>();
                    if (ml == null) ml = actor.gameObject.AddComponent<FGC.ML.MLAgentController>();
                    ml.observationProvider = () => BuildObservation(actor);
                    return ml;
#endif
                // ML無効時(FGC_MLAGENTS未定義)はMLスロットもNPCで代替
                default: return new RuleBasedNpcController();
            }
        }

        private SlotAssignment FindSlot(int slotIndex)
        {
            foreach (var s in config.Slots) if (s.SlotIndex == slotIndex) return s;
            return SlotAssignment.Make(slotIndex, AllianceColor.Red, ControllerKind.RuleBasedNpc);
        }

        private void WireObservations()
        {
            foreach (var actor in _robots)
            {
                var captured = actor;
                captured.ObservationProvider = () => BuildObservation(captured);
            }
        }

        /// <summary>ロボの観測を構築（最寄り球は WildfireManager から、残時間は MatchManager から）。</summary>
        private RobotObservation BuildObservation(RobotActor actor)
        {
            var rb = actor.GetComponent<Rigidbody>();
            Vector3 vel = rb != null ? rb.linearVelocity : Vector3.zero;
            float yawRate = rb != null ? rb.angularVelocity.y : 0f;
            // ECSロボ稼働時は GameObject rb が kinematic で速度0のため、ECS結果の実速度を優先（NPC D項/ML観測の品質確保）。
            if (_ecsRobots != null && _ecsRobots.TryGetVelocity(actor, out var ecsLin, out var ecsYaw))
            {
                vel = ecsLin;
                yawRate = ecsYaw;
            }

            Vector3 nearest = actor.transform.position;
            bool hasBall = false;
            // ★修正(2026-06-10): ECSモードでは球はECS Entity＝WildfireManagerのGameObject球リストは常に空で
            // 最寄り球の観測が死んでいた（得点学習の主観測）。ECS球を直接クエリする。
            if (useEcsBalls && _ecsBalls != null)
                hasBall = _ecsBalls.TryGetNearestBall(actor.transform.position, out nearest);
            else if (wildfire != null)
                hasBall = wildfire.TryGetNearestBall(actor.transform.position, out nearest);

            int ownSuppression = 0;
            if (wildfire != null)
                ownSuppression = actor.alliance == AllianceColor.Red
                    ? wildfire.SuppressionRedStored : wildfire.SuppressionBlueStored;

            // 最寄りの相手／味方ロボ（3v3戦術学習用）
            Vector3 selfPos = actor.transform.position;
            Vector3 nearOpp = selfPos, nearMate = selfPos;
            float bestOpp = float.MaxValue, bestMate = float.MaxValue;
            bool hasOpp = false, hasMate = false;
            foreach (var other in _robots)
            {
                if (other == actor) continue;
                float d = Vector3.Distance(selfPos, other.transform.position);
                if (other.alliance == actor.alliance)
                {
                    if (d < bestMate) { bestMate = d; nearMate = other.transform.position; hasMate = true; }
                }
                else
                {
                    if (d < bestOpp) { bestOpp = d; nearOpp = other.transform.position; hasOpp = true; }
                }
            }

            return new RobotObservation
            {
                Alliance = actor.alliance,
                SlotIndex = actor.slotIndex,
                Position = selfPos,
                HeadingRad = actor.transform.eulerAngles.y * Mathf.Deg2Rad,
                Velocity = vel,
                AngularVelocity = yawRate,
                HeldBalls = actor.HeldBalls,
                ClimbZone = actor.ClimbZone,
                // ★修正(2026-06-10): useEcsMatch既定では _match は始動せず Remaining が常に150=観測が定数1.0(死に観測)だった。
                // _hudRemaining は ECS/旧タイマー両モードで毎フレーム更新されるミラーなのでこちらを使う。
                MatchTimeRemaining = _hudRemaining,
                OwnRegionSuppression = ownSuppression,
                GlobalExtinguisher = wildfire != null ? wildfire.ExtinguisherStored : 0,
                NearestBall = nearest,
                HasNearestBall = hasBall,
                NearestOpponent = nearOpp,
                HasOpponent = hasOpp,
                NearestTeammate = nearMate,
                HasTeammate = hasMate
            };
        }
    }
}
