# Keycloak realm: `energy-tracker`

`realm-energy.json` is the whole local realm. Keycloak imports it once, on first boot, into an **empty** Keycloak (`docker compose down -v` first when the file changes; a plain `restart` does nothing).

| File | What it is |
|---|---|
| `realm-energy.json` | The realm that Keycloak imports. Plain JSON, no comments (see below). |
| `user-profile.json` | The readable source of the realm's user-profile configuration (section 7). |
| `embed-user-profile.ps1` | Copies `user-profile.json` into `realm-energy.json`. Run it after editing the profile, commit both. |
| `README.md` | This file: the notes and explanations that cannot live in the JSON. |

Settings that are not in the realm file but belong to it live in `infra/docker-compose.yml` (the Keycloak container's environment and the Mailpit service); section 9 lists them.

## Why this README holds the notes: the realm file cannot carry comments

Keycloak can not parse `//` comments. The workaround of putting notes into the JSON as extra keys (`"//verifyEmail": "..."`) was tried too, and those were not parsed as comments either, so every note was removed from `realm-energy.json`. The JSON has no comments at all, and `RealmFileTest` fails the build if a `"//..."` key ever comes back. Everything that used to be a comment lives here, next to the setting it explains, and this file is kept in step with the realm.

The user-profile configuration is the one part of the realm file that is unreadable on its own: Keycloak wants it as a single JSON document escaped into one string, and JSON strings cannot span lines. Its readable form is `user-profile.json`.

## 1. Quick reference

| What | Where |
|---|---|
| Admin console (log in as `admin` with the `KEYCLOAK_ADMIN_PASSWORD` value from `infra/.env`; that account exists only if the database was empty on first boot) | http://localhost:8080/admin/ |
| This realm in the console | http://localhost:8080/admin/master/console/#/energy-tracker |
| Account page of a user | http://localhost:8080/realms/energy-tracker/account |
| Login (authorization endpoint) | `/realms/energy-tracker/protocol/openid-connect/auth` |
| **Registration** | `/realms/energy-tracker/protocol/openid-connect/registrations` (section 2) |
| Token endpoint | `/realms/energy-tracker/protocol/openid-connect/token` |
| Discovery document / signing keys | `/realms/energy-tracker/.well-known/openid-configuration`, `/realms/energy-tracker/protocol/openid-connect/certs` |
| Admin REST API of this realm | `/admin/realms/energy-tracker/...` (needs a token with the right `realm-management` roles, section 8) |
| Health and metrics (management port) | http://localhost:9000/health/ready, http://localhost:9000/metrics |
| Every mail the realm sends (Mailpit) | http://localhost:8025 |

(Paths without a host are on `http://localhost:8080`.)

Seeded accounts (local only; blueprint rule: deterministic ids are never allowed in a real realm):

| Account | Password | Roles | Id |
|---|---|---|---|
| `testuser@example.com` | `Testpassword1` | `user` (through `default-roles-energy-tracker`) | `11111111-…` |
| `admin@example.com` | `Adminpassword1` | `admin` only (deliberately not the default-roles composite, so it does not hold `user`) | `22222222-…` |
| `service-account-user-service-admin` | none (the client authenticates with its secret) | `manage-users`, `view-users` of `realm-management` | generated |

`admin@example.com` is an *application* admin. It holds no `realm-management` role, so Keycloak's own admin API answers 403 to it (verified); the Keycloak console is for infra operators only.

## 2. How users get in

Keycloak owns login, registration, password reset and password change. None of them is a JSON API: each is a **browser flow** on Keycloak's pages. The frontend sends the browser to a Keycloak URL, Keycloak runs the flow, and it sends the browser back to the `redirect_uri` with an authorization `code`, which the frontend exchanges at the token endpoint.

### The registration page (a browser URL, not a REST endpoint)

This is the address the frontend sends the browser to for "Sign up". It returns Keycloak's own HTML registration page, not JSON; nothing is posted to it by the application. The form on that page posts to Keycloak itself.

```
GET http://localhost:8080/realms/energy-tracker/protocol/openid-connect/registrations
      ?client_id=energy-cli
      &response_type=code
      &scope=openid
      &redirect_uri=<the application's redirect URL, URL-encoded; it must match the client's redirectUris>
      &code_challenge=<S256 hash of a random verifier>
      &code_challenge_method=S256
```

It takes the same parameters as login (`.../protocol/openid-connect/auth`); only the page differs. The PKCE parameters are mandatory because the client requires `S256`. The login page's "Register" link reaches the same form by another route, `/realms/energy-tracker/login-actions/registration` (checked: both show the email-only form), which is why a frontend may also just send users to the login page; the `/registrations` URL above skips the login page and opens the form directly. There is no registration call that returns JSON: creating a user without the browser flow means the admin API (`POST /admin/realms/energy-tracker/users`, needs `manage-users`).

Other entry points of the same kind: login is `.../protocol/openid-connect/auth`; "change my password" is the same URL with `&kc_action=UPDATE_PASSWORD` (verified: it asks for a new password after the user signs in, then returns to the application); "forgot password" is the link on the login page.

### The registration flow, step by step (verified)

1. The form asks for the **email only**.
2. Keycloak mails a verification link to that address ("Verify email").
3. Opening the link leads to "Update password", where the user chooses a password, and then the browser is redirected to the application, logged in.
4. That first password also sends one "Update password" notice, like any password change. This is the rough edge of this order: the mail is a password-changed alert, and it reads oddly to someone who has just signed up. It is accepted for now (the alert is worth having on real password changes, and the listener cannot tell a first password from a changed one); the alternative below avoids it.

**Why there is no password field on the registration form. (Zoom does it)** Since Keycloak 26.7, when self-registration and `verifyEmail` are both on, the registration form no longer asks for a password; the user sets it after the email is verified (the "Password Validation" step is still marked required in the flow but renders nothing). Turning `verifyEmail` off brings the fields back (verified: email, password, password again). The upgrade guide describes the change and a switch to restore the old form, "Always set password on register form" on the registration flow's Password Validation step, and calls that option deprecated, kept for backward compatibility ([Keycloak upgrading guide](https://www.keycloak.org/docs/latest/upgrading/index.html)).

The new order is also the safer one, and the switch says so itself: its help text in the admin console reads that, with the option off, the password is set after the email is verified, which "is recommended for security reasons", while with it on the password can be set "before his email is verified" (and that the option is deprecated and might be removed). Concretely (my reading of the reason): with the old form the account exists, with a password the registrant chose, before anyone has proved they own the mailbox, so someone could register another person's address with a password they know (a "pre-account hijacking" attack). Now the password is chosen by whoever opens the link in that mailbox.

The old form is not used here, but it was tested and works; see the alternative below.

### Alternative: the old registration form (tested, not applied)

**What it is.** Setting `always_set_password_on_register_form` to `true` on the registration flow's "Password Validation" step (display name "Always set password on register form" in the admin console).

**What was verified** (Keycloak 26.7.3, the real realm file, Mailpit, run by hand on 2026-10-18; it is not part of `KeycloakRealmIT`):

| Step | Default (this realm) | With the switch on |
|---|---|---|
| Registration form | email only | email, password, password again |
| After submitting | "Email verification" page, "Verify email" mailed | the same page, the same mail |
| Opening the mailed link | "Update password" page, then redirect to the application | redirect to the application right away, logged in (`emailVerified` is true) |
| Mails received in total | "Verify email" and "Update password" | **"Verify email" only** |
| Token before the link is opened | none (no password exists yet) | none: the password grant answers 400 `invalid_grant`, "Account is not fully set up", because the pending verification blocks login |

The password mail disappears because the password is stored by the registration form itself, which is a `REGISTER` event; the `email` listener here mails only `UPDATE_PASSWORD` events (section 4), so nothing is sent. Later password changes and resets still send "Update password" as before (the switch does not touch them).

**What it costs.**
- The option is deprecated and "might be removed in the future" (Keycloak's own help text), so a later upgrade could silently change the form back.
- The account exists with a password the registrant chose before the mailbox is proved to be theirs (the pre-account hijacking situation described above). The realm issues no token for it until the email is verified (verified above). Not tested: what happens when the real owner of an address later tries to register or reset the password after someone pre-registered it.

**How to switch it on.** The way that was tested is the admin API, after the realm is imported:

1. `GET /admin/realms/energy-tracker/authentication/flows/registration%20form/executions`: take the `id` of the execution whose `providerId` is `registration-password-action`.
2. `POST /admin/realms/energy-tracker/authentication/executions/{id}/config` with `{"alias": "registration-password-always-set", "config": {"always_set_password_on_register_form": "true"}}` (answers 201; needs an admin token, e.g. the master realm's).

An API call is lost with `docker compose down -v` (the realm is imported again from the file), so a permanent switch needs either a setup step that repeats the call or the configuration inside `realm-energy.json`. The second is untried: as far as I know it means an `authenticatorConfig` entry plus the complete `authenticationFlows` definitions, because an `authenticationFlows` list replaces Keycloak's defaults. If this alternative is ever adopted, add the switch to the realm, a `KeycloakRealmIT` case for it (form fields, one mail only, no token before verification) and update this section.

### Alternative: a custom event listener that skips the mail for the first password (not built)

**Idea.** Keep Keycloak 26.7's recommended registration order (email, verification, then the password) and remove only the odd mail: switch the built-in `email` listener off for password updates and put a small Keycloak extension (an event-listener SPI, a jar in Keycloak's `providers` directory) in its place. The extension sends the "password changed" mail itself, except for the first password of a self-registered user.

**What was verified about the events** (Keycloak 26.7.3, event logging at debug; the order is pinned by `KeycloakEventOrderIT`, see "Scenarios and the events they fire" below):

- Registration produces, in this order: `REGISTER`, `SEND_VERIFY_EMAIL`, `VERIFY_EMAIL`, `UPDATE_PASSWORD`, `UPDATE_CREDENTIAL`, `LOGIN`.
- The `UPDATE_PASSWORD` event of the first password and the one of a later change carry **the same details** (`credential_type="password"`, `custom_required_action="UPDATE_PASSWORD"`, the same client and redirect). Nothing in the event says "first". That is why the built-in listener cannot be configured to skip it, and why the extension needs state of its own.
- The only visible difference is that the first one shares its authentication session (`authSessionParentId`, `code_id`) with the `REGISTER` and `VERIFY_EMAIL` events of that user. Deciding by that would be the "timing assumption" to avoid: it holds only while the user finishes in the browser that started the registration (a verification link opened later or on another device was not tested).

**How it would be done.**

1. A lifecycle marker on the user, not a timing rule. On `REGISTER` the extension sets an attribute such as `initialPasswordPending=true`: a self-registered user with `verifyEmail` on has no password yet, so the registration is exactly the moment the state "first password still to come" begins. Users created any other way (admin API, import, the seeded users) never get the marker, so every password change of theirs is mailed.
2. On `UPDATE_PASSWORD`: marker present, remove it and send nothing; marker absent, send the notice. A reset of a password the user never had (the marker is still set) is then also treated as a first password, which is right: there was no earlier password to protect.
3. The mail itself is rendered by Keycloak's email template provider, the same way the built-in listener does it (check the 26.7 source), so the text, theme and SMTP settings stay as they are. The realm's `eventsListeners` drops `email` and lists the new listener; the `KC_SPI_EVENTS_LISTENER__EMAIL__INCLUDE_EVENTS` option in `docker-compose.yml` goes away.
4. When in doubt the extension must send the mail: an extra mail is cheap, a missing security alert is not.

**What would have to be tested against this Keycloak version** (this is the hard part, and none of it was tried):

- **Transactions.** Whether a listener's `onEvent` runs inside the request's transaction or after it. If it runs after the commit, a marker write there needs a transaction of its own. The dangerous failure is the marker **not being removed**: the user's next real password change would then be silent. Removing it in the same transaction as the password change would close that, if Keycloak allows it.
- **Order across requests.** The marker must be set before the first `UPDATE_PASSWORD` even when `REGISTER` and the password step are minutes or days apart, and when verification happens in another browser.
- **The marker's visibility.** An attribute that is not declared in the realm's user profile must stay out of reach of users and of `user-service-admin`'s API results, and must survive the profile lock; unmanaged attributes are disabled by default, but how attributes written through the model behave was not checked.
- **Failure paths.** A failing SMTP server, a deleted user, two password changes at once, the listener throwing.

**Effort and cost (my estimate).** The extension itself is small, roughly 150 lines in two classes plus a service file. The work is the rest: a new Maven module built against the Keycloak libraries of the pinned version, getting its jar into the container (a volume or an image built for the project, and `docker-compose.yml` plus the integration test's container both need it), and integration tests for the list above. Afterwards every Keycloak upgrade needs a rebuild and a re-test, because event-listener classes are not a promised-stable interface for extensions. The gain is one fewer, slightly odd, mail at sign-up.

**Cheaper ways to remove the oddness, for comparison:** the old registration form above (a deprecated setting, no code), or reword the mail so it is true for both cases, which is a text override in a custom email theme and needs no code, only the theme that production will want anyway. What the mail says today is verified from the image's own theme file (`theme/base/email/messages/messages_en.properties`): subject `eventUpdatePasswordSubject=Update password`, body `eventUpdatePasswordBody=Your password was changed on {0} from {1}. If this was not you, please contact an administrator.` (`{0}` the time, `{1}` the IP address). A custom email theme that overrides those two keys (and `eventUpdatePasswordBodyHtml`) to something like "Your password was set or changed on ..." fits both cases. Putting such a theme into the container and selecting it as the realm's email theme was not tried.

### What others report (web search, 2026-10-18)

No report of this exact situation was found (a "password changed" mail right after a first password set during registration). What was found:

- Keycloak's own tracker treats the order "verify the email, then set the password" as a security matter: [issue 48206](https://github.com/keycloak/keycloak/issues/48206) (opened and closed in April 2026, labelled for 26.7.0, a follow-up to an earlier partial fix) describes a "pre-account takeover" corner case in registration with email verification, where the browser that started the registration should not continue once the email was verified in another browser. This matches the switch's help text (verified in the admin API) and the 26.7 registration change. The issue does not discuss the password step itself or the mail.
- The upgrading guide's 26.7 section is reported (second-hand, not read verbatim here) to describe the switch "Always set password on register form" as deprecated and kept mostly for backward compatibility; the deprecation is verified from the switch's own help text.
- Older forum threads ask for the email-first registration this version now has by default and were answered with custom registration steps; a thread about a custom `UPDATE_PASSWORD` mail listener not firing on old versions was a bug in the listener of that time.
- For reacting to events in code the community pattern is a custom event listener, for example forwarding events to Kafka ([keycloak-kafka](https://github.com/SnuK87/keycloak-kafka)) or to a webhook ([Skycloak's guide](https://skycloak.io/blog/keycloak-event-streaming-webhooks/)); sending mail through a different provider is done with a custom email sender SPI, which logs a warning that the interface is internal and may change without notice ([forum thread](https://forum.keycloak.org/t/how-to-use-a-custom-emailsenderproviderfactory/4049)). These are tutorials and forum posts, not statements of what companies run in production.

### Scenarios and the events they fire (verified)

Every row below is pinned by `KeycloakEventOrderIT`, which reads the order from Keycloak's own log: the realm's `jboss-logging` listener writes **every** event at debug level, whether or not the realm stores that type, so the log shows what any listener would receive. The test uses a separate Keycloak container with that log level; the realm file is the real one. "Admin" events are what Keycloak records for an admin-API call (an operation plus a resource type); they are separate from user events and have no event type of their own.

| Scenario | Events, in order | Mails | Notes |
|---|---|---|---|
| **Register** (verification link opened in the same browser) | `REGISTER`, `SEND_VERIFY_EMAIL`, `VERIFY_EMAIL`, `UPDATE_PASSWORD`, `UPDATE_CREDENTIAL`, `LOGIN` | "Verify email" (the flow), then "Update password" (the listener) | The first password is an `UPDATE_PASSWORD` event like any other (below). `LOGIN` ends in the redirect to the application. |
| **Log in** (browser) | `LOGIN` | none | |
| **Wrong password** (browser) | `LOGIN_ERROR` | none | The email listener is limited to `UPDATE_PASSWORD`; unrestricted it would mail the owner on every one of these. |
| **Forgot password** (link finished on another device) | `SEND_RESET_PASSWORD`, `UPDATE_PASSWORD`, `UPDATE_CREDENTIAL` | "Reset password" (the flow), then "Update password" (the listener) | No `LOGIN`: there is no session on that device ("Account updated" page). `RESET_PASSWORD` is **not** fired by this flow. Finishing in the same browser was not recorded. |
| **Change password** (`kc_action=UPDATE_PASSWORD`, what the account console and the frontend use) | `UPDATE_PASSWORD`, `UPDATE_CREDENTIAL`, `LOGIN` | "Update password" | The `LOGIN` is the sign-in that was waiting for the action. |
| **Token calls** (password grant, refresh, logout) | `LOGIN`; `REFRESH_TOKEN`; `LOGOUT` | none | Exchanging an authorization code (`CODE_TO_TOKEN`) is what the frontend does after the redirect; the test stops at the redirect and does not record it. |
| **Service account token** (`user-service-admin`) | `CLIENT_LOGIN` (for the service account's own user id) | none | |
| **Backend changes an email** (the email-change feature, `PUT /users/{id}` with `email`) | admin `UPDATE` on `USER` | **none** | Keycloak sends nothing to the old or the new address; the notices of that feature are the backend's. No user event exists for it. |
| **Backend creates / deletes a user** | admin `CREATE` on `USER`; admin `DELETE` on `USER` | none | A user created with a password in the same call has no `UPDATE_PASSWORD`. |
| **Admin API sets a password** (`PUT /users/{id}/reset-password`) | admin `ACTION` on `USER` | none | No `UPDATE_PASSWORD`, so **no "Update password" mail**: a password set through the admin API is silent. |

**Stored or only logged.** The realm stores only the types in `enabledEventTypes` (4.5). Of the registration's six, `REGISTER`, `VERIFY_EMAIL`, `UPDATE_PASSWORD` and `LOGIN` are stored (verified through `GET /events`); `SEND_VERIFY_EMAIL` and `UPDATE_CREDENTIAL` are logged only. The same goes for `SEND_RESET_PASSWORD`, `REFRESH_TOKEN` and `CLIENT_LOGIN`.

**Not recorded** (so nothing here is claimed about them): setting up or removing a second factor (`UPDATE_TOTP`, `REMOVE_TOTP`), resending the verification mail, a login with an unverified address, the brute-force lock's events, and the same-browser variant of a password reset.

**Why the first password sends a second mail.** Two different mechanisms send mail here, which the test separates by taking the listener out of the realm (`eventsListeners` set to `jboss-logging` only), registering, and resetting a password:

- The flows send their own mails: "Verify email" (event `SEND_VERIFY_EMAIL`) and "Reset password" (event `SEND_RESET_PASSWORD`) were still delivered without the listener.
- "Update password" is only the **email event listener's reaction** to an `UPDATE_PASSWORD` event; without the listener it was not sent, and the registration finished with just "Verify email".

The first password is chosen on the "Update password" step that follows the verification, and that step fires the same `UPDATE_PASSWORD` event, with the same details, as a change of an existing password (compared in the event log). The listener therefore cannot tell a first password from a change, and the mail's text ("Your password was changed on ... If this was not you, please contact an administrator.") is written for the second case.

### Where the user ends up after a flow

| Flow | Ending |
|---|---|
| Login, registration, "change password" started from the application and finished in the **same browser** | Keycloak redirects to the application's `redirect_uri` automatically (verified for registration, including the mailed verification link opened in the same browser, and for the password-change action). |
| A mailed link (reset password, verification) opened in **another browser or device** | There is no login session there to continue, so Keycloak ends on its own page, "Account updated" (verified for the reset and for the registration's verification link, which also leaves that device **not signed in**: no session cookie, the user must log in afterwards). The client's Home URL gives that page a **"Back to Application"** link to the frontend (verified; without it the page has no way back). Keycloak does not redirect automatically in this case and no setting for it was found; a custom theme might be a way (not tried). |
| The frontend's "change password" button for a user who is **already signed in** | Keycloak shows the password page straight away (no second login), with a **Cancel** button; "Submit" and "Cancel" both redirect to the application's `redirect_uri` with `kc_action_status=success` or `kc_action_status=cancelled` added (verified; the frontend reads that parameter and exchanges the new code). |

So users are not stranded: the normal path returns to the frontend by itself, and the other-device path ends one click away from it. The Home URL (`rootUrl` / `baseUrl` of `energy-cli`) is the setting that makes the link appear.

### Keycloak's own account page: what it is, and switching it off

`/realms/energy-tracker/account` is Keycloak's **Account Console**, a single-page application. It is not part of the login flows above: the application never sends anyone there. Two built-in clients of the realm are behind it:

- `account-console`: the page's JavaScript. A *public* client with its own authorization-code flow and PKCE (`S256`), redirect address `/realms/energy-tracker/account/*`.
- `account`: the Account REST API (`/realms/energy-tracker/account/`, JSON) that the page calls. A token with the audience `account` (the realm's default roles give it to every user, verified in the access token) can call it.

**Does PKCE keep users out? No** (verified, `KeycloakRealmIT`): PKCE protects an authorization-code exchange from interception; it is not an access rule. The console is just another client of the realm, with a flow of its own, so any user of the realm who types the address, signs in and lets the page run its own PKCE exchange gets in (the test performs exactly that sign-in as `account-console` and receives a code). The same is true of the application: nothing about PKCE is specific to it. The setting that decides is the client's `enabled` flag, which is Keycloak's configuration, not the frontend's and not the backend's; the frontend's part is only to never link there (no page of the flows above does: the "Account updated" page links to the application only).

**What a user can do there today.** The profile is locked (section 7: the Account API refuses name changes and silently ignores an email change, both verified), so editing is not possible. Passwords are not changed through the Account API at all (verified: no password endpoint; the credentials list only points at the `UPDATE_PASSWORD` action, which is the `kc_action` flow above). The API returns the credential list (verified); to my knowledge it also has endpoints for sessions and connected applications, which were not tried here.

**Options, all verified against 26.7.3 with the real realm file:**

| Option | Result |
|---|---|
| Leave it on | The page is reachable by every user; the profile lock keeps it harmless. Pinned by `KeycloakRealmIT` (`accountConsole_anyUserCanSignInToItAtTheMoment`); this is the current state. |
| Disable `account-console` only | Opening the console ends in Keycloak's "We are sorry... Client disabled." error. The Account REST API still answers a token (so the lock is still what protects it). |
| Disable `account-console` **and** `account` | The page ends in the same error and the Account REST API answers **404** to any token. Registration, the password-change action, "forgot password" and token calls all still work (verified), because they run on `energy-cli`, not on these clients. Access tokens still carry `aud: account` and the account roles, which is harmless. |

**How to disable them.**

- In the realm file: **not** with a minimal entry. `{"clientId": "account", "enabled": false}` makes the import fail with `Unable to find composite client role: view-profile`, because the realm's default role refers to the account client's roles. It works with the complete definitions of both clients (as Keycloak exports them: `GET /admin/realms/energy-tracker/partial-export?exportClients=true&exportGroupsAndRoles=true`, drop the ids, set `"enabled": false`) plus the eight account roles (`roles.client.account`). That is a long addition to the realm file and a copy of Keycloak's defaults that must be re-checked on an upgrade.
- After the import, with the admin API (`PUT /admin/realms/energy-tracker/clients/{id}` with `enabled: false`): verified to work (`kcadm.sh` does the same call; not tried), but lost with `docker compose down -v`, so it would need a setup step that repeats it.

**Not applied.** Disabling `account` would turn the four `accountApi_*` tests in `KeycloakRealmIT` (they prove the profile lock through that API) into "answers 404" tests; the lock itself would remain as the second layer. The decision is open: disabling both clients removes the page and the API, at the price of the long realm entry.

### The profile page and the password change from the frontend

The application's own profile page is the frontend's. Display name and email change through the backend. "Change password" is the one thing that goes to Keycloak: the frontend sends the browser to the same authorization endpoint as a login, with `kc_action=UPDATE_PASSWORD` added. For a signed-in user Keycloak shows its password page directly and redirects back as described in the table above. Every password change sends the "Update password" mail (section 4), and fires the events in "Scenarios and the events they fire". The frontend needs nothing else from Keycloak, and the user never reaches the account page.

### Customizing Keycloak's pages (themes), and a different title per password flow

All Keycloak pages the user sees (login, registration, the password pages, the "Account updated" page) and the mails are rendered from **themes**: a folder under `/opt/keycloak/themes/<name>/` with a `login/` part (pages) and an `email/` part (mails), selected per realm (`loginTheme`, `emailTheme`). A theme can extend the built-in one (`parent=keycloak.v2`) and replace only what it changes: CSS and images, texts (`messages/messages_en.properties`), and whole page templates (FreeMarker `.ftl` files). So the pages can be fully restyled and reworded.

**The three password pages have one template and one title key.** Registration (first password), "forgot password" and the frontend's "change password" all render `login-update-password.ftl` with the title `updatePasswordTitle` = "Update password", which is why the title fits only the third. Prototype (run in a throwaway Keycloak with the theme copied into the container; **not in the repository**): a theme `energy` with `parent=keycloak.v2`, a copy of the 26.7.3 `login-update-password.ftl` whose header section reads

```
<#if isAppInitiatedAction??>${msg("energyChangePasswordTitle")}
<#elseif (message.summary)?? && message.summary == msg("updatePasswordMessage")>${msg("energySetPasswordTitle")}
<#else>${msg("energyResetPasswordTitle")}</#if>
```

and three new keys in its `messages_en.properties` ("Change password", "Set your password", "Choose a new password"). Result, all three verified on the real pages:

| Flow | Title shown |
|---|---|
| Frontend's "change password" (`kc_action`) | Change password |
| Registration, after the verification link | Set your password |
| "Forgot password" | Choose a new password |

How the template tells the cases apart, and the limits:
- `isAppInitiatedAction` exists only when the application started the action (`kc_action`): reliable.
- Registration and reset see the **same** data (the template's variables were listed and compared: identical) except for the page message: registration shows `updatePasswordMessage` ("... to activate your account."), reset shows `resetPasswordMessage` ("You need to change your password."). The prototype compares the localized text with `msg(...)`, which works in every language the theme has texts for but depends on those two Keycloak message keys. A user attribute as a marker, as in the listener alternative above, could not be read: the `authenticationSession` object offered to the template could not be inspected (its notes and required actions were not visible).
- A copied template must be compared with Keycloak's again on every upgrade; the mail wording is a separate part of the theme (`email/`), see the listener alternative.

## 3. Realm settings, one by one

### Identity and appearance

| Setting | Value | What it does and why |
|---|---|---|
| `realm` | `energy-tracker` | The realm's name. It appears in every URL (`/realms/energy-tracker`) and in the `iss` claim of every token (`http://localhost:8080/realms/energy-tracker`), which user-service validates. A realm is an isolated space of users, clients and roles. |
| `enabled` | `true` | The realm accepts logins. |
| `displayName` | `Home Energy Tracker` | The realm's plain display name (page titles, the admin console). |
| `displayNameHtml` | `<strong>Home Energy Tracker</strong>` | The HTML version, which Keycloak shows in the header of its pages ("Home Energy Tracker" is the first text on the login, registration and "Account updated" pages); the `<strong>` tag makes it bold. Which of the two appears exactly where was not checked. |
| `sslRequired` | `none` | Whether HTTPS is required: `all`, `external` (default: required except for private addresses) or `none`. `none` allows plain HTTP, for local development only. |

### Registration and login

| Setting | Value | What it does and why |
|---|---|---|
| `registrationAllowed` | `true` | Shows the "Register" link and enables the registration page (section 2). |
| `registrationEmailAsUsername` | `true` | The registration form has no username field: the username is the email. Changing the email through the admin API also changes the username (verified), and an email-only body is enough. |
| `loginWithEmailAllowed` | `true` | Users may sign in with their email as well as their username. With email-as-username both are the same string; the flag is set explicitly so it never depends on that. |
| `duplicateEmailsAllowed` | `false` | One account per address. Verified: the comparison ignores case, and it also blocks an address that belongs to a *disabled* user (creating a second user with it answers 409), so an address is free again only when the old user is deleted, which the deletion saga does. |
| `verifyEmail` | `true` | New users must verify their email before they can use the account: Keycloak mails a link on registration. It also changes the registration form (section 2). With it `false` Keycloak applies an email change at once, unverified and without any mail. |
| `resetPasswordAllowed` | `true` | Shows the "Forgot Password?" link on the login page and enables the reset flow (verified: the link is there, the "Reset password" mail arrives, the new password works and the old one stops working). |
| `editUsernameAllowed` | `false` | Users may not edit their username. With email-as-username this also makes the email read-only for users: a change through the Account API answers 204 and is silently ignored (verified). It is a weak lock, because flipping the flag makes the email editable, so the user profile (section 7) locks it properly; the integration test flips the flag to prove the profile holds. |
| `rememberMe` | `true` | Adds a "Remember me" checkbox to the login page: the session then survives closing the browser (its own timeouts are not set, so Keycloak's defaults for them apply). |

### Brute-force protection

Keycloak counts failed logins per user and locks the account temporarily. The descriptions follow Keycloak's documentation; the tests do not exercise the lockout itself.

| Setting | Value | What it does |
|---|---|---|
| `bruteForceProtected` | `true` | Turns the protection on. |
| `permanentLockout` | `false` | The lock is temporary; with `true` an administrator would have to unlock the account. |
| `failureFactor` | `10` | Failed attempts that trigger a lock. |
| `waitIncrementSeconds` | `60` | How long the first lock lasts; it grows by this much with further lock-triggering failures. |
| `maxFailureWaitSeconds` | `900` | The longest a lock can last (15 minutes). |
| `maxDeltaTimeSeconds` | `43200` | After this long (12 hours) without a failure the counter resets. |
| `quickLoginCheckMilliSeconds` | `1000` | Two failures closer together than this look like a script, not a person, and are treated as a "quick login failure". |
| `minimumQuickLoginWaitSeconds` | `60` | The lock applied after such a quick failure. |

### Password policy

`passwordPolicy` = `length(10) and digits(1) and lowerCase(1) and upperCase(1) and notUsername and notEmail and hashAlgorithm(argon2)`: at least 10 characters, with at least one digit, one lower-case and one upper-case letter; the password may not equal the username or the email; new passwords are hashed with Argon2. Keycloak applies the policy wherever a password is chosen (registration, reset, change); the integration tests' passwords comply with it, but a rejection is not tested.

### Tokens and sessions

| Setting | Value | What it does and why |
|---|---|---|
| `accessTokenLifespan` | `300` | An access token is valid for 5 minutes. The deletion saga's quiet period is based on it: after a user is deleted, tokens issued earlier still work for up to 5 minutes. |
| `accessCodeLifespan` | `60` | The "client login timeout": the application has 60 seconds to exchange the authorization `code` for tokens. |
| `actionTokenGeneratedByUserLifespan` | `300` | How long a link that a *user* triggered stays valid: the verification link and the reset link expire after 5 minutes. That is short for an email; raise it if verification links expire before people read their mail (not verified here). Links an admin triggers through the API have a separate, longer lifespan. |
| `ssoSessionIdleTimeout` | `1800` | A login session ends after 30 minutes without activity. |
| `ssoSessionMaxLifespan` | `36000` | ...and in any case after 10 hours. |
| `offlineSessionIdleTimeout` | `2592000` | Offline sessions (long-lived refresh tokens, available only when a client asks for the optional `offline_access` scope) expire after 30 idle days. |
| `revokeRefreshToken` | `true` | A refresh token can be used once; each refresh returns a new one. |
| `refreshTokenMaxReuse` | `0` | ...and no reuse is tolerated. |

## 4. Events, mail and how they connect

Three different things get called "events" or "mail" here; they are independent of each other, which is what the tests below pin down.

### 4.1 Where mail comes from

| Mail | Produced by | Needs |
|---|---|---|
| Verification and reset mails | The login flows themselves, at the moment the user asks (register, forgot password). | The realm's SMTP server. |
| "Update password" notice | The built-in **`email` event listener**, when a password-change event happens. | The SMTP server, `email` in `eventsListeners`, and the server option that limits the listener (section 9). |
| Mails an admin triggers (`execute-actions-email` on the admin API) | The admin API call. user-service does not use it today. | The SMTP server and `manage-users`. The mail goes to the user's *current* address (verified). |

Verified mails: "Verify email" on registration, "Reset password", "Update password" on every password change (the update action, a reset, and the first password after registration); nothing for a failed login or a successful login.

### 4.2 `smtpServer`: where mail goes

| Field | Value | Meaning |
|---|---|---|
| `host` | `mailpit` | The SMTP server's name: the Mailpit container, reachable by that name on the compose network. |
| `port` | `1025` | Mailpit's SMTP port (a string, as Keycloak stores it). |
| `from` | `noreply@energy-tracker.local` | The sender address. Without one, Keycloak cannot send ("Invalid sender address 'null'", seen in its log). |
| `fromDisplayName` | not set | An optional name shown next to the sender address. |
| `auth` | `false` | No SMTP login (Mailpit accepts any sender). A real server needs `true` plus `user` and `password`, which would be a secret and therefore a `${...}` placeholder like the client secret. |
| `ssl` | `false` | No implicit TLS. |
| `starttls` | `false` | No STARTTLS upgrade. |

If the SMTP server is missing or unreachable, the flow that triggered a *notice* still completes (verified: a password change succeeded with no SMTP configured; Keycloak logged the failed send as an error). A flow that *needs* the mail, such as the verification, cannot continue without it.

### 4.3 Events: what they are

Everything a user does in Keycloak (log in, fail a login, register, change a password, ...) produces an **event** inside the server. Two separate things then happen to it:

1. It is handed to every **listener** in `eventsListeners`. A listener is a plug-in that reacts to events.
2. It may be **stored** in the database (the console's Events tab) for later reading.

| Setting | Value | What it does |
|---|---|---|
| `eventsListeners` | `["jboss-logging", "email"]` | The listeners that receive every event. `jboss-logging` writes events to Keycloak's server log (at the default log level only the failures show: a failed login is logged as a warning, successes are logged at debug level, which is off). `email` sends the owner a mail for some event types (4.4). Keycloak's default is `["jboss-logging"]` alone, so `email` is what we add. |
| `eventsEnabled` | `true` | Whether events are **stored**. It does not switch the listeners off (verified: with it `false` nothing was stored and the "Update password" mail was still sent). |
| `enabledEventTypes` | the list below | Which event types are **stored**. It does not limit the listeners (verified: with `UPDATE_PASSWORD` missing from the list the mail was still sent, and "Update credential" was mailed although it was never on the list). An **empty** list stores every type (verified: three events for one password change). Events outside the list are not stored (verified: a client-credentials login and the code-to-token step were not stored). |
| `eventsExpiration` | `604800` | Stored events are deleted after 7 days (seconds). Events hold user ids and IP addresses, so they are kept only briefly. |
| `adminEventsEnabled` | `true` | Every call to the admin API (and every change made in the admin console) is stored as an **admin event**: what happened, to which resource, and who did it. This is the audit trail for anything the service account or an operator does to users. |
| `adminEventsDetailsEnabled` | `true` | Admin events also keep the **representation**: the data that was sent, such as the new email address (verified: the event for an email change names the service account and carries the data). Useful for audit, but it stores personal data. |

**Open point on admin events:** whether `eventsExpiration` also expires admin events is not verified. The documentation and forum evidence found suggests it does not (admin events have no realm expiration setting and can grow without limit), in which case the email addresses in their representations stay until someone deletes them. Before production: either turn `adminEventsDetailsEnabled` off or purge admin events from the database on a schedule, and account for it in the deletion saga's privacy gaps.

### 4.4 The `email` listener, exactly

For each event the `email` listener checks the event's type against its own **include list**, finds the user, renders the mail template of that type in the realm's theme ("Update password"), and sends it to the user's address through `smtpServer`. Two facts shape the setup:

- Its default include list is broad: verified that left alone it mails the account owner on **every failed login** ("Login error", which lets anyone mail-bomb a victim by trying wrong passwords) and sends **two** mails per password change ("Update credential" and "Update password").
- The realm's `enabledEventTypes` does **not** limit it (4.3). The include list is a *server* option, so it cannot be part of the realm file: `docker-compose.yml` sets `KC_SPI_EVENTS_LISTENER__EMAIL__INCLUDE_EVENTS=UPDATE_PASSWORD` on the Keycloak container, and any other container that runs this realm needs the same. `KeycloakRealmIT` reads that setting from the compose file and fails if it is removed or widened.

### 4.5 Why `enabledEventTypes` lists these eleven

`["LOGIN", "LOGIN_ERROR", "LOGOUT", "REGISTER", "VERIFY_EMAIL", "RESET_PASSWORD", "UPDATE_PASSWORD", "UPDATE_PROFILE", "UPDATE_EMAIL", "UPDATE_TOTP", "REMOVE_TOTP"]`

None of them is needed for any mail (4.3). The list is the **audit trail of account security**, stored for 7 days, and it is a choice, not a requirement:

| Type | Why it is kept |
|---|---|
| `LOGIN`, `LOGOUT` | Who signed in and out, and when. |
| `LOGIN_ERROR` | Failed attempts: the sign of a password-guessing attack, and what the brute-force lock reacts to. |
| `REGISTER`, `VERIFY_EMAIL` | An account was created and its address proved. |
| `RESET_PASSWORD`, `UPDATE_PASSWORD` | A password was reset or changed: the events a hijacked account would show. |
| `UPDATE_PROFILE`, `UPDATE_EMAIL` | Profile or email changes. Both are locked (section 7), so they should never appear; if one does, the lock has failed and the events show it. |
| `UPDATE_TOTP`, `REMOVE_TOTP` | A second factor was added or removed; unused until MFA is offered. |

Types left out (code-to-token exchanges, client logins, token refreshes) are high-volume and say little about the person. Trim or extend the list freely: it changes only what is stored.

Verified in `KeycloakEventOrderIT`: a password reset through "forgot password" fires `SEND_RESET_PASSWORD`, `UPDATE_PASSWORD` and `UPDATE_CREDENTIAL` but never `RESET_PASSWORD`, so `UPDATE_PASSWORD` is the type that records resets here, and `RESET_PASSWORD` in the list currently never matches anything (harmless; it may be fired by a flow that was not exercised). `SEND_VERIFY_EMAIL`, `SEND_RESET_PASSWORD` and `UPDATE_CREDENTIAL` are not in the list, so they are logged but not stored.

### 4.6 How this relates to the service account

The service account (section 8) is not involved in sending mail. Its link to this section is the audit: everything user-service does through the admin API (look up, change, delete a user) is stored as an admin event naming the service account (verified). Mail depends on the SMTP server and the listener; the service account depends on its secret and its roles; the two meet only if user-service one day asks the admin API to send an action mail, which then goes through the same SMTP server.

## 5. Roles

| Setting | Meaning |
|---|---|
| `roles.realm` | The realm roles defined in the file. |
| `offline_access` | Built-in: lets a client request an *offline* token, a long-lived refresh token. Its description `${role_offline-access}` is a placeholder the theme turns into text. |
| `uma_authorization` | Built-in: legacy User-Managed Access, unused here. |
| `user` | The application's customer role; user-service's customer API requires it. |
| `admin` | The application's staff role; user-service's admin API requires it. |
| `default-roles-energy-tracker` | A **composite** role (`composite: true`): holding it means holding what is listed in `composites`: the realm roles `offline_access`, `uma_authorization`, `user` and the `account` client's roles `view-profile` and `manage-account` (the right to use the Account API and page). |
| `description`, `clientRole: false` | A description for the console; `false` says it is a realm role, not a client's role. |
| `defaultRole` | Points at that composite: **every newly created user gets it** (registered or created through the admin API), which is why new users are customers and can use their account page. |

## 6. Clients

A client is an application that talks to Keycloak.

### `energy-cli` (development only)

| Setting | Value | What it does and why |
|---|---|---|
| `clientId`, `name` | `energy-cli`, `Local dev / test-token client` | The identifier used in requests, and a label for the console. |
| `enabled`, `protocol` | `true`, `openid-connect` | Active; speaks OpenID Connect. |
| `rootUrl`, `baseUrl` | `http://localhost:3000` | The application's address (the future frontend). Keycloak's "Back to Application" link, shown when a mailed link is finished on another device, goes here (section 2). |
| `publicClient`, `secret` | `false`, `secret` | A confidential client: it proves itself with a secret. `secret` is a throwaway development value, committed on purpose. |
| `standardFlowEnabled` | `true` | The browser flow (authorization code), the one real users go through. |
| `directAccessGrantsEnabled` | `true` | The password grant: a script posts a username and password and gets a token. This is what `scripts/get-token.ps1` and the curl examples use. Never for a real client. |
| `serviceAccountsEnabled` | `false` | This client has no machine identity of its own. |
| `frontchannelLogout` | `true` | Logout may be signalled to the application through the browser. |
| `fullScopeAllowed` | `true` | Tokens carry every role the user holds (with `false`, only roles mapped to the client's scope). |
| `redirectUris` | `http://localhost:*`, `http://127.0.0.1:*` | Where Keycloak may send the browser after a flow. Anything else is refused. |
| `webOrigins` | `+` | Allows cross-origin requests from every origin in `redirectUris`. |
| `defaultClientScopes` | `basic`, `acr`, `profile`, `email`, `roles`, `web-origins` | The claim sets in every token: `sub` (basic), the authentication level (acr), name fields and `preferred_username` (profile), `email` and `email_verified` (email), `realm_access` / `resource_access` (roles), and `allowed-origins` (web-origins). |
| `optionalClientScopes` | `address`, `phone`, `offline_access` | Added only when the request asks for them. |
| `attributes."pkce.code.challenge.method"` | `S256` | The browser flow must use PKCE with SHA-256, so a stolen authorization code is useless. This is why the registration URL needs `code_challenge`. |
| `protocolMappers` → `user-service-audience` | adds `user-service` to `aud` | Without it `aud` is only `["account"]` and user-service's audience validator rejects every token. `included.client.audience` is the value to add, `access.token.claim` puts it in the access token, `id.token.claim: false` keeps it out of the ID token. |

### `user-service-admin` (the service account for Keycloak's admin API)

| Setting | Value | What it does and why |
|---|---|---|
| `clientId`, `name` | `user-service-admin`, a label | Identifier and console label. |
| `enabled`, `protocol` | `true`, `openid-connect` | |
| `publicClient` | `false` | Confidential: it authenticates with its secret. |
| `secret` | `${USER_SERVICE_ADMIN_CLIENT_SECRET}` | A placeholder. Keycloak substitutes the environment variable of that name while importing (verified: the right secret works, another gets 401). The variable is defined in `infra/.env` (template: `.env.example`) and passed to the container by `docker-compose.yml`; user-service reads the same one. An unset variable leaves the placeholder text as the secret. |
| `standardFlowEnabled`, `directAccessGrantsEnabled`, `implicitFlowEnabled` | `false` | No browser login and no password grant: nobody can sign in *as* this client. |
| `serviceAccountsEnabled` | `true` | Turns on the client-credentials grant: the client posts its id and secret and receives a token for its service-account user, with no person involved (section 8). |
| `fullScopeAllowed` | `true` | The token carries all roles of the service-account user, which is what makes its `realm-management` roles appear. |

## 7. Users and the user profile

### Seeded users

| Field | Meaning |
|---|---|
| `id` | A fixed UUID, for the local realm only. It becomes the token's `sub`, and user-service's primary key. |
| `username`, `email` | The same address (email-as-username). |
| `emailVerified` | `true` skips email verification for the seeded accounts. |
| `enabled` | The account may log in. |
| `firstName`, `lastName` | Names, set by import (a user cannot edit them). |
| `realmRoles` | Realm roles assigned directly. `testuser` gets the composite `default-roles-energy-tracker`; `admin@example.com` gets only `admin`. |
| `credentials` | `type: password`, the `value` (plain text in the file, hashed with the realm's policy at import, development only) and `temporary: false` (no forced change at first login). |
| `serviceAccountClientId`, `clientRoles` | For the service-account user: which client it belongs to and which client roles it holds (section 8). |

### The user profile (the email lock)

The realm's `components` entry for `org.keycloak.userprofile.UserProfileProvider` (`providerId: declarative-user-profile`, `subComponents: {}`) holds the configuration in `config` → `kc.user.profile.config`, an array with **one** string (Keycloak reads only the first element). The readable source is `user-profile.json`; after editing it run `powershell -File infra/keycloak/embed-user-profile.ps1` and commit both files. `RealmFileTest` fails when they differ.

What each part of an attribute means:

| Property | Meaning |
|---|---|
| `name` | The attribute: `username`, `email`, `firstName`, `lastName`. |
| `displayName` | The label shown on forms; `${email}` is a placeholder the theme turns into localized text. |
| `validations` | Checks run when a value is set. `length` (min / max characters); `email` (valid address format); `username-prohibited-characters` and `person-name-prohibited-characters` (reject characters that have no place in a username or a person's name); `up-username-not-idn-homograph` (rejects look-alike Unicode characters in a username, so `pаypal` with a Cyrillic "а" cannot pose as `paypal`). |
| `required` | When a value must be given. `{"roles": ["user"]}` means a user editing their own data must provide it; only `email` is required. `firstName` and `lastName` are **not** required: a user cannot enter them (see `permissions`), and Keycloak would otherwise demand values it forbids. |
| `permissions.view` | Who may see the attribute: `admin` and `user`. |
| `permissions.edit` | Who may change it: **`["admin"]` only, on all four attributes.** This is the lock. |
| `multivalued` | `false`: one value. |
| `groups` | Named groups for arranging attributes on forms; `user-metadata` is Keycloak's default and no attribute uses it. |

Why the lock exists: the deletion saga must be able to refuse a profile change while an account is scheduled for deletion, and Keycloak knows nothing about that state. So users cannot edit their email at all; a later user-service endpoint changes it through the admin API after checking the account's state. Names are locked too (the application keeps its own display name).

What the lock does (all verified, and repeated by `KeycloakRealmIT`):

- Account API: a name change answers 400 and changes nothing; an email-only change answers 204 and changes nothing (the silent ignore of `editUsernameAllowed`), also when that flag is turned on. The tests check the *outcome*, never just the status code.
- The "update profile" action shows the fields disabled and rejects a forced submit.
- `UPDATE_EMAIL`, Keycloak's own verify-before-change action, stays off (enabling it would let users change their email inside Keycloak, where no state can be checked); the test fails if it is turned on while the email is editable.
- The admin API still changes an email (a body with only `email` also moves the username, and the address is lower-cased); a duplicate answers 409 `User exists with same email`, case-insensitively.
- Registration, password reset and password change are unaffected.

## 8. The service account, `realm-management` and its roles

### Why a service account is a user

A client that needs to call an API without a person (user-service calling Keycloak's admin API) uses the **client-credentials grant**: it posts `client_id` and `client_secret` and gets an access token. Keycloak needs a subject for that token, so `serviceAccountsEnabled: true` makes it create a hidden **user** named `service-account-<clientId>`; the token's `sub` is that user's id, and the roles that user holds become the token's roles. Giving the client rights therefore means giving *that user* roles.

That is why the realm file contains a `users` entry with `serviceAccountClientId`: it is the place where those roles are assigned at import. Verified both ways:

| Import | Token of the service account |
|---|---|
| With the entry (as in the file) | Roles: `manage-users` and `view-users` (plus the `query-users` and `query-groups` that `view-users` includes); **no** realm roles, so it holds neither `user` nor `admin`. The admin API answers 200. |
| Without the entry | Keycloak still creates the user, but gives it only the default roles (including the application's `user` role) and no `realm-management` role: the admin API answers 403. |

The entry also gives it exactly the roles it needs and nothing else, in particular no `user` role.

### `realm-management`

Every realm has a built-in client named `realm-management` (it is not in our file, Keycloak creates it). It is not an application: it exists to hold the **roles that guard the realm's admin REST API**. Roles verified on this server: `manage-users`, `view-users`, `query-users`, `query-groups`, `manage-clients`, `view-clients`, `query-clients`, `manage-realm`, `view-realm`, `query-realms`, `manage-events`, `view-events`, `manage-authorization`, `view-authorization`, `manage-identity-providers`, `view-identity-providers`, `manage-organizations`, `view-organizations`, `query-organizations`, `create-client`, `impersonation` and `realm-admin`.

How they work: every admin API request carries a bearer token (a token of this realm or of the master realm). Keycloak looks in the token's `resource_access.realm-management.roles` for the role the endpoint needs. A missing role is a 403; there is no other switch. Verified for the service account: `GET /users`, lookups by email, create, update and delete work; `/clients` and `/events` answer 403; the realm settings come back stripped down (no mail server, no events configuration).

| Role | What it allows | Composite? |
|---|---|---|
| `manage-users` | Create, change and delete users (all verified); by Keycloak's role definitions also their credentials and role mappings (not tested). | no (verified) |
| `view-users` | Read users. | yes: `query-users` + `query-groups` (verified) |
| `realm-admin` | Everything above, all of them. | yes: all the others |

Granting `manage-users` alone is enough for reads (verified: with `view-users` removed, listing users and the exact-email lookup still answered 200). `view-users` is therefore redundant; it is kept to state the intent and in case `manage-users` is ever dropped.

`manage-users` is powerful: the holder can change or delete any user, including their credentials. That is why the secret is an environment variable, the client has no other flow, and the application admin role carries none of this.

### What user-service does with it

Since step 5f of the deletion saga user-service calls exactly two endpoints with this account, both for one user by id, both idempotent (`KeycloakAdminRestClient`; settings `app.keycloak.admin.*`, the secret from `USER_SERVICE_ADMIN_CLIENT_SECRET`):

| Call | Used for | Answers it relies on |
|---|---|---|
| `DELETE /admin/realms/energy-tracker/users/{id}` | The hard-delete job removes the Keycloak user of an account that is `DELETING` | 204 deleted; 404 not there (counted as success) |
| `GET /admin/realms/energy-tracker/users/{id}` | A new registration's address belongs to another account: is that account's user still in Keycloak? | 200 exists; 404 does not |

Verified against this realm file by `KeycloakAdminClientIT`: both answers as listed, a deleted user cannot log in afterwards (the direct grant answers 400 `invalid_grant`) and the address can be registered again at once, and a wrong secret is answered 401 by the token endpoint. Each delete is stored as an admin event naming the service account, which is the audit trail of what user-service removed; lookups are not stored.

## 9. Settings outside the realm file

| Where | Setting | Why |
|---|---|---|
| `docker-compose.yml`, Keycloak | `KC_SPI_EVENTS_LISTENER__EMAIL__INCLUDE_EVENTS=UPDATE_PASSWORD` | Limits the `email` listener (4.4). |
| `docker-compose.yml`, Keycloak | `USER_SERVICE_ADMIN_CLIENT_SECRET=${USER_SERVICE_ADMIN_CLIENT_SECRET}` | Fills the placeholder in the realm while importing (section 6). |
| `docker-compose.yml`, Keycloak | `KC_HOSTNAME=http://localhost:8080` | Pins the `iss` of every token. |
| `docker-compose.yml`, `mailpit` | `axllent/mailpit:v1.31.6`, ports 8025 (UI and API) and 1025 (SMTP) | The mail catcher the realm's `smtpServer` points to. |
| `infra/.env` | `USER_SERVICE_ADMIN_CLIENT_SECRET` | The secret itself (template in `.env.example`; `.env` is gitignored). |

## 10. Not in the realm yet

- A public (authorization code + PKCE) client for the frontend, with its own redirect URIs.
- `auth_time` in the access token (needed if "recent login" is to be checked from the token).
- The admin-events retention question (4.3).
- Production settings: no `energy-cli`, TLS, the admin console not exposed, a real SMTP server with credentials, longer-lived email links if 5 minutes proves too short.
