# Maven Central publication

Coordinates for the first release are:

```text
eu.pedrobatista:fastblake:0.1.0
```

The `eu.pedrobatista` namespace is registered in Sonatype Central Portal.
Central Portal accepts a signed component bundle. The repository contains no
credentials: the release workflow reads signing material and Central Portal
credentials only from the protected GitHub `release` environment.

The checked-in workflow is [`.github/workflows/release.yml`](../../.github/workflows/release.yml).
It runs for an intentional `v*` tag, verifies the project version, runs the
release checks, signs the bundle, uploads an `AUTOMATIC` deployment, and waits
for Central to report `PUBLISHED`. The protected GitHub environment is the
approval gate before that irreversible operation.

The workflow follows Sonatype’s [Publisher API](https://central.sonatype.org/publish/publish-portal-api/);
the [Central Portal guide](https://central.sonatype.org/publish/publish-portal-guide/)
describes validation and the publication immutability policy.

## Local release validation

Run the complete local gate first:

```bash
./gradlew releaseCheck
```

This runs official-vector conformance, the jar boundary check, Javadoc/POM
generation, and the downstream consumer smoke test. It does not require a
signing key.

## Signing setup

Create a primary OpenPGP key locally, keep its backup offline, and use a
dedicated signing subkey in CI. GnuPG commands (run outside the repository or
write the output to a protected location) are:

```bash
# Create a certifying primary key. Choose RSA 4096 and a suitable expiry.
gpg --quick-generate-key 'FastBlake Release <you@example.com>' rsa4096 cert 2y

# Replace PRIMARY_FINGERPRINT with the value printed by the command above.
gpg --quick-add-key PRIMARY_FINGERPRINT rsa4096 sign 2y
gpg --list-secret-keys --keyid-format LONG PRIMARY_FINGERPRINT

# Keep this complete backup offline (encrypted storage, password manager, etc.).
gpg --armor --export-secret-keys PRIMARY_FINGERPRINT > fastblake-primary-backup.asc

# Export only the signing subkey for GitHub Actions. The trailing ! selects
# exactly that subkey; do not upload the primary secret key to GitHub.
gpg --armor --export-secret-subkeys 'SIGNING_SUBKEY_FINGERPRINT!' > fastblake-signing-subkey.asc

# Publish the public certificate so Central can discover it for verification.
gpg --armor --export PRIMARY_FINGERPRINT > fastblake-public.asc
gpg --keyserver hkps://keys.openpgp.org --send-keys PRIMARY_FINGERPRINT
```

Complete any email verification requested by `keys.openpgp.org`. Record the
primary and signing-subkey fingerprints, and retain the revocation certificate
created by GnuPG with the offline backup. Never commit any `.asc` private-key
file.

For a local signing smoke test, the Gradle signing plugin accepts the armored
subkey through Gradle properties:

```bash
export ORG_GRADLE_PROJECT_signingInMemoryKey="$(< fastblake-signing-subkey.asc)"
export ORG_GRADLE_PROJECT_signingInMemoryKeyPassword='your-key-password'
export ORG_GRADLE_PROJECT_signingKeyId='96167B95'
./gradlew centralBundle -Prelease
```

The signing identity must be discoverable by Central. Do not put either value
in `gradle.properties` committed to Git or in build logs.

## Create the upload bundle

Pass `-Prelease` so missing signing configuration fails instead of silently
producing an unsigned archive:

```bash
./gradlew centralBundle -Prelease
```

The result is:

```text
build/central/fastblake-0.1.0-central-bundle.zip
```

The archive contains the Maven coordinate directory, signed binary/sources/
Javadoc artifacts, POM, Gradle module metadata, and SHA-256/SHA-512 checksums.
Inspect it locally before uploading:

```bash
unzip -l build/central/fastblake-0.1.0-central-bundle.zip
```

## Configure GitHub secrets and environment

1. In the repository, open **Settings → Environments**, create an environment
   named `release`, and add required reviewers (recommended) and any branch/tag
   restrictions your team needs.
2. Add these **environment secrets** (not ordinary repository secrets):
   `GPG_PRIVATE_KEY` = the complete contents of
   `fastblake-signing-subkey.asc`; `GPG_PASSPHRASE` = its passphrase;
   `GPG_KEY_ID` = the final 8 hexadecimal characters of the signing subkey's
   key ID (for this key: `96167B95`);
   `CENTRAL_USERNAME` and `CENTRAL_PASSWORD` = the two values from a Central
   Portal **User Token**. Do not paste the base64 token itself into the source
   tree.
3. In Central Portal, open the account page and generate a user token. The API
   uses it as `base64(username:password)` in an `Authorization: Bearer ...`
   header; the workflow constructs that value in memory.
4. Protect the `main` branch and allow releases only from reviewed tags. The
   workflow has `contents: read` permission and cannot write repository code.

## Release procedure

Run the local gate, commit the version, and push an annotated tag:

```bash
./gradlew releaseCheck
git tag -a v0.1.0 -m 'FastBlake 0.1.0'
git push origin v0.1.0
```

The `Release` action pauses at the protected environment if reviewers are
configured. It then uploads an automatic deployment, polls its status, and
fails if Central validation or publication fails. Once published, verify
`eu.pedrobatista:fastblake:0.1.0` on Maven Central. For a later release, update
`version` in `build.gradle` and use the matching `v<version>` tag.

For an extra-conservative first release, change `publishingType=AUTOMATIC` to
`publishingType=USER_MANAGED` in the workflow. Central will then stop at
`VALIDATED`; publish it manually in the Portal after inspecting the validation
results.

## Manual Portal fallback

1. Open the Central Portal Publish Component page.
2. Upload the generated bundle and use `eu.pedrobatista:fastblake:0.1.0` as
   the deployment name.
3. Wait for validation and resolve any reported metadata/signature errors.
4. Publish the validated deployment.
5. Verify the artifact on Maven Central before announcing it.

Central releases cannot normally be modified or removed after publication. Keep
the `release` environment protected with required reviewers, and use
`USER_MANAGED` for a manual first-release gate if the team prefers that policy.
