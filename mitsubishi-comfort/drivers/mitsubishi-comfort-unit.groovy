/**
 *  Mitsubishi Comfort Unit Driver for Hubitat
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
 *  Child thermostat device for one Mitsubishi indoor unit, created and fed by
 *  the Mitsubishi Comfort parent driver. Speaks the Comfort app's Kumo Cloud
 *  v3 API through the parent; this driver holds no credentials and makes no
 *  HTTP calls itself.
 *
 *  Temperature conversion follows the Comfort app exactly, using the
 *  Fahrenheit/Celsius lookup tables from dkmcgowan/ha-comfort: Mitsubishi's
 *  F-to-C mapping diverges from standard arithmetic at several setpoints
 *  (64-66 F and 69-72 F), and the C-to-F display mapping is not the simple
 *  inverse. Values outside the tables fall back to standard rounding.
 *
 *  Change history:
 *    2026-11-13  Jeff Lupien  1.0.0  Initial release.
 */

import groovy.transform.Field

@Field static final String VERSION = "1.0.0"

// Fahrenheit -> Celsius setpoint mapping (61 to 80 F), from ha-comfort.
// Values outside the table fall back to standard rounding to the nearest 0.5 C.
@Field static final Map<Integer, BigDecimal> F_TO_C = [
    61: 16.0, 62: 16.5, 63: 17.0, 64: 17.5, 65: 18.0, 66: 18.5,
    67: 19.5, 68: 20.0, 69: 21.0, 70: 21.5, 71: 22.0, 72: 22.5,
    73: 23.0, 74: 23.5, 75: 24.0, 76: 24.5, 77: 25.0, 78: 25.5,
    79: 26.0, 80: 26.5
]

// Celsius -> Fahrenheit display mapping, keyed by (C * 2) to dodge float
// equality issues. NOT the simple inverse of F_TO_C (e.g. 19.0 C displays
// as 67 F, but 67 F sets 19.5 C).
@Field static final Map<Integer, Integer> C_TO_F = [
    32: 61, 33: 62, 34: 63, 35: 64, 36: 65, 37: 66,
    38: 67, 39: 67, 40: 68, 41: 69,
    42: 69, 43: 70, 44: 71, 45: 72,
    46: 73, 47: 74, 48: 75, 49: 76, 50: 77, 51: 78,
    52: 79, 53: 80
]

// Fan speeds: Hubitat-facing UI labels <-> API values, from ha-comfort.
// The vendor's five speeds do not line up 1:1 with the usual labels.
@Field static final Map<String, String> UI_TO_API_FAN = [
    "auto": "auto",
    "quiet": "superQuiet",
    "low": "quiet",
    "medium": "low",
    "high": "powerful",
    "powerful": "superPowerful"
]
@Field static final Map<String, String> API_TO_UI_FAN = [
    "auto": "auto",
    "superQuiet": "quiet",
    "quiet": "low",
    "low": "medium",
    "powerful": "high",
    "superPowerful": "powerful"
]
@Field static final List<String> UI_FAN_ORDER = ["auto", "quiet", "low", "medium", "high", "powerful"]
@Field static final List<String> API_FAN_VALUES = ["auto", "superQuiet", "quiet", "low", "powerful", "superPowerful"]

// Operating modes: Hubitat <-> API. Hubitat has no dry/fan-only in the stock
// capability enum, but community drivers (including Unified Thermostat) use
// them, and Rule Machine passes them through setThermostatMode.
@Field static final Map<String, String> HE_TO_API_MODE = [
    "off": "off", "cool": "cool", "heat": "heat", "dry": "dry",
    "fan": "vent", "auto": "auto", "emergency heat": "heat"
]
@Field static final Map<String, String> API_TO_HE_MODE = [
    "off": "off", "cool": "cool", "heat": "heat", "dry": "dry",
    "vent": "fan", "auto": "auto", "autoCool": "auto", "autoHeat": "auto"
]

