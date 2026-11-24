param([Parameter(Mandatory=$true)][ValidateSet('UserService','RealTimeService','OfflineDataService','RedPacketService','AiService','GateWay')][string]$Module, [switch]$Discovery)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'load-env.ps1')
if ($Module -eq 'AiService' -and -not $env:DASHSCOPE_API_KEY) { throw '请先在 .env 填写 DASHSCOPE_API_KEY；其它业务服务可先启动。' }
$jar = Join-Path $projectRoot "$Module/target-maven/$Module-0.0.1-SNAPSHOT.jar"
if (-not (Test-Path -LiteralPath $jar)) { throw '请先运行 tools/verify.ps1 -Package。' }
$java = (Get-Command java -ErrorAction Stop).Source
if ($env:JAVA_HOME) { $java = Join-Path $env:JAVA_HOME 'bin/java.exe' }
$serviceArguments = @()
if ($Discovery) {
    # CLI 空值覆盖固定 URL；Windows 环境变量空字符串可能被删除，不能靠它切换 Feign。
    $serviceArguments += @('--spring.cloud.nacos.discovery.enabled=true','--services.userservice.url=','--services.aiservice.url=','--services.offlinedataservice.url=')
    if ($Module -eq 'GateWay') {
        $serviceArguments += @('--USER_SERVICE_URI=lb://UserService','--OFFLINE_SERVICE_URI=lb://OfflineDataService','--AI_SERVICE_URI=lb://AiService','--REDPACKET_SERVICE_URI=lb://RedPacketService')
    }
}
Push-Location -LiteralPath $projectRoot
try { & $java '-Duser.timezone=Asia/Shanghai' '-jar' $jar @serviceArguments; exit $LASTEXITCODE } finally { Pop-Location }
