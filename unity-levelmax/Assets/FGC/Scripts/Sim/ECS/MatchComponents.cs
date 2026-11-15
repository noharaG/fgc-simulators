using Unity.Entities;

namespace FGC.Sim.ECS
{
    /// <summary>
    /// 段階⑤: 試合タイマー/フェーズの ECS シングルトン（真実源）。MatchSystem が毎フレーム進める。
    /// Phase は FGC.Core.MatchPhase の順序と一致する int（0 PreMatch / 1 Running / 2 Settling / 3 Finished）。
    /// GameObject 側（MatchDirector/HUD）は EcsMatchBridge 経由で読み書きする。
    /// </summary>
    public struct MatchStateData : IComponentData
    {
        public int Phase;      // = (int)MatchPhase
        public float Elapsed;  // 経過[s]
        public float Remaining; // 残り[s]
        public byte Running;   // 1=タイマー進行中（PreMatch/Finishedでは0）
    }

    /// <summary>
    /// 段階⑤: スコアの ECS シングルトン。値は ScoreBoard が ScoreCalculator で算出して公開する
    /// （計算の入力＝格納カウント/登攀はまだGameObject側のため、計算は据え置き・結果のみECS化）。
    /// 同一シングルトンEntityに MatchStateData と同居させる。
    /// </summary>
    public struct ScoreData : IComponentData
    {
        public int RedRegional;
        public int BlueRegional;
        public int Coopertition;
    }
}