@Field static final List<String> AIR_DIRECTIONS = ["auto", "horizontal", "midhorizontal", "midpoint", "midvertical", "vertical", "swing"]

metadata {
    definition(
        name: "Mitsubishi Comfort Unit",
        namespace: "jlupien",
        author: "Jeff Lupien",
        importUrl: "https://raw.githubusercontent.com/jlupien/hubitat-drivers/master/mitsubishi-comfort/drivers/mitsubishi-comfort-unit.groovy"
    ) {
        capability "Refresh"
        capability "Initialize"
        capability "Thermostat"
        capability "FanControl"
        capability "TemperatureMeasurement"

        attribute "unitId", "string"
        attribute "speed", "string"  // Fan speed UI label (auto, quiet, low, medium, high, powerful)
        attribute "airDirection", "string"
        attribute "humidity", "number"
        attribute "connected", "string"  // true / false
        attribute "rssi", "number"
        attribute "hasMhk2", "string"  // true / false
        attribute "lastRunningMode", "string"
        attribute "lastCommandUTC", "string"

        attribute "MinTempCool", "number"
        attribute "MaxTempCool", "number"
        attribute "MinTempDry", "number"
        attribute "MaxTempDry", "number"
        attribute "MinTempHeat", "number"
        attribute "MaxTempHeat", "number"
        attribute "MinTempAuto", "number"
        attribute "MaxTempAuto", "number"

        attribute "CanHeat", "string"  // true / false
        attribute "CanDry", "string"
        attribute "CanCool", "string"
        attribute "HasAutomaticFanSpeed", "string"
        attribute "NumberOfFanSpeeds", "number"

        // Thermostat capability attributes
        attribute "coolingSetpoint", "NUMBER"
        attribute "heatingSetpoint", "NUMBER"
        attribute "supportedThermostatFanModes", "ENUM", ['auto', 'quiet', 'low', 'medium', 'high', 'powerful']
        attribute "supportedThermostatModes", "ENUM", ['auto', 'off', 'heat', 'cool', 'dry', 'fan']
        attribute "temperature", "NUMBER"
        attribute "thermostatFanMode", "ENUM", ['auto', 'quiet', 'low', 'medium', 'high', 'powerful']
        attribute "thermostatMode", "ENUM", ['auto', 'off', 'heat', 'cool', 'dry', 'fan']
        attribute "thermostatOperatingState", "ENUM", ['heating', 'pending cool', 'pending heat', 'vent economizer', 'idle', 'cooling', 'fan only']
        attribute "thermostatSetpoint", "NUMBER"

        // Custom commands (the rest come from the Thermostat capability)
        command "on"
        command "dry"
        command "fanOnly"
        command "setAirDirection", [[name: "direction*", type: "ENUM", constraints: ["auto", "horizontal", "midhorizontal", "midpoint", "midvertical", "vertical", "swing"], description: "Air direction (vane)"]]
    }
}

def installed() {
    parent.debugLog("installed: Mitsubishi Comfort Unit ${VERSION} installed for ${device.displayName}")
    initialize()
}

def initialize() {
    parent.debugLog("initialize: Initialize process started for unit ${getUnitId()}")
    sendEvent(name: "supportedThermostatModes", value: ["auto", "off", "heat", "cool", "dry", "fan"])
    sendEvent(name: "supportedThermostatFanModes", value: UI_FAN_ORDER)
    if (getUnitId()) {
        fetchProfile()
    }
    parent.debugLog("initialize: Initialize process complete for unit ${getUnitId()}")
}

def refresh() {
    parent.debugLog("refresh: Refresh process called for ${device.displayName}")
    parent.poll()
}

def updated() {
    parent.debugLog("updated: Settings updated for ${device.displayName}")
}

// ========== Parent wiring ==========

def getUnitId() {
    return state.unitId
}

def setUnitId(String unitId) {
    state.unitId = unitId
}

