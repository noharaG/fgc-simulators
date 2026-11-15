using FGC.Core;

namespace FGC.Match
{
    /// <summary>スロットに割り当てる制御ソース種別（PLAN.md §3.2・実行時差し替え可能）。</summary>
    public enum ControllerKind
    {
        Human,        // ゲームパッド操縦（1スロットのみ想定だが複数も可）
        RuleBasedNpc, // ルールベースNPC
        MLAgent       // 学習/推論エージェント
    }

    /// <summary>1スロット（ロボ1台）の割当。赤0-2 / 青3-5 の6スロット。</summary>
    [System.Serializable]
    public struct SlotAssignment
    {
        public int SlotIndex;          // 0..5
        public AllianceColor Alliance; // 赤/青
        public ControllerKind Kind;    // 制御ソース

        public static SlotAssignment Make(int slot, AllianceColor alliance, ControllerKind kind)
            => new SlotAssignment { SlotIndex = slot, Alliance = alliance, Kind = kind };
    }
}
