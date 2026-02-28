/**
 *  Hatch Rest+ Driver for Hubitat
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Controls Hatch Rest+ devices via AWS IoT MQTT over WebSocket.
 *
 *  Based on research from:
 *  - https://github.com/dahlb/ha_hatch (Apache 2.0 License)
 *  - https://github.com/dgreif/homebridge-hatch-baby-rest (MIT License)
 *
 */

import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import groovy.transform.Field
import hubitat.helper.HexUtils

@Field static final String VERSION = "1.2.0"

// MQTT Protocol Constants
@Field static final int MQTT_CONNECT = 0x10
@Field static final int MQTT_CONNACK = 0x20
@Field static final int MQTT_PUBLISH = 0x30
@Field static final int MQTT_PUBACK = 0x40
@Field static final int MQTT_SUBSCRIBE = 0x80
@Field static final int MQTT_SUBACK = 0x90
@Field static final int MQTT_PINGREQ = 0xC0
@Field static final int MQTT_PINGRESP = 0xD0
@Field static final int MQTT_DISCONNECT = 0xE0

// Audio track mapping for Rest+ (note: gaps at 1, 8, 12 per ha_hatch/homebridge refs)
@Field static final Map AUDIO_TRACKS = [
    0: "None",
    2: "Stream",
    3: "PinkNoise",
    4: "Dryer",
    5: "Ocean",
    6: "Wind",
    7: "Rain",
    9: "Bird",
    10: "Crickets",
    11: "Brahms",
    13: "Twinkle",
    14: "RockABye"
]

// Reverse mapping (name -> number)
@Field static final Map AUDIO_TRACK_NAMES = [
    "None": 0,
    "Stream": 2,
    "PinkNoise": 3,
    "Dryer": 4,
    "Ocean": 5,
    "Wind": 6,
    "Rain": 7,
    "Bird": 9,
    "Crickets": 10,
    "Brahms": 11,
    "Twinkle": 13,
    "RockABye": 14
]

// IoT value range (0-65535)
@Field static final Integer IOT_MAX = 65535

// MQTT connection max age before proactive reconnect (seconds)
@Field static final int MQTT_MAX_AGE = 240

metadata {
    definition(
        name: "Hatch Rest+",
        namespace: "jlupien",
        author: "Jeff Lupien",
        importUrl: "https://raw.githubusercontent.com/jlupien/hubitat-drivers/master/hatch/drivers/hatch-rest-plus.groovy"
    ) {
        // Standard Hubitat Capabilities
        capability "Switch"               // on(), off()
        capability "SwitchLevel"          // setLevel(level, duration)
        capability "ColorControl"         // setColor(colorMap), setHue(hue), setSaturation(sat)
        capability "AudioVolume"          // setVolume(volume), mute(), unmute(), volumeUp(), volumeDown()
        capability "Refresh"              // refresh()
        capability "Initialize"           // initialize()
        capability "Actuator"             // Generic actuator

        // Custom Attributes
        attribute "audioTrack", "string"
        attribute "audioTrackNumber", "number"
        attribute "playing", "string"           // on, off
        attribute "connectionStatus", "string"  // online, offline, connecting
        attribute "lastUpdate", "string"        // Timestamp of last state update
        attribute "mqttStatus", "string"        // connected, disconnected, connecting
        attribute "mute", "string"              // muted, unmuted (for AudioVolume capability)
        attribute "firmwareVersion", "string"   // Device firmware version
        attribute "batteryLevel", "number"      // Battery level percentage
        attribute "rssi", "number"              // WiFi signal strength (dBm)
        attribute "activePreset", "number"      // Active preset index (0=none)
        attribute "activeProgram", "string"     // Active program name (empty=none)

        // Light-specific attributes
        attribute "lightOn", "string"           // on, off (light state separate from overall power)

        // Custom Commands
        command "setAudioTrack", [[name:"track", type:"ENUM", constraints:getAudioTrackList(), description:"Audio track name"]]
        command "playPreset", [[name:"preset", type:"NUMBER", description:"Preset number (1-10)"]]
        command "playFavorite", [[name:"favorite", type:"STRING", description:"Program name or 'Preset N'"]]
        command "stopAudio"
        command "lightOn"
        command "lightOff"
        command "connectMqtt"
        command "disconnectMqtt"
    }

    preferences {
        input name: "pollInterval", type: "enum", title: "Poll Interval",
              options: ["1": "1 minute", "5": "5 minutes (recommended)", "15": "15 minutes", "30": "30 minutes", "0": "Disabled"],
              defaultValue: "5", required: true
        input name: "defaultColor", type: "color", title: "Default Light Color",
              defaultValue: "#FF8C00", required: false
        input name: "logEnable", type: "bool", title: "Enable Debug Logging", defaultValue: true
        input name: "traceEnable", type: "bool", title: "Enable Trace Logging (verbose)", defaultValue: false
        input name: "txtEnable", type: "bool", title: "Enable Info Logging", defaultValue: true
    }
}

