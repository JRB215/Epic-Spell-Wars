<#
  Updates the Epic Spell Wars server on this PC: downloads the newest image, replaces the running
  container and starts it again. Run it in PowerShell on the PC that hosts the game (JRBFlix).

  Usage:
    .\update-server.ps1
    .\update-server.ps1 -Pieces "C:\Users\me\Downloads\epic-spell-wars-pieces.zip"   # also refresh the game pieces
    .\update-server.ps1 -Port 8082 -Assets "D:\ESW\assets"                           # different port or folder
#>
param(
    [int]$Port = 8081,
    [string]$Assets = "C:\EpicSpellWars\assets",
    [string]$Pieces = "",
    [string]$Name = "epic-spell-wars",
    [string]$Image = "ghcr.io/jrb215/epic-spell-wars-server:latest"
)

$ErrorActionPreference = "Stop"

function Step($text) { Write-Host "`n== $text" -ForegroundColor Cyan }

# Docker must be installed and running.
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "Docker was not found. Install and start Docker Desktop first."
}
docker info *> $null
if ($LASTEXITCODE -ne 0) {
    throw "Docker is not running (or this window is not allowed to use it). Start Docker Desktop and try again."
}

# The folder for card pictures and the leaderboard.
Step "Checking the pictures folder: $Assets"
New-Item -ItemType Directory -Force -Path $Assets | Out-Null

# Optionally unpack a fresh set of game pieces (skull, win token, ...) into <assets>\Pieces.
if ($Pieces) {
    Step "Unpacking game pieces from $Pieces"
    if (-not (Test-Path $Pieces)) { throw "That zip file does not exist: $Pieces" }
    Expand-Archive -Path $Pieces -DestinationPath $Assets -Force
}

Step "Downloading the newest image"
docker pull $Image
if ($LASTEXITCODE -ne 0) {
    throw "The download failed. If it says denied or unauthorized, run: docker login ghcr.io -u JRB215"
}

Step "Replacing the old container"
# These complain if there is no old container; that is fine on a first run.
$ErrorActionPreference = "Continue"
docker stop $Name *> $null
docker rm $Name *> $null
$ErrorActionPreference = "Stop"

Step "Starting the new container on port $Port"
docker run -d --name $Name --restart unless-stopped -p "${Port}:80" -v "${Assets}:/custom-assets" $Image
if ($LASTEXITCODE -ne 0) { throw "The container did not start. Is port $Port already used by something else?" }

Start-Sleep -Seconds 4
Step "Checking that it answers"
try {
    $status = Invoke-RestMethod "http://localhost:$Port/api/status" -TimeoutSec 10
    Write-Host "Running build $($status.version.Substring(0, [Math]::Min(7, $status.version.Length)))" -ForegroundColor Green
    $missing = $status.pieces.PSObject.Properties | Where-Object { -not $_.Value } | ForEach-Object { $_.Name }
    if ($missing) {
        Write-Host "Game pieces missing: $($missing -join ', '). Run again with -Pieces <path to epic-spell-wars-pieces.zip>." -ForegroundColor Yellow
    }
    $total = ($status.folders.PSObject.Properties | Measure-Object -Property Value -Sum).Sum
    if (-not $total) {
        Write-Host "No card pictures found in $Assets. Unzip epic-spell-wars-art.zip into that folder." -ForegroundColor Yellow
    }
    Write-Host "`nOpen http://localhost:$Port (or your usual address with :$Port) and press Ctrl+F5." -ForegroundColor Green
} catch {
    Write-Host "The container started but did not answer yet. Try 'docker logs $Name' to see why." -ForegroundColor Yellow
}
