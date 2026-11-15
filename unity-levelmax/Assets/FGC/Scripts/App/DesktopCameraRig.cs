using FGC.Core;
using UnityEngine;

namespace FGC.App
{
    /// <summary>
    /// PC操縦用カメラ（VR廃止に伴うXRリグの置き換え・2026-06-11）。
    /// **視点は従来の選手視点と完全に同一**（自陣ALLIANCE STATIONに立つ・目線高さ config.EyeHeight・
    /// 位置/向きは AllianceStationPlacer と同じ計算）。変更は**視野角のみ**（60→100で横の視界を拡大）。
    /// SetupMenu で同盟・身長を変えると LateUpdate のポーリングが検知して即追従する。
    /// </summary>
    [RequireComponent(typeof(Camera))]
    public sealed class DesktopCameraRig : MonoBehaviour
    {
        public MatchDirector director;

        [Tooltip("視野角(垂直FOV)。旧VR視点は60相当。横の視界を広げるため100に拡大（位置・高さは不変）。")]
        public float fieldOfView = 100f;

        private AllianceColor _alliance;
        private int _slot = -1;
        private float _eyeHeight = -1f;

        private void LateUpdate()
        {
            if (director == null || director.config == null) return;
            var cfg = director.config;
            int slot = cfg.HumanSlotIndex();
            if (_slot == slot && _alliance == cfg.PlayerAlliance && Mathf.Approximately(_eyeHeight, cfg.EyeHeight)) return;

            Place(cfg.PlayerAlliance, slot, cfg.EyeHeight);
            _alliance = cfg.PlayerAlliance;
            _slot = slot;
            _eyeHeight = cfg.EyeHeight;
        }

        /// <summary>従来のVRリグと同じ立ち位置・向きへ配置（足元=観客床、目線=eyeHeight）。FOVのみ広角。</summary>
        public void Place(AllianceColor alliance, int humanSlot, float eyeHeight)
        {
            var cam = GetComponent<Camera>();
            if (cam != null) cam.fieldOfView = fieldOfView;

            Vector3 feet = AllianceStationPlacer.StationPosition(alliance, humanSlot);
            transform.position = feet + Vector3.up * eyeHeight;            // 高さは従来どおり目線のみ
            transform.rotation = AllianceStationPlacer.StationRotation(alliance, humanSlot);
        }
    }
}
