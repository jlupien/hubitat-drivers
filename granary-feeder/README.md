# PETLIBRO Granary Feeder for Hubitat

Trigger manual feedings on a PETLIBRO feeder from Hubitat — and by extension from Alexa ("Alexa, feed the cat").

## Features

- **Feed on demand**: Turning the switch on dispenses the configured portions (1 portion = 1/12 cup), then the switch turns itself back off
- **Alexa friendly**: Exposes a standard Switch, so it works with the Amazon Echo Skill
- **Cooldown**: Ignores repeat feed requests within a configurable window (default 60s) to prevent double feedings
- **Status**: Today's feeding amount and count, food level (ok/low), online status, WiFi signal, battery
- **Bowl scale** (Granary 2 series): food currently in the bowl and how many more portions fit
- **Feeding mode** (Granary 2 series): see and switch between Smart Refill and scheduled feeding

## Supported Devices

- Granary Smart Camera Feeder (PLAF203)
- Granary 2 Vision (PLAF205)

The rest of the Granary 2 series (Granary 2, Vision Duo, X) and other PETLIBRO feeders using the same cloud API (Granary Smart Feeder, Air, Space, One RFID) will probably work but are untested. US region only.

## Before You Start: Use a Second Account

Logging in to the PETLIBRO API logs out any other session on the same account, including the phone app. To avoid that:

1. Create a second PETLIBRO account (a different email address)
2. In the PETLIBRO app on your main account, share the feeder with the second account
3. Use the second account's credentials in this driver

## Installation

### Via Hubitat Package Manager (HPM)

1. Open HPM in your Hubitat hub
2. Select "Install" > "Search by Keywords"
3. Search for "PETLIBRO"
4. Select "PETLIBRO Granary Feeder" and install

### Manual Installation

1. Go to "Drivers Code" in Hubitat
2. Click "New Driver"
3. Paste the contents of `drivers/petlibro-granary-feeder.groovy`
4. Click "Save"

## Setup

1. Go to "Devices" > "Add Device" > "Virtual"
2. Name it (e.g. "Cat Feeder") and select the **PETLIBRO Granary Feeder** driver
3. In the device Preferences, enter the PETLIBRO email and password, then click "Save Preferences"
4. The driver finds the first feeder on the account automatically. If you have more than one, click "Discover Devices", find the serial number in the logs, and enter it in "Feeder Serial Number"

## Alexa

1. In the **Amazon Echo Skill** app on Hubitat, add the feeder device
2. In the Alexa app, discover devices. "Alexa, turn on Cat Feeder" now dispenses food
3. For "Alexa, feed the cat", create an Alexa Routine: When "feed the cat" is said → Smart Home → Cat Feeder → Power On

## Commands

| Command | Description |
|---------|-------------|
| `on()` | Feed the default portions, then turn the switch back off |
| `push()` | Feed the default portions |
| `feed(portions)` | Feed a specific number of portions (1–48) |
| `setFeedingMode(mode)` | Granary 2 series: `smartRefill` (auto-dispense when the bowl is low) or `scheduled` (feeding plan) |
| `refresh()` | Update feeding totals and status |
| `discoverDevices()` | Log all devices on the PETLIBRO account |

## Attributes

| Attribute | Description |
|-----------|-------------|
| `switch` | Briefly `on` during a feed request |
| `todayFeedingPortions` | Portions dispensed today |
| `todayFeedingCups` | Today's amount as a fraction, e.g. `4/12` |
| `todayFeedingTimes` | Number of feedings today |
| `foodLevel` | `ok` or `low` |
| `connectionStatus` | `online` or `offline` |
| `rssi` | WiFi signal strength (dBm) |
| `battery` | Backup battery level (%) |
| `bowlFoodGrams` | Food currently in the bowl, in grams (Granary 2 series; both bowls combined on dual-bowl models) |
| `feedablePortions` | Portions that still fit in the bowl (Granary 2 series, single bowl) |
| `feedingMode` | `smartRefill` or `scheduled` (Granary 2 series) |
| `lastFeeding` | Time of the last feeding triggered from Hubitat |
| `lastFeedingResult` | `success`, `skipped (cooldown)`, or an error message |

## Notes

- This uses PETLIBRO's unofficial cloud API. If PETLIBRO's servers are down or the API changes, feeding from Hubitat stops working; the feeder's own schedule is unaffected.
- The camera is not supported.

## Credits

API details from the [PETLIBRO Home Assistant integration](https://github.com/jjjonesjr33/petlibro).
