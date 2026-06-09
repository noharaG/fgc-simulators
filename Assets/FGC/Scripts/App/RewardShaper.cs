// ML-Agents 無効時は本ファイル全体を除外（FGC_MLAGENTS 定義時のみ有効）。
#if FGC_MLAGENTS
using System.Collections.Generic;
using FGC.Ball;
using FGC.Core;
using FGC.ML;
using FGC.Robot;
using UnityEngine;

namespace FGC.App
{
    /// <summary>
    /// ML報酬の整形（PLAN.md §3.8・次フェーズ⑥）。Sim のイベント／状態から MLAgentController へ AddReward。
    ///   ・格納+1相当（自リージョンSUPPRESSION）/ EXTINGUISHER寄与（GLOBAL）
    ///   ・登攀ゾーン上昇ボーナス（Contact→Z1→Z2→Z3）
    ///   ・時間整形（毎フレーム微小ペナルティで素早い得点を促す）
    /// 学習に使う MLAgentController を持つロボにのみ適用（人間/ルールベースには無害）。
    /// </summary>
    public sealed class RewardShaper : MonoBehaviour
    {
        public MatchDirector director;

        [Header("報酬係数")]
        public float suppressionReward = 1.0f;     // 自リージョンSUPPRESSION格納
        public float extinguisherReward = 0.5f;    // EXTINGUISHER格納（GLOBAL）
        public float climbStepReward = 0.3f;       // 1ゾーン上昇あたり
        public float timePenaltyPerSec = 0.01f;    // 毎秒の微小ペナルティ

        private readonly Dictionary<RobotActor, ClimbZone> _lastZone = new Dictionary<RobotActor, ClimbZone>();

        /// <summary>MatchDirector から格納ゾーンを受け取り購読する。</summary>
        public void Bind(IEnumerable<StorageTrigger> triggers)
        {
            foreach (var t in triggers)
                if (t != null) t.OnDeposited += HandleDeposit;
        }

        private void HandleDeposit(RobotActor actor, StorageKind kind)
        {
            var ml = actor.GetComponent<MLAgentController>();
            if (ml == null) return;

            switch (kind)
            {
                case StorageKind.Extinguisher:
                    ml.AddShapedReward(extinguisherReward);
                    break;
                case StorageKind.SuppressionRed:
                case StorageKind.SuppressionBlue:
                    ml.AddShapedReward(suppressionReward); // 同盟一致はStorageTrigger側で担保済
                    break;
            }
        }

        private void Update()
        {
            if (director == null) return;
            float dt = Time.deltaTime;

            foreach (var actor in director.Robots)
            {
                var ml = actor.GetComponent<MLAgentController>();
                if (ml == null) continue;

                // 時間整形
                ml.AddShapedReward(-timePenaltyPerSec * dt);

                // 登攀ゾーン上昇ボーナス
                _lastZone.TryGetValue(actor, out var prev);
                if ((int)actor.ClimbZone > (int)prev)
                {
                    int steps = (int)actor.ClimbZone - (int)prev;
                    ml.AddShapedReward(climbStepReward * steps);
                }
                _lastZone[actor] = actor.ClimbZone;
            }
        }
    }
}
#endif
