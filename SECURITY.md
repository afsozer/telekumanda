# Security Policy

## Security maintainer

Telekumanda has a single security maintainer, Alpaslan Fatih Sözer
(GitHub [@afsozer](https://github.com/afsozer)), who receives every report,
decides on fixes and publishes security advisories.

## Reporting a vulnerability

Please do not open a public issue for a security problem. Report it privately
through GitHub's private vulnerability reporting: open the repository's
**Security** tab and choose **Report a vulnerability**, or go directly to
<https://github.com/afsozer/telekumanda/security/advisories/new>.

If you cannot use GitHub, email bilgi@avfatihsozer.com with a subject line
that starts with `[SECURITY] telekumanda`.

A useful report names the affected component (bridge, Android app or web UI)
and version or commit, lists the steps to reproduce the problem and explains
what an attacker could achieve with it. A working exploit is not required; a
clear description is enough.

## What counts as a vulnerability

The bridge is a trusted local service: anyone holding a valid token or paired
device key can, by design, run agent CLIs and read and write files on the host.
Doing that with valid credentials is expected behaviour, not a vulnerability.
What matters is everything that lets someone get there without them, or go
further than they should. For example:

- bypassing token or device-key authentication, or pairing a device without
  the owner's approval;
- leaking a token, device key or session content (to logs, URLs, the web UI's
  DOM, notifications or other apps on the phone);
- reading or writing files outside the configured workspace roots;
- cross-site attacks against the web UI served at `/ui`;
- tampering with OTA update metadata or packages so that a device installs
  something the owner did not publish.

## What to expect

- Your report is acknowledged within 3 business days.
- An initial assessment, saying whether the issue is accepted and how severe it
  is, follows within 10 business days.
- Accepted issues are fixed or mitigated as soon as practical, with a target of
  30 days for critical and high-severity issues.
- Disclosure is coordinated: once a fix is released, a GitHub Security Advisory
  is published and, where the issue qualifies, a CVE is requested through
  GitHub. Reporters are credited unless they ask not to be. If a fix takes
  longer, the advisory is published no later than 90 days after the report
  unless we agree on a different date.

## Supported versions

Security fixes are made on the latest version of the bridge, the Android app
and the web UI only.

## Scope

In scope is the code in this repository: the Node.js bridge (`bridge/`), the
Android app (`android/`) and the browser UI (`web/`).

Out of scope:

- vulnerabilities in the agent CLIs the bridge drives (Claude Code, Codex,
  OpenCode, Antigravity), which should be reported to their vendors;
- installations that expose the bridge to the public internet, contrary to the
  README's guidance to keep it on a private network;
- publicly known vulnerabilities in third-party dependencies, unless
  Telekumanda uses the dependency in a way that makes them exploitable;
- installations run by other people.

## Safe harbour

Good-faith research that follows this policy is welcome. Test only against your
own installation, do not access data that is not yours, and do not degrade
services that others rely on. Research carried out this way will not be the
subject of legal action by the maintainer.

## Türkçe özet

Güvenlik açıklarını herkese açık issue olarak değil, deponun **Security**
sekmesindeki **Report a vulnerability** bağlantısıyla ya da konu satırı
`[SECURITY] telekumanda` ile başlayan bir e-postayla bilgi@avfatihsozer.com
adresine bildirin. Geçerli token ile komut çalıştırmak tasarım gereğidir;
kimlik doğrulamayı aşmak, token ya da oturum içeriği sızdırmak, çalışma alanı
dışına çıkmak ve OTA güncellemesini kurcalamak açık sayılır. Bildirimler 3 iş
günü içinde yanıtlanır; düzeltme yayımlandıktan sonra GitHub güvenlik duyurusu
çıkar ve uygunsa CVE istenir. Testleri yalnızca kendi kurulumunuzda yapın.