def setZoneName(String zoneName) {
    state.zoneName = zoneName
}

/**
 * Pull the unit's profile (capabilities + per-mode setpoint limits) from the
 * API via the parent. Limits are per-unit and live on the equipment, so this
 * is the only honest source - no defaults are invented here. When the fetch
 * fails the limit attributes stay unset and setpoints pass through
 * unclamped; the API rejects out-of-range values and the parent logs it.
 */
private def fetchProfile() {
    def profile = parent.getUnitProfile(getUnitId())
    if (profile == null) {
        parent.warnLog("fetchProfile: Could not fetch profile for ${getUnitId()} - setpoint limits unknown, setpoints will not be clamped")
        return
    }
    parent.debugLog("fetchProfile: Profile for ${getUnitId()}: ${profile}")
    sendEvent(name: "CanHeat", value: profile.hasModeHeat ? "true" : "false")
    sendEvent(name: "CanDry", value: profile.hasModeDry ? "true" : "false")
    sendEvent(name: "CanCool", value: profile.hasModeCool != false ? "true" : "false")
    sendEvent(name: "HasAutomaticFanSpeed", value: profile.hasFanSpeedAuto ? "true" : "false")
    if (profile.numberOfFanSpeeds != null) {
        sendEvent(name: "NumberOfFanSpeeds", value: profile.numberOfFanSpeeds)
    }
    def mins = profile.minimumSetPoints ?: [:]
    def maxs = profile.maximumSetPoints ?: [:]
    setLimit("MinTempHeat", "MaxTempHeat", mins.heat, maxs.heat)
    setLimit("MinTempCool", "MaxTempCool", mins.cool, maxs.cool)
    setLimit("MinTempDry", "MaxTempDry", mins.dry, maxs.dry)
    setLimit("MinTempAuto", "MaxTempAuto", mins.auto, maxs.auto)
}

private def setLimit(String minAttr, String maxAttr, def minC, def maxC) {
    if (minC != null) {
        sendEvent(name: minAttr, value: cToF(minC))
    }
    if (maxC != null) {
        sendEvent(name: maxAttr, value: cToF(maxC))
    }
}

// ========== Status updates from the parent ==========

/**
 * Apply a status map from the parent (merged zone adapter + device info).
 * Telemetry (room temp, connected, humidity, RSSI) is always applied.
 * Settings (mode, setpoints, fan) are skipped when the cloud data predates
 * our last command - the server cache lags a command by up to ~a minute,
 * and applying stale settings would bounce the state we just set.
 */
def applyStatusUpdates(Map statusInfo) {
    if (!statusInfo || statusInfo.unitId == null) {
        parent.debugLog("applyStatusUpdates: No status information was provided, no further action was taken")
        return
    }

    // Telemetry first - always current enough to show
    if (statusInfo.roomTemp != null) {
        sendEventIfChanged("temperature", cToFRoom(statusInfo.roomTemp))
    }
    if (statusInfo.humidity != null) {
        sendEventIfChanged("humidity", statusInfo.humidity)
    }
    if (statusInfo.connected != null) {
        sendEventIfChanged("connected", statusInfo.connected ? "true" : "false")
    }
    if (statusInfo.rssi != null) {
        sendEventIfChanged("rssi", statusInfo.rssi)
    }
    if (statusInfo.hasMhk2 != null) {
        sendEventIfChanged("hasMhk2", statusInfo.hasMhk2 ? "true" : "false")
    }

    if (isStatusStale(statusInfo)) {
        parent.debugLog("applyStatusUpdates: Status predates our last command, skipping mode/setpoint/fan updates")
        return
    }

    // Remember raw Celsius setpoints for building future commands
    if (statusInfo.spHeat != null) {
        state.spHeatC = statusInfo.spHeat
    }
    if (statusInfo.spCool != null) {
        state.spCoolC = statusInfo.spCool
    }

    def power = statusInfo.power
    def apiMode = statusInfo.operationMode
    def heMode = API_TO_HE_MODE[apiMode] ?: "off"
    if (power == 0) {
        heMode = "off"
    }

    if (statusInfo.spHeat != null) {
        sendEventIfChanged("heatingSetpoint", cToF(statusInfo.spHeat))
    }
    if (statusInfo.spCool != null) {
        sendEventIfChanged("coolingSetpoint", cToF(statusInfo.spCool))
    }
    sendEventIfChanged("thermostatMode", heMode)
    if (heMode != "off") {
        sendEventIfChanged("lastRunningMode", heMode)
    }
    updateThermostatSetpoint(heMode)

    if (statusInfo.fanSpeed != null) {
        def uiFan = API_TO_UI_FAN[statusInfo.fanSpeed] ?: statusInfo.fanSpeed
        sendEventIfChanged("thermostatFanMode", uiFan)
        sendEventIfChanged("speed", uiFan)
    }
    if (statusInfo.airDirection != null) {
        sendEventIfChanged("airDirection", statusInfo.airDirection)
    }

    sendEventIfChanged("thermostatOperatingState", deriveOperatingState(heMode))
}

