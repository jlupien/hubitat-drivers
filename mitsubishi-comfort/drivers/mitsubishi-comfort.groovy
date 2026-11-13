/**
 *  Mitsubishi Comfort Parent Driver for Hubitat
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
 *  Controls Mitsubishi mini-splits / ducted units through the Comfort app's
 *  Kumo Cloud v3 API (app-prod.kumocloud.com). This is a cloud-only driver:
 *  it polls the v3 REST API on a schedule and sends commands via
 *  POST /v3/devices/send-command. It does not use the local adapter API and
 *  does not hold a Socket.IO connection (Hubitat cannot easily do that), so
 *  state can lag the cloud cache by up to about a minute.
 *
 *  This driver replaces the Kumo Cloud half of the Unified Thermostat driver,
 *  which speaks the dead v2 API (geo-c.kumocloud.com). Per that driver's
 *  maintainer, a separate Comfort-only driver is the way to go.
 *
 *  Based on research from:
 *  - https://github.com/ventz/mitsubishi-comfort-api-kumo (MIT License)
 *    Full v3 API documentation and Python reference client.
 *  - https://github.com/dkmcgowan/ha-comfort (MIT License)
 *    Home Assistant v3 cloud integration; source of the Fahrenheit/Celsius
 *    lookup tables and the send-both-setpoints command pattern.
 *
 *  Change history:
 *    2026-11-13  Jeff Lupien  1.0.0  Initial release - login + token refresh,
 *                                     site/zone discovery, child creation,
 *                                     scheduled polling, send-command.
 *    2026-11-13  Jeff Lupien  1.0.1  Log accepted commands at info level:
 *                                     successful sends left no trace at the
 *                                     default log level, so a live test
 *                                     could not be corroborated from logs.
 */

import groovy.json.JsonOutput
import groovy.transform.Field

@Field static final String VERSION = "1.0.1"
@Field static final String BASE_URL = "https://app-prod.kumocloud.com"
@Field static final String APP_VERSION = "3.2.4"
// User-Agent captured from the Kumo Cloud iOS app (via ventz's doc).
@Field static final String USER_AGENT = "kumocloud/1122 CFNetwork/3860.200.71 Darwin/25.1.0"

metadata {
    definition(
        name: "Mitsubishi Comfort",
        namespace: "jlupien",
        author: "Jeff Lupien",
        importUrl: "https://raw.githubusercontent.com/jlupien/hubitat-drivers/master/mitsubishi-comfort/drivers/mitsubishi-comfort.groovy"
    ) {
        capability "Refresh"     // refresh()
        capability "Initialize"  // initialize()

        attribute "lastPoll", "string"
        attribute "connectionStatus", "string"  // online, offline, error

        command "discoverZones"
    }

    preferences {
        input name: "userName", type: "text", title: "Comfort / Kumo Cloud email", required: true
        input name: "password", type: "password", title: "Comfort / Kumo Cloud password", required: true
        input name: "pollInterval", type: "enum", title: "Status polling interval",
            options: ["1", "5", "10", "15", "30"], defaultValue: "5",
            description: "Minutes between status polls"
        input name: "enableDebugLogging", type: "bool", title: "Enable Debug Logging", defaultValue: false
        input name: "enableInfoLogging", type: "bool", title: "Enable Description Text (Info) Logging", defaultValue: true
        input name: "enableWarnLogging", type: "bool", title: "Enable Warning Logging", defaultValue: true
        input name: "enableErrorLogging", type: "bool", title: "Enable Error Logging", defaultValue: true
    }
}

def installed() {
    debugLog("installed: Mitsubishi Comfort ${VERSION} installed")
    initialize()
}

def initialize() {
    debugLog("initialize: Initialize process started")
    unschedule()
    schedulePolling()
    if (settings.userName && settings.password) {
        refresh()
    } else {
        warnLog("initialize: Comfort email/password not set - enter them in Preferences, then click Initialize")
        sendEvent(name: "connectionStatus", value: "offline")
    }
    debugLog("initialize: Initialize process complete")
}

