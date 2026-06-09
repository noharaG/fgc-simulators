using FGC.Core;
using FGC.Match;
using FGC.VR;
using UnityEngine;

namespace FGC.UI
{
    /// <summary>
    /// 試合前/実行中のセットアップUI（PLAN.md §3.7）。同盟（赤/青）・モード・操縦者身長・
    /// 6スロットの制御ソース（人間/NPC/ML）を変更する。まずは依存の少ない IMGUI で実装し、
    /// 後で VR ワールド空間 Canvas（UI Toolkit/uGUI）へ載せ替える。
    /// MatchConfig を編集し、VrPlayerRig へ身長/配置を反映、StartRequested で試合開始を通知。
    /// </summary>
    public sealed class SetupMenu : MonoBehaviour
    {
        public MatchConfig config = MatchConfig.Default(AllianceColor.Red, MatchMode.ThreeVsThree);
        public VrPlayerRig rig;

        public System.Action<MatchConfig> OnStartRequested;
        public System.Action<MatchConfig> OnConfigChanged;

        public bool visible = true;
        private Rect _win = new Rect(20, 20, 360, 420);
        private string _eyeHeightText; // 目線の数値入力バッファ

        private void OnGUI()
        {
            if (!visible) return;
            _win = GUILayout.Window(GetInstanceID(), _win, DrawWindow, "FGC セットアップ");
        }

        private void DrawWindow(int id)
        {
            // モード
            GUILayout.Label("モード");
            GUILayout.BeginHorizontal();
            if (GUILayout.Toggle(config.Mode == MatchMode.Practice, "練習")) SetMode(MatchMode.Practice);
            if (GUILayout.Toggle(config.Mode == MatchMode.ThreeVsThree, "3v3")) SetMode(MatchMode.ThreeVsThree);
            GUILayout.EndHorizontal();

            // 同盟
            GUILayout.Space(6);
            GUILayout.Label("スタート同盟");
            GUILayout.BeginHorizontal();
            if (GUILayout.Toggle(config.PlayerAlliance == AllianceColor.Red, "赤 (x=7側)")) SetAlliance(AllianceColor.Red);
            if (GUILayout.Toggle(config.PlayerAlliance == AllianceColor.Blue, "青 (x=0側)")) SetAlliance(AllianceColor.Blue);
            GUILayout.EndHorizontal();

            // 身長（数値入力）
            GUILayout.Space(6);
            GUILayout.BeginHorizontal();
            GUILayout.Label("操縦者身長(目線) [m]", GUILayout.Width(160));
            _eyeHeightText ??= config.EyeHeight.ToString("0.00");
            _eyeHeightText = GUILayout.TextField(_eyeHeightText, GUILayout.Width(80));
            GUILayout.EndHorizontal();
            if (float.TryParse(_eyeHeightText, out float typedH) && !Mathf.Approximately(typedH, config.EyeHeight))
                SetEyeHeight(typedH);

            // スロット割当
            GUILayout.Space(6);
            GUILayout.Label("スロット割当 (赤0-2 / 青3-5)");
            for (int i = 0; i < config.Slots.Count; i++)
            {
                var s = config.Slots[i];
                GUILayout.BeginHorizontal();
                GUILayout.Label($"#{s.SlotIndex} {(s.Alliance == AllianceColor.Red ? "赤" : "青")}", GUILayout.Width(60));
                if (GUILayout.Toggle(s.Kind == ControllerKind.Human, "人間")) SetKind(s.SlotIndex, ControllerKind.Human);
                if (GUILayout.Toggle(s.Kind == ControllerKind.RuleBasedNpc, "NPC")) SetKind(s.SlotIndex, ControllerKind.RuleBasedNpc);
                if (GUILayout.Toggle(s.Kind == ControllerKind.MLAgent, "ML")) SetKind(s.SlotIndex, ControllerKind.MLAgent);
                GUILayout.EndHorizontal();
            }

            // 開始
            GUILayout.Space(10);
            if (GUILayout.Button("試合開始"))
            {
                visible = false;
                OnStartRequested?.Invoke(config);
            }

            GUI.DragWindow();
        }

        private void SetMode(MatchMode m)
        {
            if (config.Mode == m) return;
            config.Mode = m;
            config.RebuildSlots();
            Changed();
        }

        private void SetAlliance(AllianceColor a)
        {
            if (config.PlayerAlliance == a) return;
            config.PlayerAlliance = a;
            config.RebuildSlots();
            ApplyPlacement();
            Changed();
        }

        private void SetEyeHeight(float h)
        {
            config.EyeHeight = Mathf.Clamp(h, 1.0f, 2.2f);
            if (rig != null) rig.SetEyeHeight(config.EyeHeight);
            Changed();
        }

        private void SetKind(int slot, ControllerKind k)
        {
            config.SetSlotKind(slot, k);
            Changed();
        }

        private void ApplyPlacement()
        {
            if (rig != null) AllianceStationPlacer.Place(rig, config);
        }

        private void Changed() => OnConfigChanged?.Invoke(config);
    }
}