private def isStatusStale(Map statusInfo) {
    def lastCommand = device.currentValue("lastCommandUTC", true)
    def statusAsAt = statusInfo.lastStatusChangeAt
    if (lastCommand == null || statusAsAt == null) {
        return false
    }
    try {
        def commandDate = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS'Z'").parse(lastCommand)
        def statusDate = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").parse(statusAsAt)
        return commandDate > statusDate
    } catch (Exception e) {
        parent.debugLog("isStatusStale: Could not compare timestamps (${e}), treating status as current")
        return false
    }
}

private def updateThermostatSetpoint(String heMode) {
    def value = null
    if (heMode == "heat" || heMode == "auto") {
        value = device.currentValue("heatingSetpoint", true)
    } else if (heMode == "cool" || heMode == "dry") {
        value = device.currentValue("coolingSetpoint", true)
    }
    if (value != null) {
        sendEventIfChanged("thermostatSetpoint", value)
    }
}

private def deriveOperatingState(String heMode) {
    def roomTemp = device.currentValue("temperature", true)?.toFloat()
    if (heMode == "off" || roomTemp == null) {
        return heMode == "fan" ? "fan only" : "idle"
    }
    if (heMode == "fan") {
        return "fan only"
    }
    if (heMode == "heat") {
        def setpoint = device.currentValue("heatingSetpoint", true)?.toFloat()
        return (setpoint != null && roomTemp < setpoint - 0.5) ? "heating" : "idle"
    }
    if (heMode == "cool" || heMode == "dry") {
        def setpoint = device.currentValue("coolingSetpoint", true)?.toFloat()
        return (setpoint != null && roomTemp > setpoint + 0.5) ? "cooling" : "idle"
    }
    if (heMode == "auto") {
        // Auto reports which half it is running as; without that detail,
        // compare against the nearer setpoint.
        def heatSp = device.currentValue("heatingSetpoint", true)?.toFloat()
        def coolSp = device.currentValue("coolingSetpoint", true)?.toFloat()
        if (heatSp != null && roomTemp < heatSp - 0.5) {
            return "heating"
        }
        if (coolSp != null && roomTemp > coolSp + 0.5) {
            return "cooling"
        }
        return "idle"
    }
    return "idle"
}

private def sendEventIfChanged(String name, def value) {
    if (value == null) {
        return
    }
    if (device.currentValue(name, true) != value) {
        sendEvent(name: name, value: value)
    }
}

// ========== Commands ==========