// ==================== Static Methods ====================

def getAudioTrackList() {
    return AUDIO_TRACKS.values().toList()
}



// ==================== Lifecycle ====================

def installed() {
    logInfo "Hatch Rest+ installed"
    initialize()
}

def updated() {
    logInfo "Hatch Rest+ updated"
    initialize()
}

def initialize() {
    logInfo "Hatch Rest+ initializing"
    unschedule()

    // Schedule polling
    switch(settings.pollInterval) {
        case "1":
            runEvery1Minute("poll")
            break
        case "5":
            runEvery5Minutes("poll")
            break
        case "15":
            runEvery15Minutes("poll")
            break
        case "30":
            runEvery30Minutes("poll")
            break
        case "0":
            logInfo "Polling disabled"
            break
    }

    // Initialize attributes that must be non-null to avoid NaN in UI
    if (device.currentValue("audioTrackNumber") == null) {
        sendEvent(name: "audioTrackNumber", value: 0)
        sendEvent(name: "audioTrack", value: "None")
    }

    // Initial poll
    runIn(2, "poll")

    // Auto-disable debug logging after 30 minutes
    if (settings.logEnable) {
        runIn(1800, "logsOff")
    }
}

def logsOff() {
    logInfo "Debug logging disabled automatically"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
    device.updateSetting("traceEnable", [value: "false", type: "bool"])
}

def uninstalled() {
    logInfo "Hatch Rest+ uninstalled"
    unschedule()
}

// ==================== Polling ====================

def poll() {
    logDebug "Polling device state"

    def thingName = device.getDataValue("thingName")
    if (!thingName) {
        logError "No thingName configured"
        return
    }

    // Check if MQTT is connected
    if (device.currentValue("mqttStatus") == "connected") {
        // Check connection age - presigned URL expires after 300s
        def connectedAt = state.mqttConnectedAt ?: 0
        def ageSeconds = (now() - connectedAt) / 1000
        if (ageSeconds > MQTT_MAX_AGE) {
            logInfo "MQTT connection age ${Math.round(ageSeconds)}s exceeds ${MQTT_MAX_AGE}s, reconnecting proactively"
            reconnectMqtt()
            return
        }
        // Request shadow state via MQTT
        requestShadow()
    } else {
        // Try to connect MQTT
        logDebug "MQTT not connected, attempting connection for poll..."
        connectMqtt()
    }
}

def refresh() {
    logInfo "Refreshing device state"

    // If MQTT connected, request shadow
    if (device.currentValue("mqttStatus") == "connected") {
        requestShadow()
    } else {
        // Connect MQTT (which will request shadow after connection)
        connectMqtt()
    }
}

// ==================== State Processing ====================

