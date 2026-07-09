# Release Prep vX.Y.Z
## Summary
Release preparation for Switchly vX.Y.Z.

## Version
- Version name:
- Version code:
- Release type: Beta/Public
- Target track: Internal/Closed/Open/Production/Website
- Source versions/previous beta:

## Release Focus
* 
* 
* 

## Code/Build
- [ ] Version name updated
- [ ] Version code updated
- [ ] Changelog updated
- [ ] Build variants checked
- [ ] Play build checked
- [ ] Firebase/direct build checked if relevant
- [ ] Offline build checked if relevant
- [ ] No secrets committed
- [ ] No noisy debug logs left
- [ ] Experimental notices updated if needed

## Strings/Docs
- [ ] English strings checked
- [ ] German strings checked
- [ ] Missing translations checked
- [ ] FAQ updated if needed
- [ ] Store notes prepared
- [ ] GitLab release thread prepared
- [ ] Website APK entry updated if relevant

## Test Checklist
- [ ] App blocking
- [ ] Website blocking
- [ ] Temporary disable
- [ ] Emergency unlock
- [ ] NFC
- [ ] QR scanner
- [ ] External QR scanner/deep links
- [ ] Barcode scanner
- [ ] Quick Actions
- [ ] Quick Settings tiles
- [ ] Widgets/launcher shortcuts
- [ ] Time schedules
- [ ] Location schedules
- [ ] App picker
- [ ] Backup/restore
- [ ] Premium/billing
- [ ] Developer Mode/logs
- [ ] Settings/permissions
- [ ] Support report

## Commands
```bash
./gradlew :app:compileFullDebugKotlin
./gradlew :app:lintFullDebug
./gradlew :app:bundleFullRelease
```

## Play Store Notes
```text
Paste Play Store release notes here.
```

## GitLab Release Thread
```text
Paste release thread link or draft here.
```

## Known Notes/Limitations
* 
* 
* 

## Final Release Decision
- [ ] Ready for beta
- [ ] Ready for public release
- [ ] Hold release

## Post-Release
- [ ] Monitor crash reports
- [ ] Monitor support mails
- [ ] Monitor GitLab feedback thread
- [ ] Monitor Play Store reviews
- [ ] Add follow-up issues for known problems
