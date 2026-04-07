# Start OpenRefine for remote access from WSL
# 
# Usage:
#   Copy this script to your OpenRefine installation folder, then run:
#   .\start-openrefine.ps1
#
# Example:
#   Copy to: C:\Users\mtoka\usrapp\openrefine-3.9.5\start-openrefine.ps1
#   Then run: .\start-openrefine.ps1

Write-Host "=========================================`n"
Write-Host "  OpenRefine Remote Start Script`n"
Write-Host "=========================================`n"

# Auto-detect Java installation
try {
    $JavaExe = (Get-Command java -ErrorAction Stop).Source
    $JavaHome = Split-Path -Parent (Split-Path -Parent $JavaExe)
    Write-Host "✓ Detected Java: $JavaExe"
    Write-Host "✓ Setting JAVA_HOME: $JavaHome`n"
} catch {
    Write-Host "✗ Error: Java not found in PATH" -ForegroundColor Red
    Write-Host "  Please ensure Java is installed and in your PATH"
    exit 1
}

# Configuration
$OpenRefineHome = Get-Location
$BindAddress = "0.0.0.0"
$Port = "3333"

# Validate OpenRefine installation
if (-not (Test-Path ".\refine.bat")) {
    Write-Host "✗ Error: refine.bat not found in current directory" -ForegroundColor Red
    Write-Host "  This script must be placed in the OpenRefine installation folder"
    Write-Host "  Current location: $OpenRefineHome"
    exit 1
}

# Set environment
$env:JAVA_HOME = $JavaHome

Write-Host "Starting OpenRefine...`n"
Write-Host "  Binding: $BindAddress`:$Port"
Write-Host "  WSL Access: http://172.27.160.1:$Port`n"
Write-Host "=========================================`n"

# Start OpenRefine
& .\refine.bat /i $BindAddress run
