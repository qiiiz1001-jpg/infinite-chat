param([string]$WslDistribution = 'Ubuntu-20.04')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'load-env.ps1')
if ($env:DB_NAME -notmatch '^[A-Za-z0-9_]+$') { throw 'DB_NAME 格式不正确。' }
$linuxRoot = '/mnt/' + $root.Substring(0,1).ToLowerInvariant() + '/' + $root.Substring(3).Replace('\','/')
$composeProject = if ($env:IM_COMPOSE_PROJECT) { $env:IM_COMPOSE_PROJECT } else { 'qingliao-local' }
if ($composeProject -notmatch '^[a-z0-9][a-z0-9_-]*$') { throw 'IM_COMPOSE_PROJECT 格式不正确。' }
$mysql = 'docker exec -i ' + $composeProject + '_mysql_1 sh -c ''MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -uroot ' + $env:DB_NAME + ''''
$postgres = 'docker exec -i ' + $composeProject + '_postgres_1 sh -c ''psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'' < database/postgres/03-scoped-knowledge.sql'
for ($attempt = 0; $attempt -lt 20; $attempt++) {
    if ($attempt) { Start-Sleep -Seconds 1 }
    & wsl.exe -d $WslDistribution -u root --cd $linuxRoot --exec bash -c ($mysql + ' < database/05-agent-actions.sql') 2>$null
    if ($LASTEXITCODE -eq 0) { break }
}
if ($LASTEXITCODE -ne 0) { throw '任务表迁移失败，请检查本地 MySQL 已就绪且账号配置正确。' }
& wsl.exe -d $WslDistribution -u root --cd $linuxRoot --exec bash -c ($mysql + ' < database/06-repair-demo-labels.sql')
if ($LASTEXITCODE -ne 0) { throw '试用会话名称迁移失败。' }
& wsl.exe -d $WslDistribution -u root --cd $linuxRoot --exec bash -c $postgres
if ($LASTEXITCODE -ne 0) { throw '知识库表迁移失败，请检查本地 PostgreSQL 已就绪且账号配置正确。' }
Write-Output '任务与知识库表已核对。'
