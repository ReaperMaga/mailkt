# Outlook / Microsoft 365

```kotlin
implementation("dev.reapermaga.mailkt:outlook:0.1.0")
```

The Outlook module uses [MSAL4J](https://github.com/AzureAD/microsoft-authentication-library-for-java) as a confidential client for Exchange Online IMAP and SMTP.

## Register the app in Microsoft Entra

1. In the [Entra admin center](https://entra.microsoft.com/), go to **App registrations → New registration**.
2. Choose the supported account types. Multi-tenant plus personal accounts matches the default `common` authority.
3. Add a **Web** platform redirect URI for each exact callback route.
4. Under **Certificates & secrets**, create a client secret for the backend.
5. Under **API permissions**, add the delegated **Office 365 Exchange Online** permissions `IMAP.AccessAsUser.All` and, if you send mail, `SMTP.Send`.

::: info Tenant settings
IMAP and authenticated SMTP must be enabled for the mailbox in Exchange Online. Organizations often disable SMTP AUTH by default.
:::

## Configure

<<< @/../examples/src/main/kotlin/dev/reapermaga/mailkt/examples/Authorization.kt#outlook-setup{kotlin}

| Property | Default | Description |
|---|---|---|
| `clientId` | | Application (client) ID. |
| `clientSecret` | | Client secret, held by the backend only. |
| `allowedRedirectUris` | | Exact callback URIs registered in Entra. |
| `authority` | `https://login.microsoftonline.com/common/` | Use a tenant-specific authority to restrict sign-in to one organization. Must be HTTPS. |
| `enableSending` | `false` | Adds the `SMTP.Send` scope and the SMTP endpoint. Without it `mailbox.outbox` is `null`. |

Requested scopes are `https://outlook.office.com/IMAP.AccessAsUser.All`, plus `https://outlook.office.com/SMTP.Send` when sending is enabled. MSAL4J adds `offline_access` for refresh tokens.

## Authorize and open

<<< @/../examples/src/main/kotlin/dev/reapermaga/mailkt/examples/Authorization.kt#outlook-flow{kotlin}

In a real backend, the three steps are split across the begin endpoint and the callback route exactly as for [Gmail](./gmail).

## Endpoints

| Protocol | Host | Port | Security |
|---|---|---|---|
| IMAP | `outlook.office365.com` | 993 | Implicit TLS, XOAUTH2 |
| SMTP | `smtp.office365.com` | 587 | STARTTLS, XOAUTH2 |

Exchange Online stores a copy of sent mail itself, so `send(saveToSent = true)` never appends a duplicate to Sent.
