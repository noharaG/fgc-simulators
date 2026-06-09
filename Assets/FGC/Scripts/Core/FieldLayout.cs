using UnityEngine;

namespace FGC.Core
{
    /// <summary>
    /// フィールド上の「配置座標」を一元管理（PLAN.md §3.5 / 仕上げB:座標校正）。
    /// **値は STEP CAD 実測（tools/distill_field_structures.py → field_structures.json）を Unity 0..7系へ
    /// 変換した精密値**（STEP中心原点 → unity = step + 3.5）。HANDOFF §8「V.」の表が出典。
    /// 座標系: Unity (x, y=高さ, z)。フィールド原点=左下隅、7m×7m。RISER天面=床(y0)。
    /// **CAD準拠: 赤=左(x小) / 青=右(x大)**（既存 field_obstacles.json の FIELD_TAPE 位置とも一致）。
    /// </summary>
    public static class FieldLayout
    {
        // ===== 格納ゾーン（中心ワールド座標・トリガBoxのSize） =====
        // SUPPRESSION（リージョン得点）。CAD: 前辺(z≈0.55)に左右並び。赤=左x2.095 / 青=右x4.905。高さ約2.0m。
        public static readonly Vector3 SuppressionRedCenter = new Vector3(2.095f, 0.6f, 0.55f);
        public static readonly Vector3 SuppressionBlueCenter = new Vector3(4.905f, 0.6f, 0.55f);
        public static readonly Vector3 SuppressionZoneSize = new Vector3(1.82f, 1.2f, 1.11f); // CAD footprint

        // EXTINGUISHER（GLOBAL）。CAD: フィールド中央を横断する大トラス（center z≈3.38, 幅4.71×奥6.85×高2.02）。
        // 格納口は中央付近とみなしゾーンを置く（厳密な開口位置は将来CADから特定）。
        public static readonly Vector3 ExtinguisherCenter = new Vector3(3.5f, 1.0f, 3.384f);
        public static readonly Vector3 ExtinguisherZoneSize = new Vector3(1.6f, 1.2f, 0.8f);

        // ===== FIRE SHIELD（HUMAN PLAYER STATION隣接）。CAD: 反対側 z≈6.4。赤=左x0.744 / 青=右x6.27。 =====
        public static readonly Vector3 FireShieldRedCenter = new Vector3(0.744f, 0.5f, 6.403f);
        public static readonly Vector3 FireShieldBlueCenter = new Vector3(6.27f, 0.5f, 6.403f);

        // ===== NPCゴール（XZ平面）。自リージョンの SUPPRESSION を狙う =====
        public static Vector2 NpcGoal(AllianceColor alliance)
        {
            var c = alliance == AllianceColor.Red ? SuppressionRedCenter : SuppressionBlueCenter;
            return new Vector2(c.x, c.z);
        }

        // ===== ロボ開始位置（旧Java版 CompetitionManager.resetRobotPositions に準拠） =====
        /// <summary>
        /// スロット0..5（赤0-2/青3-5）の初期配置。Java版の領域に一致：
        /// 赤=左帯(x 0..0.552, 中央0.276) / 青=右帯(x 6.448..7.0, 中央6.724)。
        /// 自領域(z 0.748..4.852)を DOWN/MID/UP の3点(z=1.432/2.800/4.168)へ分散。yは床直上。
        /// </summary>
        public static Vector3 RobotStart(int slotIndex, AllianceColor alliance)
        {
            int idxInTeam = slotIndex % 3;
            float x = alliance == AllianceColor.Red ? 6.724f : 0.276f; // 赤=右帯 / 青=左帯
            float[] zByIdx = { 1.432f, 2.800f, 4.168f }; // DOWN / MID / UP
            float z = zByIdx[Mathf.Clamp(idxInTeam, 0, 2)];
            return new Vector3(x, 0.05f, z); // 0.5³ロボが床に乗るよう床直上に出す
        }

        /// <summary>初期の向き。互いに向かい合う：赤=右なので−x向き／青=左なので+x向き。</summary>
        public static Quaternion RobotStartRotation(AllianceColor alliance)
            => Quaternion.Euler(0f, alliance == AllianceColor.Red ? -90f : 90f, 0f);

        // ===== ALLIANCE STATION（選手立ち位置）。CAD: 左右の外側（赤=左x≈-0.95 / 青=右x≈7.95）。 =====
        public const float StationStandOffset = 0.95f; // CAD ALLIANCE_TAPE 中心が外側約0.95m
    }
}
