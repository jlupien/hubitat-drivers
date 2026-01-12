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
 *  Controls Hatch Rest+ devices via AWS IoT Shadow HTTP API.
 *
 *  Based on research from:
 *  - https://github.com/dahlb/ha_hatch (Apache 2.0 License)
 *  - https://github.com/dgreif/homebridge-hatch-baby-rest (MIT License)
 *
 */

import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import groovy.transform.Field

@Field static final String VERSION = "1.0.0"

// Audio track mapping for Rest+
@Field static final Map AUDIO_TRACKS = [
    0: "None",
    1: "Stream",
    2: "PinkNoise",
    3: "Dryer",
    4: "Ocean",
    5: "Wind",
    6: "Rain",
    7: "Bird",
    8: "Crickets",
    9: "Brahms",
    10: "Twinkle",
    11: "RockABye"
]

// Reverse mapping (name -> number) - defined inline to avoid static scope issue
@Field static final Map AUDIO_TRACK_NAMES = [
    "None": 0,
    "Stream": 1,
    "PinkNoise": 2,
    "Dryer": 3,
    "Ocean": 4,
    "Wind": 5,
    "Rain": 6,
    "Bird": 7,
    "Crickets": 8,
    "Brahms": 9,
    "Twinkle": 10,
    "RockABye": 11
]

// IoT value range (0-65535)
@Field static final Integer IOT_MAX = 65535

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
        attribute "connectionStatus", "string"  // online, offline
        attribute "lastUpdate", "string"        // Timestamp of last state update

        // Light-specific attributes
        attribute "lightOn", "string"           // on, off (light state separate from overall power)

        // Custom Commands
        command "setAudioTrack", [[name:"track", type:"NUMBER", description:"Track number (0-11, 0=None)"]]
        command "setAudioTrackByName", [[name:"name", type:"ENUM", constraints:getAudioTrackList()]]
        command "playFavorite", [[name:"favorite", type:"ENUM", constraints:["Refresh list..."]]]
        command "stopAudio"
        command "lightOn"
        command "lightOff"
    }

    preferences {
        input name: "pollInterval", type: "enum", title: "Poll Interval",
              options: ["1": "1 minute", "5": "5 minutes (recommended)", "15": "15 minutes", "30": "30 minutes", "0": "Disabled"],
              defaultValue: "5", required: true
        input name: "defaultColor", type: "color", title: "Default Light Color",
              defaultValue: "#FF8C00", required: false
        input name: "logEnable", type: "bool", title: "Enable Debug Logging", defaultValue: true
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

    // Initial poll
    runIn(2, "poll")

    // Fetch favorites
    runIn(5, "fetchFavorites")
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

    def shadow = parent.getDeviceShadow(thingName)
    if (shadow) {
        processDeviceState(shadow)
    } else {
        sendEvent(name: "connectionStatus", value: "offline")
    }
}

def refresh() {
    logInfo "Refreshing device state"
    poll()
}

// ==================== State Processing ====================

