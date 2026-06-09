using FGC.Core;
using UnityEngine;

namespace FGC.VR
{
    /// <summary>
    /// 同盟選択に応じて選手の立ち位置・向きを算出する（PLAN.md §3.7）。
    /// **CAD準拠: 赤=左(x=0側) / 青=右(x=7側)**（STEP実測 ALLIANCE_TAPE 位置・FieldLayout参照）。
    /// ALLIANCE STATION はフィールド端の外側（FieldLayout.StationStandOffset）。選手は中央を向く（外から目視操作）。
    /// </summary>
    public static class AllianceStationPlacer
    {
        /// <summary>
        /// 同盟の立ち位置（フィールド外側）を返す。standOffset はレールからの後退距離[m]。
        /// 現状は左右辺の中央(z=3.5)に立つ。※CAD実測ではHUMAN_PLAYER_STATION(=FIRE_SHIELD)が奥(z≈6.4)に
        ///   あり、ドライバーは自陣FIRE_SHIELD付近(FieldLayout.FireShieldRed/BlueCenter)に立つのが本来かもしれない。
        ///   外観図/Game Manualで確認し、必要なら z を FireShield 側へ寄せる（朝の確認事項）。
        /// </summary>
        public static Vector3 StationPosition(AllianceColor alliance, float standOffset = FieldLayout.StationStandOffset)
        {
            float z = FieldConstants.FieldSizeZ * 0.5f; // 辺の中央（要確認: FIRE_SHIELD奥側 z≈6.4 かも）
            // ロボ配置に合わせる: 赤=右(x=7)側の外 / 青=左(x=0)側の外
            if (alliance == AllianceColor.Red)
                return new Vector3(FieldConstants.FieldSizeX + standOffset, 0f, z);   // 赤=x7側の外（右）
            return new Vector3(-standOffset, 0f, z);                                  // 青=x0側の外（左）
        }

        /// <summary>フィールド中央を向く回転。</summary>
        public static Quaternion StationRotation(AllianceColor alliance)
        {
            Vector3 pos = StationPosition(alliance);
            Vector3 center = new Vector3(FieldConstants.FieldSizeX * 0.5f, 0f, FieldConstants.FieldSizeZ * 0.5f);
            Vector3 dir = center - pos; dir.y = 0f;
            return Quaternion.LookRotation(dir.normalized, Vector3.up);
        }

        /// <summary>リグを同盟ステーションへ配置（位置＋向き）。</summary>
        public static void Place(VrPlayerRig rig, AllianceColor alliance)
        {
            if (rig == null) return;
            rig.PlaceAt(StationPosition(alliance), StationRotation(alliance));
        }
    }
}
