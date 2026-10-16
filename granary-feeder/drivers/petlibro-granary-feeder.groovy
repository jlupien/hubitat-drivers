/**
 *  PETLIBRO Granary Feeder Driver for Hubitat
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
 *  Triggers manual feedings on PETLIBRO feeders (e.g. Granary Smart Camera Feeder PLAF203, Granary 2 series) via the
 *  PETLIBRO cloud API.
 *  Turning the switch on dispenses the configured number of portions (1 portion = 1/12 cup), then the switch
 *  turns itself back off - suitable for exposing to Alexa ("Alexa, turn on Cat Feeder").
 *
 *  Based on research from:
 *  - https://github.com/jjjonesjr33/petlibro (MIT License)
 *
 */

import groovy.json.JsonOutput
import groovy.transform.Field
import java.security.MessageDigest

@Field static final String VERSION = "1.1.0"
@Field static final String API_URL = "https://api.us.petlibro.com"
@Field static final int APP_ID = 1
@Field static final String APP_SN = "c35772530d1041699c87fe62348507a8"
@Field static final String APP_VERSION = "1.3.45"

// Feeding mode API values (Granary 2 series) <-> attribute values
@Field static final Map FEEDING_MODES = ["FREE": "smartRefill", "PLAN": "scheduled"]

// API response codes
@Field static final int CODE_OK = 0
@Field static final int CODE_NOT_LOGGED_IN = 1009

metadata {
    definition(
        name: "PETLIBRO Granary Feeder",
        namespace: "jlupien",
        author: "Jeff Lupien",
        importUrl: "https://raw.githubusercontent.com/jlupien/hubitat-drivers/master/granary-feeder/drivers/petlibro-granary-feeder.groovy"
    ) {
        capability "Switch"               // on() feeds, then auto-off
        capability "Momentary"            // push() feeds
        capability "Refresh"              // refresh()
        capability "Initialize"           // initialize()
        capability "Battery"              // Backup battery level (Granary 2)
        capability "Actuator"
        capability "Sensor"

        attribute "todayFeedingPortions", "number"  // Portions (1/12 cup) dispensed today
        attribute "todayFeedingCups", "string"      // e.g. "4/12"
        attribute "todayFeedingTimes", "number"     // Number of feedings today
        attribute "foodLevel", "string"             // ok, low
        attribute "connectionStatus", "string"      // online, offline
        attribute "rssi", "number"                  // WiFi signal strength (dBm)
        attribute "bowlFoodGrams", "number"         // Food currently in the bowl(s) (Granary 2 bowl scale)
        attribute "feedablePortions", "number"      // Portions that still fit in the bowl (Granary 2, single bowl)
        attribute "feedingMode", "string"           // smartRefill, scheduled (Granary 2)
        attribute "lastFeeding", "string"           // Timestamp of last manual feeding from Hubitat
        attribute "lastFeedingResult", "string"     // success, skipped (cooldown), error message
        attribute "deviceSn", "string"
        attribute "lastUpdate", "string"

        command "feed", [[name: "portions", type: "NUMBER", description: "Portions to dispense (1 portion = 1/12 cup). Blank = default"]]
        command "discoverDevices"
        command "setFeedingMode", [[name: "mode", type: "ENUM", constraints: ["smartRefill", "scheduled"],
                                    description: "Granary 2: Smart Refill (auto-dispense when the bowl is low) or scheduled feeding plan"]]
    }

    preferences {
        input name: "email", type: "text", title: "PETLIBRO Email", required: true,
              description: "Tip: use a second account with the feeder shared to it - logging in here logs out the phone app"
        input name: "password", type: "password", title: "PETLIBRO Password", required: true
        input name: "feederSn", type: "text", title: "Feeder Serial Number", required: false,
              description: "Leave blank to use the first feeder on the account"
        input name: "defaultPortions", type: "number", title: "Portions per Feeding (1 portion = 1/12 cup)",
              range: "1..48", defaultValue: 1, required: true
        input name: "cooldownSeconds", type: "number", title: "Cooldown Between Feedings (seconds)",
              description: "Ignore repeat feed requests within this window. 0 = disabled",
              range: "0..3600", defaultValue: 60, required: true
        input name: "pollInterval", type: "enum", title: "Poll Interval",
              options: ["5": "5 minutes", "15": "15 minutes", "30": "30 minutes (recommended)", "60": "1 hour", "0": "Disabled"],
              defaultValue: "30", required: true
        input name: "logEnable", type: "bool", title: "Enable Debug Logging", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable Info Logging", defaultValue: true
    }
}

// ==================== Lifecycle ====================

def installed() {
    logInfo "PETLIBRO Granary Feeder installed"
    sendEvent(name: "switch", value: "off")
    initialize()
}

def updated() {
    logInfo "PETLIBRO Granary Feeder updated"
    // Credentials or serial may have changed - force a fresh login and lookup
    state.remove("token")
    state.remove("deviceSn")
    initialize()
}

