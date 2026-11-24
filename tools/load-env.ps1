param([string]$EnvironmentFile = (Join-Path (Split-Path -Parent $PSScriptRoot) '.env'))
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $EnvironmentFile)) { throw '请先运行 tools/prepare-env.ps1 生成本机 .env。' }
foreach ($line in Get-Content -LiteralPath $EnvironmentFile -Encoding UTF8) {
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') {
        $key = $Matches[1]
        if ($key -in @('HOME', 'CODEX_HOME', 'PATH', 'SystemRoot', 'USERPROFILE')) { throw '环境文件包含系统变量名，请移除。' }
        $value = $Matches[2].Trim()
        if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        [Environment]::SetEnvironmentVariable($key, $value, 'Process')
    }
}
