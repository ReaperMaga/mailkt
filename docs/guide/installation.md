# Installation

## Requirements

- **JDK 25** or newer.
- **Kotlin** with coroutines. `kotlinx-coroutines-core` is exposed as an `api` dependency of `core`.
- An **SLF4J 2** backend of your choice. MailKT only depends on the SLF4J API.

## Gradle

MailKT is published to the Averix Maven repository.

::: code-group

```kotlin [build.gradle.kts]
repositories {
    mavenCentral()
    maven {
        name = "Averix"
        url = uri("https://repo.averix.tech/repository/maven-releases/")
    }
}

dependencies {
    implementation("dev.reapermaga.mailkt:core:0.1.0")
    implementation("dev.reapermaga.mailkt:gmail:0.1.0")   // Gmail
    implementation("dev.reapermaga.mailkt:outlook:0.1.0") // Outlook / Microsoft 365

    runtimeOnly("org.slf4j:slf4j-simple:2.0.17") // or logback, log4j2, ...
}
```

```groovy [build.gradle]
repositories {
    mavenCentral()
    maven {
        name = 'Averix'
        url = 'https://repo.averix.tech/repository/maven-releases/'
    }
}

dependencies {
    implementation 'dev.reapermaga.mailkt:core:0.1.0'
    implementation 'dev.reapermaga.mailkt:gmail:0.1.0'
    implementation 'dev.reapermaga.mailkt:outlook:0.1.0'

    runtimeOnly 'org.slf4j:slf4j-simple:2.0.17'
}
```

```xml [pom.xml]
<repositories>
  <repository>
    <id>averix</id>
    <url>https://repo.averix.tech/repository/maven-releases/</url>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>dev.reapermaga.mailkt</groupId>
    <artifactId>core</artifactId>
    <version>0.1.0</version>
  </dependency>
  <dependency>
    <groupId>dev.reapermaga.mailkt</groupId>
    <artifactId>gmail</artifactId>
    <version>0.1.0</version>
  </dependency>
</dependencies>
```

:::

The provider modules depend on `core` transitively, so you only add the providers you use. Declaring `core` explicitly is still recommended because you will reference its types directly.

## Packages

| Package | Contents |
|---|---|
| `dev.reapermaga.mailkt.client` | `Mailbox`, capabilities (`Folders`, `Messages`, `Conversations`, `Outbox`), `ConnectionPolicy`, `MailboxOptions`, `MailboxRegistry`, `TokenStore`, `AuthorizationSessionStore`. |
| `dev.reapermaga.mailkt.model` | Immutable models: envelopes, structures, checkpoints, drafts, states and `MailException`. |
| `dev.reapermaga.mailkt.gmail` | `Gmail`, `GmailConfig`. |
| `dev.reapermaga.mailkt.outlook` | `Outlook`, `OutlookConfig`. |

Anything under `dev.reapermaga.mailkt.internal` is not public API and may change without notice.

## Building from source

::: code-group

```bash [macOS / Linux]
./gradlew build
```

```powershell [Windows]
./gradlew.bat build
```

:::

The build runs architecture checks (no Jakarta or Angus types in public packages) and the full test suite on JDK 25.
