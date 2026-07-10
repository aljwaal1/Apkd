param(
  [string]$TargetIP = "",
  [string[]]$Files
)

$ErrorActionPreference = 'Stop'
$Port = 5051
$Magic = 'EXPLSEND2'
$Config = Join-Path $PSScriptRoot 'LocalSend-IP.txt'

function Write-BigEndianInt64([System.IO.Stream]$stream, [Int64]$value) {
  $bytes = [BitConverter]::GetBytes($value)
  if ([BitConverter]::IsLittleEndian) { [Array]::Reverse($bytes) }
  $stream.Write($bytes, 0, $bytes.Length)
}

function Write-JavaUTF([System.IO.Stream]$stream, [string]$text) {
  $bytes = [Text.Encoding]::UTF8.GetBytes($text)
  if ($bytes.Length -gt 65535) { throw 'Text field is too long.' }
  $len = [byte[]](($bytes.Length -shr 8) -band 255), [byte]($bytes.Length -band 255)
  $stream.Write($len, 0, 2)
  $stream.Write($bytes, 0, $bytes.Length)
}

function Read-JavaUTF([System.IO.Stream]$stream) {
  $a = $stream.ReadByte(); $b = $stream.ReadByte()
  if ($a -lt 0 -or $b -lt 0) { throw 'Connection closed before reply.' }
  $length = ($a -shl 8) -bor $b
  $buffer = New-Object byte[] $length
  $read = 0
  while ($read -lt $length) {
    $n = $stream.Read($buffer, $read, $length - $read)
    if ($n -le 0) { throw 'Connection closed before reply.' }
    $read += $n
  }
  return [Text.Encoding]::UTF8.GetString($buffer)
}

if ([string]::IsNullOrWhiteSpace($TargetIP) -and (Test-Path $Config)) {
  $TargetIP = (Get-Content $Config -Raw).Trim()
}
if ([string]::IsNullOrWhiteSpace($TargetIP)) {
  $TargetIP = Read-Host 'Enter receiving device IP'
}
$TargetIP = $TargetIP.Replace('http://','').Replace('https://','').Split('/')[0].Split(':')[0].Trim()
Set-Content -Path $Config -Value $TargetIP -Encoding UTF8

if (-not $Files -or $Files.Count -eq 0) {
  Add-Type -AssemblyName System.Windows.Forms
  $dialog = New-Object System.Windows.Forms.OpenFileDialog
  $dialog.Multiselect = $true
  $dialog.Title = 'Select files to send'
  if ($dialog.ShowDialog() -ne [System.Windows.Forms.DialogResult]::OK) { exit }
  $Files = $dialog.FileNames
}

$sent = 0
foreach ($path in $Files) {
  if (-not (Test-Path $path -PathType Leaf)) { Write-Warning "Skipped: $path"; continue }
  $file = Get-Item $path
  Write-Host "Sending $($file.Name) ($($file.Length) bytes)..."
  $client = New-Object Net.Sockets.TcpClient
  try {
    $client.Connect($TargetIP, $Port)
    $stream = $client.GetStream()
    $stream.ReadTimeout = 120000
    $stream.WriteTimeout = 120000
    Write-JavaUTF $stream $Magic
    Write-JavaUTF $stream $file.Name
    Write-BigEndianInt64 $stream $file.Length

    $input = [IO.File]::OpenRead($file.FullName)
    try {
      $buffer = New-Object byte[] 65536
      $done = 0L
      while (($n = $input.Read($buffer,0,$buffer.Length)) -gt 0) {
        $stream.Write($buffer,0,$n)
        $done += $n
        $pct = if ($file.Length -eq 0) { 100 } else { [int](($done * 100) / $file.Length) }
        Write-Progress -Activity "Sending $($file.Name)" -Status "$pct%" -PercentComplete $pct
      }
      $stream.Flush()
    } finally { $input.Dispose() }

    $reply = Read-JavaUTF $stream
    if ($reply -ne 'OK') { throw $reply }
    $sent++
    Write-Host "Done: $($file.Name)" -ForegroundColor Green
  } finally { $client.Dispose() }
}
Write-Host "Completed. Sent $sent file(s)." -ForegroundColor Cyan