def initialize() {
    logInfo "PETLIBRO Granary Feeder v${VERSION} initializing"
    unschedule()

    switch (settings.pollInterval) {
        case "5":
            runEvery5Minutes("refresh")
            break
        case "15":
            runEvery15Minutes("refresh")
            break
        case "30":
            runEvery30Minutes("refresh")
            break
        case "60":
            runEvery1Hour("refresh")
            break
        default:
            logInfo "Polling disabled"
            break
    }

    if (settings.email && settings.password) {
        runIn(2, "refresh")
    } else {
        logWarn "Enter your PETLIBRO email and password in Preferences"
    }

    if (settings.logEnable) {
        runIn(1800, "logsOff")
    }
}

def logsOff() {
    logInfo "Debug logging disabled automatically"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

def uninstalled() {
    unschedule()
}

// ==================== Commands ====================

def on() {
    sendEvent(name: "switch", value: "on")
    feed()
    runIn(3, "switchOff")
}

def off() {
    switchOff()
}

def switchOff() {
    sendEvent(name: "switch", value: "off")
}

def push() {
    feed()
}

def feed(portions = null) {
    int amount = (portions ?: settings.defaultPortions ?: 1) as int
    if (amount < 1 || amount > 48) {
        logWarn "Invalid portion count ${amount} (must be 1-48)"
        return
    }

    int cooldown = (settings.cooldownSeconds != null ? settings.cooldownSeconds : 60) as int
    if (cooldown > 0 && state.lastFeedTime) {
        long elapsed = (now() - (state.lastFeedTime as long)) / 1000
        if (elapsed < cooldown) {
            logWarn "Feed request ignored - last feeding was ${elapsed}s ago (cooldown ${cooldown}s)"
            sendEvent(name: "lastFeedingResult", value: "skipped (cooldown)")
            return
        }
    }

    String sn = getDeviceSn()
    if (!sn) {
        sendEvent(name: "lastFeedingResult", value: "error: no feeder found")
        return
    }

    // Record the attempt up front so rapid duplicate requests hit the cooldown
    state.lastFeedTime = now()

    def body = [
        deviceSn : sn,
        grainNum : amount,
        requestId: UUID.randomUUID().toString().replace("-", "")
    ]
    def resp = apiPost("/device/device/manualFeeding", body)
    if (resp?.code == CODE_OK) {
        logInfo "Dispensed ${amount} portion(s) (${amount}/12 cup)"
        sendEvent(name: "lastFeeding", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))
        sendEvent(name: "lastFeedingResult", value: "success")
        // Give the feeder time to report the new totals
        runIn(20, "refresh")
    } else {
        state.remove("lastFeedTime")
        String msg = resp ? "code ${resp.code}: ${resp.msg}" : "no response"
        logError "Manual feeding failed - ${msg}"
        sendEvent(name: "lastFeedingResult", value: "error: ${msg}")
    }
}

def refresh() {
    String sn = getDeviceSn()
    if (!sn) return

    def grain = apiPost("/device/data/grainStatus", [id: sn, deviceSn: sn])
    if (grain?.code == CODE_OK && grain.data) {
        int portions = (grain.data.todayFeedingQuantity ?: 0) as int
        sendEvent(name: "todayFeedingPortions", value: portions)
        sendEvent(name: "todayFeedingCups", value: "${portions}/12")
        sendEvent(name: "todayFeedingTimes", value: (grain.data.todayFeedingTimes ?: 0) as int)
    }

    def info = apiPost("/device/device/realInfo", [id: sn, deviceSn: sn])
    if (info?.code == CODE_OK && info.data) {
        sendEvent(name: "connectionStatus", value: info.data.online ? "online" : "offline")
        // surplusGrain is true while there is enough food in the hopper
        if (info.data.surplusGrain != null) {
            sendEvent(name: "foodLevel", value: info.data.surplusGrain ? "ok" : "low")
        }
        if (info.data.wifiRssi != null) {
            sendEvent(name: "rssi", value: info.data.wifiRssi as int)
        }
        if (info.data.electricQuantity != null) {
            sendEvent(name: "battery", value: info.data.electricQuantity as int, unit: "%")
        }
    }

    // Bowl scale telemetry - only Granary 2 series feeders report these fields
    def bowl = apiPost("/data/data/realInfo", [id: sn, deviceSn: sn])
    if (bowl?.code == CODE_OK && bowl.data) {
        def d = bowl.data
        if (d.remainingGrain != null) {
            sendEvent(name: "bowlFoodGrams", value: d.remainingGrain as int, unit: "g")
        } else if (d.leftRemainingGrain != null || d.rightRemainingGrain != null) {
            int total = ((d.leftRemainingGrain ?: 0) as int) + ((d.rightRemainingGrain ?: 0) as int)
            sendEvent(name: "bowlFoodGrams", value: total, unit: "g")
        }
        if (d.maxFeedable != null) {
            sendEvent(name: "feedablePortions", value: d.maxFeedable as int)
        }
        if (d.feedingMode) {
            sendEvent(name: "feedingMode", value: FEEDING_MODES[d.feedingMode] ?: d.feedingMode.toString().toLowerCase())
        }
    }

    sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))
}

