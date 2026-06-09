using FGC.App;
using FGC.Ball;
using FGC.Core;
using FGC.Match;
using FGC.UI;
using FGC.VR;
using Unity.XR.CoreUtils;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;

namespace FGC.Editor
{
    /// <summary>
    /// メニュー「FGC > Build Field Scene」で、フィールド＋500球＋6ロボ＋VRリグ＋SetupMenu を
    /// 1クリックでシーンに生成する（PLAN.md §3.5 / マイルストーン8）。STEP視覚メッシュ無しで動作。
    /// field_obstacles.json を読み込んで MatchDirector に渡し、Play でそのまま試合できる状態にする。
    /// </summary>
    public static class FieldSceneBuilder
    {
        private const string FieldFbxPath = "Assets/Models/Field/FG26_F_v001.fbx";

        [MenuItem("FGC/Build Field Scene")]
        public static void BuildScene()
        {
            var scene = EditorSceneManager.NewScene(NewSceneSetup.EmptyScene, NewSceneMode.Single);

            CreateLighting();
            var wildfire = CreateWildfire();
            var rig = CreateXrRig();
            var director = CreateDirector(wildfire, rig);
            BuildFieldInScene(director); // フィールド＋コライダをエディタ時に焼き込み（Play開始を軽く）
            CreateSetupMenu(director, rig);
            CreateHud(director);

            EditorSceneManager.MarkSceneDirty(scene);
            Debug.Log("[FGC] Field scene を生成しました。Play で MatchDirector が起動します。");
        }

        [MenuItem("FGC/Build Training Scene")]
        public static void BuildTrainingScene()
        {
            var scene = EditorSceneManager.NewScene(NewSceneSetup.EmptyScene, NewSceneMode.Single);

            CreateLighting();
            var wildfire = CreateWildfire();
            var director = CreateDirector(wildfire, null); // VRリグ無し
            BuildFieldInScene(director); // フィールド＋コライダをエディタ時に焼き込み
            director.autoRestart = true;                    // 連続エピソード
            director.autoBegin = true;                      // メニュー無しで即開始
            director.config.SetAllKind(ControllerKind.MLAgent); // 全6機を学習エージェントに

            CreatePlainCamera(); // 学習の様子を見る用（ヘッドレス時は無視される）
            CreateHud(director);

            EditorSceneManager.MarkSceneDirty(scene);
            Debug.Log("[FGC] Training scene を生成しました。mlagents-learn 起動後に Play、または " +
                      "ビルドして ./ml/train.ps1 -EnvExe で並列学習。");
        }

        private static void CreatePlainCamera()
        {
            var camGo = new GameObject("Main Camera");
            camGo.tag = "MainCamera";
            var cam = camGo.AddComponent<Camera>();
            camGo.AddComponent<AudioListener>();
            // フィールド全体を俯瞰（学習観察用）
            camGo.transform.position = new Vector3(FieldConstants.FieldSizeX * 0.5f, 8f, -3f);
            camGo.transform.rotation = Quaternion.Euler(55f, 0f, 0f);
        }

        private static void CreateLighting()
        {
            var sun = new GameObject("Directional Light");
            var light = sun.AddComponent<Light>();
            light.type = LightType.Directional;
            light.intensity = 1.1f;
            sun.transform.rotation = Quaternion.Euler(50f, -30f, 0f);
        }

        private static WildfireManager CreateWildfire()
        {
            var go = new GameObject("WildfireManager");
            var wf = go.AddComponent<WildfireManager>();
            // 球はゴールのスロットから放出。位置は可視アンカー(BallEmitPoint)で指定。
            // ★ユーザー確定値 (3.5, 0.15, 0.45) で固定（2026-06-09 実機調整済み。Sceneで微調整したい時のみ動かす）。
            wf.emitOrigin = new Vector3(3.5f, 0.15f, 0.45f);
            var anchor = new GameObject("BallEmitPoint");
            anchor.transform.SetParent(go.transform, false);
            anchor.transform.position = wf.emitOrigin;
            wf.emitAnchor = anchor.transform;
            // 描画メッシュ/マテリアルは内蔵Sphere/Defaultで暫定割当（朝にInstancing対応マテリアルへ）
            wf.ballMesh = Resources.GetBuiltinResource<Mesh>("Sphere.fbx");
            wf.ballMaterial = MaterialUtil.Colored(new Color(0.95f, 0.55f, 0.15f)); // WILDFIRE=橙系・Instancing有効
            return wf;
        }

