using FGC.Core;
using UnityEngine;

namespace FGC.App
{
    /// <summary>
    /// 試合HUD（PLAN.md §3.7・次フェーズ⑤）。スコア（RED/BLUE）・残時間・フェーズ・自ロボ強調情報を表示。
    /// まずは依存の少ない IMGUI で即動作させ、後で VR ワールド空間 uGUI Canvas へ載せ替える。
    /// MatchDirector から Match（タイマー/フェーズ）と ScoreBoard（スコア）を読む。
    /// </summary>
    public sealed class MatchHud : MonoBehaviour
    {
        public MatchDirector director;
        public bool visible = true;

        private GUIStyle _big;
        private GUIStyle _label;

        private void EnsureStyles()
        {
            if (_big != null) return;
            _big = new GUIStyle(GUI.skin.label) { fontSize = 28, fontStyle = FontStyle.Bold };
            _label = new GUIStyle(GUI.skin.label) { fontSize = 16 };
        }

        private void OnGUI()
        {
            if (!visible || director == null) return;
            EnsureStyles();

            var match = director.Match;
            var score = director.Score;

            // 残時間・フェーズ（上部中央）
            float remaining = match != null ? match.Remaining : FieldConstants.MatchDuration;
            int mm = Mathf.FloorToInt(remaining / 60f);
            int ss = Mathf.FloorToInt(remaining % 60f);
            string phase = match != null ? PhaseLabel(match.Phase) : "";

            float cx = Screen.width * 0.5f;
            GUI.Label(new Rect(cx - 120, 8, 240, 40), $"{mm:0}:{ss:00}   {phase}", _big);

            // スコア（左=赤 / 右=青）
            int red = score != null ? score.RedRegional : 0;
            int blue = score != null ? score.BlueRegional : 0;
            var prevColor = GUI.color;
            GUI.color = new Color(1f, 0.4f, 0.4f);
            GUI.Label(new Rect(20, 8, 200, 40), $"RED {red}", _big);
            GUI.color = new Color(0.4f, 0.6f, 1f);
            GUI.Label(new Rect(Screen.width - 220, 8, 200, 40), $"BLUE {blue}", _big);
            GUI.color = prevColor;

            // 補足（COOPERTITION・球の状況）
            if (score != null)
                GUI.Label(new Rect(cx - 120, 48, 240, 22),
                    $"COOP +{score.CoopertitionGlobal}", _label);
        }

        private static string PhaseLabel(MatchPhase p)
        {
            switch (p)
            {
                case MatchPhase.PreMatch: return "準備中";
                case MatchPhase.Running: return "進行中";
                case MatchPhase.Settling: return "静定";
                case MatchPhase.Finished: return "終了";
                default: return "";
            }
        }
    }
}
