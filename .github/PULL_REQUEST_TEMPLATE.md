# Pull Request

**Type of Change**
- [ ] Bug fix (non-breaking, fixes issue #_)
- [ ] Feature (non-breaking, adds functionality)
- [ ] Breaking change (fix or feature that changes existing functionality)
- [ ] Documentation update

**Related Issue**
Closes #(issue)

## Description

Please include a summary of the changes and the motivation behind them:

- What problem does this solve?
- How was this tested?
- Are there any breaking changes?

## Testing

Describe how you tested your changes (local device, emulator, specific scenarios):

- [ ] Tested on emulator (AAOS 9, MT2712)
- [ ] Tested on physical device (firmware version: _)
- [ ] Manual test steps: _
- [ ] Unit/integration tests added/updated

**Vehicle Testing (if applicable)**
- [ ] No vehicle impact (UI/UX only)
- [ ] Tested at 0 km/h (safety gates applied)
- [ ] Tested with speed gate (refused changes at speed > 0)
- [ ] ADAS/AEB changes tested and reverified

## Code Review Checklist

- [ ] Code follows project style and conventions
- [ ] No new permissions added without justification
- [ ] No hardcoded secrets, URLs, or credentials
- [ ] Comments explain complex logic
- [ ] Breaking changes documented in commit message
- [ ] Related documentation (README, FIRMWARE.md) updated

**Security Considerations**
- [ ] No prompt injection vulnerabilities (input validated)
- [ ] No unauthorized vehicle writes (speed gate enforced)
- [ ] No new privacy leaks or data exfiltration
- [ ] Dependencies checked for known vulnerabilities

**Stability Considerations**
- [ ] No crashes observed during testing
- [ ] No ANR (Application Not Responding) warnings
- [ ] Memory usage reasonable (profile with Android Profiler if adding services)
- [ ] Logs are informative but not spammy

## CI/CD Status

Ensure all checks pass:
- [ ] Tests pass locally (`./gradlew test`)
- [ ] No new lint errors (`./gradlew lint`)
- [ ] APK builds without warnings (`./gradlew build`)
- [ ] Security checks pass (gitleaks, mobsfscan, dependency-check)

## Claude-Assisted Description (Optional)

*If you used Claude AI to refine this PR description, design, or commit messages, summarize how it was improved:*
- Original issue: _
- Claude suggestions applied: _
- Confidence in description clarity: high / medium / low

---

**Note:** All contributions are subject to [CONTRIBUTING.md](CONTRIBUTING.md). Please ensure your PR aligns with security and stability requirements for vehicle-integrated Android systems.