def processDeviceState(shadow) {
    logDebug "Processing shadow state"

    def reported = shadow?.state?.reported
    if (!reported) {
        logWarn "No reported state in shadow"
        return
    }

    // Log key state fields without presets/programs (which are huge)
    def summary = reported.findAll { k, v -> !["presets", "programs", "deviceInfo"].contains(k) }
    logTrace "Shadow state: ${summary}"

    // Connection status from device (separate from MQTT connection)
    if (reported.containsKey("connected")) {
        sendEvent(name: "connectionStatus", value: reported.connected ? "online" : "offline")
    } else {
        sendEvent(name: "connectionStatus", value: "online")
    }
    sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss"))

    // Device info (firmware, battery)
    if (reported.deviceInfo) {
        if (reported.deviceInfo.f) {
            sendEvent(name: "firmwareVersion", value: reported.deviceInfo.f)
            logDebug "Firmware: ${reported.deviceInfo.f}"
        }
        if (reported.deviceInfo.b != null) {
            sendEvent(name: "batteryLevel", value: reported.deviceInfo.b, unit: "%")
            logDebug "Battery: ${reported.deviceInfo.b}%"
        }
    }

    // RSSI
    if (reported.containsKey("rssi")) {
        sendEvent(name: "rssi", value: reported.rssi, unit: "dBm")
        logDebug "RSSI: ${reported.rssi} dBm"
    }

    // Power state
    if (reported.containsKey("isPowered")) {
        def powerState = reported.isPowered ? "on" : "off"
        sendEvent(name: "switch", value: powerState)
        logDebug "Power: ${powerState}"
    }

    // Active preset/program indices
    if (reported.containsKey("activePresetIndex")) {
        sendEvent(name: "activePreset", value: reported.activePresetIndex)
        logDebug "Active preset: ${reported.activePresetIndex}"
    }
    if (reported.containsKey("activeProgramIndex")) {
        def progIdx = reported.activeProgramIndex
        def progName = ""
        if (progIdx > 0 && state.programs) {
            def prog = state.programs[progIdx.toString()]
            progName = prog?.name ?: "Program ${progIdx}"
        }
        sendEvent(name: "activeProgram", value: progName)
        logDebug "Active program: ${progIdx} (${progName})"
    }

    // Color/Light state (c = color object)
    if (reported.c) {
        def color = reported.c

        // Cache R and W flags for buildDesiredState
        if (color.containsKey("R")) state.lastColorR = color.R
        if (color.containsKey("W")) state.lastColorW = color.W

        // Brightness/Level (i = intensity, 0-65535)
        if (color.containsKey("i")) {
            def level = Math.round(color.i * 100.0 / IOT_MAX)
            sendEvent(name: "level", value: level, unit: "%")
            sendEvent(name: "lightOn", value: level > 0 ? "on" : "off")
            logDebug "Level: ${level}%"
        }

        // RGB values (device uses 0-65535 range, convert to 0-255 for Hubitat)
        if (color.containsKey("r") && color.containsKey("g") && color.containsKey("b")) {
            def r = Math.round(color.r * 255.0 / IOT_MAX) as int
            def g = Math.round(color.g * 255.0 / IOT_MAX) as int
            def b = Math.round(color.b * 255.0 / IOT_MAX) as int

            // Convert RGB to HSV for Hubitat
            def hsv = rgbToHsv(r, g, b)
            sendEvent(name: "hue", value: hsv.hue)
            sendEvent(name: "saturation", value: hsv.saturation)
            sendEvent(name: "colorName", value: getColorName(hsv.hue, hsv.saturation))

            // Store RGB for reference
            def hexColor = String.format("#%02X%02X%02X", r, g, b)
            sendEvent(name: "color", value: hexColor)
            logDebug "Color: RGB(${r},${g},${b}) = ${hexColor}, HSV(${hsv.hue},${hsv.saturation})"
        }
    }

    // Audio state (a = audio object)
    if (reported.a) {
        def audio = reported.a

        // Volume (v = volume, 0-65535)
        if (audio.containsKey("v")) {
            def volume = Math.round(audio.v * 100.0 / IOT_MAX)
            sendEvent(name: "volume", value: volume, unit: "%")
            // Update mute attribute based on volume
            sendEvent(name: "mute", value: volume == 0 ? "muted" : "unmuted")
            logDebug "Volume: ${volume}%"
        }

        // Track (t = track number)
        if (audio.containsKey("t")) {
            def trackNum = audio.t
            def trackName = AUDIO_TRACKS[trackNum] ?: "Unknown"
            sendEvent(name: "audioTrackNumber", value: trackNum)
            sendEvent(name: "audioTrack", value: trackName)
            sendEvent(name: "playing", value: trackNum > 0 ? "on" : "off")
            logDebug "Audio Track: ${trackNum} (${trackName})"
        }
    }

    // Timer (cache for buildDesiredState)
    if (reported.containsKey("timer")) {
        state.lastTimer = reported.timer
    }

    // Presets (only present in full shadow/get response, skip if unchanged)
    if (reported.containsKey("presets")) {
        def presetKeys = reported.presets.keySet().sort().toString()
        if (presetKeys != state.lastPresetKeys) {
            parsePresets(reported.presets)
            state.lastPresetKeys = presetKeys
        }
    }

    // Programs (only present in full shadow/get response, skip if unchanged)
    if (reported.containsKey("programs")) {
        def programSig = reported.programs.collect { k, v -> "${k}:${v.n}" }.sort().toString()
        if (programSig != state.lastProgramSig) {
            parsePrograms(reported.programs)
            state.lastProgramSig = programSig
        }
    }
}

// ==================== Presets & Programs ====================

def parsePresets(presets) {
    if (!presets) return

    def parsed = [:]
    presets.each { key, value ->
        parsed[key] = [
            index: key as int,
            audio: value.a,
            color: value.c,
            flags: value.f
        ]
    }
    state.presets = parsed
    logInfo "Loaded ${parsed.size()} presets"
    logDebug "Presets: ${parsed.keySet()}"
}

def parsePrograms(programs) {
    if (!programs) return

    def parsed = [:]
    programs.each { key, value ->
        parsed[key] = [
            index: key as int,
            name: value.n ?: "Program ${key}",
            audio: value.a,
            color: value.c,
            dayMask: value.D,
            timeSeconds: value.U,
            enabled: value.P
        ]
    }
    state.programs = parsed
    def names = parsed.collect { k, v -> "${v.name} (#${k})" }.join(", ")
    logInfo "Loaded ${parsed.size()} programs: ${names}"
}

def playPreset(presetNumber) {
    def num = presetNumber as int
    if (num < 1 || num > 10) {
        logWarn "Invalid preset number: ${num}. Must be 1-10."
        return
    }

    logInfo "Activating preset ${num}"
    updateShadow([activePresetIndex: num, activeProgramIndex: 0, isPowered: true])
}

