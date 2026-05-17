# PostToolUse hook (opt-in) — run the unit test suite when core/network or core/data is touched.
# Wire into settings.local.json under PostToolUse if you want this active.

$ErrorActionPreference = 'SilentlyContinue'

try {
    $payload = [Console]::In.ReadToEnd() | ConvertFrom-Json
} catch { exit 0 }

$path = $payload.tool_input.file_path
if (-not $path) { exit 0 }

if ($path -match 'core[\\/]network' -or $path -match 'core[\\/]data') {
    if (Test-Path .\gradlew.bat) {
        & .\gradlew.bat :app:testDebugUnitTest --quiet
        if ($LASTEXITCODE -ne 0) {
            Write-Error "Unit tests failed after editing $path. Investigate before continuing."
            exit 2
        }
    }
}

exit 0
