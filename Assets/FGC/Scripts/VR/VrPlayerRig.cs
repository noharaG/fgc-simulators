using Unity.XR.CoreUtils;
using UnityEngine;
using UnityEngine.InputSystem;
using UnityEngine.InputSystem.XR;

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

        [Header("VR描画速度 / 低遅延")]
        [Tooltip("片目レンダリング解像度スケール。下げるとGPU1フレームが軽くなりフレームレートが上がる＝描画遅延/カクつきが減る（画質とのトレードオフ）。1=フル解像度。")]
        [Range(0.5f, 1.2f)] public float vrRenderScale = 0.8f;

        private XROrigin _origin;

        private void Awake()
        {
            _origin = GetComponent<XROrigin>();
            ConfigureVrPerformance(); // 描画遅延対策はカメラ/トラッキング設定より先に行う
            // Device原点で固定し、こちらでカメラ高さを与える（実身長に依存させない）。
            _origin.RequestedTrackingOriginMode = XROrigin.TrackingOriginMode.Device;
            ApplyEyeHeight();
            EnsureHeadTracking();
        }

        /// <summary>段階⑥: **VRの描画速度を上げて遅延(motion-to-photon)を減らす**設定。
        /// ・VSyncは切る：XRコンポジタがフレーム同期するため、デスクトップVSyncはフレームレート半減/遅延の元になる。
        /// ・フレームレート上限を外す：XRランタイム(Questの72/90/120Hz)にペーシングを委ねる。
        /// ・片目解像度スケールを下げる：GPU1フレームを軽くしフレームレートを底上げ＝カクつき/遅延を低減（画質とのトレードオフ）。
        /// ※エディタPlay＋Quest Linkは構造的に遅延が大きい。**本番ビルド(exe)では大幅に滑らかになる**点に留意。</summary>
        private void ConfigureVrPerformance()
        {
            QualitySettings.vSyncCount = 0;                 // XR側がフレーム同期＝VSyncは遅延/半減の元なので切る
            Application.targetFrameRate = -1;               // 上限を外しXRランタイムにペースを任せる
            UnityEngine.XR.XRSettings.eyeTextureResolutionScale = Mathf.Clamp(vrRenderScale, 0.5f, 1.2f);
            UnityEngine.XR.XRSettings.renderViewportScale = 1f;
        }

        /// <summary>段階⑥: センターアイ用カメラに **Input System の TrackedPoseDriver** を付与・設定する。
        /// これが無いと「Main Camera does not use a Tracked Pose Driver」警告が出て、HMDの頭の動きが
        /// カメラに反映されない。OpenXR(Meta Quest)有効化＋Quest Link接続時、これでHMD姿勢がカメラへ流れる。
        /// 埋め込みInputActionは TrackedPoseDriver の OnEnable で有効化されるため、割当後に enabled を再トグルする。</summary>
        private void EnsureHeadTracking()
        {
            var cam = _origin != null ? _origin.Camera : null;
            if (cam == null) cam = GetComponentInChildren<Camera>();
            if (cam == null) { Debug.LogWarning("[FGC] VrPlayerRig: センターアイ用カメラが見つからずTrackedPoseDriverを設定できません。"); return; }
            ConfigureHeadTracking(cam);
        }

        /// <summary>カメラに Input System の TrackedPoseDriver（センターアイ姿勢）を get-or-add で設定する。
        /// 焼き込み時(FieldSceneBuilder)と実行時(Awake)の両方から呼べるよう静的。**XROrigin.Awakeが警告を出す前に
        /// 付いているよう焼き込み時に呼ぶのが本筋**。実行時呼び出しは既存シーン向けの保険（冪等）。</summary>
        public static void ConfigureHeadTracking(Camera cam)
        {
            if (cam == null) return;
            var tpd = cam.GetComponent<TrackedPoseDriver>();
            if (tpd == null) tpd = cam.gameObject.AddComponent<TrackedPoseDriver>();
            tpd.trackingType = TrackedPoseDriver.TrackingType.RotationAndPosition;
            tpd.updateType = TrackedPoseDriver.UpdateType.UpdateAndBeforeRender;
            tpd.positionInput = new InputActionProperty(new InputAction(
                "CenterEyePosition", InputActionType.Value, "<XRHMD>/centerEyePosition", expectedControlType: "Vector3"));
            tpd.rotationInput = new InputActionProperty(new InputAction(
                "CenterEyeRotation", InputActionType.Value, "<XRHMD>/centerEyeRotation", expectedControlType: "Quaternion"));
            // AddComponent直後にOnEnableが既定(空)アクションで走っているため、割当後に再トグルして埋め込みアクションを有効化。
            tpd.enabled = false;
            tpd.enabled = true;
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
