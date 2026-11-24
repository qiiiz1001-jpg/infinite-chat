param([switch]$Ai)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
foreach ($command in @('java','mvn')) {
    if (Get-Command $command -ErrorAction SilentlyContinue) { Write-Output "[找到] $command" }
    else { Write-Output "[缺少] $command" }
}
if(Get-Command docker -ErrorAction SilentlyContinue){Write-Output '[找到] docker'}
elseif(Get-Command wsl -ErrorAction SilentlyContinue){
    & wsl.exe -d Ubuntu-20.04 -u root --exec docker info --format '{{.ServerVersion}}' *> $null
    if($LASTEXITCODE -eq 0){Write-Output '[找到] WSL Ubuntu-20.04 Docker'}else{Write-Output '[缺少] 可用 Docker'}
}else{Write-Output '[缺少] Docker'}
Write-Output ('.env 存在：' + (Test-Path -LiteralPath (Join-Path $projectRoot '.env')))
if (Test-Path -LiteralPath (Join-Path $projectRoot '.env')) {
    . (Join-Path $PSScriptRoot 'load-env.ps1')
}
foreach ($module in @('UserService','RealTimeService','OfflineDataService','RedPacketService','GateWay','AiService')) {
    $artifact = Join-Path $projectRoot "$module/target-maven/$module-0.0.1-SNAPSHOT.jar"
    Write-Output ("打包 $module ：" + (Test-Path -LiteralPath $artifact))
}
$databasePort=if($env:DB_PORT){[int]$env:DB_PORT}else{3307}
$redisPort=if($env:REDIS_PORT){[int]$env:REDIS_PORT}else{6379}
$kafkaPort=if($env:KAFKA_BOOTSTRAP_SERVERS){([uri]('tcp://'+$env:KAFKA_BOOTSTRAP_SERVERS.Split(',')[0])).Port}else{9092}
$objectPort=if($env:MINIO_ENDPOINT){([uri]$env:MINIO_ENDPOINT).Port}else{9000}
$vectorPort=if($env:PGVECTOR_PORT){[int]$env:PGVECTOR_PORT}else{5432}
$endpoints = @(@('MySQL',$databasePort),@('Redis',$redisPort),@('Kafka',$kafkaPort),@('MinIO',$objectPort),@('网关',10010),@('WebSocket',9101))
if ($Ai) { $endpoints += ,@('PgVector',$vectorPort) }
foreach ($endpoint in $endpoints) {
    $client = New-Object Net.Sockets.TcpClient
    try {
        $pending = $client.ConnectAsync('127.0.0.1',[int]$endpoint[1])
        $ready = $pending.Wait(1500) -and $client.Connected
    } catch { $ready = $false } finally { $client.Dispose() }
    Write-Output ("端口 $($endpoint[0]) localhost:$($endpoint[1]) ：$ready")
}
if ($Ai) {
    Write-Output ('AI 密钥已填写：' + (-not [string]::IsNullOrWhiteSpace($env:DASHSCOPE_API_KEY)))
}
Write-Output '端口可达仅代表有进程监听；不代表认证、表结构和业务链路通过。'
