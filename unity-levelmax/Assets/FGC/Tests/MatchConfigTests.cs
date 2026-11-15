using FGC.Core;
using FGC.Match;
using NUnit.Framework;

namespace FGC.Tests
{
    /// <summary>
    /// 試合設定のスロット割当（誰が人間/NPC/ML か・赤青の振り分け）の EditMode テスト。
    /// 6スロット = 赤0-2 / 青3-5。自陣は人間1+NPC2、敵陣はNPC3 が既定（PLAN.md §1）。
    /// </summary>
    public class MatchConfigTests
    {
        [Test]
        public void Default_Red3v3_Slot0HumanRestNpc_AlliancesSplit()
        {
            var cfg = MatchConfig.Default(AllianceColor.Red, MatchMode.ThreeVsThree);
            Assert.AreEqual(6, cfg.Slots.Count);

            for (int i = 0; i < 6; i++)
            {
                Assert.AreEqual(i, cfg.Slots[i].SlotIndex);
                var expectedAlliance = i < 3 ? AllianceColor.Red : AllianceColor.Blue;
                Assert.AreEqual(expectedAlliance, cfg.Slots[i].Alliance, $"slot{i} alliance");
            }

            // 赤スタート → スロット0が人間、残りNPC
            Assert.AreEqual(ControllerKind.Human, cfg.Slots[0].Kind);
            for (int i = 1; i < 6; i++)
                Assert.AreEqual(ControllerKind.RuleBasedNpc, cfg.Slots[i].Kind, $"slot{i} kind");
        }

        [Test]
        public void Default_Blue_HumanIsSlot3()
        {
            var cfg = MatchConfig.Default(AllianceColor.Blue, MatchMode.ThreeVsThree);
            Assert.AreEqual(ControllerKind.Human, cfg.Slots[3].Kind); // 青の先頭スロット
            Assert.AreEqual(ControllerKind.RuleBasedNpc, cfg.Slots[0].Kind);
        }

        [Test]
        public void SetAllKind_OverridesEverySlot()
        {
            var cfg = MatchConfig.Default(AllianceColor.Red, MatchMode.ThreeVsThree);
            cfg.SetAllKind(ControllerKind.MLAgent);
            for (int i = 0; i < 6; i++)
                Assert.AreEqual(ControllerKind.MLAgent, cfg.Slots[i].Kind);
        }

        [Test]
        public void SetSlotKind_ChangesOnlyTargetSlot()
        {
            var cfg = MatchConfig.Default(AllianceColor.Red, MatchMode.ThreeVsThree);
            cfg.SetSlotKind(2, ControllerKind.MLAgent);

            Assert.AreEqual(ControllerKind.MLAgent, cfg.Slots[2].Kind);
            Assert.AreEqual(ControllerKind.Human, cfg.Slots[0].Kind);       // 変わらない
            Assert.AreEqual(ControllerKind.RuleBasedNpc, cfg.Slots[1].Kind); // 変わらない
        }

        [Test]
        public void RebuildSlots_AfterAllianceChange_MovesHuman()
        {
            var cfg = MatchConfig.Default(AllianceColor.Red, MatchMode.ThreeVsThree);
            cfg.PlayerAlliance = AllianceColor.Blue;
            cfg.RebuildSlots();
            Assert.AreEqual(ControllerKind.Human, cfg.Slots[3].Kind);
            Assert.AreEqual(ControllerKind.RuleBasedNpc, cfg.Slots[0].Kind);
        }
    }
}
