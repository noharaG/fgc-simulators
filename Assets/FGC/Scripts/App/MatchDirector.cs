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
        [Tooltip("構造物の精密MeshCollider(1612個・非Convex)を実行時も使うか。500球だとフレーム崩壊するため通常OFF。" +
                 "OFF時は重いMeshColliderを無効化し、軽量な床＋外周壁のBoxのみで物理を回す。")]
        public bool useHeavyFieldColliders = false;

        public MatchConfig config = MatchConfig.Default(AllianceColor.Red, MatchMode.ThreeVsThree);

        [Tooltip("試合終了で自動的にリセット→再開する（ML学習で連続エピソードを回す用。VR対戦ではfalse）")]
        public bool autoRestart = false;

        [Tooltip("Start時にSetupMenuを介さず即試合開始する（学習用シーン。VR対戦ではfalse=メニュー待ち）")]
        public bool autoBegin = false;

        private readonly MatchManager _match = new MatchManager();
        private readonly List<RobotActor> _robots = new List<RobotActor>(6);

        public MatchManager Match => _match;
        public IReadOnlyList<RobotActor> Robots => _robots;

        public ScoreBoard Score { get; private set; }
        private readonly List<StorageTrigger> _storageTriggers = new List<StorageTrigger>(3);

        private void Start()
        {
            // 床/球のめり込みを抑えつつ、500球での重さを避ける程度にソルバ反復を控えめに上げる。
            // （20は重すぎて全球アクティブ時にフレーム崩壊→試合が止まる原因になっていた）
            Physics.defaultSolverIterations = 8;
            Physics.defaultSolverVelocityIterations = 2;

            BuildField();
            SpawnRobots();
            // 準備フェーズ：試合開始前にフィールドへ球を撒いておく（本来の挙動）。ロボは Active=false で待機。
            if (wildfire != null) { wildfire.Prepare(); wildfire.EmitAll(); }
            CreateStorageZones();
            CreateScoreBoard();
#if FGC_MLAGENTS
            CreateRewardShaper();
#endif
            if (playerRig != null) AllianceStationPlacer.Place(playerRig, config.PlayerAlliance);

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
            _match.Reset();
            // 準備フェーズへ戻す：球を撒き直し、ロボは待機(Active=false)。
            if (wildfire != null) { wildfire.ResetToPool(); wildfire.EmitAll(); }

            foreach (var actor in _robots)
            {
                Vector3 pos = FieldLayout.RobotStart(actor.slotIndex, actor.alliance);
                actor.ResetState(pos, FieldLayout.RobotStartRotation(actor.alliance));
                actor.Active = false;
            }
        }

#if FGC_MLAGENTS
        private void CreateRewardShaper()
        {
            var go = new GameObject("RewardShaper");
            go.transform.SetParent(transform, false);
            var shaper = go.AddComponent<RewardShaper>();
            shaper.director = this;
            shaper.Bind(_storageTriggers);
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
            _match.Tick(Time.deltaTime);
        }

        /// <summary>SetupMenu から呼ぶ：試合開始（球放出＋タイマー）。</summary>
        public void BeginMatch(MatchConfig cfg)
        {
            config = cfg;
            ReassignControllers();
            // 試合フェーズ開始：球は準備フェーズで撒き済み。ここではロボを始動させタイマー開始。
            foreach (var actor in _robots) actor.Active = true;
            _match.StartMatch();
        }

        private void BuildField()
        {
            // エディタ(FGC > Build Field Scene)でフィールド＋コライダを焼き込み済みなら、
            // 実行時の再生成・MeshCollider再クックをしない（Play開始のフリーズ回避）。
            var bakedField = transform.Find("Field");
            if (bakedField != null) { ConfigureFieldColliders(bakedField); return; }

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
            }
            else
            {
                Debug.LogWarning("[FGC] フィールドが見つかりません。FGC > Build Field Scene を実行してください。");
            }
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

            Vector3 nearest = actor.transform.position;
            bool hasBall = false;
            if (wildfire != null)
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
                MatchTimeRemaining = _match.Remaining,
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