def setFeedingMode(String mode) {
    String apiMode = FEEDING_MODES.find { it.value == mode }?.key
    if (!apiMode) {
        logWarn "Invalid feeding mode ${mode} (must be ${FEEDING_MODES.values().join(' or ')})"
        return
    }

    String sn = getDeviceSn()
    if (!sn) return

    def resp = apiPost("/device/device/updateFeedingMode", [deviceSn: sn, mode: apiMode])
    if (resp?.code == CODE_OK) {
        logInfo "Feeding mode set to ${mode}"
        sendEvent(name: "feedingMode", value: mode)
        runIn(5, "refresh")
    } else {
        logError "Set feeding mode failed - ${resp ? "code ${resp.code}: ${resp.msg}" : "no response"}"
    }
}

def discoverDevices() {
    def resp = apiPost("/device/device/list", [:])
    if (resp?.code != CODE_OK) {
        logError "Device list failed - ${resp ? "code ${resp.code}: ${resp.msg}" : "no response"}"
        return []
    }
    def devices = resp.data ?: []
    devices.each { d ->
        logInfo "Found device: ${d.name} (${d.productName}) SN ${d.deviceSn}"
    }
    if (!devices) {
        logWarn "No devices found on this PETLIBRO account"
    }
    return devices
}

// ==================== Helpers ====================

private String getDeviceSn() {
    if (settings.feederSn) {
        return settings.feederSn.trim()
    }
    if (state.deviceSn) {
        return state.deviceSn
    }

    def devices = discoverDevices()
    // Granary 2 series report productName without "feeder" (e.g. "Granary 2", "Granary 2 Vision")
    def feeder = devices.find {
        String name = it.productName?.toLowerCase() ?: ""
        name.contains("feeder") || name.contains("granary")
    } ?: devices[0]
    if (feeder?.deviceSn) {
        state.deviceSn = feeder.deviceSn
        sendEvent(name: "deviceSn", value: feeder.deviceSn)
        logInfo "Using feeder ${feeder.name} (${feeder.productName})"
        return state.deviceSn
    }
    logError "No feeder found on this PETLIBRO account"
    return null
}

private boolean login() {
    if (!settings.email || !settings.password) {
        logWarn "Enter your PETLIBRO email and password in Preferences"
        return false
    }

    def body = [
        appId             : APP_ID,
        appSn             : APP_SN,
        country           : "US",
        email             : settings.email.trim(),
        password          : md5(settings.password),
        phoneBrand        : "",
        phoneSystemVersion: "",
        timezone          : getTimeZoneId(),
        thirdId           : null,
        type              : null
    ]
    def resp = httpPostJson("/member/auth/login", body, null)
    if (resp?.code == CODE_OK && resp.data?.token) {
        state.token = resp.data.token
        logDebug "Login successful"
        return true
    }
    logError "Login failed - ${resp ? "code ${resp.code}: ${resp.msg}" : "no response"}"
    state.remove("token")
    return false
}

/**
 * POST to the PETLIBRO API with the session token, logging in first if needed
 * and once more if the token has expired. Returns the parsed response or null.
 */
private Map apiPost(String path, Map body) {
    if (!state.token && !login()) {
        return null
    }
    def resp = httpPostJson(path, body, state.token)
    if (resp?.code == CODE_NOT_LOGGED_IN) {
        logDebug "Token expired, logging in again"
        if (!login()) {
            return null
        }
        resp = httpPostJson(path, body, state.token)
    }
    return resp
}

private Map httpPostJson(String path, Map body, String token) {
    def headers = [
        source  : "ANDROID",
        language: "EN",
        timezone: getTimeZoneId(),
        version : APP_VERSION
    ]
    if (token) {
        headers.token = token
    }

    def params = [
        uri               : API_URL,
        path              : path,
        headers           : headers,
        requestContentType: "application/json",
        contentType       : "application/json",
        body              : JsonOutput.toJson(body),
        timeout           : 20
    ]

    Map result = null
    try {
        logDebug "POST ${path}"
        httpPost(params) { resp ->
            result = resp.data as Map
            logDebug "${path} -> code ${result?.code}"
        }
    } catch (e) {
        logError "POST ${path} failed: ${e.message}"
    }
    return result
}

private String getTimeZoneId() {
    return location?.timeZone?.ID ?: "America/New_York"
}

private String md5(String s) {
    return MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8")).encodeHex().toString()
}

// ==================== Logging ====================

def logDebug(msg) {
    if (settings.logEnable) {
        log.debug "PetLibro[${device.displayName}]: ${msg}"
    }
}

def logInfo(msg) {
    if (settings.txtEnable) {
        log.info "PetLibro[${device.displayName}]: ${msg}"
    }
}

def logWarn(msg) {
    log.warn "PetLibro[${device.displayName}]: ${msg}"
}

def logError(msg) {
    log.error "PetLibro[${device.displayName}]: ${msg}"
}
