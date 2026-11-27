# 변경 이력

## 0.3.0

### Compatibility

- Added support for Instant P2P 1.7.3.
- Updated Fabric Loader to 0.20.1.
- Updated Fabric API to 0.166.2+26.3.
- Retained Minecraft 26.3 and Java 25 support.

### Resource pack sharing

- Added four sharing modes: off, direct ZIP URL, ZIP file upload, and the host's active resource packs.
- Added a refreshable list of active local packs from Minecraft's pack repository, with per-pack checkboxes, sizes, and priority.
- Uploads keep the bytes of original ZIP files and compress folder packs into temporary ZIPs. The addon verifies the hosted download against the original SHA-1 and reuses verified download URLs for identical files.
- Active sharing prepares up to three packs concurrently and shows the number ready. The host publishes changes with Apply. A failed update preserves the previous verified list.
- When a guest accepts, the host's integrated server sends each pack's download URL and SHA-1 through vanilla resource-pack packets in the host's application order. The guest's client downloads each ZIP from its web host. Guests can join with Instant P2P alone.
- Existing enabled settings are loaded in direct URL mode.

### Guest resource pack choice

- Added a Minecraft confirmation dialog on join and when the host changes the shared pack list. It shows pack names with the highest priority first. The choices apply to the entire list, and previous choices expire when the list changes.
- Guest dialog and status messages use the guest's Minecraft language. Korean and English are supported, with English as the fallback.
- Public rooms use optional packs. Guests can choose Apply or Skip and can stay connected without responding. Private rooms can require packs and present Apply or Leave.
- Dialog buttons submit choices through Minecraft's dialog protocol without running chat commands.

### Instant P2P integration

- Migrated QUIC bridge integration to the g8 package.
- Updated CustomRoomScreen mixin targets.
- Updated GUI smoke tests for Instant P2P 1.7.3.
- Updated version metadata and documentation.

### Validation

- The Gradle build and nine automated tests passed. Coverage includes settings migration, file names, folder ZIP changes, overlapping uploads, SHA-1 cache reuse over local HTTP, pack order, and one-time choices.
- The Minecraft 26.3 smoke test passed for room settings, all four sharing modes, public and private required settings, localized dialogs and pack names, upload gating, and vanilla pack stack order.
- Live uploads to mcpacks.dev and two-account P2P delivery and consent remain unverified.
