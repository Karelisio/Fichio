# Changelog

All notable changes to Fichio are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.1.1] - unreleased

### Fixed
- Fixed an instant crash on launch caused by a circular initialization in the trash manager (the app closed before showing any UI).

## [0.1.0] - 2026-11-17

### Added
- Home dashboard with animated space gauge, categories, recent files and favorites.
- File browser with breadcrumb navigation, list/grid/large-thumbnail views, sorting and thumbnails.
- Basic file operations (copy, move, rename, delete, create, properties) with undo.
- Trash with Room metadata, restore and 30-day auto-purge.
- Full-text search index (Room FTS) updated in the background.
- Image/video/audio/text previews and a small text editor.
- Archive support (zip, 7z, rar, tar, gz) and APK inspection/installation/extraction.
- Android/data & Android/obb access via Shevery (Shizuku fork) UserService.
- Storage analysis (folder sunburst, large files, duplicates, old/empty folders).
- Local HTTP transfer server with QR code and one-time access token.
- Glance widget and app shortcuts.
- Onboarding + permissions wizard.
- In-app update from GitHub Releases.