def updated() {
    debugLog("updated: Applying settings")
    unschedule()
    schedulePolling()
    if (settings.enableDebugLogging) {
        // Debug logging auto-disables after 30 minutes
        runIn(1800, "disableDebugLogging")
    }
    if (settings.userName && settings.password) {
        // Credentials may have changed - drop cached tokens and re-login
        state.accessToken = null
        state.refreshToken = null
        refresh()
    }
    debugLog("updated: Settings applied")
}

def disableDebugLogging() {
    if (settings.enableDebugLogging) {
        device.updateSetting("enableDebugLogging", [value: "false", type: "bool"])
        infoLog("Debug logging disabled after 30 minutes")
    }
}

private def schedulePolling() {
    def interval = (settings.pollInterval ?: "5").toInteger()
    switch (interval) {
        case 1:
            runEvery1Minute("poll")
            break
        case 10:
            runEvery10Minutes("poll")
            break
        case 15:
            runEvery15Minutes("poll")
            break
        case 30:
            runEvery30Minutes("poll")
            break
        default:
            runEvery5Minutes("poll")
            break
    }
    debugLog("schedulePolling: polling every ${interval} minute(s)")
}

def refresh() {
    debugLog("refresh: Refresh process called")
    discoverAndPoll()
    debugLog("refresh: Refresh process complete")
}

def poll() {
    debugLog("poll: Scheduled poll")
    discoverAndPoll()
}

def discoverZones() {
    debugLog("discoverZones: Manual zone discovery")
    discoverAndPoll()
}

// ========== Discovery + polling ==========

