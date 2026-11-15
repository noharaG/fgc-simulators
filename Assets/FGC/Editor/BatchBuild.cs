using System;
using UnityEditor;
using UnityEditor.Build.Reporting;
using UnityEngine;

namespace FGC.Editor
{
    /// <summary>コマンドラインからの学習用プレイヤービルド。
    /// Unity.exe -batchmode -quit -projectPath ... -executeMethod FGC.Editor.BatchBuild.BuildTrainingPlayer
    /// で C:\fgcbuild\fgc.exe を直接生成する（ASCIIパス＝gRPC DLL要件、exe名も最初からfgc=リネーム不要）。
    /// エディタを開かずにビルドできるため、シーン保存事故（2026-06-12のフラグ消失）の混入経路も断てる。</summary>
    public static class BatchBuild
    {
        public static void BuildTrainingPlayer()
        {
            var options = new BuildPlayerOptions
            {
                scenes = new[] { "Assets/TrainingScene.unity" },
                locationPathName = @"C:\fgcbuild\fgc.exe",
                target = BuildTarget.StandaloneWindows64,
                options = BuildOptions.None,
            };

            BuildReport report = BuildPipeline.BuildPlayer(options);
            var summary = report.summary;
            Debug.Log($"[FGC] BatchBuild: result={summary.result} size={summary.totalSize} " +
                      $"errors={summary.totalErrors} time={summary.totalTime}");

            if (summary.result != BuildResult.Succeeded)
                EditorApplication.Exit(1); // 失敗を終了コードで呼び出し元に伝える
        }
    }
}
