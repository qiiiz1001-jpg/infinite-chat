param([switch]$StopMiddleware)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$file=Join-Path $root 'deploy/run/processes.json'
if(-not(Test-Path -LiteralPath $file)){return}
foreach($record in (Get-Content -LiteralPath $file -Raw|ConvertFrom-Json)){
    $process=Get-CimInstance Win32_Process -Filter ('ProcessId='+$record.pid) -ErrorAction SilentlyContinue
    if($process -and $process.CommandLine.Contains($record.jar)){
        Stop-Process -Id $record.pid
        Write-Output ('Stopped '+$record.name)
    }
}
if($StopMiddleware){
    $linuxRoot='/mnt/'+$root.Substring(0,1).ToLower()+'/'+$root.Substring(3).Replace('\','/')
    & wsl.exe -d Ubuntu-20.04 -u root --exec docker-compose --project-directory $linuxRoot -p qianyan-integrated -f ($linuxRoot+'/deploy/compose-integrated.json') stop
    $keepFile=Join-Path $root 'deploy/run/wsl-keepalive.json'
    if(Test-Path -LiteralPath $keepFile){
        $keep=Get-Content -LiteralPath $keepFile -Raw|ConvertFrom-Json
        $process=Get-CimInstance Win32_Process -Filter ('ProcessId='+$keep.pid) -ErrorAction SilentlyContinue
        if($process -and $process.CommandLine -match 'sleep infinity'){Stop-Process -Id $keep.pid}
    }
}