def playFavorite(favoriteName) {
    if (!favoriteName) {
        logWarn "No favorite name specified"
        logInfo "Available programs: ${state.programs?.collect { k, v -> v.name }}"
        logInfo "Available presets: 1-${state.presets?.size() ?: 0}"
        return
    }

    // Check if it's "Preset N" format
    def presetMatch = (favoriteName =~ /(?i)preset\s*(\d+)/)
    if (presetMatch.find()) {
        playPreset(presetMatch.group(1) as int)
        return
    }

    // Try to match program name (case-insensitive)
    def program = state.programs?.find { k, v ->
        v.name?.equalsIgnoreCase(favoriteName)
    }
    if (program) {
        def progIdx = program.value.index
        logInfo "Activating program '${program.value.name}' (#${progIdx})"
        updateShadow([activeProgramIndex: progIdx, activePresetIndex: 0, isPowered: true])
        return
    }

    // Try as raw number (treat as preset)
    if (favoriteName.isInteger()) {
        playPreset(favoriteName as int)
        return
    }

    logWarn "Favorite not found: ${favoriteName}"
    logInfo "Available programs: ${state.programs?.collect { k, v -> v.name }}"
    logInfo "Available presets: 1-${state.presets?.size() ?: 0}"
}

// ==================== Power Commands ====================

def on() {
    logInfo "Turning on"
    updateShadow([isPowered: true])
}

def off() {
    logInfo "Turning off"
    updateShadow([isPowered: false])
}

// ==================== Light Commands ====================

def setLevel(level, duration = null) {
    logInfo "Setting brightness to ${level}%"

    def intensity = Math.round(level * IOT_MAX / 100.0)

    // Only send intensity - AWS IoT deep merges, preserving existing RGB color
    updateShadow([c: [i: intensity]])
}

def setColor(colorMap) {
    logInfo "Setting color: ${colorMap}"

    def hue = colorMap.hue != null ? colorMap.hue : (device.currentValue("hue") ?: 30)
    def sat = colorMap.saturation != null ? colorMap.saturation : (device.currentValue("saturation") ?: 100)

    def rgb = hsvToRgb(hue, sat, 100)

    // Scale RGB from 0-255 to 0-65535 (device uses 16-bit color values)
    def iotR = Math.round(rgb.red * IOT_MAX / 255.0)
    def iotG = Math.round(rgb.green * IOT_MAX / 255.0)
    def iotB = Math.round(rgb.blue * IOT_MAX / 255.0)

    // Send only RGB + mode flags; omit intensity so brightness is preserved (deep merge)
    updateShadow([
        c: [r: iotR, g: iotG, b: iotB, R: false, W: false],
        isPowered: true
    ])
}

def setHue(hue) {
    logInfo "Setting hue to ${hue}"
    setColor([hue: hue])
}

def setSaturation(sat) {
    logInfo "Setting saturation to ${sat}"
    setColor([saturation: sat])
}

def lightOn() {
    logInfo "Turning light on"
    def level = device.currentValue("level") ?: 100
    if (level == 0) level = 100
    setLevel(level)
}

def lightOff() {
    logInfo "Turning light off"
    setLevel(0)
}

// ==================== Audio Commands ====================

def setVolume(volumeLevel) {
    logInfo "Setting volume to ${volumeLevel}%"

    def iotVolume = Math.round(volumeLevel * IOT_MAX / 100.0)

    // AWS IoT deep merges, so only send volume - track stays unchanged
    updateShadow([a: [v: iotVolume]])

    // Update mute attribute
    sendEvent(name: "mute", value: volumeLevel == 0 ? "muted" : "unmuted")
}

def volumeUp() {
    def current = (device.currentValue("volume") ?: 50) as int
    def newLevel = Math.min(current + 10, 100)
    setVolume(newLevel)
}

def volumeDown() {
    def current = (device.currentValue("volume") ?: 50) as int
    def newLevel = Math.max(current - 10, 0)
    setVolume(newLevel)
}

def mute() {
    logInfo "Muting audio"
    def current = (device.currentValue("volume") ?: 0) as int
    if (current > 0) {
        state.preMuteVolume = current
    }
    setVolume(0)
}

def unmute() {
    logInfo "Unmuting audio"
    def volume = state.preMuteVolume ?: 10
    setVolume(volume)
}

def setAudioTrack(track) {
    // Accept either a name ("Dryer") or a number (4)
    def num
    if (AUDIO_TRACK_NAMES.containsKey(track.toString())) {
        num = AUDIO_TRACK_NAMES[track.toString()]
    } else {
        num = track as int
    }
    logInfo "Setting audio track to ${AUDIO_TRACKS[num] ?: num}"

    if (!AUDIO_TRACKS.containsKey(num)) {
        logWarn "Invalid track: ${track}. Valid: ${AUDIO_TRACKS.values()}"
        return
    }

    // AWS IoT deep merges, so only send track - volume stays unchanged
    updateShadow([a: [t: num]])
}


