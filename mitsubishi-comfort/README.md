# Mitsubishi Comfort

Hubitat drivers for Mitsubishi mini-splits and ducted units through the
**Comfort** app's Kumo Cloud **v3** API (`app-prod.kumocloud.com`).

This replaces the Kumo Cloud half of the
[Unified Thermostat driver](https://github.com/sburke781/hubitat/tree/master/UnifiedThermostat),
which speaks the v2 API (`geo-c.kumocloud.com`) that died when Mitsubishi
migrated everyone from the Kumo Cloud app to the Comfort app on March 25,
2025. Per that driver's maintainer, a separate Comfort-only driver is the way
to go — so here it is.

## How it works

Cloud-only, REST polling. The parent driver logs in with your Comfort
email/password (JWT, access token ~20 min, refresh token ~30 days),
discovers your sites and zones, creates one child thermostat device per zone,
and polls status on a schedule (default 5 minutes). Commands go out via
`POST /v3/devices/send-command`.

There is no Socket.IO push channel — Hubitat can't easily hold one — so state
can lag the cloud cache by up to about a minute after a command. The parent
polls 60 seconds after every command to pick the new state up promptly.

## Setup

1. Install both drivers, via Hubitat Package Manager (search for
   "Mitsubishi Comfort") or by importing the two `.groovy` files in
   Drivers Code:
   - `Mitsubishi Comfort` (parent)
   - `Mitsubishi Comfort Unit` (child)
2. Create a virtual device (Devices → Add Device → Virtual) with type
   **Mitsubishi Comfort**.
3. In the device's Preferences, enter your Comfort app email and password,
   then Save.
4. Click **Initialize**. The parent logs in, discovers your zones, and creates
   a child thermostat device per zone. The children show up as
   `<parent name> - <zone name>`.

To move existing rules/automations from the old Unified Thermostat child
device to the new one, use Settings → Swap Apps Device.

## What the child exposes

Standard Thermostat capability: mode (`off`, `heat`, `cool`, `dry`, `fan`,
`auto`), heating/cooling setpoints, fan mode, temperature, operating state.
Plus `humidity`, `connected`, `rssi`, `airDirection`, `hasMhk2`,
per-mode setpoint limits from the unit's profile (`MinTempHeat` etc.), and a
`setAirDirection` command.

Fan speeds use the same UI labels as the Home Assistant integration —
`auto`, `quiet`, `low`, `medium`, `high`, `powerful` — mapped to the API's
`auto`, `superQuiet`, `quiet`, `low`, `powerful`, `superPowerful`.

## Temperature conversion

Setpoints use Mitsubishi's own Fahrenheit/Celsius lookup tables (from
[dkmcgowan/ha-comfort](https://github.com/dkmcgowan/ha-comfort)): the F-to-C
mapping diverges from standard arithmetic at 64–66 °F and 69–72 °F, and the
C-to-F display mapping is not the simple inverse. Values outside the tables
fall back to standard rounding. Room temperature is plain arithmetic rounded
to one decimal.

## Command behavior

- Mode and setpoint commands always send **both** setpoints (the changed one
  plus the current value of the other), so a mode change never resets a
  setpoint. Same pattern ha-comfort uses.
- The API returns HTTP 200 even for a disconnected adapter, so "command
  accepted" in the logs means the cloud took it, not that the unit acted.
  The follow-up poll shows whether state moved.
- Setpoints are clamped to the unit's own profile limits (from function code
  181 on the equipment) when the profile could be fetched. Otherwise they
  pass through and the API validates them.
- Status updates that predate your last command are skipped for mode,
  setpoints, and fan (the server cache lags commands); telemetry (room temp,
  humidity, connected) is always applied.

## Files

- `drivers/mitsubishi-comfort.groovy` — parent driver (auth, discovery,
  polling, commands)
- `drivers/mitsubishi-comfort-unit.groovy` — child thermostat driver
- `packageManifest.json` — HPM manifest

## References

- [ventz/mitsubishi-comfort-api-kumo](https://github.com/ventz/mitsubishi-comfort-api-kumo) —
  full v3 API documentation and Python reference client
- [dkmcgowan/ha-comfort](https://github.com/dkmcgowan/ha-comfort) —
  Home Assistant v3 cloud integration (temp tables, command patterns)