def setThermostatMode(String mode) {
    def heMode = mode?.toString()?.toLowerCase()?.trim()
    def apiMode = HE_TO_API_MODE[heMode]
    if (apiMode == null) {
        parent.warnLog("setThermostatMode: Unsupported mode '${mode}' for ${device.displayName}")
        return
    }
    parent.debugLog("setThermostatMode: Setting mode to ${heMode} (${apiMode}) for ${device.displayName}")
    def commands = [operationMode: apiMode]
    if (apiMode != "off") {
        // Include the current setpoints so the mode change does not reset
        // them (same pattern ha-comfort uses).
        addCurrentSetpoints(commands)
    }
    if (parent.sendUnitCommand(getUnitId(), commands)) {
        sendEvent(name: "thermostatMode", value: heMode)
        if (heMode != "off") {
            sendEvent(name: "lastRunningMode", value: heMode)
        }
        updateThermostatSetpoint(heMode)
        sendEvent(name: "thermostatOperatingState", value: deriveOperatingState(heMode))
        recordCommand()
    }
}

def setHeatingSetpoint(def givenTemp) {
    def tempF = clampSetpoint(givenTemp, "MinTempHeat", "MaxTempHeat", "heat")
    if (tempF == null) {
        return
    }
    parent.debugLog("setHeatingSetpoint: Setting heating setpoint to ${tempF} F for ${device.displayName}")
    def commands = [spHeat: fToC(tempF)]
    if (state.spCoolC != null) {
        commands.spCool = state.spCoolC
    }
    if (parent.sendUnitCommand(getUnitId(), commands)) {
        state.spHeatC = commands.spHeat
        sendEvent(name: "heatingSetpoint", value: tempF)
        updateThermostatSetpoint(device.currentValue("thermostatMode", true))
        recordCommand()
    }
}

def setCoolingSetpoint(def givenTemp) {
    def tempF = clampSetpoint(givenTemp, "MinTempCool", "MaxTempCool", "cool")
    if (tempF == null) {
        return
    }
    parent.debugLog("setCoolingSetpoint: Setting cooling setpoint to ${tempF} F for ${device.displayName}")
    def commands = [spCool: fToC(tempF)]
    if (state.spHeatC != null) {
        commands.spHeat = state.spHeatC
    }
    if (parent.sendUnitCommand(getUnitId(), commands)) {
        state.spCoolC = commands.spCool
        sendEvent(name: "coolingSetpoint", value: tempF)
        updateThermostatSetpoint(device.currentValue("thermostatMode", true))
        recordCommand()
    }
}

def setThermostatFanMode(String fanMode) {
    def label = fanMode?.toString()?.toLowerCase()?.trim()
    def apiValue = UI_TO_API_FAN[label]
    if (apiValue == null && label in API_FAN_VALUES) {
        // Already an API value (e.g. "superQuiet") - pass it through
        apiValue = label
        label = API_TO_UI_FAN[label] ?: label
    }
    if (apiValue == null) {
        parent.warnLog("setThermostatFanMode: Unsupported fan mode '${fanMode}' for ${device.displayName}")
        return
    }
    parent.debugLog("setThermostatFanMode: Setting fan to ${label} (${apiValue}) for ${device.displayName}")
    if (parent.sendUnitCommand(getUnitId(), [fanSpeed: apiValue])) {
        sendEvent(name: "thermostatFanMode", value: label)
        sendEvent(name: "speed", value: label)
        recordCommand()
    }
}

def setAirDirection(String direction) {
    def dir = direction?.toString()?.toLowerCase()?.trim()
    if (!(dir in AIR_DIRECTIONS)) {
        parent.warnLog("setAirDirection: Unsupported air direction '${direction}' for ${device.displayName}")
        return
    }
    parent.debugLog("setAirDirection: Setting air direction to ${dir} for ${device.displayName}")
    if (parent.sendUnitCommand(getUnitId(), [airDirection: dir])) {
        sendEvent(name: "airDirection", value: dir)
        recordCommand()
    }
}

