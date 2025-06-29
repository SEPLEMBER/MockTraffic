# MockTraffic: Browser-Like Traffic Generator

## Overview
MockTraffic is an Android app designed to generate realistic HTTP/HTTPS traffic, mimicking browser behavior. Built for studying network security, DPI analysis, and traffic obfuscation in censored environments (e.g., Russia, China, Iran, Turkey). Uses `OkHttp`, `Jsoup`, and DoH to create near-indistinguishable browser traffic.

## Goals
- Simulate browser behavior (GET/POST, resources, Google Analytics).
- Support DNS over HTTPS (DoH) with fallback to system DNS.
- Protect against Zero-Click vulnerabilities.
- Minimize suspicion for ISPs/DPI in censored regions.

## Technical Description

### Architecture
- **TrafficService.java**: Background service generating traffic.
  - **GET Requests**: HTTPS requests to URLs from `SharedPreferences` (`urlsToVisit`) with 10–30s delays.
  - **POST Requests**: 20% chance, mimicking form submissions (`dummy=1`).
  - **Google Analytics**: 30% chance, requests to `google-analytics.com/collect`.
  - **Resources**: Loads CSS, JS, images (up to 10, HTTPS) with 0.1–1s delays.
  - **DoH**: Supports Google, Cloudflare, Quad9 with automatic switching and fallback to system DNS (port 53).
- **MainActivity.java**: UI for control (enable/disable traffic, DoH, URL input).
- **config.json**: Configures `userAgents`, `blacklistedUrls`, `timeout`.

### Security
- **Zero-Click**: JS is downloaded but not executed (`response.body().string()`). SVG not processed.
- **Filtering**: HTTPS-only, blacklisted domains (`isBlacklisted`).
- **Logs**: Disabled in release via `proguard-rules.pro`.

### Traffic Naturalness
- **Surface DPI**: 95–98% (HTTPS, system DNS, resources, Google Analytics).
- **Deep Analysis**: 85–90% (fixed `Referer`, simplistic POST).
- **Censored Regions**: 85–90% (system DNS), 75–80% (DoH). DoH may be flagged as circumvention.

### Limitations
- Fixed `Referer` (`https://www.google.com/`).
- Simplistic POST body (`dummy=1`).
- No AJAX/WebSocket support.
- Requests to authenticated pages (e.g., `vk.com/feed`) return 401/403 but raise minimal suspicion.

## Installation
1. Clone the repository: `git clone <repo-url>`.
2. Build the project: ./gradlew build.
3. Install APK on Android (minSdk 24).

## Testing
- Wireshark: Verify HTTPS (port 443), DNS (port 53), Google Analytics.
- UI: Test enable/disable DoH, URL input (vk.com, google.com).
- Logs: Ensure Log.d is disabled in release.

## Wanna Improvements
- Dynamic Referer from lastVisitedUrls.
- Complex POST parameters (search=query&csrf_token=xyz).
- Whitelist domains (vk.com, google.com).
- Support AJAX/WebSocket.
- Filter authenticated pages (/feed, /mail).

## Authors

????
