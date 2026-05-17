# PreToolUse hook — block edits that would write secrets into tracked files.
# Reads tool input from stdin (JSON), exits non-zero with message on stderr to deny the call.

$ErrorActionPreference = 'Stop'

try {
    $payload = [Console]::In.ReadToEnd() | ConvertFrom-Json
} catch {
    exit 0   # malformed input, don't block legitimate edits
}

$path = $payload.tool_input.file_path
if (-not $path) { exit 0 }

# Hard-deny: any tracked file matching these names is off-limits to writes.
$forbiddenPaths = @(
    'local.properties',
    'keystore.properties',
    '.env'
)
foreach ($f in $forbiddenPaths) {
    if ($path -like "*$f") {
        Write-Error "Refusing to write to '$path'. Secrets file is gitignored and must stay local-only. Use the .example template instead."
        exit 2
    }
}

# Content-based deny: detect obvious credential patterns being written into ANY file.
$content = $payload.tool_input.content
if (-not $content) { $content = $payload.tool_input.new_string }
if ($content) {
    $patterns = @(
        '(?i)password\s*[:=]\s*["''](?!CHANGE_ME|YOUR_|\$\{)[^"''\s]{3,}',
        '(?i)api[_-]?key\s*[:=]\s*["''](?!YOUR_|\$\{)[A-Za-z0-9]{16,}',
        '(?i)bearer\s+[A-Za-z0-9\-_\.]{30,}',
        '-----BEGIN (RSA |EC |OPENSSH |)PRIVATE KEY-----'
    )
    foreach ($p in $patterns) {
        if ($content -match $p) {
            Write-Error "Refusing edit to '$path' — content matches a credential pattern ($p). If this is a false positive, rewrite the literal as a placeholder."
            exit 2
        }
    }
}

exit 0
