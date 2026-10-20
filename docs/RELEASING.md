# Releasing MeshCore TAK Bridge

How signed releases are made. Debug builds from the CI workflow are for testing; a release is the
signed APK people install and keep updating.

Every release must be signed with the same key. Android installs an update only if it carries the
same signature as the copy already on the phone, so a lost key means no more updates, and a leaked
one would let someone else sign an "update". The key is created once and kept as described here.

## Who holds what

- **Key holder: Bud (W0RMT), decided 14 October 2026.** He created the release key and keeps the
  backups described below.
- **The key's certificate** has SHA-256 fingerprint `f59a5a05f3924b8e0dd2cc24aeb4f11405875d966d68bc25c2bbae9d38a7e554`
  (`F5:9A:5A:05:F3:92:4B:8E:0D:D2:CC:24:AE:B4:F1:14:05:87:5D:96:6D:68:BC:25:C2:BB:AE:9D:38:A7:E5:54` as `keytool` shows it). Every release must show this fingerprint.
- **GitHub** keeps a copy as secrets in the repository's `release` environment. Only a release
  workflow run that a maintainer approves can use them, and GitHub never shows them again.
- The key, its password and its base64 copy are never committed, attached to an issue, or pasted
  into a chat, including with Claude. `.gitignore` excludes `*.jks` and `*.keystore`.

## One-time setup

### 1. Create the key

On your own computer, with a JDK installed (`keytool -help` should work; on Debian, Ubuntu or
Pop!_OS, `sudo apt install openjdk-17-jdk-headless`). Work in a folder outside any git repository.

```bash
mkdir -p ~/takbridge-release && cd ~/takbridge-release
keytool -genkeypair -v \
  -keystore meshcore-tak-bridge-release.p12 -storetype PKCS12 \
  -alias takbridge -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=MeshCore TAK Bridge, O=Colorado Mesh, L=Denver, ST=Colorado, C=US"
```

`keytool` asks for a password twice. Use a long, random one from a password manager. A PKCS12
keystore uses that one password for both the store and the key. The key is valid for 10,000 days
(about 27 years).

Check it and note the certificate's SHA-256 fingerprint:

```bash
keytool -list -v -keystore meshcore-tak-bridge-release.p12 | grep -E "Alias name|Valid from|SHA256:"
```

### 2. Back it up, twice, before going further

- **Password manager:** a new entry holding the password, the alias (`takbridge`), the
  fingerprint, and the `.p12` file as an attachment.
- **Offline copy:** the `.p12` file on a USB drive or similar, kept somewhere safe. Write the
  password down separately, not on the drive.
- Check the backup by listing it with the password: `keytool -list -keystore <backup copy>`.
- Optionally, give a second Colorado Mesh maintainer access to the password manager entry, so the
  project does not depend on one person.

### 3. Create the `release` environment

On GitHub: the repository's **Settings → Environments → New environment**, named `release`.

- **Required reviewers:** add yourself (and any other maintainer who may approve releases). Leave
  "Prevent self-review" off if you will approve your own releases, and press **Save protection
  rules**.
- **Deployment branches and tags:** choose "Selected branches and tags", then **Add deployment
  branch or tag rule** with **Ref type: Tag** (the dialog defaults to Branch) and pattern `v*`, so
  only version tags can use the key. With a branch rule instead, the run fails with "Tag v0.1.0 is
  not allowed to deploy to release due to environment protection rules".

If a release run starts without waiting for approval, the environment's reviewers are not set, or
the job is using a `release` environment GitHub created by itself because none existed.

### 4. Add the three secrets to the environment

Under the `release` environment, **Environment secrets → Add environment secret**:

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | The `.p12` file as one line of base64 (below) |
| `RELEASE_KEYSTORE_PASSWORD` | The password |
| `RELEASE_KEY_ALIAS` | `takbridge` |

Make the base64 copy, paste it in, then delete it:

```bash
cd ~/takbridge-release
base64 -w0 meshcore-tak-bridge-release.p12 > keystore.b64
# Open keystore.b64, copy its single line into RELEASE_KEYSTORE_BASE64, save, then:
rm keystore.b64
```

With the GitHub CLI you can skip the copy and paste:

```bash
base64 -w0 meshcore-tak-bridge-release.p12 | gh secret set RELEASE_KEYSTORE_BASE64 --env release --repo Colorado-Mesh/meshcore-tak-bridge
gh secret set RELEASE_KEYSTORE_PASSWORD --env release --repo Colorado-Mesh/meshcore-tak-bridge   # prompts for it
gh secret set RELEASE_KEY_ALIAS --env release --repo Colorado-Mesh/meshcore-tak-bridge --body takbridge
```

The secrets must be **environment** secrets on `release`, not repository secrets. If one is missing, the run stops at "Unpack the signing key" and names it.

## Making a release

1. **Pick the build.** A CI build of `main` that has run on devices without problems, ideally
   overnight. Name it as the release candidate at the top of [STATUS.md](STATUS.md).
2. **Set the version** in `app/build.gradle.kts`, by pull request: `versionName` is what people see
   (`0.1.0`, `0.1.1`, `0.2.0`); `versionCode` must go up by at least 1 for every release, or phones
   will not take it as an update. Check the last release below for the numbers to go up from.
3. **Tag the merged commit and push the tag.** The tag must be `v` plus the `versionName` exactly:
   ```bash
   git checkout main && git pull
   git tag -a v0.1.1 -m "MeshCore TAK Bridge 0.1.1"
   git push origin v0.1.1
   ```
4. **Approve the run.** The **Release** workflow starts and waits for approval to use the
   `release` environment. On the repository's Actions tab, open the run, choose **Review
   deployments**, tick `release`, and approve.
5. **Check the draft.** The workflow tests, builds and signs the app, checks the signature, and
   makes a **draft** release with `meshcore-tak-bridge-<version>.apk`, its SHA-256 file, and notes
   that include the signing certificate's fingerprint. The run's summary page shows the same
   fingerprint: it must match the one noted in step 1.
6. **Install it from the draft** on a test device. It installs over the previous release and keeps
   its settings. If a debug build is installed, uninstall that first, since debug builds are signed
   with a different key.
7. **Publish** the draft on GitHub (edit the notes if you like). Then record the release in
   [STATUS.md](STATUS.md).

Releases so far:

| Version | Version code | Published | Tag on |
| --- | --- | --- | --- |
| [0.1.2](https://github.com/Colorado-Mesh/meshcore-tak-bridge/releases/tag/v0.1.2) | 3 | 20 October 2026 | `43a7d1a` |
| [0.1.1](https://github.com/Colorado-Mesh/meshcore-tak-bridge/releases/tag/v0.1.1) | 2 | 17 October 2026 | `2b45e8d` |
| [0.1.0](https://github.com/Colorado-Mesh/meshcore-tak-bridge/releases/tag/v0.1.0) | 1 | 14 October 2026 | `48a2501` |

All are signed with the certificate above. The next release needs `versionCode = 4` or more.

The certificate fingerprint is also in the README, so anyone can check that a download is genuine
with `apksigner verify --print-certs`.

## If something goes wrong

- **The run fails before the draft exists because of a setting** (a missing secret, the
  environment's rules): fix the setting, then open the run and choose **Re-run jobs → Re-run failed
  jobs**. The tag can stay.
- **The run fails because of the code or the workflow** (a tag that does not match `versionName`,
  a failing test, a workflow fix): a re-run uses the workflow and code as they were at the tag, so
  fix it by pull request, then move the tag to the new `main` and push it again:
  ```bash
  git push origin :refs/tags/v0.1.0   # delete the tag on GitHub
  git tag -d v0.1.0                   # and locally
  # fix, merge, pull, then tag and push again (step 3)
  ```
- **A draft is wrong:** delete the draft release on GitHub (its trash icon on the Releases page, or
  `gh release delete <tag>`), delete the tag as above, and start again. A draft has not been
  published, so nobody has it.
- **A published release is wrong:** do not reuse its version. Fix it and release the next version
  (`0.1.1`, with a higher `versionCode`).

## If the key is lost or leaked

- **Lost** (every copy): there can be no further updates to installs signed with it. A new key
  means users uninstall and reinstall. This is why there are two backups.
- **Leaked:** remove the secrets from GitHub at once, warn users through the repository and
  Colorado Mesh channels, and make a new key. Android can move an app to a new key ("key
  rotation", APK Signature Scheme v3) only with a release signed by the old key too, so ask before
  doing anything else.
