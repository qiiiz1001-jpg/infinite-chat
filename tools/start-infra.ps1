param([string]$WslDistribution = 'Ubuntu-20.04')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'prepare-env.ps1') -LocalPorts
. (Join-Path $PSScriptRoot 'load-env.ps1')
$composeProject = if ($env:IM_COMPOSE_PROJECT) { $env:IM_COMPOSE_PROJECT } else { 'qingliao-local' }
if ($composeProject -notmatch '^[a-z0-9][a-z0-9_-]*$') { throw 'IM_COMPOSE_PROJECT 格式不正确。' }
$linuxRoot = '/mnt/' + $root.Substring(0,1).ToLowerInvariant() + '/' + $root.Substring(3).Replace('\','/')
& wsl.exe -d $WslDistribution -u root --cd $linuxRoot --exec docker-compose -p $composeProject --env-file .env -f deploy/compose-integrated.json up -d
if ($LASTEXITCODE -ne 0) { throw '启动轻聊 IM 中间件失败。' }
& (Join-Path $PSScriptRoot 'migrate-local.ps1') -WslDistribution $WslDistribution
