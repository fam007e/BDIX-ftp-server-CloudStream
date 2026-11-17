# Fam007e's BDIX CloudStream Plugin Repo

![Build](https://github.com/fam007e/BDIX-ftp-server-CloudStream/actions/workflows/build.yml/badge.svg)

Only BDIX-supported servers (accessible from Bangladeshi ISP LANs) and Bangladeshi websites are maintained here. $\color{Red}{\textbf{Please, don't request other websites.}}$

## Install

Add this repository in CloudStream (Settings → Extensions → Add repository):

```
https://github.com/fam007e/BDIX-ftp-server-CloudStream
```

Or install directly: <a href="https://raw.githubusercontent.com/fam007e/BDIX-ftp-server-CloudStream/master/repo.json">repo.json</a>

## Providers

| Plugin | Type | Source |
|---|---|---|
| 9kMovies | Movies/Series | Website |
| BanglaPlex | Movies/Series | Website |
| BdixBdipTV | BDIX FTP | 172.16.50.x |
| BdixCircleftp | BDIX FTP | 172.16.50.x |
| BdixCloudTV | BDIX FTP | 172.16.50.x |
| BdixDflix | BDIX FTP | 172.16.50.x |
| BdixDhakaFlix (7/9/12/14) | BDIX FTP | DhakaFlix servers |
| BdixICCFtp | BDIX FTP | 172.16.50.x |
| BdixMyMovieBazarTV | BDIX FTP | 172.16.50.x |
| BdixRoarZoneTV | BDIX FTP | 172.16.50.x |
| EmwBD | Movies/Series | Website |
| FootReplays / FullReplays | Sports replays | Website |
| FullyMaza | Movies/Series | Website |
| MoviPK / Mp4Moviez / TheMoviesFlix / WatchMoviesPk | Movies/Series | Website |

## Building

All plugins build automatically via GitHub Actions (`.github/workflows/build.yml`):

- Triggers on pushes to `master` that change plugin sources or Gradle config (README/repo.json edits don't rebuild), or via manual **Run workflow** dispatch
- Outputs `.cs3` plugins + `plugins.json` to the `builds` branch
- The app fetches `repo.json` → `builds/plugins.json` → the individual `.cs3` files

To build locally: `./gradlew make makePluginsJson` (JDK 17 + Android SDK required).
