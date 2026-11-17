<#
第一步：先运行 build-local-engine-sdk.bat check，仅显示检测结果，不编译、不下载。
第二步：运行 build-local-engine-sdk.bat，导出本地引擎并构建 SDK；加 run 会启动 SDK。
第三步：仅修改 SDK 时运行 build-and-run-sdk.bat，复用已导出的引擎。
第四步：特殊目录可复制 sdk-local.example.json 为 sdk-local.json 再编辑；后者不提交 Git。
优先级：SDK_JAVA_HOME/SDK_ENGINE_DIR 环境变量 > JSON > 自动查找。
自动支持相邻 jmonkeyengine、JMESources（同时存在时前者优先）。
代理端口 7897 可连接时自动使用；JSON 设置 proxyHost 为空字符串可禁用自动代理。
此脚本由 BAT 在独立 PowerShell 子进程调用，不写系统或用户环境，不改变 UE5 JDK。
#>
param([ValidateSet('build','run','sdk','check')][string]$Mode = 'build')
$ErrorActionPreference = 'Stop'
# This child process only: never change user/system JAVA_HOME or PATH (UE Android).
$root = Split-Path $PSScriptRoot -Parent
$config = @{}
$configPath = Join-Path $root 'sdk-local.json'
if (Test-Path $configPath) {
    $json = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    foreach ($property in $json.PSObject.Properties) {
        $config[$property.Name] = $property.Value
    }
}
function Is-Jdk25([string]$path) {
    if (!$path -or !(Test-Path (Join-Path $path 'bin/javac.exe')) -or !(Test-Path (Join-Path $path 'release'))) {
        return $false
    }
    return [bool](Select-String -LiteralPath (Join-Path $path 'release') -Pattern '^JAVA_VERSION="25([.\"+-])' -Quiet)
}
try {
    $jdk = $env:SDK_JAVA_HOME
    if (!$jdk) { $jdk = $config['javaHome'] }
    if ($jdk) {
        if (!(Is-Jdk25 $jdk)) { throw "Configured javaHome is not JDK 25: $jdk" }
    } else {
        $candidates = @($env:JAVA_HOME)
        foreach ($base in @('C:/Java', "$env:USERPROFILE/.jdks", "$env:USERPROFILE/.gradle/jdks", "$env:ProgramFiles/Java", "$env:ProgramFiles/Eclipse Adoptium")) {
            if (Test-Path $base) {
                $candidates += @(Get-ChildItem -LiteralPath $base -Directory | Sort-Object Name -Descending | ForEach-Object FullName)
            }
        }
        $java = Get-Command java.exe -ErrorAction SilentlyContinue
        if ($java) { $candidates += Split-Path (Split-Path $java.Source -Parent) -Parent }
        $jdk = $candidates | Where-Object { Is-Jdk25 $_ } | Select-Object -First 1
        if (!$jdk) { throw 'JDK 25 not found. Install it, or set javaHome in sdk-local.json (see sdk-local.example.json).' }
    }
    $engine = $env:SDK_ENGINE_DIR
    if (!$engine) { $engine = $config['engineDir'] }
    if (!$engine) {
        foreach ($name in @('jmonkeyengine', 'JMESources')) {
            $candidate = Join-Path (Split-Path $root -Parent) $name
            if (Test-Path (Join-Path $candidate 'gradlew.bat')) {
                $engine = $candidate
                break
            }
        }
    }
    if (!$engine) { throw 'Sibling engine not found. Set engineDir in sdk-local.json.' }
    if (![IO.Path]::IsPathRooted($engine)) { $engine = Join-Path $root $engine }
    $engine = (Resolve-Path -LiteralPath $engine).Path
    if (!(Test-Path (Join-Path $engine 'gradlew.bat'))) { throw "Missing engine Gradle wrapper: $engine" }
    $repository = Join-Path $engine 'build/sdk-engine-repo'
    $proxyHost = $env:SDK_PROXY_HOST
    $proxyPort = $env:SDK_PROXY_PORT
    if (!$proxyHost -and $config.ContainsKey('proxyHost')) { $proxyHost = $config['proxyHost'] }
    if (!$proxyPort) { $proxyPort = $config['proxyPort'] }
    if (!$proxyPort) { $proxyPort = '7897' }
    if (!$proxyHost -and !$config.ContainsKey('proxyHost')) {
        $client = New-Object Net.Sockets.TcpClient
        try {
            $connection = $client.BeginConnect('127.0.0.1', 7897, $null, $null)
            if ($connection.AsyncWaitHandle.WaitOne(500)) {
                $client.EndConnect($connection)
                $proxyHost = '127.0.0.1'
            }
        } catch { } finally { $client.Dispose() }
    }
    $proxyArgs = @()
    if ($proxyHost) {
        if ($proxyHost -notmatch '^[a-zA-Z0-9.-]+$' -or $proxyPort -notmatch '^\d+$') { throw 'Invalid proxy host/port.' }
        $proxyArgs = @("-Dhttp.proxyHost=$proxyHost", "-Dhttp.proxyPort=$proxyPort", "-Dhttps.proxyHost=$proxyHost", "-Dhttps.proxyPort=$proxyPort")
    }
    Write-Host "JDK:    $jdk"
    Write-Host "Engine: $engine"
    Write-Host "Repo:   $repository"
    Write-Host "Proxy:  $proxyHost $proxyPort (empty host means direct)"
    if ($Mode -eq 'check') { exit 0 }
    $env:JAVA_HOME = $jdk
    $env:PATH = "$jdk/bin;$env:PATH"
    $env:GRADLE_OPTS = "$env:GRADLE_OPTS $($proxyArgs -join ' ')"
    Set-Location -LiteralPath $root
    if ($Mode -ne 'sdk') {
        Write-Host 'Exporting engine binaries and sources. Close the SDK product window first.'
        # Quote dotted property values: Windows PowerShell 5.1 otherwise splits the version.
        & "$engine/gradlew.bat" -p $engine -I "$root/gradle/local-engine-export.gradle" "-PsdkEngineRepository=$repository" "-PjmeVersionName=3.10.0-local-SNAPSHOT" @proxyArgs exportSdkEngine
        if ($LASTEXITCODE -ne 0) { throw "Engine export failed: $LASTEXITCODE" }
    }
    if (!(Test-Path "$repository/sdk-engine.properties")) { throw 'Engine not exported. Run build-local-engine-sdk.bat first.' }
    $task = 'buildSdk'
    if ($Mode -in @('run','sdk')) { $task = 'run' }
    & "$root/gradlew.bat" @proxyArgs "-PuseLocalEngine=true" "-PlocalEngineRepository=$repository" $task
    if ($LASTEXITCODE -ne 0) { throw "SDK build failed: $LASTEXITCODE" }
    exit 0
} catch {
    Write-Host "ERROR: $_" -ForegroundColor Red
    exit 1
}
