# Security policy

## Reporting a vulnerability

Report vulnerabilities privately through GitHub: open the repository's
[Security tab](https://github.com/bernhardberger/tvheadend-htsp-kotlin/security) and
choose **Report a vulnerability**. Please don't open a public issue or pull
request for a suspected vulnerability. The maintainer will respond in the
advisory.

A useful report names the affected version, describes what an attacker controls
(for example a hostile server, or a value the application passes in), and
includes a minimal reproduction. Leave real credentials, hostnames and
recordings out of it.

## Supported versions

Only the latest release line receives security fixes. Older lines don't.

## What counts

- Credentials or other secrets reaching outcome values, `toString` output or
  logs. The library promises that error values never carry credentials,
  endpoints or throwables; see
  [what outcome values deliberately omit](docs/public-api.md#what-outcome-values-deliberately-omit).
- Memory or CPU exhaustion from a hostile server's frames that goes beyond the
  documented budgets and frame-size limit.
- Flaws in frame, message or authentication parsing that let a server or a
  network attacker bypass a check or corrupt library state.

## What doesn't count

- Vulnerabilities in Tvheadend itself. Report those to the
  [Tvheadend project](https://github.com/tvheadend/tvheadend).
- Behaviour of an application built on this library, such as how it stores the
  credentials it passes in.
- Ordinary bugs that expose nothing. Use the
  [bug report form](https://github.com/bernhardberger/tvheadend-htsp-kotlin/issues/new?template=bug_report.yml).
