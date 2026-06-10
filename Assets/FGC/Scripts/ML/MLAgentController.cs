using FGC.Core;
using FGC.Robot;
using Unity.MLAgents;
using Unity.MLAgents.Actuators;
using Unity.MLAgents.Policies;
using Unity.MLAgents.Sensors;
using UnityEngine;

namespace FGC.ML
{
    /// <summary>
    /// ML-Agents による学習可能コントローラ（PLAN.md §3.2 / §3.8）。
    /// ML-Agents の Agent を継承しつつ IRobotController を実装し、6スロットの差込口に置ける。
    ///   観測: 自ロボ位置/向き/速度・最寄り球・格納数・時計・登攀状態（ベクトル観測）
    ///   行動: 連続[並進, 旋回] + 離散[インテーク, シュート, 登攀]
    ///   報酬: 格納+1相当・EXTINGUISHER寄与・登攀ボーナス・反則ペナルティ・時間整形（Sim側から AddReward）
    /// 学習前/推論なし時は Heuristic で RuleBasedNpcController にフォールバック。
    /// </summary>
    public sealed class MLAgentController : Agent, IRobotController
    {
        [Tooltip("観測を供給する関数（Sim層 RobotActor から注入）")]
        public System.Func<RobotObservation> observationProvider;

        private DriveCommand _latest = DriveCommand.Zero;
        private readonly RuleBasedNpcController _fallback = new RuleBasedNpcController();

        // フィールド正規化用スケール
        private const float PosScale = 7.0f;
        private const float SpeedScale = 2.0f;

        // 脳設定（yaml の behavior 名・観測/行動次元と一致させる）
        public const string BehaviorNameConst = "FgcRobot";
        public const int ObservationSize = 22;        // CollectObservations の追加数と一致（基本16＋近傍ロボ6）
        public const int ContinuousActions = 2;       // 並進, 旋回
        private static readonly int[] DiscreteBranches = { 2, 2, 2 }; // Intake, Shoot, Climb

        private void Awake()
        {
            ConfigureBrain();
        }

        /// <summary>
        /// BehaviorParameters / DecisionRequester をコードで実体化（PLAN.md §3.8・仕上げC）。
        /// GUIでの手設定が無くても学習・推論が回るよう、観測16・連続2＋離散[2,2,2] を設定する。
        /// </summary>
        private void ConfigureBrain()
        {
            var bp = GetComponent<BehaviorParameters>();
            if (bp == null) bp = gameObject.AddComponent<BehaviorParameters>();
            bp.BehaviorName = BehaviorNameConst;
            // **重要**: Default でないと外部トレーナ(mlagents-learn)へ接続しない（学習時の必須設定）。
            bp.BehaviorType = BehaviorType.Default;
            // **自己対戦の必須設定**: 赤=Team0 / 青=Team1。全員Team0だと ghost trainer のチーム交代と
            // ELO計算が機能しない（fgc_run9で全agentが FgcRobot?team=0 になりELOが指標にならなかった）。
            // alliance は MatchDirector.CreateRobot が本コンポーネント追加前に設定済み＝Awake時点で確定。
            var actorForTeam = GetComponent<RobotActor>();
            bp.TeamId = (actorForTeam != null && actorForTeam.alliance == AllianceColor.Blue) ? 1 : 0;
            bp.BrainParameters.VectorObservationSize = ObservationSize;
            bp.BrainParameters.NumStackedVectorObservations = 1;
            bp.BrainParameters.ActionSpec = new ActionSpec(ContinuousActions, DiscreteBranches);

            var dr = GetComponent<DecisionRequester>();
            if (dr == null) dr = gameObject.AddComponent<DecisionRequester>();
            dr.DecisionPeriod = 5;
            dr.TakeActionsBetweenDecisions = true;

            // 診断: ML エージェントが実際に生成・設定されたか（Editor.log で接続前提を確認する用）。
            Debug.Log($"[FGC] MLAgentController 設定: behavior='{bp.BehaviorName}' team={bp.TeamId} type={bp.BehaviorType} obs={ObservationSize} on '{gameObject.name}'");
        }

        /// <summary>IRobotController：直近に decision で確定した指令を返す。</summary>
        public DriveCommand GetCommand(in RobotObservation obs, float dt)
        {
            // ML-Agents の意思決定は OnActionReceived 経由で _latest を更新する。
            // ここでは最新の確定指令を返すだけ（RequestDecision の周期は DecisionRequester に委ねる）。
            return _latest;
        }

