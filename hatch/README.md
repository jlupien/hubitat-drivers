# Hatch Connect for Hubitat

Control your Hatch Rest+ devices from Hubitat Elevation.

## Features

- **Light Control**: RGB color, brightness, on/off
- **Sound Machine**: 12 built-in audio tracks, volume control
- **Favorites**: Play saved favorites from the Hatch app
- **Polling**: Configurable state polling (1-30 minute intervals)
- **Hubitat Integration**: Works with Dashboard, Rule Machine, and other Hubitat apps

## Supported Devices

- Hatch Rest+ (Gen 1 & 2)

Other Hatch devices (Rest Mini, Restore, Rest Baby) may work but are untested.

## Installation

### Via Hubitat Package Manager (HPM)

1. Open HPM in your Hubitat hub
2. Select "Install" > "Search by Keywords"
3. Search for "Hatch"
4. Select "Hatch Connect" and install

### Manual Installation

1. Install the App:
   - Go to "Apps Code" in Hubitat
   - Click "New App"
   - Paste the contents of `apps/hatch-connect.groovy`
   - Click "Save"

2. Install the Driver:
   - Go to "Drivers Code" in Hubitat
   - Click "New Driver"
   - Paste the contents of `drivers/hatch-rest-plus.groovy`
   - Click "Save"

3. Add the App:
   - Go to "Apps" > "Add User App"
   - Select "Hatch Connect"

## Setup

1. Open the Hatch Connect app
2. Click "Login to Hatch"
3. Enter your Hatch account credentials (same as the Hatch mobile app)
4. After successful login, go to "Device Selection"
5. Select the devices you want to add to Hubitat
6. Your devices will appear as new devices in Hubitat

## Capabilities

The Hatch Rest+ driver supports these Hubitat capabilities:

| Capability | Commands |
|------------|----------|
| Switch | on(), off() |
| SwitchLevel | setLevel(level) |
| ColorControl | setColor(map), setHue(hue), setSaturation(sat) |
| AudioVolume | setVolume(vol), mute(), unmute(), volumeUp(), volumeDown() |
| Refresh | refresh() |

### Custom Commands

- `setAudioTrack(number)` - Set audio track (0-11)
- `setAudioTrackByName(name)` - Set audio by name (None, Stream, PinkNoise, etc.)
- `stopAudio()` - Stop audio playback
- `lightOn()` / `lightOff()` - Control light separately from power
- `playFavorite(name)` - Play a saved favorite
- `connectMqtt()` / `disconnectMqtt()` - Manually manage MQTT connection

### Custom Attributes

| Attribute | Description |
|-----------|-------------|
| audioTrack | Current audio track name |
| audioTrackNumber | Current audio track number (0-11) |
| playing | Audio playing status (on/off) |
| connectionStatus | Device connection status (online/offline) |
| mqttStatus | MQTT connection status (connected/disconnected) |
| mute | Mute status (muted/unmuted) |
| firmwareVersion | Device firmware version |
| batteryLevel | Battery level percentage |
| lightOn | Light on/off status |

### Audio Tracks

| Number | Name |
|--------|------|
| 0 | None |
| 1 | Stream |
| 2 | PinkNoise |
| 3 | Dryer |
| 4 | Ocean |
| 5 | Wind |
| 6 | Rain |
| 7 | Bird |
| 8 | Crickets |
| 9 | Brahms |
| 10 | Twinkle |
| 11 | RockABye |

## Example Automations

### Bedtime Routine (Rule Machine)
```
Trigger: Time 7:30 PM
Actions:
  - Set Hatch Rest+ color to #FF6600 (warm orange)
  - Set Hatch Rest+ level to 30%
  - Set Hatch Rest+ audio track to "Ocean"
  - Set Hatch Rest+ volume to 40%
```

### Wake Up (Rule Machine)
```
Trigger: Time 6:30 AM
Actions:
  - Turn off Hatch Rest+ (or stopAudio)
```

## Troubleshooting

### Device not responding
- Check the "connectionStatus" attribute - should be "online"
- Try clicking "Refresh" on the device page
- Verify the device is connected to WiFi in the Hatch app

### Authentication issues
- Go to Hatch Connect app > "Refresh Authentication"
- If that fails, logout and login again

### Commands not working
- Ensure polling is enabled (5 minutes recommended)
- Check Hubitat logs for errors
- Verify AWS IoT connectivity in logs

## Technical Details

This integration uses:
- Hatch REST API for authentication and device discovery
- AWS IoT Device Shadow HTTP API for device control
- AWS SigV4 request signing for secure API calls

Based on research from:
- [ha_hatch](https://github.com/dahlb/ha_hatch) - Home Assistant integration
- [homebridge-hatch-baby-rest](https://github.com/dgreif/homebridge-hatch-baby-rest) - Homebridge plugin

## License

Apache License 2.0