// Mode convenience commands (Thermostat capability + custom)
def on() {
    def mode = device.currentValue("lastRunningMode", true) ?: "heat"
    parent.debugLog("on: Restoring last running mode ${mode} for ${device.displayName}")
    setThermostatMode(mode)
}

def off() {
    setThermostatMode("off")
}

def heat() {
    setThermostatMode("heat")
}

def cool() {
    setThermostatMode("cool")
}

def auto() {
    setThermostatMode("auto")
}

def dry() {
    setThermostatMode("dry")
}

def fanOnly() {
    setThermostatMode("fan")
}

def emergencyHeat() {
    parent.debugLog("emergencyHeat: No emergency heat on a mini-split, using heat for ${device.displayName}")
    setThermostatMode("heat")
}

// Fan capability commands. A mini-split has no separate fan switch, so these
// map to the closest fixed fan speed (continuous running) or auto.
def fanAuto() {
    setThermostatFanMode("auto")
}

def fanOn() {
    setThermostatFanMode("medium")
}

def fanCirculate() {
    setThermostatFanMode("low")
}

// ========== Helpers ==========

private def addCurrentSetpoints(Map commands) {
    if (state.spCoolC != null) {
        commands.spCool = state.spCoolC
    }
    if (state.spHeatC != null) {
        commands.spHeat = state.spHeatC
    }
}

private def clampSetpoint(def givenTemp, String minAttr, String maxAttr, String modeName) {
    if (givenTemp == null || !"${givenTemp}".isNumber()) {
        parent.warnLog("clampSetpoint: Setpoint '${givenTemp}' is not a number for ${device.displayName}")
        return null
    }
    def tempF = givenTemp.toFloat().round(1)
    def min = device.currentValue(minAttr, true)?.toFloat()
    def max = device.currentValue(maxAttr, true)?.toFloat()
    if (min != null && tempF < min) {
        parent.warnLog("clampSetpoint: ${modeName} setpoint ${tempF} F is below the unit minimum (${min} F), clamping")
        tempF = min
    }
    if (max != null && tempF > max) {
        parent.warnLog("clampSetpoint: ${modeName} setpoint ${tempF} F is above the unit maximum (${max} F), clamping")
        tempF = max
    }
    return tempF
}

private def recordCommand() {
    def fmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS'Z'")
    fmt.setTimeZone(TimeZone.getTimeZone("UTC"))
    sendEvent(name: "lastCommandUTC", value: fmt.format(new Date()))
}

// ========== Temperature conversion (ha-comfort lookup tables) ==========

/**
 * Fahrenheit setpoint -> Celsius for the API. Whole-degree Fahrenheit values
 * 61-80 use Mitsubishi's lookup table; anything else rounds to the nearest
 * 0.5 C, which is the unit's internal step.
 */
private def fToC(def fahrenheit) {
    def fInt = Math.round(fahrenheit.toFloat())
    if (F_TO_C.containsKey(fInt)) {
        return F_TO_C[fInt]
    }
    def c = (fahrenheit.toFloat() - 32.0) * 5.0 / 9.0
    return Math.round(c * 2.0) / 2.0
}

/**
 * Celsius setpoint -> Fahrenheit for display. Uses Mitsubishi's display
 * table (keyed by C * 2); values outside it fall back to standard rounding.
 */
private def cToF(def celsius) {
    def key = Math.round(celsius.toFloat() * 2.0)
    if (C_TO_F.containsKey(key)) {
        return C_TO_F[key]
    }
    return Math.round((celsius.toFloat() * 9.0 / 5.0 + 32.0) * 10) / 10.0
}

/**
 * Celsius room temperature -> Fahrenheit for display. Room readings are
 * telemetry, not setpoints, so plain arithmetic rounded to one decimal
 * (matches how the old driver displayed them, e.g. 54.5).
 */
private def cToFRoom(def celsius) {
    return Math.round((celsius.toFloat() * 9.0 / 5.0 + 32.0) * 10) / 10.0
}
