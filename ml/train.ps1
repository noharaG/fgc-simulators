# FGCシミュレーター ML-Agents 学習起動（Windows）
# 使い方:
#   ./ml/train.ps1                      … 新規学習（run-id 自動）
#   ./ml/train.ps1 -RunId fgc_001       … run-id 指定
#   ./ml/train.ps1 -Resume -RunId fgc_001 … 途中再開
# 学習中、Unity 側で Play を押すと環境が接続される（Editor学習）。
# ヘッドレス並列はビルド済み実行ファイルを --env で指定（高速）。

param(
    [string]$RunId = "",
    [switch]$Resume,
    [string]$EnvExe = ""   # ビルド済み環境(.exe)。未指定なら Editor 接続待ち。
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$venv = Join-Path $here ".venv310"
$mlagents = Join-Path $venv "Scripts\mlagents-learn.exe"
$config = Join-Path $here "fgc_ppo.yaml"

if (-not (Test-Path $mlagents)) {
    Write-Error "ML-Agents 未構築。先に ./ml/setup_env.ps1 を実行してください。"
    exit 1
}
if ($RunId -eq "") {
    # 時刻はスクリプト側で付与（Claude実行制約と無関係）
    $RunId = "fgc_" + (Get-Date -Format "MMdd_HHmmss")
}

$args = @($config, "--run-id=$RunId")
if ($Resume) { $args += "--resume" }
if ($EnvExe -ne "") {
    $args += "--env=$EnvExe"
    $args += "--num-envs=8"     # RTX4070+16C で並列。負荷を見て調整。
    $args += "--no-graphics"
    $args += "--time-scale=20"
}

Write-Host "学習開始: run-id=$RunId"
Write-Host "(Editor学習の場合はこの後 Unity で Play を押す)"
& $mlagents @args