def stopAudio() {
    logInfo "Stopping audio"
    setAudioTrack(0)
}

// ==================== Shadow Update ====================

def updateShadow(Map desiredState) {
    logDebug "Updating shadow: ${desiredState}"

    def thingName = device.getDataValue("thingName")
    if (!thingName) {
        logError "No thingName configured"
        return
    }

    // Check if MQTT is connected
    if (device.currentValue("mqttStatus") == "connected") {
        // Use MQTT to send shadow update
        def topic = "\$aws/things/${thingName}/shadow/update"
        def payload = JsonOutput.toJson([state: [desired: desiredState]])
        mqttPublish(topic, payload, 1)
        logDebug "Shadow update sent via MQTT"
    } else {
        // Try to connect MQTT first
        logInfo "MQTT not connected, attempting connection..."
        if (connectMqtt()) {
            // Queue the update for after connection
            state.pendingUpdate = desiredState
            runIn(3, "sendPendingUpdate")
        } else {
            logError "Failed to connect MQTT for shadow update"
        }
    }
}

def sendPendingUpdate() {
    if (state.pendingUpdate) {
        def desiredState = state.pendingUpdate
        state.remove("pendingUpdate")
        updateShadow(desiredState)
    }
}

// ==================== Color Conversion ====================

def hexToRgb(String hex) {
    hex = hex.replaceFirst("#", "")
    return [
        red: Integer.parseInt(hex.substring(0, 2), 16),
        green: Integer.parseInt(hex.substring(2, 4), 16),
        blue: Integer.parseInt(hex.substring(4, 6), 16)
    ]
}

def rgbToHsv(r, g, b) {
    def rNorm = r / 255.0
    def gNorm = g / 255.0
    def bNorm = b / 255.0

    def max = [rNorm, gNorm, bNorm].max()
    def min = [rNorm, gNorm, bNorm].min()
    def delta = max - min

    def hue = 0
    def saturation = 0
    def value = max * 100

    if (delta != 0) {
        saturation = (delta / max) * 100

        if (max == rNorm) {
            hue = 60 * (((gNorm - bNorm) / delta).doubleValue() % 6)
        } else if (max == gNorm) {
            hue = 60 * (((bNorm - rNorm) / delta) + 2)
        } else {
            hue = 60 * (((rNorm - gNorm) / delta) + 4)
        }

        if (hue < 0) hue += 360
    }

    // Hubitat uses 0-100 for hue
    hue = Math.round(hue * 100 / 360)

    return [hue: Math.round(hue), saturation: Math.round(saturation), value: Math.round(value)]
}

def hsvToRgb(h, s, v) {
    // Convert from Hubitat's 0-100 scale to standard
    def hue = h * 360 / 100.0
    def sat = s / 100.0
    def val = v / 100.0

    def c = val * sat
    def x = c * (1 - Math.abs((hue / 60).doubleValue() % 2 - 1))
    def m = val - c

    def rPrime, gPrime, bPrime

    if (hue < 60) {
        rPrime = c; gPrime = x; bPrime = 0
    } else if (hue < 120) {
        rPrime = x; gPrime = c; bPrime = 0
    } else if (hue < 180) {
        rPrime = 0; gPrime = c; bPrime = x
    } else if (hue < 240) {
        rPrime = 0; gPrime = x; bPrime = c
    } else if (hue < 300) {
        rPrime = x; gPrime = 0; bPrime = c
    } else {
        rPrime = c; gPrime = 0; bPrime = x
    }

    return [
        red: Math.round((rPrime + m) * 255),
        green: Math.round((gPrime + m) * 255),
        blue: Math.round((bPrime + m) * 255)
    ]
}

def getColorName(hue, saturation) {
    if (saturation < 10) return "White"

    // Hue is 0-100 in Hubitat
    def h = hue * 3.6 // Convert to 0-360

    if (h < 15 || h >= 345) return "Red"
    if (h < 45) return "Orange"
    if (h < 75) return "Yellow"
    if (h < 150) return "Green"
    if (h < 195) return "Cyan"
    if (h < 255) return "Blue"
    if (h < 285) return "Purple"
    if (h < 345) return "Pink"

    return "Unknown"
}

// ==================== Logging ====================

def logTrace(msg) {
    if (settings.traceEnable) {
        log.debug "HatchRest+[${device.displayName}]: TRACE: ${msg}"
    }
}

def logDebug(msg) {
    if (settings.logEnable) {
        log.debug "HatchRest+[${device.displayName}]: ${msg}"
    }
}

def logInfo(msg) {
    if (settings.txtEnable) {
        log.info "HatchRest+[${device.displayName}]: ${msg}"
    }
}

def logWarn(msg) {
    log.warn "HatchRest+[${device.displayName}]: ${msg}"
}

