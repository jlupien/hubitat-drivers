# Hubitat Rivian Integration

A Hubitat Elevation integration for Rivian electric vehicles (R1S and R1T). Get real-time vehicle data via WebSocket connection for use in dashboards, automations, and Rule Machine.

## Features

- **Real-time updates** via WebSocket subscription (no polling delays)
- **70+ vehicle attributes** including battery, location, climate, closures, and more
- **Presence detection** based on vehicle location relative to home coordinates
- **Multi-vehicle support** - each vehicle gets its own child device
- **Automatic reconnection** with exponential backoff
- **Unit conversion** - configure Fahrenheit/Celsius, miles/km, PSI/bar

## Requirements

- Hubitat Elevation hub (C-5 or newer recommended)
- Rivian account with at least one vehicle
- Vehicle must be delivered and activated in the Rivian app

## Installation

### Option 1: Hubitat Package Manager (Recommended)

1. Open **Hubitat Package Manager** (HPM)
2. Select **Install** → **From URL**
3. Enter: `https://raw.githubusercontent.com/jlupien/hubitat-rivian/master/packageManifest.json`
4. Click **Next** and follow the prompts to install

Don't have HPM? [Install it first](https://community.hubitat.com/t/how-to-install-hubitat-package-manager-or-any-other-user-app/92387) - it makes managing updates easy.

### Option 2: Manual Installation

<details>
<summary>Click to expand manual installation steps</summary>

#### Install the Driver

1. In Hubitat, go to **Drivers Code**
2. Click **+ New Driver**
3. Copy the contents of [`drivers/rivian-vehicle.groovy`](drivers/rivian-vehicle.groovy) and paste it
4. Click **Save**

#### Install the App

1. In Hubitat, go to **Apps Code**
2. Click **+ New App**
3. Copy the contents of [`apps/rivian-connect.groovy`](apps/rivian-connect.groovy) and paste it
4. Click **Save**

</details>

### Configure the App

1. Go to **Apps** → **+ Add User App**
2. Select **Rivian Connect**
3. Enter your Rivian email and password
4. Click **Authenticate**
5. Enter the OTP code sent to your phone
6. Select your vehicle(s) to add
7. Click **Done**

The app will create a child device for each selected vehicle and automatically connect via WebSocket.

## Vehicle Attributes

### Battery & Charging

| Attribute | Type | Description |
|-----------|------|-------------|
| `battery` | number | State of charge (0-100%) |
| `batteryRange` | number | Estimated range in configured units |
| `batteryLimit` | number | Charge limit percentage |
| `batteryCapacity` | number | Battery capacity in kWh |
| `chargingState` | string | idle, charging_active, charging_complete, etc. |
| `chargerStatus` | string | chrgr_sts_not_connected, connected, etc. |
| `chargerDerateStatus` | string | Charger derate status |
| `chargePortState` | string | open, closed |
| `timeToEndOfCharge` | number | Minutes until charge complete |
| `remoteChargingAvailable` | string | Remote charging availability |

### Location & Movement

| Attribute | Type | Description |
|-----------|------|-------------|
| `latitude` | number | Current latitude |
| `longitude` | number | Current longitude |
| `presence` | string | present/not present (based on home coordinates) |
| `distanceFromHome` | number | Distance from home in configured units |
| `speed` | number | Current speed (when moving) |
| `bearing` | number | Compass bearing in degrees |
| `altitude` | number | Altitude in meters |

### Climate

| Attribute | Type | Description |
|-----------|------|-------------|
| `temperature` | number | Cabin temperature in configured units |
| `driverTemperature` | number | Driver-side set temperature |
| `preconditioningStatus` | string | Preconditioning state |
| `preconditioningType` | string | Type of preconditioning active |
| `defrostStatus` | string | Defrost/defog status |
| `steeringWheelHeat` | string | Heated steering wheel state |

### Seat Heaters & Vents

| Attribute | Type | Description |
|-----------|------|-------------|
| `seatFrontLeftHeat` | string | Off, Level_1, Level_2, Level_3 |
| `seatFrontRightHeat` | string | Off, Level_1, Level_2, Level_3 |
| `seatRearLeftHeat` | string | Off, Level_1, Level_2, Level_3 |
| `seatRearRightHeat` | string | Off, Level_1, Level_2, Level_3 |
| `seatFrontLeftVent` | string | Vented seat status |
| `seatFrontRightVent` | string | Vented seat status |
| `seatThirdRowLeftHeat` | string | R1S only - 3rd row heat |
| `seatThirdRowRightHeat` | string | R1S only - 3rd row heat |

