param([switch]$Package)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path -LiteralPath (Join-Path $projectRoot '.env'))) {
    & (Join-Path $PSScriptRoot 'prepare-env.ps1')
}
. (Join-Path $PSScriptRoot 'load-env.ps1')
if (-not $env:JAVA_HOME -and (Test-Path 'F:/Dev/jdk17/bin/java.exe')) { $env:JAVA_HOME = 'F:/Dev/jdk17' }
& (Join-Path $PSScriptRoot 'format-java.ps1') -Check
$maven = Get-Command mvn.cmd,mvn -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $maven) { throw '未找到 Maven，请配置 Maven 与 JDK 17。' }
$tests = 'JwtContractTest,AuthorizeFilterTest,ConsistentHashTest,MessageFlowTest,WebSocketAuthTest,NettySocketTest,MessagePersistenceTest,GroupRoleTest,FinancialTransactionTest,RedPacketAlgorithmTest,KnowledgeFilesTest,AiMetricsContractTest,AiContextTest,GatewaySmokeTest,UserServiceSmokeTest,AgentActionTest,AgentActionHttpTest,ImActionToolTest,KnowledgeParserTest,ScopedKnowledgeTest'
$lifecycle = 'test'
if ($Package) { $lifecycle = 'package' }
Push-Location -LiteralPath $projectRoot
try {
    $options = @('clean', $lifecycle, '-Dchat.build-dir=target-maven', "-Dtest=$tests", '-Dsurefire.failIfNoSpecifiedTests=false', '-B', '-ntp')
    & $maven.Source @options
    if ($LASTEXITCODE -ne 0) { throw '构建或针对性测试失败。' }
} finally { Pop-Location }