def logError(msg) {
    log.error "HatchRest+[${device.displayName}]: ${msg}"
}

// ==================== WebSocket/MQTT Implementation ====================

def connectMqtt() {
    logInfo "Connecting to AWS IoT via MQTT over WebSocket"
    sendEvent(name: "mqttStatus", value: "connecting")

    // Get presigned URL from parent app
    def wsUrl = parent.generatePresignedMqttUrl()
    if (!wsUrl) {
        logError "Failed to generate presigned MQTT URL"
        sendEvent(name: "mqttStatus", value: "disconnected")
        return false
    }

    logDebug "WebSocket URL length: ${wsUrl.length()}"

    state.mqttPacketId = 1
    state.pendingSubscribes = []
    state.pendingPublishes = []

    try {
        // AWS IoT requires Sec-WebSocket-Protocol: mqtt header for WebSocket MQTT connections
        // byteInterface: true is required for binary MQTT protocol (messages sent/received as hex)
        interfaces.webSocket.connect(wsUrl, headers: ["Sec-WebSocket-Protocol": "mqtt"], byteInterface: true)
        return true
    } catch (e) {
        logError "WebSocket connect error: ${e.message}"
        sendEvent(name: "mqttStatus", value: "disconnected")
        return false
    }
}

def disconnectMqtt() {
    logInfo "Disconnecting MQTT"

    // Stop keep-alive pings
    unschedule("mqttPing")

    try {
        // Send MQTT DISCONNECT
        def packet = [(byte)MQTT_DISCONNECT, (byte)0]
        sendMqttPacket(packet as byte[])
    } catch (e) {
        logDebug "Error sending disconnect: ${e.message}"
    }

    try {
        interfaces.webSocket.close()
    } catch (e) {
        logDebug "Error closing WebSocket: ${e.message}"
    }

    sendEvent(name: "mqttStatus", value: "disconnected")
}

def reconnectMqtt() {
    logInfo "Reconnecting MQTT with fresh presigned URL"
    try {
        unschedule("mqttPing")
        interfaces.webSocket.close()
    } catch (e) {
        logDebug "Error closing WebSocket during reconnect: ${e.message}"
    }
    sendEvent(name: "mqttStatus", value: "disconnected")

    // Small delay to allow WebSocket to close cleanly, then reconnect
    runIn(1, "connectMqtt")
}

def mqttPing() {
    if (device.currentValue("mqttStatus") != "connected") {
        logDebug "MQTT not connected, skipping ping"
        unschedule("mqttPing")
        return
    }

    // Check if we got a PINGRESP since last ping
    def lastPing = state.lastPingSent ?: 0
    def lastPingResp = state.lastPingResponse ?: 0
    if (lastPing > 0 && lastPingResp < lastPing) {
        logWarn "No PINGRESP received since last ping, connection likely dead"
        reconnectMqtt()
        return
    }

    logDebug "Sending MQTT PINGREQ"
    state.lastPingSent = now()
    def packet = [(byte)MQTT_PINGREQ, (byte)0]
    sendMqttPacket(packet as byte[])
}

// WebSocket callbacks
def webSocketStatus(String status) {
    logDebug "WebSocket status: ${status}"

    if (status.startsWith("status: open")) {
        logInfo "WebSocket connected, sending MQTT CONNECT"
        sendMqttConnect()
    } else if (status.startsWith("status: closing") || status.startsWith("status: closed")) {
        logInfo "WebSocket closed"
        unschedule("mqttPing")
        sendEvent(name: "mqttStatus", value: "disconnected")
        sendEvent(name: "connectionStatus", value: "offline")
    } else if (status.startsWith("failure:")) {
        logError "WebSocket failure: ${status}"
        unschedule("mqttPing")
        sendEvent(name: "mqttStatus", value: "disconnected")
        sendEvent(name: "connectionStatus", value: "offline")
    }
}

def parse(String message) {
    // WebSocket binary messages come as hex strings
    logDebug "Received message (length ${message.length()})"

    try {
        def bytes = HexUtils.hexStringToByteArray(message)
        parseMqttPacket(bytes)
    } catch (e) {
        logError "Error parsing message: ${e.message}"
    }
}

def parseMqttPacket(byte[] bytes) {
    if (bytes.length < 2) {
        logWarn "Packet too short"
        return
    }

    def packetType = (bytes[0] & 0xF0) as int
    logDebug "MQTT packet type: 0x${Integer.toHexString(packetType)}"

    switch (packetType) {
        case MQTT_CONNACK:
            handleConnack(bytes)
            break
        case MQTT_SUBACK:
            handleSuback(bytes)
            break
        case MQTT_PUBLISH:
            handlePublish(bytes)
            break
        case MQTT_PUBACK:
            handlePuback(bytes)
            break
        case MQTT_PINGRESP:
            state.lastPingResponse = now()
            logDebug "Received PINGRESP"
            break
        default:
            logDebug "Unknown packet type: 0x${Integer.toHexString(packetType)}"
    }
}