        public override void CollectObservations(VectorSensor sensor)
        {
            var o = observationProvider != null ? observationProvider() : default;

            sensor.AddObservation(o.Position.x / PosScale);
            sensor.AddObservation(o.Position.z / PosScale);
            sensor.AddObservation(Mathf.Sin(o.HeadingRad));
            sensor.AddObservation(Mathf.Cos(o.HeadingRad));
            sensor.AddObservation(o.Velocity.x / SpeedScale);
            sensor.AddObservation(o.Velocity.z / SpeedScale);
            sensor.AddObservation(o.AngularVelocity / FieldConstants.MatchDuration); // 緩い正規化

            sensor.AddObservation(o.HeldBalls / (float)FieldConstants.RobotBallCapacity);
            sensor.AddObservation((int)o.ClimbZone / 4f);
            sensor.AddObservation(o.MatchTimeRemaining / FieldConstants.MatchDuration);
            sensor.AddObservation(o.OwnRegionSuppression / 100f);
            sensor.AddObservation(o.GlobalExtinguisher / 100f);

            // 最寄り球（相対位置・距離・有無）= 4観測。ここまでで16次元。
            Vector3 rel = o.HasNearestBall ? (o.NearestBall - o.Position) : Vector3.zero;
            sensor.AddObservation(rel.x / PosScale);                       // 13
            sensor.AddObservation(rel.z / PosScale);                       // 14
            sensor.AddObservation(o.HasNearestBall ? rel.magnitude / PosScale : 1f); // 15 距離
            sensor.AddObservation(o.HasNearestBall ? 1f : 0f);             // 16 有無

            // 近傍ロボ（相手・味方の相対位置＋有無）= 6観測。合計22次元（3v3戦術学習用・PLAN §3.8）。
            Vector3 relOpp = o.HasOpponent ? (o.NearestOpponent - o.Position) : Vector3.zero;
            sensor.AddObservation(relOpp.x / PosScale);                    // 17
            sensor.AddObservation(relOpp.z / PosScale);                    // 18
            sensor.AddObservation(o.HasOpponent ? 1f : 0f);               // 19
            Vector3 relMate = o.HasTeammate ? (o.NearestTeammate - o.Position) : Vector3.zero;
            sensor.AddObservation(relMate.x / PosScale);                   // 20
            sensor.AddObservation(relMate.z / PosScale);                   // 21
            sensor.AddObservation(o.HasTeammate ? 1f : 0f);               // 22
        }

        public override void OnActionReceived(ActionBuffers actions)
        {
            var c = actions.ContinuousActions;
            var d = actions.DiscreteActions;

            _latest = new DriveCommand
            {
                Forward = c.Length > 0 ? Mathf.Clamp(c[0], -1f, 1f) : 0f,
                Turn = c.Length > 1 ? Mathf.Clamp(c[1], -1f, 1f) : 0f,
                Intake = d.Length > 0 && d[0] == 1,
                Shoot = d.Length > 1 && d[1] == 1,
                Climb = d.Length > 2 && d[2] == 1
            };
        }

        public override void Heuristic(in ActionBuffers actionsOut)
        {
            // 学習・推論が無い時はルールベースNPCで動かす（自己対戦の相手やデモに使える）。
            var o = observationProvider != null ? observationProvider() : default;
            DriveCommand cmd = _fallback.GetCommand(in o, Time.fixedDeltaTime);

            var c = actionsOut.ContinuousActions;
            if (c.Length > 0) c[0] = cmd.Forward;
            if (c.Length > 1) c[1] = cmd.Turn;
            var dd = actionsOut.DiscreteActions;
            if (dd.Length > 0) dd[0] = cmd.Intake ? 1 : 0;
            if (dd.Length > 1) dd[1] = cmd.Shoot ? 1 : 0;
            if (dd.Length > 2) dd[2] = cmd.Climb ? 1 : 0;
        }

        public override void OnEpisodeBegin()
        {
            _latest = DriveCommand.Zero;
            // ロボ/球のリセットは MatchManager 連携で Sim 側が実施する。
        }

        /// <summary>外部（RewardShaper）から報酬を加算する公開API（PLAN.md §3.8）。</summary>
        public void AddShapedReward(float r) => AddReward(r);

        /// <summary>エピソード終了（試合終了）を通知。</summary>
        public void FinishEpisode() => EndEpisode();
    }
}
