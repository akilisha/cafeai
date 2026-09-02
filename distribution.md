# Publishing to Maven Central

CafeAI publishes straight from Gradle to the **Sonatype Central Portal**
(`central.sonatype.com`). No Maven, no hand-written POMs.

The [vanniktech `maven-publish` plugin](https://vanniktech.github.io/gradle-maven-publish-plugin/)
generates the POM **and** the Gradle Module Metadata from each module's real
dependency graph, builds the sources + javadoc jars, signs everything with GPG,
and uploads the bundle. Because the POM is generated, transitive dependencies
resolve correctly for downstream consumers — that was the bug in the old
`pom-dist.xml` + `maven-shade-plugin` flow (shade stripped every compile
dependency out of the published POM).

Published coordinates: `com.akilisha.oss:<module>:<version>` (namespace verified
on the Central Portal).

---

## One-time machine setup

Two things are needed: **Central Portal credentials** (so Gradle can upload) and a
**GPG signing key** (Maven Central rejects unsigned artifacts). Both are read by
Gradle from a properties file.

### Where the properties file goes

Gradle loads `gradle.properties` from exactly two places:

| Path | Use |
|---|---|
| `D:\Users\<you>\.gradle\gradle.properties` | **Recommended.** Machine-global, never in any repo. |
| `D:\Projects\java\cafeai\gradle.properties` | Project root. Would have to be git-ignored. |

`<project>\.gradle\gradle.properties` is **not** loaded — that `.gradle\` folder is
Gradle's build cache. Anything you put there is silently ignored.

### 1. Central Portal credentials

Copy the token from `settings.xml` (the `<username>` / `<password>` under
`<server id="central">`) into your `gradle.properties` under these names:

```properties
mavenCentralUsername=<the settings.xml username>
mavenCentralPassword=<the settings.xml password>
```

### 2. GPG signing key

You already have the key — releases `0.1.0` / `0.1.1` were signed with it. It's
`71337946596FE931DCA600DD8DE042C053D6492A` (short id **`53D6492A`**, uid
*"Jarred Web … maven central repo"*). The old Maven flow got its passphrase from
the GPG agent; Gradle needs it spelled out.

**Method A — keyring file (simplest here; you're already half-configured for it).**
Your `~\.gradle\gradle.properties` has `signing.keyId` / `signing.password` /
`signing.secretKeyRingFile` lines pointing at a **key that no longer exists**
(`503641A8`) and a **stale keyring file**. Replace all three:

```powershell
# export just the release key into its own keyring file
gpg --export-secret-keys 71337946596FE931DCA600DD8DE042C053D6492A > $HOME\.gnupg\cafeai-secring.gpg
```

```properties
signing.keyId=53D6492A
signing.password=<passphrase for key 53D6492A>
signing.secretKeyRingFile=D:/Users/<you>/.gnupg/cafeai-secring.gpg
```

`signing.keyId` is the **last 8 hex digits** of the fingerprint. Use forward
slashes (or `\\`) in the path. Also delete the leftover `user=` / `pwd=` lines
(old OSSRH, unused).

**Method B — in-memory key (use on CI, or to avoid the keyring file).**
`signingInMemoryKey` is the ASCII-armored *private* key itself;
`signingInMemoryKeyPassword` is its passphrase.

```powershell
# prints a multi-line PGP block — this is the value of signingInMemoryKey
gpg --armor --export-secret-keys 71337946596FE931DCA600DD8DE042C053D6492A
```

In a properties file it must be **one line** with real newlines written as `\n`.
That's fiddly, so on CI pass it via env var instead (real newlines are fine):

```powershell
$env:ORG_GRADLE_PROJECT_mavenCentralUsername   = '<token username>'
$env:ORG_GRADLE_PROJECT_mavenCentralPassword   = '<token password>'
$env:ORG_GRADLE_PROJECT_signingInMemoryKey     = (gpg --armor --export-secret-keys 53D6492A | Out-String)
$env:ORG_GRADLE_PROJECT_signingInMemoryKeyPassword = '<passphrase>'
```

Pick **one** of A or B, not both.

### 3. Publish your public key

Central verifies the signature against a keyserver. Once:

```powershell
gpg --keyserver keys.openpgp.org --send-keys 53D6492A
```

---

## Cutting a release

1. **Bump the version** in `build.gradle` (the `subprojects { version = '…' }`
   line). All modules share it. Central versions are immutable — never re-publish
   a number.

2. **Build and test.**

   ```bash
   ./gradlew clean build
   ```

3. **Smoke-test the artifacts locally** (optional).

   ```bash
   ./gradlew publishToMavenLocal
   ```

   Inspect `~/.m2/repository/com/akilisha/oss/cafeai-core/<version>/` — you want
   `.jar` (~200 KB, *not* a fat jar), `-sources.jar`, `-javadoc.jar`, `.pom`,
   `.module`, and a `.asc` next to each.

4. **Upload every module.**

   ```bash
   ./gradlew publishToMavenCentral
   ```

   Runs for all nine opted-in modules in one invocation; the plugin stages them
   together and uploads a bundle. Inter-module dependencies
   (`com.akilisha.oss:cafeai-core:<version>`, …) are wired automatically, with no
   ordering requirement.

5. **Release it.** Open <https://central.sonatype.com/publishing>. You'll see the
   uploaded deployment(s) in *Validated* state — check the contents, then hit
   **Publish**. Maven Central picks it up in ~15–30 min; `repo1` sync follows
   within a couple of hours.

   To skip the manual gate, run `./gradlew publishAndReleaseToMavenCentral` (or
   change `publishToMavenCentral()` → `publishToMavenCentral(true)` in
   `gradle/maven-central.gradle`) and it releases itself once validation passes.

---

## What each module opts into

A module that ships to Central has **one line** in its `build.gradle`:

```groovy
description = 'What this module is.'          // becomes <description> in the POM

apply from: "$rootDir/gradle/maven-central.gradle"
```

`gradle/maven-central.gradle` holds everything shared: the plugin, signing, and
the common POM fields (license, developer, SCM, project URL). `cafeai-examples`
does **not** apply it — it's a runnable sample, not a library.

Adding a new library module = add those two lines. Nothing else.

---

## How versions resolve for consumers

`cafeai-core` depends on Helidon and LangChain4j through their BOMs
(`implementation platform("io.helidon:helidon-bom:…")`). The generated metadata
carries this through:

- **`.pom`** — BOMs appear under `<dependencyManagement>` as `<scope>import</scope>`;
  the individual artifacts are version-less `<dependency>` entries. Maven
  consumers resolve the versions from the imported BOMs.
- **`.module`** — the BOMs are included as `platform` dependencies with
  `endorseStrictVersions`. Gradle consumers resolve through them.

Either way the consumer gets a complete, resolvable graph without listing a
single Helidon coordinate themselves.
