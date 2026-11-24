param([string]$BaseUrl='http://localhost:10010')
$ErrorActionPreference = 'Stop'
function Invoke-ChatApi($Path, $Body, $Token) {
    $headers = @{}
    if ($Token) { $headers['Access-Token'] = $Token }
    $payload = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 8 -Compress))
    $result = Invoke-RestMethod -Uri "$BaseUrl$Path" -Method Post -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $payload -TimeoutSec 15
    if ($result.code -ne 200) { throw "业务失败：$Path，code=$($result.code)，$($result.message)" }
    return $result.data
}
$login = Invoke-ChatApi '/api/user/login/password' @{email='demo-a@example.com';password='123456'} $null
if (-not $login.accessToken -or -not $login.nettyUri) { throw '登录缺少 Token 或 Netty 地址。' }
Write-Output '登录通过（未输出 Token）。'
$history = Invoke-ChatApi '/api/message/history' @{sessionId='99001';beforeTime=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds();limit=20} $login.accessToken
Write-Output ("历史查询通过，返回 $(@($history).Count) 条。")
$socket = New-Object Net.WebSockets.ClientWebSocket
$timeout = New-Object Threading.CancellationTokenSource
$timeout.CancelAfter(10000)
try {
    $socket.Options.SetRequestHeader('Authorization',"Bearer $($login.accessToken)")
    $socket.ConnectAsync([Uri]$login.nettyUri,$timeout.Token).GetAwaiter().GetResult()
    $bytes = [Text.Encoding]::UTF8.GetBytes('ping')
    $segment = New-Object 'System.ArraySegment[byte]' -ArgumentList @(,$bytes)
    $socket.SendAsync($segment,[Net.WebSockets.WebSocketMessageType]::Text,$true,$timeout.Token).GetAwaiter().GetResult()
    $buffer = New-Object byte[] 2048
    $reply = $socket.ReceiveAsync((New-Object 'System.ArraySegment[byte]' -ArgumentList @(,$buffer)),$timeout.Token).GetAwaiter().GetResult()
    $text = [Text.Encoding]::UTF8.GetString($buffer,0,$reply.Count)
    if ($text -ne 'pong') { throw "心跳响应异常：$text" }
    Write-Output 'WebSocket 真实握手及心跳通过。'
} finally { $socket.Dispose(); $timeout.Dispose() }
Write-Output '本脚本不发送聊天或红包，不修改测试余额；完整业务演示见 deploy/README.md。'
