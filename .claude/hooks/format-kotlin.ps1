# PostToolUse hook — format the Kotlin file that was just edited.
# Best-effort: only runs if ktlint is on PATH. Never blocks the toolchain.

$ErrorActionPreference = 'SilentlyContinue'

try {
    $payload = [Console]::In.ReadToEnd() | ConvertFrom-Json
} catch { exit 0 }

$path = $payload.tool_input.file_path
if (-not $path) { exit 0 }
if (-not ($path -like '*.kt' -or $path -like '*.kts')) { exit 0 }

# Run ktlint if available; otherwise no-op.
$ktlint = Get-Command ktlint -ErrorAction SilentlyContinue
if ($ktlint) {
    & ktlint --format $path *> $null
}

exit 0