### Doors & Locks

| Attribute | Type | Description |
|-----------|------|-------------|
| `lock` | string | locked/unlocked (aggregate of all closures) |
| `allDoorsLocked` | string | true/false |
| `doorFrontLeftLocked` | string | locked/unlocked |
| `doorFrontRightLocked` | string | locked/unlocked |
| `doorRearLeftLocked` | string | locked/unlocked |
| `doorRearRightLocked` | string | locked/unlocked |
| `doorFrontLeftClosed` | string | open/closed |
| `doorFrontRightClosed` | string | open/closed |
| `doorRearLeftClosed` | string | open/closed |
| `doorRearRightClosed` | string | open/closed |

### Closures (Model-Specific)

| Attribute | Type | Description |
|-----------|------|-------------|
| `frunkLocked` | string | Front trunk lock state |
| `frunkClosed` | string | Front trunk open/closed |
| `liftgateLocked` | string | R1S - Liftgate lock state |
| `liftgateClosed` | string | R1S - Liftgate open/closed |
| `liftgateNextAction` | string | R1S - Next liftgate action |
| `tailgateLocked` | string | R1T - Tailgate lock state |
| `tailgateClosed` | string | R1T - Tailgate open/closed |
| `tonneauLocked` | string | R1T - Tonneau cover lock state |
| `tonneauClosed` | string | R1T - Tonneau cover open/closed |
| `sideBinLeftLocked` | string | R1T - Gear tunnel left lock |
| `sideBinLeftClosed` | string | R1T - Gear tunnel left open/closed |
| `sideBinRightLocked` | string | R1T - Gear tunnel right lock |
| `sideBinRightClosed` | string | R1T - Gear tunnel right open/closed |

### Windows

| Attribute | Type | Description |
|-----------|------|-------------|
| `allWindowsClosed` | string | true/false |
| `windowFrontLeftClosed` | string | open/closed |
| `windowFrontRightClosed` | string | open/closed |
| `windowRearLeftClosed` | string | open/closed |
| `windowRearRightClosed` | string | open/closed |

### Tire Pressure

| Attribute | Type | Description |
|-----------|------|-------------|
| `tirePressureFrontLeft` | number | Pressure in configured units |
| `tirePressureFrontRight` | number | Pressure in configured units |
| `tirePressureRearLeft` | number | Pressure in configured units |
| `tirePressureRearRight` | number | Pressure in configured units |
| `tirePressureStatusFrontLeft` | string | Status (normal, low, etc.) |
| `tirePressureStatusFrontRight` | string | Status |
| `tirePressureStatusRearLeft` | string | Status |
| `tirePressureStatusRearRight` | string | Status |

### Vehicle State

| Attribute | Type | Description |
|-----------|------|-------------|
| `powerState` | string | sleep, standby, ready, go |
| `gearStatus` | string | park, drive, reverse, neutral |
| `driveMode` | string | All-Purpose, Sport, Conserve, Snow, etc. |
| `odometer` | number | Total mileage in configured units |

### Gear Guard & Security

| Attribute | Type | Description |
|-----------|------|-------------|
| `gearGuardLocked` | string | Gear Guard arm state |
| `gearGuardAlarm` | string | triggered/off |
| `gearGuardVideoMode` | string | Video recording mode |
| `gearGuardVideoStatus` | string | Video status |

### Pet Mode

| Attribute | Type | Description |
|-----------|------|-------------|
| `petModeStatus` | string | Pet mode on/off |
| `petModeTemperatureStatus` | string | Pet mode temperature status |

### Service & Modes

| Attribute | Type | Description |
|-----------|------|-------------|
| `serviceMode` | string | Vehicle in service mode |
| `carWashMode` | string | Car wash mode enabled |
| `trailerStatus` | string | Trailer connection status |

### Vehicle Health

| Attribute | Type | Description |
|-----------|------|-------------|
| `twelveVoltBatteryHealth` | string | 12V battery status |
| `limitedAccelCold` | string | Cold weather acceleration limit |
| `limitedRegenCold` | string | Cold weather regen limit |
| `batteryThermalStatus` | string | HV battery thermal status |
| `brakeFluidLow` | string | Brake fluid level warning |
| `wiperFluidState` | string | Wiper fluid level |
| `rangeThreshold` | string | Low range warning status |