private def discoverAndPoll() {
    if (!settings.userName || !settings.password) {
        warnLog("discoverAndPoll: Comfort email/password not set")
        sendEvent(name: "connectionStatus", value: "offline")
        return
    }
    if (!ensureAuthenticated()) {
        errorLog("discoverAndPoll: Authentication failed - check email/password")
        sendEvent(name: "connectionStatus", value: "error")
        return
    }

    def sites = apiRequest("GET", "/v3/sites/")
    if (!(sites instanceof List)) {
        errorLog("discoverAndPoll: Listing sites failed (no sites returned)")
        sendEvent(name: "connectionStatus", value: "error")
        return
    }
    debugLog("discoverAndPoll: Found ${sites.size()} site(s)")

    sites.each { site ->
        def zones = apiRequest("GET", "/v3/sites/${site.id}/zones")
        if (!(zones instanceof List)) {
            warnLog("discoverAndPoll: Listing zones failed for site ${site.name}")
            return
        }
        zones.each { zone ->
            def adapter = zone.adapter ?: [:]
            def serial = adapter.deviceSerial
            if (!serial) {
                debugLog("discoverAndPoll: Zone ${zone.name} has no device serial, skipping")
                return
            }
            def child = findOrCreateChild(serial, zone.name)
            if (child == null) {
                errorLog("discoverAndPoll: Could not create/find child device for ${zone.name} (${serial})")
                return
            }
            // Full device info carries fan speed, vane, humidity and RSSI,
            // which the zone adapter object does not. Device data wins on
            // conflicts (same merge the ventz client does with full=True).
            def deviceInfo = apiRequest("GET", "/v3/devices/${serial}") ?: [:]
            def merged = [:] + adapter + deviceInfo
            merged.unitId = serial
            merged.zoneId = zone.id
            merged.zoneName = zone.name
            child.applyStatusUpdates(merged)
        }
    }

    sendEvent(name: "connectionStatus", value: "online")
    sendEvent(name: "lastPoll", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
}

// ========== Child devices ==========

private def deriveChildDNI(String serial) {
    // Same scheme as the old Unified Thermostat driver, so the DNI shape is
    // familiar: <parent DNI>-id<serial>-typeAC
    return "${device.deviceNetworkId}-id${serial}-typeAC"
}

private def findChildDevice(String serial) {
    return getChildDevices()?.find { it.deviceNetworkId == deriveChildDNI(serial) }
}

private def findOrCreateChild(String serial, String zoneName) {
    def child = findChildDevice(serial)
    if (child == null) {
        child = addChildDevice("jlupien", "Mitsubishi Comfort Unit", deriveChildDNI(serial),
            [label: "${device.displayName} - ${zoneName}"])
        infoLog("findOrCreateChild: Created child device ${device.displayName} - ${zoneName} (${serial})")
        child.setUnitId(serial)
        child.setZoneName(zoneName)
        runIn(30, "initializeChildDevices", [overwrite: true])
    }
    return child
}

def initializeChildDevices() {
    debugLog("initializeChildDevices: Initializing all child devices")
    getChildDevices()?.each { it.initialize() }
    debugLog("initializeChildDevices: Initialization complete")
}

// ========== Commands from child devices ==========

/**
 * Send a climate command to one unit.
 * commands is the raw API map, e.g. [operationMode: "heat", spHeat: 20.0].
 * Returns true when the API accepted the command (HTTP 200). Note the API
 * returns 200 even for a disconnected adapter, so true means "accepted",
 * not "the unit acted on it" - the next poll shows whether state moved.
 */
def sendUnitCommand(String serial, Map commands) {
    if (!ensureAuthenticated()) {
        errorLog("sendUnitCommand: Authentication failed, command not sent for ${serial}")
        return false
    }
    debugLog("sendUnitCommand: Sending to ${serial}: ${JsonOutput.toJson(commands)}")
    def result = apiRequest("POST", "/v3/devices/send-command",
        [deviceSerial: serial, commands: commands])
    if (result == null) {
        errorLog("sendUnitCommand: Command failed for ${serial}")
        return false
    }
    debugLog("sendUnitCommand: Command accepted for ${serial}: ${JsonOutput.toJson(result)}")
    infoLog("sendUnitCommand: Command accepted for ${serial}: ${JsonOutput.toJson(commands)}")
    // The cloud cache lags a command by up to ~a minute; poll soon after so
    // the child picks up the new state without waiting for the next interval.
    runIn(60, "poll", [overwrite: true])
    return true
}

/**
 * Fetch one unit's profile (capabilities + setpoint limits). Used by the
 * child at initialize() time. Returns the profile map, or null on failure.
 * The API returns a single-element list for some accounts and a bare object
 * for others - unwrap before returning.
 */
def getUnitProfile(String serial) {
    if (!ensureAuthenticated()) {
        return null
    }
    def profile = apiRequest("GET", "/v3/devices/${serial}/profile")
    if (profile instanceof List) {
        profile = profile ? profile[0] : null
    }
    return (profile instanceof Map) ? profile : null
}

// ========== Authentication ==========

private def ensureAuthenticated() {
    if (!state.accessToken) {
        return login()
    }
    if (state.accessExpiresAt && now() >= state.accessExpiresAt) {
        if (state.refreshToken && (!state.refreshExpiresAt || now() < state.refreshExpiresAt)) {
            if (refreshAccessToken()) {
                return true
            }
            // Refresh failed - fall through to a full login
            warnLog("ensureAuthenticated: Token refresh failed, re-logging in")
        }
        return login()
    }
    return true
}

private def login() {
    debugLog("login: Logging in as ${settings.userName}")
    def resp = rawRequest("POST", "/v3/login", [
        username: settings.userName,
        password: settings.password,
        appVersion: APP_VERSION
    ])
    if (resp == null || resp.status != 200 || !(resp.data instanceof Map)) {
        errorLog("login: Login failed (HTTP ${resp?.status}) - check email/password")
        if (resp?.status == 426) {
            errorLog("login: HTTP 426 means the app version (${APP_VERSION}) was rejected as too old - the driver needs an update")
        }
        state.accessToken = null
        state.refreshToken = null
        return false
    }
    def token = resp.data.token ?: [:]
    if (!token.access) {
        errorLog("login: Login returned HTTP 200 but no access token")
        return false
    }
    storeTokens(token)
    debugLog("login: Login successful")
    return true
}

private def refreshAccessToken() {
    debugLog("refreshAccessToken: Refreshing access token")
    // The refresh response carries the token at the top level (unlike login,
    // which nests it under "token") - accept either shape.
    def resp = rawRequest("POST", "/v3/refresh", [refresh: state.refreshToken],
        ["Authorization": "Bearer ${state.refreshToken}"])
    if (resp == null || resp.status != 200 || !(resp.data instanceof Map)) {
        errorLog("refreshAccessToken: Token refresh failed (HTTP ${resp?.status})")
        state.accessToken = null
        state.refreshToken = null
        return false
    }
    def token = resp.data.token ?: resp.data
    if (!token.access) {
        errorLog("refreshAccessToken: Refresh returned HTTP 200 but no access token")
        return false
    }
    storeTokens(token)
    debugLog("refreshAccessToken: Token refresh successful")
    return true
}

private def storeTokens(Map token) {
    state.accessToken = token.access
    state.refreshToken = token.refresh
    // Access tokens live ~20 minutes, refresh tokens ~30 days. Keep a buffer
    // so we refresh before expiry rather than after a 401.
    state.accessExpiresAt = now() + (18 * 60 * 1000)
    state.refreshExpiresAt = now() + (25 * 24 * 60 * 60 * 1000)
}

// ========== HTTP plumbing ==========

private def defaultHeaders() {
    return [
        "Accept": "application/json",
        "Accept-Language": "en-US,en;q=0.9",
        "x-app-version": APP_VERSION,
        "app-env": "prd",
        "Content-Type": "application/json",
        "User-Agent": USER_AGENT,
        "Cache-Control": "no-cache, no-store",
        "x-allow-cache": "false"
    ]
}

/**
 * One authenticated API call. Handles a 401 by refreshing the token and
 * retrying once. Returns the parsed response body, or null on failure.
 */
private def apiRequest(String method, String path, Map body = null) {
    if (!ensureAuthenticated()) {
        return null
    }
    def resp = rawRequest(method, path, body,
        ["Authorization": "Bearer ${state.accessToken}"])
    if (resp != null && resp.status == 401) {
        debugLog("apiRequest: Got 401 for ${path}, refreshing token and retrying")
        if (refreshAccessToken() || login()) {
            resp = rawRequest(method, path, body,
                ["Authorization": "Bearer ${state.accessToken}"])
        }
    }
    if (resp == null) {
        return null
    }
    if (resp.status >= 400) {
        errorLog("apiRequest: ${method} ${path} failed (HTTP ${resp.status}): ${resp.data}")
        return null
    }
    return resp.data
}

/**
 * One raw HTTP call. Returns [status: int, data: parsed body] or null when
 * the call itself threw. Never logs the body of a login/refresh call (it
 * carries tokens).
 */
private def rawRequest(String method, String path, Map body = null, Map extraHeaders = [:]) {
    def headers = defaultHeaders() + extraHeaders
    def params = [
        uri: "${BASE_URL}${path}",
        headers: headers,
        contentType: "application/json",
        timeout: 30
    ]
    if (body != null) {
        params.body = JsonOutput.toJson(body)
    }
    def result = null
    try {
        if (method == "POST") {
            httpPost(params) { resp ->
                result = [status: resp.status, data: resp.data]
            }
        } else {
            httpGet(params) { resp ->
                result = [status: resp.status, data: resp.data]
            }
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        // Hubitat throws on non-2xx responses instead of calling the closure
        // with the error status. Recover the status + body so callers can
        // tell a 401 (retry after refresh) from other failures.
        def errorBody = null
        try {
            errorBody = e.response?.data
        } catch (Exception ignored) {
            // Some error responses have no parseable body
        }
        debugLog("rawRequest: ${method} ${path} returned HTTP ${e.statusCode}")
        return [status: e.statusCode, data: errorBody]
    } catch (Exception e) {
        errorLog("rawRequest: ${method} ${path} threw ${e}")
        return null
    }
    return result
}

// ========== Logging ==========

def debugLog(String message) {
    if (settings.enableDebugLogging) {
        log.debug "${device.displayName}: ${message}"
    }
}

def infoLog(String message) {
    if (settings.enableInfoLogging != false) {
        log.info "${device.displayName}: ${message}"
    }
}

def warnLog(String message) {
    if (settings.enableWarnLogging != false) {
        log.warn "${device.displayName}: ${message}"
    }
}

def errorLog(String message) {
    if (settings.enableErrorLogging != false) {
        log.error "${device.displayName}: ${message}"
    }
}
