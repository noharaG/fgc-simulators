using FGC.Core;
using FGC.Match;
using UnityEngine;

namespace FGC.App
{
    /// <summary>
    /// 同盟選択に応じて選手の立ち位置・向きを算出する（PLAN.md §3.7）。
    /// **赤=右(x=7側) / 青=左(x=0側)**（ロボ初期位置と同じ側・FieldLayout参照）。
    /// ALLIANCE STATION はフィールド端の外側（FieldLayout.StationStandOffset）。
    /// 立ち位置は**人間操縦ロボの初期位置の真後ろ**（同じ奥行きz）。正面（フィールド横断方向）を向く（外から目視操作）。
    /// PC操縦専用（VR廃止・2026-06-11）。DesktopCameraRig が LateUpdate でこのクラスを呼んで配置する。
    /// </summary>
    public static class AllianceStationPlacer
    {
        /// <summary>
        /// 同盟の立ち位置（フィールド外側）を返す。standOffset はレールからの後退距離[m]。
        /// 奥行き z は**人間操縦ロボ(humanSlot)の初期位置と同じ**にして、自ロボが正面に来る（＝ロボの真後ろに立つ）。
        /// humanSlot を変えれば視点もそのロボ（DOWN/MID/UP=左/真ん中/右 等）の真後ろへ自動追従する。
        /// 足元 y は**観客床（地上）= RISER天面より GroundRaise(0.70m) 下**。身長(eyeHeight)はこの地上を起点に測る。
        /// </summary>
        public static Vector3 StationPosition(AllianceColor alliance, int humanSlot, float standOffset = FieldLayout.StationStandOffset)
        {
            float z = FieldLayout.RobotStart(humanSlot, alliance).z; // 人間ロボ初期位置の真後ろ
            float y = -FieldConstants.GroundRaise; // 観客床（地上）。フィールドは70cm嵩上げ＝ロボ床はy0なので足元は−0.70
            // ロボ配置に合わせる: 赤=右(x=7)側の外 / 青=左(x=0)側の外
            if (alliance == AllianceColor.Red)
                return new Vector3(FieldConstants.FieldSizeX + standOffset, y, z);   // 赤=x7側の外（右）
            return new Vector3(-standOffset, y, z);                                  // 青=x0側の外（左）
        }

        /// <summary>フィールドを横断する方向（自ロボ正面・敵陣方向）を向く回転。z はロボと同じなので真正面に自ロボが来る。</summary>
        public static Quaternion StationRotation(AllianceColor alliance, int humanSlot)
        {
            Vector3 pos = StationPosition(alliance, humanSlot);
            // z は保ったまま、反対側(x)へまっすぐ向く＝自ロボが視界の真ん中、その先に敵陣。
            Vector3 dir = new Vector3(FieldConstants.FieldSizeX * 0.5f - pos.x, 0f, 0f);
            return Quaternion.LookRotation(dir.normalized, Vector3.up);
        }
    }
}
