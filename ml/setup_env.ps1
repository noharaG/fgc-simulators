# FGC Simulator - ML-Agents Python env setup (Windows / Python 3.10)
# ML-Agents is not compatible with Python 3.13 (torch/mlagents target 3.10). Use Python 3.10 + venv.
# Run once:  PowerShell>  ./ml/setup_env.ps1
# NOTE: ASCII-only on purpose. Windows PowerShell 5.1 mangles UTF-8(no BOM) Japanese -> parse error.
#
# STATUS (2026-06-10): venv already built & GPU-verified at ml/.venv310
#   torch 2.5.1+cu121 / CUDA=True / RTX 4070 Laptop / mlagents 1.1.0.
#   Re-run this only to rebuild from scratch.

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$venv = Join-Path $here ".venv310"

function Find-Python310 {
    try {
        $v = & py -3.10 -c "import sys;print(sys.version)" 2>$null
        if ($LASTEXITCODE -eq 0) { return @("py", "-3.10") }
    } catch {}
    $cmd = Get-Command python3.10 -ErrorAction SilentlyContinue
    if ($cmd) { return @($cmd.Source) }
    $candidates = @(
        "$env:LOCALAPPDATA\Programs\Python\Python310\python.exe",
        "C:\Python310\python.exe",
        "$env:ProgramFiles\Python310\python.exe"
    )
    foreach ($c in $candidates) { if (Test-Path $c) { return @($c) } }
    return $null
}

$py = Find-Python310
if (-not $py) {
    Write-Error "Python 3.10 not found. Install: winget install -e --id Python.Python.3.10"
    exit 1
}
Write-Host "Using Python 3.10: $($py -join ' ')"

if (-not (Test-Path $venv)) {
    & $py[0] $py[1..($py.Length-1)] -m venv $venv
}
$vpy = Join-Path $venv "Scripts\python.exe"

# Deps. PyTorch CUDA cu121 for RTX 4070. For CPU-only drop the --index-url.
& $vpy -m pip install --upgrade pip
& $vpy -m pip install torch --index-url https://download.pytorch.org/whl/cu121
& $vpy -m pip install mlagents==1.1.0

Write-Host "Done. Train with ./ml/train.ps1   venv: $venv"
