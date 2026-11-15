# FGC Simulator - DETACHED training launcher (survives agent-tool process cleanup).
# Launches mlagents-learn via WMI (parent = WmiPrvSE service), fully outside any caller's
# process tree / job objects. Use this when launching from Claude Code or any tool harness
# that reaps child processes; from a normal user terminal, plain train.ps1 also works.
# Usage:
#   ./ml/train_detached.ps1 -RunId fgc_010 [-NumEnvs 8] [-TimeScale 20] [-Resume]
# Monitor:  Get-Content ml\train_<RunId>.log -Tail 20 -Wait
# Stop:     taskkill /F /IM mlagents-learn.exe ; taskkill /F /IM fgc.exe
# NOTE: ASCII-only on purpose (PS5.1 mangles UTF-8 no-BOM Japanese).

param(
    [string]$RunId = ("fgc_" + (Get-Date -Format "MMdd_HHmmss")),
    [string]$EnvExe = "C:\fgcbuild\fgc.exe",
    [int]$NumEnvs = 8,
    [int]$TimeScale = 20,
    [switch]$Resume,
    [switch]$WithGraphics   # default headless; current build requires runInBackground=1 for graphics
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$proj = Split-Path -Parent $here
$ml   = Join-Path $here ".venv310\Scripts\mlagents-learn.exe"
$cfg  = Join-Path $here "fgc_ppo.yaml"
# Timestamped log per launch: a stale reader once held a write-lock on the fixed-name log,
# which made cmd's redirect fail instantly and silently killed every relaunch (2026-06-11).
$log  = Join-Path $here ("train_" + $RunId + "_" + (Get-Date -Format "HHmmss") + ".log")

if (-not (Test-Path $ml)) { Write-Error "venv not found: $ml"; exit 1 }
if (-not (Test-Path $EnvExe)) { Write-Error "env exe not found: $EnvExe (build TrainingScene to an ASCII path)"; exit 1 }

$opts = "--run-id=$RunId --env=$EnvExe --num-envs=$NumEnvs --time-scale=$TimeScale --torch-device=cpu"
if (-not $WithGraphics) { $opts += " --no-graphics" }
if ($Resume) { $opts += " --resume" }
# --env-args must come LAST: everything after it is passed to the env executable.
# -fgcTraining: force training mode in MatchDirector even if scene flags were saved off
# (failsafe for the 2026-06-12 incident where TrainingScene flags got overwritten to 0).
$opts += " --env-args -fgcTraining"

# CUDA_VISIBLE_DEVICES=-1: CUDA-build torch crashed natively in NVIDIA dlls (nvdxgdmal64);
# venv now has CPU torch, the env var is belt-and-suspenders.
$cmd = "cmd.exe /c `"set CUDA_VISIBLE_DEVICES=-1 && `"$ml`" `"$cfg`" $opts > `"$log`" 2>&1`""
# ShowWindow=0 (SW_HIDE): the visible console window was accidentally closed twice (^C killed
# training at 4.78M and 4.68M steps). Hidden window = nothing to close. Monitor via the log file.
$startup = New-CimInstance -ClassName Win32_ProcessStartup -ClientOnly -Property @{ ShowWindow = [UInt16]0 }
$r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = $cmd; CurrentDirectory = $proj; ProcessStartupInformation = $startup }
if ($r.ReturnValue -ne 0) { Write-Error "WMI Create failed: $($r.ReturnValue)"; exit 1 }
Write-Host "Detached training started. run-id=$RunId pid=$($r.ProcessId)"
Write-Host "Log: $log"
Write-Host "Results/checkpoints: $proj\results\$RunId"
