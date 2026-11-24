param([switch]$Build, [switch]$StartMiddleware, [switch]$Models, [switch]$Mcp)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if ($Mcp -and -not $Models) { throw '-Mcp 需要同时指定 -Models。' }
if (-not (Test-Path -LiteralPath (Join-Path $root '.env'))) { & (Join-Path $PSScriptRoot 'prepare-env.ps1') -LocalPorts }
. (Join-Path $PSScriptRoot 'load-env.ps1')
if ($Models -and -not $env:DASHSCOPE_API_KEY) { throw '请配置 DASHSCOPE_API_KEY。' }
if ($Mcp -and -not $env:BIGMODEL_API_KEY) { throw '请配置 BIGMODEL_API_KEY。' }
if (-not $env:JAVA_HOME -and (Test-Path 'F:/Dev/jdk17/bin/java.exe')) { $env:JAVA_HOME = 'F:/Dev/jdk17' }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java -ErrorAction Stop).Source }
$env:SPRING_PROFILES_ACTIVE = if ($Models) { 'dev' } else { 'infra' }
$env:AI_ENABLED = if ($Models) { 'true' } else { 'false' }
$env:MCP_ENABLED = if ($Mcp) { 'true' } else { 'false' }
$env:RAG_LOAD_ON_STARTUP = 'false'
$env:MINIO_INITIALIZE = 'true'
Push-Location -LiteralPath $root
$jobs = @()
try {
    if ($Build -or -not (Test-Path 'GateWay/target-maven/GateWay-0.0.1-SNAPSHOT.jar')) {
        & mvn -q '-Dchat.build-dir=target-maven' -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw '轻聊 IM 打包失败。' }
    }
    if ($StartMiddleware) {
        $jobs += Start-Job -Name 'IM WSL' -ScriptBlock { & wsl.exe -d Ubuntu-20.04 -u root --exec sleep infinity }
        & (Join-Path $PSScriptRoot 'start-infra.ps1')
    }
    $modules = @('UserService','OfflineDataService','RedPacketService','AiService','RealTimeService','GateWay')
    $runtime = Join-Path $root 'deploy/run'
    [void][IO.Directory]::CreateDirectory($runtime)
    for ($index = 0; $index -lt $modules.Length; $index++) {
        $module = $modules[$index]
        $jar = Join-Path $root "$module/target-maven/$module-0.0.1-SNAPSHOT.jar"
        if (-not (Test-Path -LiteralPath $jar)) { throw ('缺少打包产物：' + $module) }
        $log = Join-Path $runtime "$module.local.log"
        $jobs += Start-Job -Name $module -ArgumentList $java,$root,$jar,($index + 1),$log -ScriptBlock {
            param($java,$root,$jar,$worker,$log)
            Set-Location -LiteralPath $root
            $env:SNOWFLAKE_WORKER_ID = [string]$worker
            & $java '-Xms64m' '-Xmx384m' '-Dfile.encoding=UTF-8' '-Duser.timezone=Asia/Shanghai' '-jar' $jar *> $log
            if ($LASTEXITCODE -ne 0) { throw ('服务退出，详细日志见 ' + $log) }
        }
    }
    Write-Output '轻聊 IM http://127.0.0.1:10010/ 正在启动。关闭终端会结束本轮应用进程。'
    $services = @($jobs | Where-Object Name -ne 'IM WSL')
    $ended = Wait-Job -Job $services -Any
    Receive-Job -Job $ended
    throw ('服务已停止：' + $ended.Name)
} finally {
    if ($jobs.Count) {
        $jobs | Stop-Job -ErrorAction SilentlyContinue
        $jobs | Remove-Job -Force -ErrorAction SilentlyContinue
    }
    Pop-Location
}