def sendMqttConnect() {
    logDebug "Sending MQTT CONNECT"

    // Build client ID
    def clientId = "hubitat-${device.deviceNetworkId}-${now()}"

    // MQTT CONNECT packet
    // Fixed header: CONNECT (0x10) + remaining length
    // Variable header: Protocol Name (MQTT), Protocol Level (4), Connect Flags, Keep Alive
    // Payload: Client ID

    def protocolName = "MQTT"
    def protocolLevel = 4
    def connectFlags = 0x02  // Clean session
    def keepAlive = 300  // 5 minutes

    // Build variable header
    def varHeader = []
    // Protocol name
    varHeader.add((byte)(protocolName.length() >> 8))
    varHeader.add((byte)(protocolName.length() & 0xFF))
    protocolName.each { varHeader.add((byte)it) }
    // Protocol level
    varHeader.add((byte)protocolLevel)
    // Connect flags
    varHeader.add((byte)connectFlags)
    // Keep alive
    varHeader.add((byte)(keepAlive >> 8))
    varHeader.add((byte)(keepAlive & 0xFF))

    // Build payload (client ID)
    def payload = []
    def clientIdBytes = clientId.getBytes("UTF-8")
    payload.add((byte)(clientIdBytes.length >> 8))
    payload.add((byte)(clientIdBytes.length & 0xFF))
    clientIdBytes.each { payload.add(it) }

    // Calculate remaining length
    def remainingLength = varHeader.size() + payload.size()

    // Build packet
    def packet = []
    packet.add((byte)MQTT_CONNECT)
    encodeRemainingLength(packet, remainingLength)
    packet.addAll(varHeader)
    packet.addAll(payload)

    sendMqttPacket(packet as byte[])
}

def handleConnack(byte[] bytes) {
    logDebug "Received CONNACK"

    if (bytes.length >= 4) {
        def returnCode = bytes[3] & 0xFF
        if (returnCode == 0) {
            logInfo "MQTT connected successfully"
            sendEvent(name: "mqttStatus", value: "connected")
            sendEvent(name: "connectionStatus", value: "online")

            // Track connection time for proactive reconnect
            state.mqttConnectedAt = now()
            state.lastPingSent = 0
            state.lastPingResponse = 0

            // Schedule MQTT keep-alive pings (every 1 minute, keep-alive is 5 min)
            runEvery1Minute("mqttPing")

            // Subscribe to shadow topics
            subscribeToShadow()
        } else {
            logError "MQTT connection refused, code: ${returnCode}"
            sendEvent(name: "mqttStatus", value: "disconnected")
        }
    }
}

def subscribeToShadow() {
    def thingName = device.getDataValue("thingName")
    if (!thingName) {
        logError "No thingName for subscription"
        return
    }

    // Subscribe to shadow update accepted and get accepted topics
    def topics = [
        "\$aws/things/${thingName}/shadow/update/accepted",
        "\$aws/things/${thingName}/shadow/get/accepted",
        "\$aws/things/${thingName}/shadow/update/delta"
    ]

    topics.each { topic ->
        mqttSubscribe(topic, 1)
    }

    // Request current shadow state
    runIn(2, "requestShadow")
}

def mqttSubscribe(String topic, int qos) {
    logDebug "Subscribing to: ${topic}"

    def packetId = getNextPacketId()

    // Build SUBSCRIBE packet
    def varHeader = []
    varHeader.add((byte)(packetId >> 8))
    varHeader.add((byte)(packetId & 0xFF))

    def payload = []
    def topicBytes = topic.getBytes("UTF-8")
    payload.add((byte)(topicBytes.length >> 8))
    payload.add((byte)(topicBytes.length & 0xFF))
    topicBytes.each { payload.add(it) }
    payload.add((byte)qos)

    def remainingLength = varHeader.size() + payload.size()

    def packet = []
    packet.add((byte)(MQTT_SUBSCRIBE | 0x02))  // QoS 1
    encodeRemainingLength(packet, remainingLength)
    packet.addAll(varHeader)
    packet.addAll(payload)

    sendMqttPacket(packet as byte[])
}

def handleSuback(byte[] bytes) {
    logDebug "Received SUBACK"
    // Subscription confirmed
}

def requestShadow() {
    def thingName = device.getDataValue("thingName")
    if (!thingName) return

    def topic = "\$aws/things/${thingName}/shadow/get"
    mqttPublish(topic, "", 0)

    // Track shadow request for timeout detection
    state.shadowGetPending = now()
    runIn(10, "checkShadowResponse")
}

def checkShadowResponse() {
    def pending = state.shadowGetPending
    if (pending && pending > 0) {
        def elapsed = (now() - pending) / 1000
        logWarn "No shadow/get response after ${Math.round(elapsed)}s, connection likely dead"
        state.shadowGetPending = 0
        reconnectMqtt()
    }
}

