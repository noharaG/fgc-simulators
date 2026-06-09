# FGCシミュレーター ML-Agents Python環境構築（Windows / Python 3.10）
# ML-Agents は Python 3.13 非対応（torch/mlagents が 3.10 系）。3.10 を別途用意して venv を作る。
# 実行: PowerShell で  ./ml/setup_env.ps1   （初回のみ）
#
# 前提: Python 3.10 が入っていること。py ランチャ優先、無ければ python3.10 を探す。
# HANDOFF §3: winget で 3.10 を入れたが py -3.10 で見つからない状況だった → 本スクリプトで所在探索する。

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$venv = Join-Path $here ".venv310"

function Find-Python310 {
    # 1) py -3.10
    try {
        $v = & py -3.10 -c "import sys;print(sys.version)" 2>$null
        if ($LASTEXITCODE -eq 0) { return @("py", "-3.10") }
    } catch {}
    # 2) PATH上の python3.10
    $cmd = Get-Command python3.10 -ErrorAction SilentlyContinue
    if ($cmd) { return @($cmd.Source) }
    # 3) 既知の配置を総当り
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
    Write-Error "Python 3.10 が見つかりません。https://www.python.org/downloads/release/python-31011/ から 3.10 を入れて再実行してください（winget: 'winget install Python.Python.3.10'）。"
    exit 1
}
Write-Host "Python 3.10 を使用: $($py -join ' ')"

# venv 作成
if (-not (Test-Path $venv)) {
    & $py[0] $py[1..($py.Length-1)] -m venv $venv
}
$vpy = Join-Path $venv "Scripts\python.exe"

# 依存導入（PyTorch CUDA は RTX4070 向けに cu121。CPU可なら index-url を外す）
& $vpy -m pip install --upgrade pip
& $vpy -m pip install torch --index-url https://download.pytorch.org/whl/cu121
& $vpy -m pip install mlagents==1.1.0

Write-Host "完了。学習は ./ml/train.ps1 で起動。venv: $venv"