        private static VrPlayerRig CreateXrRig()
        {
            var originGo = new GameObject("XR Origin");
            var origin = originGo.AddComponent<XROrigin>();

            var offsetGo = new GameObject("Camera Offset");
            offsetGo.transform.SetParent(originGo.transform, false);

            var camGo = new GameObject("Main Camera");
            camGo.tag = "MainCamera";
            camGo.transform.SetParent(offsetGo.transform, false);
            var cam = camGo.AddComponent<Camera>();
            camGo.AddComponent<AudioListener>();
            // 段階⑥: センターアイ姿勢の TrackedPoseDriver(Input System) を焼き込み時に付与。
            // XROrigin.Awake が「Tracked Pose Driver が無い」警告を出す前に存在させるためここで設定する。
            VrPlayerRig.ConfigureHeadTracking(cam);

            origin.Camera = cam;
            origin.CameraFloorOffsetObject = offsetGo;

            var rig = originGo.AddComponent<VrPlayerRig>();
            return rig;
        }

        private static MatchDirector CreateDirector(WildfireManager wf, VrPlayerRig rig)
        {
            var go = new GameObject("MatchDirector");
            var dir = go.AddComponent<MatchDirector>();
            dir.wildfire = wf;
            dir.playerRig = rig;

            // STEP由来の実形状FBX（実色付き）を割り当て。Play時に MatchDirector が配置する。
            var fieldModel = AssetDatabase.LoadAssetAtPath<GameObject>(FieldFbxPath);
            if (fieldModel != null) dir.fieldModelPrefab = fieldModel;
            else Debug.LogWarning($"[FGC] フィールドFBXが見つかりません（{FieldFbxPath}）。" +
                                  "Unityの取り込み（数分かかる場合あり）完了後に再生成してください。");
            return dir;
        }

        /// <summary>
        /// フィールドFBXをシーンに配置し、当たり判定を「エディタ時に」焼き込む。
        /// MeshColliderのクックがこの1回で済み、Play開始時に再クックしない（フリーズ回避）。
        /// MatchDirector配下の "Field" として作るので、実行時 BuildField はこれを検出して再生成をスキップする。
        /// </summary>
        private static void BuildFieldInScene(MatchDirector dir)
        {
            var fbx = AssetDatabase.LoadAssetAtPath<GameObject>(FieldFbxPath);
            if (fbx == null)
            {
                Debug.LogWarning($"[FGC] フィールドFBXが見つかりません（{FieldFbxPath}）。" +
                                 "取り込み（数分かかる場合あり）完了後に再実行してください。");
                return;
            }

            var fieldRoot = new GameObject("Field");
            fieldRoot.transform.SetParent(dir.transform, false);

            var model = Object.Instantiate(fbx, fieldRoot.transform);
            model.name = "FieldModel";
            model.transform.localPosition = dir.fieldOffset;
            if (dir.fieldEuler != Vector3.zero)
                model.transform.localRotation = Quaternion.Euler(dir.fieldEuler) * model.transform.localRotation;
            if (!Mathf.Approximately(dir.fieldScale, 1f))
                model.transform.localScale = model.transform.localScale * dir.fieldScale;

            if (dir.buildFieldColliders)
                FGC.App.FieldColliders.Build(fieldRoot.transform, model, dir.colliderMinSize);

            Debug.Log("[FGC] フィールド＋コライダをシーンに焼き込みました（Play開始は再クック無しで軽量）。");
        }

        private static void CreateHud(MatchDirector dir)
        {
            var go = new GameObject("MatchHud");
            var hud = go.AddComponent<MatchHud>();
            hud.director = dir;
        }

        private static void CreateSetupMenu(MatchDirector dir, VrPlayerRig rig)
        {
            var go = new GameObject("SetupMenu");
            var menu = go.AddComponent<SetupMenu>();
            menu.rig = rig;
            menu.config = dir.config;
            menu.OnStartRequested += dir.BeginMatch;
        }
    }
}
