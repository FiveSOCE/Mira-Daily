# MiraDaily

MiraDaily is the daily reward calendar and streak system for the Mira Paper server suite. It provides configurable day-by-day rewards, persistent claim history, streak tracking, streak protection and MiraCore milestone/audit integration.

## Download

[**Download MiraDaily v0.1.1**](https://github.com/FiveSOCE/Mira-Daily/releases/download/v0.1.1/MiraDaily-0.1.1.jar)

[View All Releases](https://github.com/FiveSOCE/Mira-Daily/releases)

## Requirements / Dependencies

- Paper 1.21.11
- Java 21
- MiraCore 0.2.0 or newer
- PlaceholderAPI optional
- MiraLeaderboards optional/future consumer of the public API

## How MiraDaily Works

Players open a configurable reward calendar and claim the reward available for the current day. Claims and streak state persist across restarts. Rewards are command-driven, allowing the calendar to grant money, items, crate keys, tags or anything else exposed through server commands.

v0.1.1 makes daily reset behavior timezone-safe with an explicit configurable timezone instead of relying on the host machine timezone. The default is `Australia/Brisbane`. Streak milestones can be configured and are awarded through MiraCore using stable keys such as `miradaily.streak_7`, `miradaily.streak_14` and `miradaily.streak_30`. Claims and administrative state changes are written to the MiraCore audit trail.

The player command surface now matches the documented behavior: `/daily` opens the GUI, `/daily claim` claims directly, and `/daily streak` reports the current streak/protection count. A typed `DailyRewardClaimEvent` is emitted after successful claims so other Mira plugins can integrate without parsing commands or files.

## Commands

| Command | Permission | What it does |
| --- | --- | --- |
| `/daily` | `miradaily.use` | Opens the daily rewards calendar. |
| `/daily claim` | `miradaily.use` | Claims the currently available daily reward directly. |
| `/daily streak` | `miradaily.use` | Shows the player's current streak and streak protections. |
| `/mdaily protection <player> <amount>` | `miradaily.admin` | Adds/removes streak-protection charges. |
| `/mdaily reset <player>` | `miradaily.admin` | Resets the selected player's daily state. |
| `/mdaily reload` | `miradaily.admin` | Reloads configuration, including the reset timezone. |

## Permissions

| Permission | Default | What it does |
| --- | --- | --- |
| `miradaily.use` | Everyone | Allows normal Daily use. |
| `miradaily.admin` | OP | Allows protection, reset and reload administration. |

## Configuration

Important settings:

- `track-days` - length of the reward calendar.
- `starting-streak-protections` - starting protection charges.
- `timezone` - timezone used to determine a new daily claim day.
- `milestones.enabled` - enables MiraCore streak milestones.
- `milestones.streaks` - streak thresholds to award.
- `rewards.<day>.commands` - command chain for a calendar day.

Absolute player claim state is stored in `plugins/MiraDaily/daily.yml` and survives restart.

## PlaceholderAPI

Player-context placeholders:

- `%miradaily_streak%`
- `%miradaily_protections%`
- `%miradaily_track_day%`
- `%miradaily_next_day%`
- `%miradaily_can_claim%`

Global/reset placeholders:

- `%miradaily_reset_seconds%`
- `%miradaily_timezone%`

## Integration

MiraDaily registers its `MiraDailyApi` through both Bukkit ServicesManager and MiraCore. Integrations can query claim availability, streak, protections, calendar position, next reward day, reset countdown and configured timezone. Successful claims also emit `DailyRewardClaimEvent`.

## Building

```bash
gradle clean build
```

The output JAR is created in `build/libs/`.
