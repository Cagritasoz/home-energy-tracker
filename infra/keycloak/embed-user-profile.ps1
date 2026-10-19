# Copies user-profile.json (the readable source) into realm-energy.json.
#
# Why this script exists: Keycloak imports the user-profile configuration as ONE JSON string inside the realm file
# ("kc.user.profile.config": [ "{\"attributes\":[...]}" ]). A JSON string cannot span lines, and Keycloak reads only
# the first element of that array, so inside the realm file the configuration is unavoidably a single escaped line.
# user-profile.json is where it is edited and read; this script must be run after changing it.
# RealmFileTest fails when the two disagree, so a forgotten run is caught by the build.
#
# Usage (any directory):  powershell -File infra/keycloak/embed-user-profile.ps1

$ErrorActionPreference = 'Stop'

$profilePath = Join-Path $PSScriptRoot 'user-profile.json'
$realmPath   = Join-Path $PSScriptRoot 'realm-energy.json'

# Parse (this also proves the source is valid JSON) and write it back compact, on one line.
$compact = (Get-Content -LiteralPath $profilePath -Raw -Encoding UTF8 | ConvertFrom-Json) | ConvertTo-Json -Depth 20 -Compress

# Escape it as the content of a JSON string.
$escaped = $compact.Replace('\', '\\').Replace('"', '\"')

$realm = [System.IO.File]::ReadAllText($realmPath)

# Replace only the value of "kc.user.profile.config". A MatchEvaluator keeps the "$" of placeholders such as
# ${username} from being read as a regex replacement token.
$pattern = '("kc\.user\.profile\.config":\s*\[\s*)"(?:[^"\\]|\\.)*"(\s*\])'
$evaluator = [System.Text.RegularExpressions.MatchEvaluator] {
    param($match)
    $match.Groups[1].Value + '"' + $escaped + '"' + $match.Groups[2].Value
}

if (-not [regex]::IsMatch($realm, $pattern)) {
    throw "kc.user.profile.config not found in $realmPath"
}

$updated = [regex]::Replace($realm, $pattern, $evaluator)

# The repository's files are UTF-8 without a byte order mark.
[System.IO.File]::WriteAllText($realmPath, $updated, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "Embedded user-profile.json into realm-energy.json"
