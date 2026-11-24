param([switch]$LocalPorts)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$environmentFile = Join-Path $projectRoot '.env'
$encoding = New-Object System.Text.UTF8Encoding($false)
function New-LocalSecret {
    $bytes = New-Object byte[] 32
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    return [Convert]::ToBase64String($bytes)
}
if (-not (Test-Path -LiteralPath $environmentFile)) {
    $content = [IO.File]::ReadAllText((Join-Path $projectRoot 'deploy/.env.example'))
    if ($LocalPorts) {
        $content = $content.Replace('DB_PORT=3307','DB_PORT=13307').Replace('REDIS_PORT=6379','REDIS_PORT=16379')
        $content = $content.Replace('PGVECTOR_PORT=5432','PGVECTOR_PORT=15432').Replace('http://localhost:9000','http://localhost:19000')
        $content = $content.Replace('localhost:9092','localhost:19092').Replace('localhost:8848','localhost:18848')
    }
    $content = $content.Replace('REPLACE_WITH_RANDOM_BASE64', (New-LocalSecret))
    $content = [regex]::Replace($content, 'REPLACE_WITH_RANDOM', { param($match) (New-LocalSecret).Replace('+','a').Replace('/','b').Replace('=','') })
    [IO.File]::WriteAllText($environmentFile, $content, $encoding)
    Write-Output '已生成本机随机凭证；未在终端输出密钥。'
} else { Write-Output '保留现有 .env。' }
. (Join-Path $PSScriptRoot 'load-env.ps1') -EnvironmentFile $environmentFile
[IO.File]::WriteAllText((Join-Path $projectRoot 'deploy/integrated-postgres/03-scoped-knowledge.sql'), [IO.File]::ReadAllText((Join-Path $projectRoot 'database/postgres/03-scoped-knowledge.sql')), $encoding)
if ([string]::IsNullOrWhiteSpace($env:NACOS_DB_NAME)) {
    $env:NACOS_DB_NAME = 'nacos_config'
    [IO.File]::AppendAllText($environmentFile, "`nNACOS_DB_NAME=nacos_config`n", $encoding)
}
if ([string]::IsNullOrWhiteSpace($env:MYSQL_NACOS_PASSWORD)) {
    $env:MYSQL_NACOS_PASSWORD = (New-LocalSecret).Replace('+','a').Replace('/','b').Replace('=','')
    [IO.File]::AppendAllText($environmentFile, "MYSQL_NACOS_PASSWORD=$($env:MYSQL_NACOS_PASSWORD)`n", $encoding)
}
if ($env:DB_NAME -notmatch '^[A-Za-z0-9_]+$') { throw 'DB_NAME 只允许字母、数字、下划线。' }
if ($env:NACOS_DB_NAME -notmatch '^[A-Za-z0-9_]+$') { throw 'NACOS_DB_NAME 只允许字母、数字、下划线。' }
$generated = Join-Path $projectRoot 'deploy/generated'
$mysqlDirectory = Join-Path $generated 'mysql'
[IO.Directory]::CreateDirectory($mysqlDirectory) | Out-Null
foreach ($name in @('01-init.sql','02-business-upgrade.sql','03-demo-data.sql','04-course-examples.sql','05-agent-actions.sql')) {
    $sql = [IO.File]::ReadAllText((Join-Path $projectRoot "database/$name"))
    $sql = $sql.Replace('Qingliao', $env:DB_NAME)
    if ($name -eq '05-agent-actions.sql') { $sql = 'USE `' + $env:DB_NAME + '`;' + [Environment]::NewLine + $sql }
    [IO.File]::WriteAllText((Join-Path $mysqlDirectory $name), $sql, $encoding)
}
$canalPasswordSql = $env:MYSQL_CANAL_PASSWORD.Replace("'", "''")
$userSql = "CREATE USER IF NOT EXISTS 'canal'@'%' IDENTIFIED BY '$canalPasswordSql';`nGRANT SELECT, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'canal'@'%';`nFLUSH PRIVILEGES;`n"
[IO.File]::WriteAllText((Join-Path $mysqlDirectory '04-canal-user.sql'), $userSql, $encoding)
$nacosSchema = [IO.File]::ReadAllText((Join-Path $projectRoot 'database/nacos/01-schema.sql'))
$nacosSchema = $nacosSchema.Replace('nacos_config', $env:NACOS_DB_NAME)
[IO.File]::WriteAllText((Join-Path $mysqlDirectory '05-nacos-schema.sql'), $nacosSchema, $encoding)
$nacosPasswordSql = $env:MYSQL_NACOS_PASSWORD.Replace("'", "''")
$nacosUserSql = "CREATE USER IF NOT EXISTS 'nacos'@'%' IDENTIFIED BY '$nacosPasswordSql';`nGRANT ALL PRIVILEGES ON $($env:NACOS_DB_NAME).* TO 'nacos'@'%';`n"
[IO.File]::WriteAllText((Join-Path $mysqlDirectory '06-nacos-user.sql'), $nacosUserSql, $encoding)
$propertiesPassword = $env:MYSQL_CANAL_PASSWORD.Replace('\', '\\')
$properties = @"
canal.instance.master.address=mysql:3306
canal.instance.dbUsername=canal
canal.instance.dbPassword=$propertiesPassword
canal.instance.connectionCharset=UTF-8
canal.instance.defaultDatabaseName=$($env:DB_NAME)
canal.instance.filter.regex=$($env:DB_NAME)\\.message
canal.instance.tsdb.enable=true
canal.instance.memory.buffer.size=16384
"@
[IO.File]::WriteAllText((Join-Path $generated 'canal-instance.properties'), $properties, $encoding)
Write-Output '已准备数据库与 Canal 初始化文件；尚未执行 SQL，也未启动容器。'
