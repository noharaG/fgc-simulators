using System.Collections.Generic;
using FGC.Core;

namespace FGC.Match
{
    /// <summary>試合モード（PLAN.md §1）。</summary>
    public enum MatchMode
    {
        Practice, // ①1人練習
        ThreeVsThree // ②3v3対戦
    }

    /// <summary>
    /// 試合設定。SetupMenu が編集し、MatchManager/Sim が読む。
    /// 6スロットの割当・人間の同盟・操縦者身長（eye height）を保持する。
    /// </summary>
    [System.Serializable]
    public sealed class MatchConfig
    {
        public MatchMode Mode = MatchMode.ThreeVsThree;
        public AllianceColor PlayerAlliance = AllianceColor.Red; // 人間の所属（赤=x7側/青=x0側）
        public float EyeHeight = 1.60f; // 操縦者目線高さ[m]（後から変更可）

        public readonly List<SlotAssignment> Slots = new List<SlotAssignment>(6);

        /// <summary>基本構成を生成：自陣=人間1+NPC2／敵陣=NPC3。練習は自陣スロット0のみ人間で他NPC。</summary>
        public static MatchConfig Default(AllianceColor playerAlliance, MatchMode mode)
        {
            var cfg = new MatchConfig { Mode = mode, PlayerAlliance = playerAlliance };
            cfg.RebuildSlots();
            return cfg;
        }

        /// <summary>Mode/PlayerAlliance から6スロットの既定割当を作り直す。</summary>
        public void RebuildSlots()
        {
            Slots.Clear();
            // 赤=スロット0-2, 青=スロット3-5
            int humanSlot = PlayerAlliance == AllianceColor.Red ? 0 : 3;
            for (int i = 0; i < 6; i++)
            {
                var alliance = i < 3 ? AllianceColor.Red : AllianceColor.Blue;
                ControllerKind kind = (i == humanSlot) ? ControllerKind.Human : ControllerKind.RuleBasedNpc;
                Slots.Add(SlotAssignment.Make(i, alliance, kind));
            }
        }

        /// <summary>全6スロットを同じ制御ソースにする（学習用＝全ML 等）。</summary>
        public void SetAllKind(ControllerKind kind)
        {
            for (int i = 0; i < Slots.Count; i++)
            {
                var s = Slots[i];
                s.Kind = kind;
                Slots[i] = s;
            }
        }

        /// <summary>実行時にスロットの制御ソースを差し替える（人間⇄NPC⇄ML）。</summary>
        public void SetSlotKind(int slotIndex, ControllerKind kind)
        {
            for (int i = 0; i < Slots.Count; i++)
            {
                if (Slots[i].SlotIndex == slotIndex)
                {
                    var s = Slots[i];
                    s.Kind = kind;
                    Slots[i] = s;
                    return;
                }
            }
        }
    }
}
