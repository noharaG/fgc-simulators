# FGC Simulator - ML-Agents training launcher (Windows)
# Usage:
#   ./ml/train.ps1                          new run (auto run-id)
#   ./ml/train.ps1 -RunId fgc_001           explicit run-id
#   ./ml/train.ps1 -Resume -RunId fgc_001   resume a run
#   -EnvExe <path>   use a built headless env (.exe) for fast parallel training.
#                    Omit to connect to the Editor (press Play in Unity after launch).
# NOTE: ASCII-only on purpose. Windows PowerShell 5.1 mangles UTF-8(no BOM) Japanese -> parse error.

param(
    [string]$RunId = "",
    [switch]$Resume,
    [string]$EnvExe = "",
    # Headless rendering. Requires a build that contains the EcsBallSim headless guard
    # (DrawMeshInstanced throws "Instancing is not supported" on the Null graphics device
    # and kills every env otherwise). Omit to run envs with graphics (safe with any build).
    [switch]$NoGraphics
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$venv = Join-Path $here ".venv310"
$mlagents = Join-Path $venv "Scripts\mlagents-learn.exe"
$config = Join-Path $here "fgc_ppo.yaml"

if (-not (Test-Path $mlagents)) {
    Write-Error "ML-Agents venv not found ($mlagents). Build it: py -3.10 -m venv ml/.venv310; then pip install torch (cu121) and mlagents==1.1.0."
    exit 1
}
if ($RunId -eq "") {
    $RunId = "fgc_" + (Get-Date -Format "MMdd_HHmmss")
}

# NOTE: do not use $args (PowerShell automatic variable).
# CPU training on purpose: mlagents 1.1.0 + CUDA crashes with normalize:true
# ("Expected all tensors to be on the same device, cuda:0 and cpu") even with --torch-device=cpu,
# because the threaded trainer does not fully propagate the device setting. Hiding CUDA entirely
# makes the bug structurally impossible. Our net is tiny (256x2, obs22) so CPU is plenty fast;
# the bottleneck is env collection (8 parallel envs).
$env:CUDA_VISIBLE_DEVICES = "-1"
$mlArgs = @($config, "--run-id=$RunId", "--torch-device=cpu")
if ($Resume) { $mlArgs += "--resume" }
if ($EnvExe -ne "") {
    $mlArgs += "--env=$EnvExe"
    $mlArgs += "--num-envs=8"     # RTX4070 + 16C: parallel envs. Tune to load.
    if ($NoGraphics) { $mlArgs += "--no-graphics" }
    $mlArgs += "--time-scale=20"
}

Write-Host "Training start: run-id=$RunId"
Write-Host "(Editor training: press Play in Unity after this line appears 'Listening on port ...')"
& $mlagents @mlArgs
