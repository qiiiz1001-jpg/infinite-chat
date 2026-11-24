param([switch]$Check)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$cache = Join-Path $projectRoot '.tools'
[IO.Directory]::CreateDirectory($cache) | Out-Null
$jar = Join-Path $cache 'google-java-format-1.24.0-all-deps.jar'
if (-not (Test-Path -LiteralPath $jar)) {
    Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/com/google/googlejavaformat/google-java-format/1.24.0/google-java-format-1.24.0-all-deps.jar' -OutFile $jar
}
if ((Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -ne '812F805F58112460EDF01BF202A8E61D0FD1F35C0D4FABD54220640776EC57A1') { throw '格式化工具校验失败。' }
$java = (Get-Command java -ErrorAction Stop).Source
if ($env:JAVA_HOME) { $java = Join-Path $env:JAVA_HOME 'bin/java.exe' }
$sources = @(Get-ChildItem -LiteralPath $projectRoot -Directory | ForEach-Object {
    $sourceRoot = Join-Path $_.FullName 'src'
    if (Test-Path -LiteralPath $sourceRoot) { Get-ChildItem -LiteralPath $sourceRoot -Recurse -File -Filter '*.java' }
})
for ($index = 0; $index -lt $sources.Count; $index += 15) {
    $options = @('-jar', $jar, '--aosp')
    if ($Check) { $options += @('--dry-run', '--set-exit-if-changed') } else { $options += '--replace' }
    $end = [Math]::Min($index + 14, $sources.Count - 1)
    $options += @($sources[$index..$end] | ForEach-Object { $_.FullName })
    & $java @options
    if ($LASTEXITCODE -ne 0) { throw 'Java 格式检查或格式化失败。' }
}
Write-Output "Java 格式已检查，共 $($sources.Count) 个文件，4 空格缩进。"
