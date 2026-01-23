# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.4] - 2026-01-23

### Added
- Custom search rule management (add, remove, clear)
- Custom JavaScript rule management (add, remove, clear)

### Updated
- Bundled ConfigBuilder.jar to support custom searches and custom JavaScript patches

## [0.1.0] - 2026-01-22

### Added
- Initial release
- `SFConfig` class for loading, inspecting, and modifying `.seospiderconfig` files
- `SFDiff` class for comparing two config files
- Custom extraction rule management (add, remove, clear)
- Exclude/include pattern management
- Convenience properties for common fields (max_urls, max_depth, rendering_mode, etc.)
- Crawl execution support (blocking and async)
- Extraction testing against live URLs
- Cross-platform support (Windows, macOS, Linux)
- Comprehensive exception hierarchy
