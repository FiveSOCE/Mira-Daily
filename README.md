# MiraDaily

MiraDaily is the daily reward calendar and streak system for the Mira Paper server suite. It provides configurable day-by-day rewards, persistent claim history, streak tracking and administrative streak-protection tools.

## Download

[**Download MiraDaily v0.1.0**](https://github.com/FiveSOCE/Mira-Daily/releases/download/v0.1.0/MiraDaily-0.1.0.jar)

## Requirements / Dependencies

- Paper 1.21.11
- Java 21
- PlaceholderAPI optional

## How MiraDaily Works

Players open a configurable 30-day reward calendar and claim the reward available for the current day. Claims and streak state persist across restarts. Rewards are command-driven, allowing the calendar to grant money, items, crate keys, tags or anything else exposed through server commands. Streak protection can be administered when a player's streak needs to be preserved or repaired.

MiraDaily also exposes PlaceholderAPI values and a public Bukkit ServicesManager API for other Mira systems.

## Commands

| Command | Permission | What it does |
| --- | --- | --- |
| `/daily` | None required | Opens the daily rewards calendar. |
| `/daily claim` | None required | Claims the currently available daily reward. |
| `/daily streak` | None required | Shows the player's current claim streak. |
| `/mdaily protection ...` | `miradaily.admin` | Manages streak-protection state. |
| `/mdaily reset ...` | `miradaily.admin` | Resets daily reward/streak data for administrative recovery. |
| `/mdaily reload` | `miradaily.admin` | Reloads MiraDaily configuration. |

## Permissions

| Permission | Default | What it does |
| --- | --- | --- |
| `miradaily.admin` | OP | Allows streak-protection, reset and reload administration. |