def processDeviceState(shadow) {
    logDebug "Processing shadow state: ${shadow}"

    def reported = shadow?.state?.reported
    if (!reported) {
        logWarn "No reported state in shadow"
        return
    }

    sendEvent(name: "connectionStatus", value: "online")
    sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss"))

    // Power state
    if (reported.containsKey("isPowered")) {
        def powerState = reported.isPowered ? "on" : "off"
        sendEvent(name: "switch", value: powerState)
        logDebug "Power: ${powerState}"
    }

    // Color/Light state (c = color object)
    if (reported.c) {
        def color = reported.c

        // Brightness/Level (i = intensity, 0-65535)
        if (color.containsKey("i")) {
            def level = Math.round(color.i * 100.0 / IOT_MAX)
            sendEvent(name: "level", value: level, unit: "%")
            sendEvent(name: "lightOn", value: level > 0 ? "on" : "off")
            logDebug "Level: ${level}%"
        }

        // RGB values
        if (color.containsKey("r") && color.containsKey("g") && color.containsKey("b")) {
            def r = color.r
            def g = color.g
            def b = color.b

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

    // Get current color or use default
    def currentHue = device.currentValue("hue") ?: 30
    def currentSat = device.currentValue("saturation") ?: 100
    def rgb = hsvToRgb(currentHue, currentSat, 100)

    updateShadow([
        c: [
            r: rgb.red,
            g: rgb.green,
            b: rgb.blue,
            i: intensity
        ],
        isPowered: level > 0
    ])
}

def setColor(colorMap) {
    logInfo "Setting color: ${colorMap}"

    def hue = colorMap.hue != null ? colorMap.hue : (device.currentValue("hue") ?: 30)
    def sat = colorMap.saturation != null ? colorMap.saturation : (device.currentValue("saturation") ?: 100)
    def level = colorMap.level != null ? colorMap.level : (device.currentValue("level") ?: 100)

    def rgb = hsvToRgb(hue, sat, 100)
    def intensity = Math.round(level * IOT_MAX / 100.0)

    updateShadow([
        c: [
            r: rgb.red,
            g: rgb.green,
            b: rgb.blue,
            i: intensity
        ],
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
    updateShadow([a: [v: iotVolume]])
}

def volumeUp() {
    def current = device.currentValue("volume") ?: 50
    def newLevel = Math.min(current + 10, 100)
    setVolume(newLevel)
}

def volumeDown() {
    def current = device.currentValue("volume") ?: 50
    def newLevel = Math.max(current - 10, 0)
    setVolume(newLevel)
}

def mute() {
    logInfo "Muting audio"
    state.preMuteVolume = device.currentValue("volume") ?: 50
    setVolume(0)
}

def unmute() {
    logInfo "Unmuting audio"
    def volume = state.preMuteVolume ?: 50
    setVolume(volume)
}

def setAudioTrack(trackNumber) {
    logInfo "Setting audio track to ${trackNumber}"

    if (trackNumber < 0 || trackNumber > 11) {
        logWarn "Invalid track number: ${trackNumber}. Must be 0-11."
        return
    }

    updateShadow([a: [t: trackNumber]])
}

def setAudioTrackByName(trackName) {
    logInfo "Setting audio track to ${trackName}"

    def trackNumber = AUDIO_TRACK_NAMES[trackName]
    if (trackNumber == null) {
        logWarn "Unknown track name: ${trackName}"
        return
    }

    setAudioTrack(trackNumber)
}

def stopAudio() {
    logInfo "Stopping audio"
    setAudioTrack(0)
}

// ==================== Favorites ====================

def fetchFavorites() {
    logDebug "Fetching favorites"

    def macAddress = device.getDataValue("macAddress")
    if (!macAddress) {
        logWarn "No MAC address configured"
        return
    }

    def favorites = parent.getFavorites(macAddress)
    if (favorites) {
        state.favorites = favorites.collect { fav ->
            [
                id: fav.id,
                name: fav.name ?: "Favorite ${fav.id}"
            ]
        }
        logInfo "Loaded ${state.favorites.size()} favorites"

        // Update command constraint
        updateFavoritesList()
    }
}

def updateFavoritesList() {
    // This would update the playFavorite command options
    // Hubitat doesn't support dynamic command constraints, so favorites are stored in state
    logDebug "Favorites available: ${state.favorites?.collect { it.name }}"
}

def playFavorite(favoriteName) {
    logInfo "Playing favorite: ${favoriteName}"

    if (favoriteName == "Refresh list...") {
        fetchFavorites()
        return
    }

    def favorite = state.favorites?.find { it.name == favoriteName }
    if (!favorite) {
        logWarn "Favorite not found: ${favoriteName}"
        logInfo "Available favorites: ${state.favorites?.collect { it.name }}"
        return
    }

    // Apply favorite settings via shadow update
    // The exact format depends on how Hatch stores favorites
    // This may need adjustment based on actual API response
    logDebug "Applying favorite ID: ${favorite.id}"

    // For now, log that we would apply the favorite
    // Full implementation requires knowing the favorite's stored settings
    logWarn "Favorite application not yet implemented - favorite ID: ${favorite.id}"
}

// ==================== Shadow Update ====================

def updateShadow(Map desiredState) {
    logDebug "Updating shadow: ${desiredState}"

    def thingName = device.getDataValue("thingName")
    if (!thingName) {
        logError "No thingName configured"
        return
    }

    def result = parent.updateDeviceShadow(thingName, desiredState)

    if (result?.success) {
        logDebug "Shadow update successful"
        // Poll to get updated state
        runIn(2, "poll")
    } else {
        logError "Shadow update failed: ${result?.error}"
    }
}

// ==================== Color Conversion ====================

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
            hue = 60 * (((gNorm - bNorm) / delta) % 6)
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
    def x = c * (1 - Math.abs((hue / 60) % 2 - 1))
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
