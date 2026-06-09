namespace FGC.Core
{
    /// <summary>試合フェーズ。</summary>
    public enum MatchPhase
    {
        PreMatch,   // 開始前（球未放出・配置）
        Running,    // 進行中（150s）
        Settling,   // 終了直前の静定判定（G21・最後5s）
        Finished    // 終了・最終スコア確定
    }

    /// <summary>
    /// 試合タイマー・フェーズ遷移の純ロジック（PLAN.md §3.6）。MonoBehaviour非依存。
    /// Sim層から毎フレーム Tick(dt) を呼ぶ。静定判定は終了5秒前から開始し、終了時にスコア確定。
    /// </summary>
    public sealed class MatchManager
    {
        public MatchPhase Phase { get; private set; } = MatchPhase.PreMatch;
        public float Elapsed { get; private set; }
        public float Remaining => System.Math.Max(0f, FieldConstants.MatchDuration - Elapsed);

        public event System.Action OnMatchStart;
        public event System.Action OnSettleBegin;
        public event System.Action OnMatchFinish;

        public void StartMatch()
        {
            if (Phase != MatchPhase.PreMatch) return;
            Elapsed = 0f;
            Phase = MatchPhase.Running;
            OnMatchStart?.Invoke();
        }

        public void Tick(float dt)
        {
            if (Phase != MatchPhase.Running && Phase != MatchPhase.Settling) return;

            Elapsed += dt;

            if (Phase == MatchPhase.Running &&
                Remaining <= FieldConstants.SettleTime)
            {
                Phase = MatchPhase.Settling;
                OnSettleBegin?.Invoke();
            }

            if (Elapsed >= FieldConstants.MatchDuration)
            {
                Elapsed = FieldConstants.MatchDuration;
                Phase = MatchPhase.Finished;
                OnMatchFinish?.Invoke();
            }
        }

        public void Reset()
        {
            Phase = MatchPhase.PreMatch;
            Elapsed = 0f;
        }
    }
}
