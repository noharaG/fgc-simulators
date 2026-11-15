# run.ps1
# 薄いラッパー。gradlew.bat を呼び出します。
# 引数なしで実行された場合は、アプリ終了後にキー入力で簡単に再起動できるループモードになります。

$cmd = $args[0]
if ($null -eq $cmd) {
    $cmd = "run"
    
    while ($true) {
        Clear-Host
        Write-Host "==================================================" -ForegroundColor Green
        Write-Host "       FGC Simulator - Auto Restart Mode          " -ForegroundColor Green
        Write-Host "==================================================" -ForegroundColor Green
        Write-Host "Executing Gradle via Wrapper: $cmd" -ForegroundColor Cyan
        Write-Host "Close the application window to trigger restart prompt." -ForegroundColor DarkGray
        Write-Host ""
        
        # Gradleを実行
        & "$PSScriptRoot\gradlew.bat" $cmd
        
        Write-Host ""
        Write-Host "Application stopped." -ForegroundColor Yellow
        Write-Host "--> Press any key to RESTART (Recompile & Run)..." -ForegroundColor Magenta
        Write-Host "--> Press [Ctrl+C] to Exit." -ForegroundColor Magenta
        
        # キー入力を待機
        [void]$Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
    }
} else {
    Write-Host "Executing Gradle via Wrapper: $cmd" -ForegroundColor Cyan
    & "$PSScriptRoot\gradlew.bat" $cmd
}

