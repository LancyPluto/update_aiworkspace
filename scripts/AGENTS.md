# Local development scripts

## Scope

These rules apply to files under `scripts/`.

## Startup contract

- Startup scripts must expose the selected checkout, Git branch, runtime mode, dependency endpoints, and health-check result. A developer must be able to identify a stalled dependency without opening source code.
- Do not silently stop, replace, or reconfigure existing containers or host processes. Detect port conflicts and print an actionable command.
- Keep host-process and Docker-network addresses separate. A host backend reaches local containers through published loopback ports; a container reaches a host backend through `host.docker.internal`.
- PPT startup must never inject provider API keys. Banana Slides uses only the platform model gateway.
- `check` modes are read-only. Applying SQL, recreating services, and changing persistent state must require an explicit mode.
- Any new PowerShell parameter must preserve the existing default behavior unless the output clearly explains a safe fail-fast condition.

## Verification

- Parse changed PowerShell scripts with the PowerShell parser.
- Exercise read-only preflight/diagnostic paths before using a path that spawns services.
- Keep production compose behavior separate from local-only port publishing.
