using Unity.XR.CoreUtils;
using UnityEngine;

namespace FGC.VR
{
    /// <summary>
    /// 選手（ドライバー）視点リグ（PLAN.md §3.7）。XR Origin を ALLIANCE STATION に立たせ、
    /// 操縦者身長（eye height）をプリセット/スライダで上書きする。Quest実身長→任意値に変更可。
    /// 視点は選手視点固定（ロボ視点でも俯瞰でもない）。実行中も SetEyeHeight で変更できる。
    /// </summary>
    [RequireComponent(typeof(XROrigin))]
    public sealed class VrPlayerRig : MonoBehaviour
    {
        [Header("身長プリセット")]
        public float[] heightPresets = { 1.40f, 1.55f, 1.60f, 1.70f, 1.80f };
        [Range(1.0f, 2.2f)] public float eyeHeight = 1.60f;

        private XROrigin _origin;

        private void Awake()
        {
            _origin = GetComponent<XROrigin>();
            // Device原点で固定し、こちらでカメラ高さを与える（実身長に依存させない）。
            _origin.RequestedTrackingOriginMode = XROrigin.TrackingOriginMode.Device;
            ApplyEyeHeight();
        }

        /// <summary>目線高さを上書き適用（XR OriginのCameraYOffset）。</summary>
        public void ApplyEyeHeight()
        {
            if (_origin == null) return;
            _origin.CameraYOffset = eyeHeight;
            if (_origin.CameraFloorOffsetObject != null)
            {
                var p = _origin.CameraFloorOffsetObject.transform.localPosition;
                p.y = eyeHeight;
                _origin.CameraFloorOffsetObject.transform.localPosition = p;
            }
        }

        public void SetEyeHeight(float meters)
        {
            eyeHeight = Mathf.Clamp(meters, 1.0f, 2.2f);
            ApplyEyeHeight();
        }

        /// <summary>プリセット番号で身長設定（SetupMenuから）。</summary>
        public void SetPreset(int index)
        {
            if (heightPresets == null || heightPresets.Length == 0) return;
            index = Mathf.Clamp(index, 0, heightPresets.Length - 1);
            SetEyeHeight(heightPresets[index]);
        }

        /// <summary>立ち位置と向きを設定（AllianceStationPlacer が算出した値を渡す）。</summary>
        public void PlaceAt(Vector3 worldPos, Quaternion worldRot)
        {
            transform.SetPositionAndRotation(worldPos, worldRot);
        }
    }
}
