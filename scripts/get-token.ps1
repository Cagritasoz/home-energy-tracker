<#
.SYNOPSIS
    Gets a local-dev access token from Keycloak for the test user or the admin user.

.NOTES
    If this fails with "cannot be loaded because running scripts is disabled on this system",
    run once: Set-ExecutionPolicy -Scope CurrentUser RemoteSigned

.DESCRIPTION
    Uses the "local-dev" client's direct access grant (username + password straight to the token
    endpoint, no browser). The credentials are the throwaway ones seeded by
    infra/keycloak/realm-energy.json, so they are only valid against the local docker compose
    Keycloak. Needs `127.0.0.1 keycloak` in the hosts file, the same host name the tokens are
    issued for. Access tokens live 5 minutes (accessTokenLifespan in the realm), so run this again
    when a request starts returning 401.

.PARAMETER Role
    user  -> testuser@example.com (realm role USER)
    admin -> admin@example.com    (realm roles USER and ADMIN)

.PARAMETER Copy
    Also copies the token to the clipboard.

.EXAMPLE
    cd "home-energy-tracker"
    .\scripts\get-token.ps1 -Role admin -Copy

.EXAMPLE
    $token = .\scripts\get-token.ps1
    Invoke-RestMethod http://localhost:8080/api/v1/users/me -Headers @{ Authorization = "Bearer $token" }

.EXAMPLE
    .\scripts\get-token.ps1
#>
param(
    [ValidateSet("user", "admin")]
    [string]$Role = "user",

    [string]$KeycloakUrl = "http://keycloak:8180",

    [switch]$Copy
)

$ErrorActionPreference = "Stop"

$credentials = @{
    user  = @{ username = "testuser@example.com"; password = "Testpassword1" }
    admin = @{ username = "admin@example.com";    password = "Adminpassword1" }
}[$Role]

$tokenUrl = "$KeycloakUrl/realms/energy-tracker/protocol/openid-connect/token"

try {
    # A hashtable body is sent as application/x-www-form-urlencoded, which is what the token endpoint expects.
    $response = Invoke-RestMethod -Method Post -Uri $tokenUrl -Body @{
        grant_type    = "password"
        client_id     = "local-dev"
        client_secret = "secret"
        username      = $credentials.username
        password      = $credentials.password
    }
}
catch {
    Write-Error "Could not get a token from $tokenUrl. Is Keycloak up (docker compose up -d in infra/)? $($_.Exception.Message)"
}

if ($Copy) {
    Set-Clipboard -Value $response.access_token
    Write-Host "Token copied to the clipboard." -ForegroundColor Green
}

# Written to the pipeline so it can be captured: $token = .\scripts\get-token.ps1
$response.access_token