### Software Updates (OTA)

| Attribute | Type | Description |
|-----------|------|-------------|
| `softwareVersion` | string | Current software version |
| `otaStatus` | string | OTA update status |
| `otaAvailableVersion` | string | Available update version |
| `otaDownloadProgress` | number | Download progress % |
| `otaInstallProgress` | number | Install progress % |
| `otaInstallReady` | string | Ready to install status |

### Connection Status

| Attribute | Type | Description |
|-----------|------|-------------|
| `websocketStatus` | string | connected, disconnected, connecting |
| `lastUpdate` | string | Human-readable timestamp |
| `lastDataReceivedAt` | number | Epoch ms (for Rule Machine) |
| `minutesSinceLastData` | number | Minutes since last update |

## Driver Preferences

| Setting | Description | Default |
|---------|-------------|---------|
| Temperature Unit | Fahrenheit or Celsius | Fahrenheit |
| Distance Unit | Miles or Kilometers | Miles |
| Pressure Unit | PSI or Bar | PSI |
| Home Latitude | Your home latitude for presence | - |
| Home Longitude | Your home longitude for presence | - |
| Presence Radius | Distance threshold in meters | 100 |
| Enable WebSocket | Enable/disable real-time updates | true |
| Reconnect Check Interval | Watchdog interval | 15 min |
| Enable Debug Logging | Verbose logging | false |

## Presence Detection

To enable presence detection:

1. Open the vehicle device in Hubitat
2. Go to **Preferences**
3. Enter your home **Latitude** and **Longitude** (e.g., 42.3601, -71.0589)
4. Optionally adjust the **Presence Radius** (default 100 meters)
5. Click **Save Preferences**

The `presence` attribute will show:
- `present` - Vehicle is within the radius of home
- `not present` - Vehicle is outside the radius

Use this with Rule Machine, HSM, or other automations.

## Example Automations

### Notify when charging complete
```
Trigger: chargingState changes to charging_complete
Action: Send notification "Rivian charging complete at {batteryLevel}%"
```

### Arm security when vehicle leaves
```
Trigger: presence changes to not present
Action: Set HSM to Armed-Away
```

### Dashboard tile showing range
```
Use attribute: batteryRange
Template: "${value} miles remaining"
```

### Alert on low tire pressure
```
Trigger: tirePressureStatusFrontLeft changes to low
Action: Send notification "Check front left tire pressure"
```

## Troubleshooting

### WebSocket disconnects every ~3 minutes
This is normal. Rivian's server closes idle connections after ~180 seconds. The driver automatically reconnects within 2 seconds and resubscribes.

### Attributes not updating
1. Check `websocketStatus` - should show "connected"
2. Check `lastUpdate` for the last data timestamp
3. Vehicle may be asleep - some attributes only update when vehicle is awake

### OTP code not being sent
Rivian sends OTP via SMS to your registered phone number. Ensure your phone number is correct in the Rivian app.

### "No authentication tokens available" error
Re-authenticate by going to the Rivian Connect app and entering your credentials again.

## Known Limitations

- **Read-only in Phase 1**: Vehicle commands (lock, unlock, climate) require phone enrollment with cryptographic keys. This is planned for Phase 2.
- **Vehicle must be awake**: Some data (odometer, tire pressure) is only sent when the vehicle is not in deep sleep.
- **WebSocket connection**: Requires continuous connection; will reconnect automatically if dropped.

## API Notes

This integration uses Rivian's unofficial GraphQL API via WebSocket subscriptions. The API may change without notice.

## For Developers

This package is managed via [Hubitat Package Manager](https://github.com/HubitatCommunity/hubitatpackagemanager).

**To release a new version:**
1. Update `version` and `dateReleased` in `packageManifest.json`
2. Add release notes to `releaseNotes` field
3. Commit and push to master

HPM detects updates by comparing version numbers. Pushing code without bumping the version won't trigger user updates.

## Acknowledgments

Inspired by the [Home Assistant Rivian integration](https://github.com/bretterer/home-assistant-rivian) by @bretterer and contributors.

## License

Apache License 2.0 - See [LICENSE](LICENSE) for details.
