param(
  [string]$SaveFolder = ""
)

$ErrorActionPreference = 'Stop'
$Port = 5051
$Magic = 'EXPLSEND2'
if ([string]::IsNullOrWhiteSpace($SaveFolder)) {
  $SaveFolder = Join-Path ([Environment]::GetFolderPath('UserProfile')) 'Downloads\LocalSend'
}
New-Item -ItemType Directory -Force -Path $SaveFolder | Out-Null

function Read-Exact([System.IO.Stream]$stream, [int]$count) {
  $buffer = New-Object byte[] $count
  $read = 0
  while ($read -lt $count) {
    $n = $stream.Read($buffer, $read, $count - $read)
    if ($n -le 0) { throw 'Connection closed unexpectedly.' }
    $read += $n
  }
  return $buffer
}

function Read-JavaUTF([System.IO.Stream]$stream) {
  $lenBytes = Read-Exact $stream 2
  $length = ($lenBytes[0] -shl 8) -bor $lenBytes[1]
  $buffer = Read-Exact $stream $length
  return [Text.Encoding]::UTF8.GetString($buffer)
}

function Write-JavaUTF([System.IO.Stream]$stream, [string]$text) {
  $bytes = [Text.Encoding]::UTF8.GetBytes($text)
  $len = [byte[]](($bytes.Length -shr 8) -band 255), [byte]($bytes.Length -band 255)
  $stream.Write($len,0,2)
  $stream.Write($bytes,0,$bytes.Length)
  $stream.Flush()
}

function Read-BigEndianInt64([System.IO.Stream]$stream) {
  $bytes = Read-Exact $stream 8
  if ([BitConverter]::IsLittleEndian) { [Array]::Reverse($bytes) }
  return [BitConverter]::ToInt64($bytes,0)
}

function Get-UniquePath([string]$folder, [string]$name) {
  $safe = $name -replace '[\\/:*?"<>|]','_'
  $path = Join-Path $folder $safe
  if (-not (Test-Path $path)) { return $path }
  $base = [IO.Path]::GetFileNameWithoutExtension($safe)
  $ext = [IO.Path]::GetExtension($safe)
  $i = 1
  do { $path = Join-Path $folder "$base ($i)$ext"; $i++ } while (Test-Path $path)
  return $path
}

$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Any, $Port)
$listener.Start()
$hostIp = (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254*' } | Select-Object -First 1 -ExpandProperty IPAddress)
Write-Host "Ready to receive on $hostIp`:$Port" -ForegroundColor Cyan
Write-Host "Files will be saved to: $SaveFolder"
Write-Host 'Press Ctrl+C to stop.'

try {
  while ($true) {
    $client = $listener.AcceptTcpClient()
    try {
      $stream = $client.GetStream()
      $stream.ReadTimeout = 120000
      $magic = Read-JavaUTF $stream
      if ($magic -ne $Magic) { throw 'Incompatible transfer protocol.' }
      $name = Read-JavaUTF $stream
      $size = Read-BigEndianInt64 $stream
      if ($size -lt 0) { throw 'Invalid file size.' }
      $path = Get-UniquePath $SaveFolder $name
      Write-Host "Receiving $name ($size bytes)..."
      $output = [IO.File]::Create($path)
      try {
        $buffer = New-Object byte[] 65536
        $done = 0L
        while ($done -lt $size) {
          $need = [int][Math]::Min($buffer.Length, $size - $done)
          $n = $stream.Read($buffer,0,$need)
          if ($n -le 0) { throw 'Connection closed before file completed.' }
          $output.Write($buffer,0,$n)
          $done += $n
          $pct = if ($size -eq 0) { 100 } else { [int](($done * 100) / $size) }
          Write-Progress -Activity "Receiving $name" -Status "$pct%" -PercentComplete $pct
        }
      } finally { $output.Dispose() }
      Write-JavaUTF $stream 'OK'
      Write-Host "Saved: $path" -ForegroundColor Green
    } catch {
      try { Write-JavaUTF $stream ("ERROR:" + $_.Exception.Message) } catch {}
      Write-Warning $_.Exception.Message
    } finally { $client.Dispose() }
  }
} finally { $listener.Stop() }
