# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Link-following fields are now editable: `mCrawlConfig.mCrawlInternalLinks`,
  `mCrawlConfig.mCrawlExternalLinks` and `mCrawlConfig.mAutoDiscoverSitemaps`.
  These are what keep a `--crawl-list` run on its list. Without them a list crawl
  follows every link it finds: measured on a 12-URL list against a live site, 40
  pages were crawled, 28 of them not on the list.
- Allowlisted `mCrawlConfig.mStoreChromeConsoleLog`, with a
  `store_chrome_console_log` property. It is where Screaming Frog records a
  page's JavaScript errors and warnings, and it sits on the same object as
  `mStoreRenderedHtml` and `mStoreJavaScript`, which were allowlisted already.
  Custom JavaScript cannot substitute: those rules run after the page has
  loaded, so the errors have already happened. Verified against SF 24.3.0 on
  macOS ARM64: the field is refused by 0.1.7 ("Field not allowed") and, with
  this change, writes and reloads as `True`.

### Fixed
- macOS jar detection now probes `Contents/Java` as well as the legacy
  `Contents/Resources/Java`. Current Screaming Frog bundles ship the jars in
  `Contents/Java`, so `get_sf_jar_path()` raised `SFNotFoundError` on a default
  macOS install and every call needed an explicit `SF_PATH` / `sf_path=`.
  The current layout is probed first; older bundles still resolve.
- Custom JavaScript rules work on Screaming Frog 24.3 again. The script-type
  enum was looked up by one build's obfuscated class name
  (`seo.spider.config.custom.javascript.id142006137`); SF 24.3 calls it
  `seo.spider.config.custom.javascript.id`, so `Class.forName` threw and every
  rule failed with "Invalid custom JavaScript type: EXTRACTION", whichever type
  was asked for. The enum now comes from `CustomJavaScriptInfo`'s `mType`
  field, whose name does not change between builds; the old class name remains
  as a fallback. Verified against SF 24.3.0 on macOS ARM64: the same
  `add_custom_javascript(...)` fails with 0.1.7's jar and writes the rule with
  this one.

### Documentation
- `setMaxDepth` and `setMaxUrls` now document that **a value of 0 means NO
  LIMIT**, not a limit of zero. The enable flag is derived from `value != 0`, so
  passing 0 disables the limit and leaves `mLimitSearchDepth` / `mLimitSearchTotal`
  false. This is intentional, and it was easy to read a returned `mMaxDepth=0` as
  a depth limit of zero that had been applied.

## [0.1.7] - 2026-07-15

### Added
- Custom HTTP headers: `mCustomHttpHeadersConfig.mHttpHeaders` is now editable, with
  `add_http_header(name, value)` / `remove_http_header(name)` Python helpers. Entries
  accept `{"name","value"}` objects, `"Name: Value"` strings, or the raw
  `HttpHeader [...]` form; list ops `set/append/prepend/clear/remove` supported.
  Header objects are rebuilt reflectively from the list field's generic element type.
- Custom User-Agent that actually persists: `set_user_agent(ua, robots_ua=None)`.
  Root cause: with `mUserAgentConfig.mIsSeoSpider=True`, SF restores the product UA on
  config deserialization, so a bare `mUserAgent` write silently reverted. `mIsSeoSpider`
  and `mRobotsUserAgent` are now on the allowlist and the helper sets all three.
- Allowlisted `mCrawlConfig.mAjaxTimeoutMillis` (JS render wait) and
  `mCrawlConfig.mCrawlHreflang` â€” both exist in SF 22.2 and round-trip correctly.
- Allowlisted `mInteralURLConfig.mSearchAllSubdomains` ("Crawl All Subdomains";
  the group name carries SF's own typo â€” "mInteral" â€” and must stay that way) and
  `mInteralURLConfig.mCrawlOutsideStartFolder`, with a `crawl_all_subdomains`
  property. Include patterns cannot substitute: they filter within scope but never
  reclassify internal/external, so subdomains stayed uncrawlable.
- Allowlisted `mCrawlConfig.mCrawlImages` / `mStoreImages` with `crawl_images` /
  `store_images` properties â€” same treatment CSS/JS already had. On rendered
  crawls, images/fonts consume URL budget as crawled URLs (measured: 1 real HTML
  page out of 25 URLs before, 23/25 after disabling assets).

### Fixed
- Removed `mUserAgentConfig.mPreset` from the allowlist â€” the field does not exist in
  SF 22.2 (`save()` raised "Field not found" when set).
- Enum validation errors now include the valid options, e.g.
  `Invalid rendering mode: STATIC (valid: HTML, JAVASCRIPT)`, plus `enumOptions` in
  error details.

## [0.1.6] - 2026-02-21

### Added
- Java allowlist support for CSS/JS crawl/store fields:
  - `mCrawlConfig.mCrawlCSS`
  - `mCrawlConfig.mStoreCSS`
  - `mCrawlConfig.mCrawlJavaScript`
  - `mCrawlConfig.mStoreJavaScript`
- Java allowlist support for performance throttling fields:
  - `mPerformanceConfig.mLimitPerformance`
  - `mPerformanceConfig.mUrlRequestsPerSecond`
- Python convenience properties:
  - `crawl_css`, `store_css`
  - `crawl_javascript`, `store_javascript`
  - `limit_performance`, `url_requests_per_second`

### Tests
- Added unit tests for all new Python convenience properties.

## [0.1.5] - 2026-01-23

### Fixed
- Custom extraction rules now serialize safely (non-null attribute field)

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