def mqttPublish(String topic, String payload, int qos) {
    logDebug "Publishing to ${topic}"
    logTrace "Payload: ${payload}"

    def topicBytes = topic.getBytes("UTF-8")
    def payloadBytes = payload.getBytes("UTF-8")

    def varHeader = []
    varHeader.add((byte)(topicBytes.length >> 8))
    varHeader.add((byte)(topicBytes.length & 0xFF))
    topicBytes.each { varHeader.add(it) }

    if (qos > 0) {
        def packetId = getNextPacketId()
        varHeader.add((byte)(packetId >> 8))
        varHeader.add((byte)(packetId & 0xFF))
    }

    def remainingLength = varHeader.size() + payloadBytes.length

    def packet = []
    packet.add((byte)(MQTT_PUBLISH | (qos << 1)))
    encodeRemainingLength(packet, remainingLength)
    packet.addAll(varHeader)
    payloadBytes.each { packet.add(it) }

    sendMqttPacket(packet as byte[])
}

def handlePublish(byte[] bytes) {
    logDebug "Received PUBLISH"

    // Parse PUBLISH packet
    def idx = 1
    def (remainingLength, bytesUsed) = decodeRemainingLength(bytes, idx)
    idx += bytesUsed

    // Topic length
    def topicLen = ((bytes[idx] & 0xFF) << 8) | (bytes[idx + 1] & 0xFF)
    idx += 2

    // Topic
    def topicBytes = bytes[idx..(idx + topicLen - 1)]
    def topic = new String(topicBytes as byte[], "UTF-8")
    idx += topicLen

    // QoS
    def qos = (bytes[0] & 0x06) >> 1
    if (qos > 0) {
        // Skip packet ID
        idx += 2
    }

    // Payload
    def payloadBytes = bytes[idx..-1]
    def payload = new String(payloadBytes as byte[], "UTF-8")

    logDebug "MQTT message on ${topic} (${payload.length()} bytes)"

    // Process shadow message
    processShadowMessage(topic, payload)
}

def handlePuback(byte[] bytes) {
    logDebug "Received PUBACK"
}

def processShadowMessage(String topic, String payload) {
    logDebug "Processing shadow message from ${topic}"

    try {
        def json = new JsonSlurper().parseText(payload)

        if (topic.endsWith("/shadow/get/accepted")) {
            // Clear shadow/get timeout
            def wasPending = state.shadowGetPending > 0
            state.shadowGetPending = 0
            // Full shadow state from get request
            // Skip if unsolicited (from another client) and we processed recently
            if (!wasPending) {
                def lastProcessed = state.lastShadowProcessed ?: 0
                if (now() - lastProcessed < 30000) {
                    logDebug "Skipping unsolicited shadow/get response (processed ${(now() - lastProcessed) / 1000}s ago)"
                    return
                }
            }
            if (json.state?.reported) {
                state.lastShadowProcessed = now()
                processDeviceState([state: json.state])
            }
        } else if (topic.endsWith("/shadow/update/accepted")) {
            // Shadow update confirmation
            if (json.state?.reported) {
                processDeviceState([state: json.state])
            }
        } else if (topic.endsWith("/shadow/update/delta")) {
            // Delta update
            if (json.state) {
                logDebug "Shadow delta: ${json.state}"
            }
        }
    } catch (e) {
        logError "Error parsing shadow message: ${e.message}"
    }
}

def sendMqttPacket(byte[] packet) {
    try {
        def hex = HexUtils.byteArrayToHexString(packet)
        interfaces.webSocket.sendMessage(hex)
    } catch (e) {
        logError "Error sending MQTT packet: ${e.message}"
    }
}

def encodeRemainingLength(List packet, int length) {
    def encodedByte = length % 128
    length = (int)(length / 128)
    if (length > 0) {
        encodedByte = encodedByte | 0x80
    }
    packet.add((byte)encodedByte)

    while (length > 0) {
        encodedByte = length % 128
        length = (int)(length / 128)
        if (length > 0) {
            encodedByte = encodedByte | 0x80
        }
        packet.add((byte)encodedByte)
    }
}

def decodeRemainingLength(byte[] bytes, int startIdx) {
    def multiplier = 1
    def value = 0
    def idx = startIdx

    def encodedByte = bytes[idx] & 0xFF
    value += (encodedByte & 0x7F) * multiplier
    multiplier *= 128
    idx++

    while ((encodedByte & 0x80) != 0 && idx < bytes.length) {
        encodedByte = bytes[idx] & 0xFF
        value += (encodedByte & 0x7F) * multiplier
        multiplier *= 128
        idx++
    }

    return [value, idx - startIdx]
}

def getNextPacketId() {
    def packetId = state.mqttPacketId ?: 1
    state.mqttPacketId = (packetId % 65535) + 1
    return packetId
}
