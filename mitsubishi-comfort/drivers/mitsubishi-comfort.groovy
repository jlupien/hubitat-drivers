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
 *  Kumo Cloud v3 API (app-prod.kumocloud.com), with optional local LAN
 *  control of each unit's Wi-Fi adapter when its device key is available.
 *  The parent logs in (JWT), discovers sites/zones, creates one child
 *  thermostat per zone, and polls status on a schedule. Commands go out over
 *  the LAN when local control is ready, otherwise via
 *  POST /v3/devices/send-command; cloud always works as the backstop.
 *
 *  Local control: each adapter accepts an authenticated HTTP PUT on port 80
 *  (the same protocol pykumo / Home Assistant's mitsubishi_comfort use).
 *  Auth is a SHA-256 token derived from the adapter's per-unit password and
 *  cryptoSerial - no AES involved. Keys are fetched from Comfort Cloud on
 *  demand with the Fetch local device keys command: a legacy v2 login first,
 *  then a short Socket.IO/Engine.IO polling session. Mitsubishi stopped
 *  publishing those keys for US Comfort-native accounts around Aug/Sep 2026
 *  (pykumo #78 / hass-kumo #233), so the fetch may report "not published by
 *  cloud" - cloud control keeps working either way, and the localKeyStatus
 *  attribute says which outcome happened instead of failing silently.
 *  Local polls/commands degrade to cloud after failures (3 failed polls, or
 *  1 failed command, degrades local for 5 minutes, then it retries).
 *
 *  Design notes (driver-pair port of an app-based design):
 *  - Local status/commands use SYNC httpPut: the calls are bounded (8 s
 *    timeout), the driver is already sync, and sendUnitCommand's boolean
 *    return contract to the child stays intact.
 *  - The key fetch uses ASYNC asynchttpGet/Post: it is a multi-step
 *    sequence with long-poll waits (up to ~25 s), too long to block a
 *    driver method. Its state machine lives in state.sio. NOTE: drivers
 *    have no atomicState (that is app-only) - the 1.1.0 port used
 *    atomicState.sio and the fetch state never survived past the
 *    fetchLocalKeys execution, so the async callback and watchdog
 *    silently no-opped (2026-11-17 live test).
 *  - Drivers have no dynamic preference pages, so LAN IPs come from one
 *    text preference (comma-separated serial=IP pairs) and/or the
 *    setUnitLanIp command. No subnet scan, no offline-only mode: discovery
 *    always needs the cloud, and the fetch is manual-only (run it again if
 *    a unit is added or Mitsubishi resumes publishing keys).
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
 *  - https://github.com/evdev/mitsubishi-comfort-hubitat (MIT License)
 *    Hubitat app + drivers with hybrid local LAN control; source of the
 *    local PUT protocol, the SHA-256 token construction, and the
 *    Socket.IO/Engine.IO device-key fetch ported below. Line references
 *    in comments (evdev:NNNN) point at its apps/mitsubishi-comfort-app.groovy.
 *  - https://github.com/dlarrick/hass-kumo + pykumo
 *    Local adapter protocol reference (pykumo #78 tracks the key outage).
 *
 *  Change history:
 *    2026-11-13  Jeff Lupien  1.0.0  Initial release - login + token refresh,
 *                                     site/zone discovery, child creation,
 *                                     scheduled polling, send-command.
 *    2026-11-13  Jeff Lupien  1.0.1  Log accepted commands at info level:
 *                                     successful sends left no trace at the
 *                                     default log level, so a live test
 *                                     could not be corroborated from logs.
 *    2026-11-17  Jeff Lupien  1.1.0  Local LAN control (hybrid): per-unit
 *                                     device-key fetch (legacy v2 login +
 *                                     Socket.IO/Engine.IO polling session,
 *                                     ported from evdev), SHA-256 local
 *                                     auth token, sync local PUT for status
 *                                     + commands with cloud fallback,
 *                                     localKeyStatus attribute,
 *                                     connectionPath on each child, per-zone
 *                                     LAN IP preference + commands. Cloud
 *                                     behavior unchanged.
 *    2026-11-17  Jeff Lupien  1.1.1  Fix key fetch: the Socket.IO state
 *                                     machine used atomicState.sio, which
 *                                     does not exist in drivers (app-only),
 *                                     so the fetch stalled silently after
 *                                     "Connecting..." - the async callback
 *                                     and watchdog never saw an active
 *                                     fetch. Now state.sio. Also log when
 *                                     the callback/watchdog fire with no
 *                                     active fetch, instead of returning
 *                                     silently.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import java.security.MessageDigest

@Field static final String VERSION = "1.1.1"
@Field static final String BASE_URL = "https://app-prod.kumocloud.com"
@Field static final String SOCKET_BASE = "https://socket-prod.kumocloud.com"
@Field static final String LEGACY_API_BASE = "https://geo-c.kumocloud.com"
@Field static final String LEGACY_APP_VERSION = "2.2.0"
@Field static final String APP_VERSION = "3.2.4"
// User-Agent captured from the Kumo Cloud iOS app (via ventz's doc).
@Field static final String USER_AGENT = "kumocloud/1122 CFNetwork/3860.200.71 Darwin/25.1.0"

// Local LAN control (evdev:41-55). Local polls/commands degrade to cloud
// after LOCAL_FAIL_THRESHOLD consecutive poll failures (or one failed
// command) and retry after LOCAL_DEGRADE_MS.
@Field static final Integer LOCAL_FAIL_THRESHOLD = 3
@Field static final Long LOCAL_DEGRADE_MS = 5L * 60L * 1000L
@Field static final Integer LOCAL_HTTP_TIMEOUT = 8
@Field static final Integer CRYPTO_SERIAL_MIN_BYTES = 9
@Field static final Long SIO_DEADLINE_MS = 25000L
@Field static final Long SIO_REFORCE_MS = 20000L
@Field static final String LOCAL_STATUS_QUERY = '{"c":{"indoorUnit":{"status":{}}}}'
// The profile/adapter/MHK2 queries are not used yet - kept for the
// diagnostics follow-up (firmware, RSSI, MHK2 humidity).
@Field static final String LOCAL_PROFILE_QUERY = '{"c":{"indoorUnit":{"profile":{}}}}'
@Field static final String LOCAL_ADAPTER_STATUS_QUERY = '{"c":{"adapter":{"status":{}}}}'
@Field static final String LOCAL_ADAPTER_INFO_QUERY = '{"c":{"adapter":{"info":{}}}}'
@Field static final String LOCAL_MHK2_QUERY = '{"c":{"mhk2":{"status":{}}}}'
// Fixed 32-byte prefix of the local auth token construction (evdev:55).
@Field static final String W_PARAM_HEX = "44c73283b498d432ff25f5c8e06a016aef931e68f0a00ea710e36e6338fb22db"

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
        // Local device-key fetch outcome: not fetched, fetching, cached,
        // cached (n of m), not published by cloud, timeout, error.
        attribute "localKeyStatus", "string"

        command "discoverZones"
        command "fetchLocalKeys"
        command "setUnitLanIp", [[name: "serial*", type: "STRING", description: "Unit serial (from the child device's unitId attribute)"],
                                 [name: "ip*", type: "STRING", description: "LAN IP of the unit's Wi-Fi adapter, e.g. 192.168.1.44"]]
        command "clearUnitLanIp", [[name: "serial*", type: "STRING", description: "Unit serial (from the child device's unitId attribute)"]]
    }

    preferences {
        input name: "userName", type: "text", title: "Comfort / Kumo Cloud email", required: true
        input name: "password", type: "password", title: "Comfort / Kumo Cloud password", required: true
        input name: "pollInterval", type: "enum", title: "Status polling interval",
            options: ["1", "5", "10", "15", "30"], defaultValue: "5",
            description: "Minutes between status polls"
        input name: "preferLocal", type: "bool", title: "Prefer local LAN control when available", defaultValue: true,
            description: "When on, polls and commands use the LAN (if keys + IP are set) and fall back to cloud on failure. When off, everything uses the cloud."
        input name: "localUnitIps", type: "text", title: "Unit LAN IPs (optional)",
            description: "Comma-separated serial=IP pairs, e.g. ABCD1234567890EF=192.168.1.44. One per unit. A DHCP reservation on your router keeps the IP stable. You can also set IPs with the setUnitLanIp command instead."
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
    refreshLocalKeyStatus()
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
    parseLocalUnitIps()
    if (settings.userName && settings.password) {
        // Credentials may have changed - drop cached tokens and re-login
        state.accessToken = null
        state.refreshToken = null
        refresh()
    }
    refreshLocalKeyStatus()
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

    def seenSerials = []
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
            seenSerials << serial
            def child = findOrCreateChild(serial, zone.name)
            if (child == null) {
                errorLog("discoverAndPoll: Could not create/find child device for ${zone.name} (${serial})")
                return
            }
            // Harvest cryptoSerial whenever the cloud includes it (it
            // sometimes appears in the device object even when the
            // password is withheld) - one less thing the key fetch must
            // find. Evdev does the same merge (evdev:2224 mergeStatusIntoLocalCreds).
            mergeCryptoSerialFromCloud(serial, adapter)
            // Local first: a live LAN poll replaces the cloud device
            // fetch for this zone. On any local failure, fall through to
            // the cloud path below - cloud always works as the backstop.
            def appliedLocally = false
            if (canUseLocal(serial)) {
                def localStatus = localPollStatus(serial)
                if (localStatus != null) {
                    localStatus.unitId = serial
                    localStatus.zoneId = zone.id
                    localStatus.zoneName = zone.name
                    localStatus.connectionPath = "local"
                    child.applyStatusUpdates(localStatus)
                    appliedLocally = true
                } else {
                    recordLocalFailure(serial)
                    debugLog("discoverAndPoll: Local poll failed for ${zone.name} (${tailSerial(serial)}), falling back to cloud")
                }
            }
            if (!appliedLocally) {
                // Full device info carries fan speed, vane, humidity and RSSI,
                // which the zone adapter object does not. Device data wins on
                // conflicts (same merge the ventz client does with full=True).
                def deviceInfo = apiRequest("GET", "/v3/devices/${serial}") ?: [:]
                mergeCryptoSerialFromCloud(serial, deviceInfo)
                def merged = [:] + adapter + deviceInfo
                merged.unitId = serial
                merged.zoneId = zone.id
                merged.zoneName = zone.name
                merged.connectionPath = "cloud"
                child.applyStatusUpdates(merged)
            }
        }
    }
    if (!seenSerials.isEmpty()) {
        state.knownSerials = seenSerials
    }
    refreshLocalKeyStatus()

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
 * Tries the LAN first when local control is ready; on a local failure it
 * degrades local immediately and falls back to the cloud in the same call.
 * Returns true when either path accepted the command (HTTP 200 / adapter
 * response). Note the cloud API returns 200 even for a disconnected adapter,
 * so true means "accepted", not "the unit acted on it" - the next poll shows
 * whether state moved.
 */
def sendUnitCommand(String serial, Map commands) {
    if (canUseLocal(serial)) {
        infoLog("sendUnitCommand: Sending locally to ${serial}: ${JsonOutput.toJson(commands)}")
        if (sendLocalCommand(serial, commands)) {
            infoLog("sendUnitCommand: Command accepted locally for ${serial}")
            // Local state updates fast; re-poll in a few seconds instead
            // of waiting out the cloud cache lag.
            runIn(5, "poll", [overwrite: true])
            return true
        }
        // A failed local command degrades local immediately (evdev's
        // handleLocalCommandResponse, evdev:2805, does the same) so the
        // next command does not burn another 8 s timeout before falling
        // back to cloud.
        warnLog("sendUnitCommand: Local send failed for ${serial}, falling back to cloud")
        degradeLocal(serial)
    }
    if (!ensureAuthenticated()) {
        errorLog("sendUnitCommand: Authentication failed, command not sent for ${serial}")
        return false
    }
    debugLog("sendUnitCommand: Sending via cloud to ${serial}: ${JsonOutput.toJson(commands)}")
    def result = apiRequest("POST", "/v3/devices/send-command",
        [deviceSerial: serial, commands: commands])
    if (result == null) {
        errorLog("sendUnitCommand: Command failed for ${serial}")
        return false
    }
    debugLog("sendUnitCommand: Command accepted via cloud for ${serial}: ${JsonOutput.toJson(result)}")
    infoLog("sendUnitCommand: Command accepted via cloud for ${serial}: ${JsonOutput.toJson(commands)}")
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

// ========== Local LAN control ==========
//
// Per-unit credentials + LAN IPs live in state.localCreds, keyed by serial:
//   [password: <base64 adapter password>, cryptoSerial: <hex>,
//    address: <LAN IP>, addressLocked: <bool, set from preference/command>]
// Passwords are never logged. Hubitat does not persist in-place mutations
// of a stored map, so every write below copies the map and reassigns it
// (same gotcha evdev's putSlot works around, evdev:488).

private def knownSerials() {
    def serials = state.knownSerials
    return (serials instanceof List) ? ([] + serials) : []
}

private def localCreds(String serial) {
    if (!serial) return null
    def all = state.localCreds
    if (!(all instanceof Map)) return null
    def creds = all[serial]
    return (creds instanceof Map) ? ([:] + creds) : null
}

private def saveLocalCreds(String serial, Map creds) {
    if (!serial) return
    def all = (state.localCreds instanceof Map) ? ([:] + state.localCreds) : [:]
    all[serial] = [:] + (creds ?: [:])
    state.localCreds = all
}

private def ensureLocalCredEntry(String serial) {
    if (!serial) return
    if (!(localCreds(serial) instanceof Map)) saveLocalCreds(serial, [:])
}

/**
 * Local control needs all three: the adapter password, the cryptoSerial,
 * and a LAN IP (evdev:2303 localCredsComplete). The preferLocal setting
 * and the degrade window gate it further (evdev:2293 canUseLocal).
 */
private def canUseLocal(String serial) {
    if (!serial) return false
    if (settings.preferLocal == false) return false
    if (localDegraded(serial)) return false
    return localCredsComplete(serial)
}

private def localCredsComplete(String serial) {
    def creds = localCreds(serial)
    return creds instanceof Map && creds.password && creds.cryptoSerial && creds.address
}

private def localDegraded(String serial) {
    if (!serial) return false
    def until = state.localDegradedUntil
    if (!(until instanceof Map)) return false
    def v = until[serial]
    return (v instanceof Number) && ((v as long) > now())
}

private def localFailCount(String serial) {
    def counts = state.localFailCounts
    if (!(counts instanceof Map)) return 0
    def v = counts[serial]
    return (v instanceof Number) ? (v as int) : 0
}

private def recordLocalSuccess(String serial) {
    if (!serial) return
    def counts = (state.localFailCounts instanceof Map) ? ([:] + state.localFailCounts) : [:]
    counts[serial] = 0
    state.localFailCounts = counts
}

private def recordLocalFailure(String serial) {
    if (!serial) return
    def fails = localFailCount(serial) + 1
    def counts = (state.localFailCounts instanceof Map) ? ([:] + state.localFailCounts) : [:]
    counts[serial] = fails
    state.localFailCounts = counts
    if (fails >= LOCAL_FAIL_THRESHOLD) {
        degradeLocal(serial)
    }
}

private def degradeLocal(String serial) {
    if (!serial) return
    def until = (state.localDegradedUntil instanceof Map) ? ([:] + state.localDegradedUntil) : [:]
    until[serial] = now() + LOCAL_DEGRADE_MS
    state.localDegradedUntil = until
    warnLog("degradeLocal: Local control degraded for ${tailSerial(serial)} for ${LOCAL_DEGRADE_MS / 60000} minutes, using cloud")
}

private def clearLocalDegrade(String serial) {
    if (!serial) return
    def until = (state.localDegradedUntil instanceof Map) ? ([:] + state.localDegradedUntil) : [:]
    until[serial] = 0L
    state.localDegradedUntil = until
    def counts = (state.localFailCounts instanceof Map) ? ([:] + state.localFailCounts) : [:]
    counts[serial] = 0
    state.localFailCounts = counts
}

// --- Local HTTP: SHA-256 token + sync PUT (evdev:2344-2420) ---
//
// The adapter authenticates each request with a token in the query string:
//   PUT http://<ip>/api?m=<token>
//   token = hex( SHA256( hexBytes( W_PARAM_HEX
//                                  + hex( SHA256( passwordBytes + bodyBytes ) )
//                                  + "084000" + 12 zero bytes
//                                  + serialHex[16:18] + serialHex[8:16] + serialHex[0:8] ) ) )
// The password arrives base64-encoded from the cloud; the cryptoSerial is
// hex. There is no AES/Cipher anywhere on this path - SHA-256 only.
// Hex encode/decode are hand-rolled pure Groovy so the driver does not
// depend on hubitat.helper.HexUtils.

private def hexToBytes(String hex) {
    if (!hex) return new byte[0]
    def clean = hex.trim()
    def bytes = new byte[clean.length().intdiv(2)]
    for (int i = 0; i < bytes.length; i++) {
        bytes[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16)
    }
    return bytes
}

private def bytesToHex(byte[] bytes) {
    def hex = "0123456789abcdef"
    def sb = new StringBuilder(bytes.length * 2)
    bytes.each { b ->
        // Cast to int first so a negative byte (0x80-0xFF) masks to its
        // unsigned value instead of depending on Groovy's promotion rules.
        int v = (b as int) & 0xFF
        sb.append(hex.charAt((v >> 4) & 0xF))
        sb.append(hex.charAt(v & 0xF))
    }
    return sb.toString()
}

private def sha256Bytes(byte[] data) {
    return MessageDigest.getInstance("SHA-256").digest(data)
}

private def sha256Concat(byte[] a, byte[] b) {
    MessageDigest md = MessageDigest.getInstance("SHA-256")
    md.update(a)
    md.update(b)
    return md.digest()
}

private def computeKumoToken(String passwordB64, String cryptoSerialHex, String bodyStr) {
    try {
        byte[] password = passwordB64.decodeBase64()
        byte[] postData = bodyStr.getBytes("UTF-8")
        byte[] dataHash = sha256Concat(password, postData)
        String serialHex = cryptoSerialHex.trim().toLowerCase()
        if (serialHex.length() < CRYPTO_SERIAL_MIN_BYTES * 2) return null

        // Build the 88-byte intermediate buffer as hex (avoids
        // System.arraycopy in the sandbox).
        StringBuilder sb = new StringBuilder(176)
        sb.append(W_PARAM_HEX)
        sb.append(bytesToHex(dataHash))
        sb.append("084000")
        for (int i = 0; i < 12; i++) sb.append("00")
        sb.append(serialHex.substring(16, 18))
        sb.append(serialHex.substring(8, 16))
        sb.append(serialHex.substring(0, 8))

        return bytesToHex(sha256Bytes(hexToBytes(sb.toString())))
    } catch (Exception e) {
        errorLog("computeKumoToken failed: ${e.message}")
        return null
    }
}

/**
 * One sync local PUT to the adapter. Returns the parsed JSON body, or null
 * on any failure (unreachable, timeout, non-2xx, bad token, unparseable).
 * Callers treat a Map with a non-null "r" key as success (evdev:2455).
 */
private def localPutSync(String serial, String bodyStr) {
    def creds = localCreds(serial)
    if (!(creds instanceof Map) || !creds.password || !creds.cryptoSerial || !creds.address) {
        return null
    }
    def token = computeKumoToken(creds.password as String, creds.cryptoSerial as String, bodyStr)
    if (!token) {
        debugLog("localPutSync: Could not compute local auth token for ${tailSerial(serial)}")
        return null
    }
    def result = null
    try {
        httpPut([
            uri: "http://${creds.address}/api?m=${token}",
            headers: [
                "Accept": "application/json, text/plain, */*",
                "Content-Type": "application/json"
            ],
            body: bodyStr,
            contentType: "application/json",
            requestContentType: "application/json",
            timeout: LOCAL_HTTP_TIMEOUT
        ]) { resp ->
            result = resp.data
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        debugLog("localPutSync: Local PUT to ${tailSerial(serial)} returned HTTP ${e.statusCode}")
        return null
    } catch (Exception e) {
        debugLog("localPutSync: Local PUT to ${tailSerial(serial)} threw ${e}")
        return null
    }
    if (result instanceof Map) return result
    // If the adapter answers with a non-JSON content type, Hubitat hands
    // back a raw String instead of a parsed map - parse it ourselves
    // (same fallback evdev's parseAsyncBody applies, evdev:2151).
    if (result instanceof String || result instanceof GString) {
        try {
            def parsed = new JsonSlurper().parseText(result.toString())
            return (parsed instanceof Map) ? parsed : null
        } catch (Exception ignored) {
            return null
        }
    }
    return null
}

/**
 * Local status poll. Returns a status map shaped like the cloud merge the
 * child already consumes (operationMode, fanSpeed, spHeat, spCool,
 * roomTemp, humidity), or null on failure. Field mapping from
 * evdev:2506 applyLocalStatusResponse.
 */
private def localPollStatus(String serial) {
    def parsed = localPutSync(serial, LOCAL_STATUS_QUERY)
    if (!(parsed instanceof Map) || parsed.r == null) {
        return null
    }
    def raw = parsed.r?.indoorUnit?.status
    if (!(raw instanceof Map)) {
        return null
    }
    recordLocalSuccess(serial)
    def status = [:]
    status.operationMode = raw.mode
    status.airDirection = raw.vaneDir
    status.fanSpeed = raw.fanSpeed
    status.spHeat = raw.spHeat
    status.spCool = raw.spCool
    status.roomTemp = raw.roomTemp
    status.connected = true
    if (raw.humidity != null) status.humidity = raw.humidity
    return status
}

private def cloudToLocalField(String cloudField) {
    switch (cloudField) {
        case "operationMode": return "mode"
        case "airDirection": return "vaneDir"
        default: return cloudField
    }
}

/**
 * Send one command over the LAN. Cloud field names map to local ones
 * (operationMode -> mode, airDirection -> vaneDir); setpoints and fan
 * speed pass through unchanged (evdev:2771 sendLocalCommands).
 */
private def sendLocalCommand(String serial, Map commands) {
    def localStatus = [:]
    commands.each { k, v ->
        localStatus[cloudToLocalField(k as String)] = v
    }
    def body = JsonOutput.toJson([c: [indoorUnit: [status: localStatus]]])
    def parsed = localPutSync(serial, body)
    if (parsed instanceof Map && parsed.r != null) {
        recordLocalSuccess(serial)
        return true
    }
    return false
}

// --- LAN IPs: preference + commands ---

/**
 * Parse the localUnitIps preference (comma-separated serial=IP pairs) into
 * state.localCreds. Runs on updated(). Entries are addressLocked: unlike
 * evdev, this driver has no subnet scan, so a degraded unit keeps its IP
 * instead of dropping it - there is nothing to re-discover it with.
 */
private def parseLocalUnitIps() {
    def raw = settings.localUnitIps?.toString()?.trim()
    if (!raw) return
    raw.split(",").each { pair ->
        def parts = pair.trim().split("=", 2)
        if (parts.size() == 2) {
            def serial = parts[0].trim()
            def ip = parts[1].trim()
            if (serial && (ip ==~ /\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}/)) {
                ensureLocalCredEntry(serial)
                def creds = localCreds(serial) ?: [:]
                creds.address = ip
                creds.addressLocked = true
                creds.remove("addressSuppressed")
                saveLocalCreds(serial, creds)
                debugLog("parseLocalUnitIps: LAN IP for ${tailSerial(serial)} set to ${ip}")
            } else {
                warnLog("parseLocalUnitIps: Ignoring malformed entry '${pair.trim()}' - use serial=IP, comma-separated")
            }
        } else if (pair.trim()) {
            warnLog("parseLocalUnitIps: Ignoring malformed entry '${pair.trim()}' - use serial=IP, comma-separated")
        }
    }
}

def setUnitLanIp(String serial, String ip) {
    serial = serial?.toString()?.trim()
    ip = ip?.toString()?.trim()
    if (!serial || !ip) {
        warnLog("setUnitLanIp: Serial and IP are both required")
        return
    }
    if (!(ip ==~ /\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}/)) {
        warnLog("setUnitLanIp: '${ip}' is not a valid IPv4 address")
        return
    }
    ensureLocalCredEntry(serial)
    def creds = localCreds(serial) ?: [:]
    creds.address = ip
    creds.addressLocked = true
    creds.remove("addressSuppressed")
    saveLocalCreds(serial, creds)
    // Clear any degrade so the next poll/command tries local right away.
    clearLocalDegrade(serial)
    infoLog("setUnitLanIp: LAN IP for ${tailSerial(serial)} set to ${ip}")
    refreshLocalKeyStatus()
}

def clearUnitLanIp(String serial) {
    serial = serial?.toString()?.trim()
    if (!serial) {
        warnLog("clearUnitLanIp: Serial is required")
        return
    }
    def creds = localCreds(serial)
    if (creds instanceof Map) {
        creds = [:] + creds
        creds.remove("address")
        creds.remove("addressLocked")
        saveLocalCreds(serial, creds)
        infoLog("clearUnitLanIp: LAN IP cleared for ${tailSerial(serial)} - that unit is cloud-only until an IP is set again")
    }
    refreshLocalKeyStatus()
}

// --- cryptoSerial harvesting from cloud responses ---

private def mergeCryptoSerialFromCloud(String serial, Map source) {
    if (!serial || !(source instanceof Map)) return
    def crypto = source.cryptoSerial ?: source.crypto_serial
    if (!crypto) return
    ensureLocalCredEntry(serial)
    def creds = localCreds(serial) ?: [:]
    if (creds.cryptoSerial != crypto.toString()) {
        creds.cryptoSerial = crypto.toString()
        saveLocalCreds(serial, creds)
        debugLog("mergeCryptoSerialFromCloud: cryptoSerial captured from cloud for ${tailSerial(serial)}")
    }
}

// --- Fetch-status attribute ---

private def setLocalKeyStatus(String value) {
    sendEvent(name: "localKeyStatus", value: value)
}

/**
 * Recompute localKeyStatus from cached state. Never clobbers "fetching"
 * while a fetch is running; finalizeCredFetch owns the status then.
 */
private def refreshLocalKeyStatus() {
    if (sioFetchRunning()) return
    def serials = knownSerials()
    if (serials.isEmpty()) {
        setLocalKeyStatus("not fetched")
        return
    }
    def cached = serials.count { localCreds(it as String)?.password }
    if (cached == serials.size()) {
        setLocalKeyStatus(serials.size() == 1 ? "cached" : "cached (${cached} of ${serials.size()})")
    } else if (cached > 0) {
        setLocalKeyStatus("cached (${cached} of ${serials.size()})")
    } else if (state.v3AdapterOmitsPassword == true || state.legacyZoneTableEmpty == true) {
        setLocalKeyStatus("not published by cloud")
    } else {
        setLocalKeyStatus("not fetched")
    }
}

// ========== Device-key fetch (Socket.IO + legacy login) ==========
//
// Around August 1, 2026 Mitsubishi stopped including password and
// cryptoSerial in Comfort Cloud V3 REST and Socket.IO adapter_update. The
// official Comfort app no longer receives them either (pykumo issue 78).
// Cached adapter keys still authenticate locally; new fetches often return
// nothing. This section tries the legacy v2 login and then a short socket
// session, in case Mitsubishi restores the fields - and reports the outcome
// explicitly via localKeyStatus instead of failing silently.
// Ported from evdev:2832-3600, 3636-3816, 3947-3960.

def fetchLocalKeys() {
    if (!settings.userName || !settings.password) {
        warnLog("fetchLocalKeys: Comfort email/password not set - enter them in Preferences first")
        setLocalKeyStatus("error")
        return
    }
    if (!ensureAuthenticated()) {
        errorLog("fetchLocalKeys: Authentication failed - check email/password")
        setLocalKeyStatus("error")
        return
    }
    def serials = knownSerials()
    if (serials.isEmpty()) {
        infoLog("fetchLocalKeys: No zones discovered yet - discovering first")
        discoverAndPoll()
        serials = knownSerials()
    }
    if (serials.isEmpty()) {
        warnLog("fetchLocalKeys: Still no zones discovered - cannot fetch device keys")
        setLocalKeyStatus("error")
        return
    }
    startSocketIoPasswordFetch(true, false)
}

private def sioState() {
    def s = state.sio
    return (s instanceof Map) ? ([:] + (s as Map)) : [:]
}

private def sioStateSet(Map sio) {
    state.sio = sio
}

private def sioFetchRunning() {
    return sioState().active == true
}

private def startSocketIoPasswordFetch(Boolean forceAll, Boolean isRetry) {
    def existing = sioState()
    if (existing.active == true) {
        def deadline = (existing.deadline ?: 0L) as long
        if (!forceAll && now() <= deadline) return
        infoLog("startSocketIoPasswordFetch: Resetting previous device-key fetch before starting a new one")
        existing.active = false
        sioStateSet(existing)
    }
    def need = []
    knownSerials().each { serial ->
        def s = serial as String
        def creds = localCreds(s)
        if (forceAll || !(creds instanceof Map) || !creds.password) need << s
    }
    if (need.isEmpty()) {
        infoLog("startSocketIoPasswordFetch: All zones already have a local device key")
        refreshLocalKeyStatus()
        return
    }
    def userId = jwtUserId()
    if (!userId) userId = fetchKumoUserIdFromAccount()
    if (userId) {
        state.kumoUserId = userId
    } else {
        warnLog("startSocketIoPasswordFetch: Comfort Cloud user id not found before socket fetch - adapter keys may not arrive")
    }
    setLocalKeyStatus("fetching")
    infoLog("startSocketIoPasswordFetch: Fetching local device keys for ${need.size()} zone(s)")
    infoLog("startSocketIoPasswordFetch: Trying legacy Comfort Cloud login before socket fetch")
    tryLegacyPasswordFetch(need)
    need = need.findAll { s -> !localCreds(s as String)?.password }
    if (need.isEmpty()) {
        // Legacy login found everything; the socket session never started,
        // so there is no sio state for finalizeCredFetch to count from -
        // report straight from the cached creds instead.
        infoLog("startSocketIoPasswordFetch: Legacy login provided all device keys")
        refreshLocalKeyStatus()
        return
    }
    infoLog("startSocketIoPasswordFetch: Socket.IO fetch starting for ${need.size()} zone(s), accountSubscribe=${userId ? 'yes' : 'NO'}")
    sioStateSet([
        active: true,
        need: need,
        got: [:],
        deadline: now() + SIO_DEADLINE_MS,
        step: "open",
        retryOnce: isRetry == true,
        nsWaitTries: 0,
        userId: userId,
        lastForceAt: 0L,
        busy: false,
        adapterSeen: []
    ])
    runIn(12, "socketIoWatchdog", [overwrite: true])
    socketIoStartAsync()
}

private def socketIoStartAsync() {
    infoLog("socketIoStartAsync: Connecting to Comfort Cloud for device keys")
    socketIoSend("GET", null, "open")
}

private def socketIoHeaders() {
    return [
        "Accept": "text/plain, */*",
        "Authorization": "Bearer ${state.accessToken}",
        "User-Agent": "kumocloud/1122",
        "x-app-version": APP_VERSION
    ]
}

// One Engine.IO polling request. Only one may be in flight at a time; a
// second is deferred and flushed when the first completes (evdev:2949).
private def socketIoSend(String method, String body, String step) {
    def sio = sioState()
    if (sio.active != true) return
    if (sio.busy == true) {
        infoLog("socketIoSend: Socket.IO defer ${method} ${step}; in-flight ${sio.step}")
        sio.deferMethod = method
        sio.deferBody = (body ?: "").toString()
        sio.deferStep = step
        sioStateSet(sio)
        return
    }
    sio.busy = true
    sio.step = step
    sio.remove("deferMethod")
    sio.remove("deferBody")
    sio.remove("deferStep")
    sioStateSet(sio)
    def query = [EIO: "4", transport: "polling", t: now().toString()]
    if (step != "open" && sio.sid) query.sid = sio.sid.toString()
    def rawBody = (body ?: "").toString()
    def params = [
        uri: "${SOCKET_BASE}/socket.io/",
        query: query,
        headers: socketIoHeaders(),
        contentType: "text/plain",
        requestContentType: "text/plain",
        timeout: (method == "GET") ? 30 : 15
    ]
    if (method == "POST") {
        params.headers = socketIoHeaders() + ["Content-Type": "text/plain;charset=UTF-8"]
        params.body = rawBody
        infoLog("socketIoSend: Socket.IO POST ${step} chars=${rawBody.length()}")
    }
    def data = [step: step, method: method, sid: (sio.sid ?: "")]
    try {
        if (method == "POST") asynchttpPost("socketIoAsyncCallback", params, data)
        else asynchttpGet("socketIoAsyncCallback", params, data)
    } catch (Exception e) {
        sio = sioState()
        sio.busy = false
        sioStateSet(sio)
        finishSocketIo("send error: ${e.message}")
    }
}

private def socketIoFlushDeferred() {
    def sio = sioState()
    if (sio.active != true || sio.busy == true) return
    def step = sio.deferStep as String
    if (!step) return
    def method = (sio.deferMethod ?: "GET") as String
    def body = (sio.deferBody ?: "").toString()
    sio.remove("deferMethod")
    sio.remove("deferBody")
    sio.remove("deferStep")
    sioStateSet(sio)
    socketIoSend(method, body, step)
}

private def socketIoAsyncParsed(response) {
    try {
        if (response?.respondsTo("getJson")) {
            def json = response.getJson()
            if (json instanceof Map || json instanceof List) return json
        }
    } catch (ignored) {}
    try {
        def d = null
        if (response?.respondsTo("getData")) d = response.getData()
        else if (response?.hasProperty("data")) d = response.data
        if (d instanceof Map || d instanceof List) return d
    } catch (ignored) {}
    return null
}

private def socketIoAsyncText(response, parsed) {
    def chunks = []
    try {
        def d = null
        if (response?.respondsTo("getData")) d = response.getData()
        else if (response?.hasProperty("data")) d = response.data
        if (d instanceof Map || d instanceof List) {
            // parsed separately
        } else if (d instanceof byte[]) {
            chunks << new String(d, "UTF-8")
        } else if (d) {
            chunks << d.toString()
        }
    } catch (ignored) {}
    try {
        if (response?.respondsTo("getErrorData")) {
            def err = response.getErrorData()
            if (err) chunks << err.toString()
        }
    } catch (ignored) {}
    try {
        if (response?.respondsTo("getErrorMessage")) {
            def err = response.getErrorMessage()
            if (err && err != "null") chunks << err.toString()
        }
    } catch (ignored) {}
    def text = chunks.find { it } ?: ""
    if (!text && parsed != null) {
        try { text = JsonOutput.toJson(parsed) } catch (ignored) {}
    }
    return text
}

private def socketIoParsedKind(parsed) {
    if (parsed instanceof Map) return "map"
    if (parsed instanceof List) return "list"
    if (parsed != null) return "yes"
    return "none"
}

// Never log a password or token, even in a debug snippet.
private def socketIoLogSnippet(String text) {
    if (!text) return "(empty)"
    def snippet = text.take(180)
    snippet = snippet.replaceAll("(?i)\"password\"\\s*:\\s*\"[^\"]*\"", "\"password\":\"***\"")
    snippet = snippet.replaceAll("(?i)\"token\"\\s*:\\s*\"[^\"]*\"", "\"token\":\"***\"")
    return snippet.replace("\n", " ")
}

private def socketIoHasHttpError(response) {
    try {
        return response?.respondsTo("hasError") && response.hasError()
    } catch (ignored) {
        return false
    }
}

// Async callback for every Socket.IO request. Must stay public: Hubitat
// invokes asynchttp callbacks by method name.
def socketIoAsyncCallback(response, data) {
    def sio = sioState()
    if (sio.active != true) {
        infoLog("socketIoAsyncCallback: Socket.IO callback arrived with no active fetch; ignoring")
        return
    }
    def step = (data?.step ?: sio.step) as String
    def callbackSid = data?.sid?.toString()
    if (step != "open" && callbackSid && sio.sid && callbackSid != sio.sid.toString()) {
        infoLog("socketIoAsyncCallback: Socket.IO ignore stale ${step}")
        return
    }
    sio.busy = false
    sioStateSet(sio)
    def method = (data?.method ?: "?") as String
    def status = safeStatus(response)
    def parsed = socketIoAsyncParsed(response)
    def text = socketIoAsyncText(response, parsed)
    def errFlag = socketIoHasHttpError(response)
    infoLog("socketIoAsyncCallback: Socket.IO ${method} ${step} HTTP ${status} err=${errFlag} parsed=${socketIoParsedKind(parsed)} len=${text.length()} ${socketIoLogSnippet(text)}")

    if (status == 401) {
        socketIoRetryAuth("unauthorized")
        socketIoFlushDeferred()
        return
    }

    def timedOut = (status == 408 || status == 0 || (errFlag && status != 200 && !text && parsed == null))
    if (step == "open" && status != 200 && !timedOut) {
        finishSocketIo("handshake HTTP ${status}")
        return
    }
    if (status == 400) {
        warnLog("socketIoAsyncCallback: Socket.IO session error at ${step}: ${socketIoLogSnippet(text)}")
        socketIoRetryAuth("session HTTP 400")
        socketIoFlushDeferred()
        return
    }

    extractSocketIoPasswords(text, parsed)
    if (sioState().active != true) return
    if (socketIoNeedRemaining().isEmpty()) {
        finishSocketIo("complete")
        return
    }
    if (socketIoAllAdapterUpdatesLackPassword()) {
        infoLog("socketIoAsyncCallback: Socket.IO adapter_update omitted the local device key; stopping fetch")
        finishSocketIo("v3-no-password")
        return
    }

    if (timedOut) {
        if (step == "open") {
            finishSocketIo("handshake timeout")
            return
        }
        infoLog("socketIoAsyncCallback: Socket.IO ${step} timed out; continuing")
        if (step == "poll" || step == "pong" || step == "reforcePost") {
            runIn(1, "socketIoPoll", [overwrite: true])
            socketIoFlushDeferred()
            return
        }
        if (step?.endsWith("Post")) {
            socketIoHandleStep(step, "", null)
            socketIoFlushDeferred()
            return
        }
        socketIoSend("GET", null, step)
        socketIoFlushDeferred()
        return
    }

    if (step != "open" && extractEngineIoOpenPacket(text)) {
        warnLog("socketIoAsyncCallback: Socket.IO server started a new handshake during ${step}")
        socketIoRetryAuth("session reset")
        socketIoFlushDeferred()
        return
    }

    def msgs = splitEngineIoMessages(text)
    def hasPing = msgs.any { stripEngineIoLengthPrefix(it) == "2" }
    if (hasPing && step != "pong") {
        sio = sioState()
        sio.resumeStep = step
        sio.resumeText = text
        sioStateSet(sio)
        socketIoSend("POST", "3", "pong")
        socketIoFlushDeferred()
        return
    }

    if (step == "pong") {
        sio = sioState()
        step = (sio.resumeStep ?: "poll") as String
        text = (sio.resumeText ?: text) as String
        sio.remove("resumeStep")
        sio.remove("resumeText")
        sioStateSet(sio)
    }

    socketIoHandleStep(step, text, parsed)
    socketIoFlushDeferred()
}

private def socketIoRetryAuth(String reason) {
    def sio = sioState()
    if (sio.retryOnce != true && ensureAuthenticated()) {
        sio.active = false
        sioStateSet(sio)
        startSocketIoPasswordFetch(true, true)
        return
    }
    finishSocketIo(reason)
}

// Step machine: open -> nsPost -> nsWait -> acctPost -> devPost ->
// forcePost -> poll (re-force every 20 s until the deadline).
private def socketIoHandleStep(String step, String text, parsed) {
    def sio = sioState()
    if (sio.active != true) return
    if (step == "open") {
        def sid = extractSocketIoSid(text, parsed)
        if (!sid) {
            finishSocketIo("handshake missing sid")
            return
        }
        sio.sid = sid
        sioStateSet(sio)
        infoLog("socketIoHandleStep: Socket.IO handshake ok")
        socketIoSend("POST", "40", "nsPost")
        return
    }
    if (step == "nsPost") {
        socketIoSend("GET", null, "nsWait")
        return
    }
    if (step == "nsWait") {
        if (engineIoHasConnectError(text)) {
            warnLog("socketIoHandleStep: Socket.IO namespace rejected")
            socketIoRetryAuth("namespace rejected")
            return
        }
        if (!engineIoHasConnectAck(text) && ((sio.nsWaitTries ?: 0) as int) < 6) {
            sio.nsWaitTries = ((sio.nsWaitTries ?: 0) as int) + 1
            sioStateSet(sio)
            socketIoSend("GET", null, "nsWait")
            return
        }
        def userId = sio.userId ?: jwtUserId()
        if (userId) {
            sio.userId = userId.toString()
            sioStateSet(sio)
            infoLog("socketIoHandleStep: Socket.IO account-level subscribe")
            socketIoSend("POST", socketIoEvent(["subscribe", "", userId.toString()]), "acctPost")
        } else {
            warnLog("socketIoHandleStep: No Comfort Cloud user id - device keys may not arrive")
            socketIoPostDeviceSubscribes()
        }
        return
    }
    if (step == "acctPost") {
        socketIoPostDeviceSubscribes()
        return
    }
    if (step == "acctWait") {
        socketIoPostDeviceSubscribes()
        return
    }
    if (step == "devPost") {
        socketIoPostForceAndStatus()
        return
    }
    if (step == "devWait") {
        socketIoPostForceAndStatus()
        return
    }
    if (step == "forcePost" || step == "statusPost" || step == "reforcePost") {
        infoLog("socketIoHandleStep: Waiting for device keys from Comfort Cloud")
        socketIoSend("GET", null, "poll")
        return
    }
    if (step == "pong" || step == "poll") {
        if (now() > ((sio.deadline ?: 0L) as long)) {
            finishSocketIo("timeout")
            return
        }
        def lastForce = (sio.lastForceAt ?: 0L) as long
        if (socketIoNeedRemaining() && (now() - lastForce) > SIO_REFORCE_MS) {
            if (socketIoAllAdapterUpdatesLackPassword()) {
                finishSocketIo("v3-no-password")
                return
            }
            socketIoReforceRemaining()
            return
        }
        socketIoSend("GET", null, "poll")
        return
    }
    warnLog("socketIoHandleStep: Socket.IO unknown step ${step}")
    finishSocketIo("unknown step")
}

private def socketIoPostDeviceSubscribes() {
    def serials = (sioState().need ?: []) as List
    if (!serials) {
        finishSocketIo("complete")
        return
    }
    def subs = serials.collect { s -> socketIoEvent(["subscribe", s.toString()]) }.join("\u001e")
    socketIoSend("POST", subs.toString(), "devPost")
}

private def socketIoPostForceAndStatus() {
    def sio = sioState()
    def serials = (sio.need ?: []) as List
    def packets = []
    serials.each { s ->
        packets << socketIoEvent(["force_adapter_request", s.toString(), "adapterStatus"])
        packets << socketIoEvent(["force_adapter_request", s.toString(), "adapterInfo"])
    }
    packets << socketIoEvent(["device_status_v2", ""])
    serials.each { s -> packets << socketIoEvent(["device_status_v2", s.toString()]) }
    sio.lastForceAt = now()
    sioStateSet(sio)
    infoLog("socketIoPostForceAndStatus: Socket.IO force adapterStatus for ${serials.size()} zone(s)")
    socketIoSend("POST", packets.join("\u001e").toString(), "forcePost")
}

private def socketIoReforceRemaining() {
    def remaining = socketIoNeedRemaining()
    if (!remaining) {
        socketIoSend("GET", null, "poll")
        return
    }
    def sio = sioState()
    def packets = []
    if (sio.userId) packets << socketIoEvent(["subscribe", "", sio.userId.toString()])
    remaining.each { s ->
        packets << socketIoEvent(["subscribe", s.toString()])
        packets << socketIoEvent(["force_adapter_request", s.toString(), "adapterStatus"])
    }
    sio.lastForceAt = now()
    sioStateSet(sio)
    infoLog("socketIoReforceRemaining: Socket.IO re-force adapterStatus for ${remaining.size()} zone(s)")
    socketIoSend("POST", packets.join("\u001e").toString(), "reforcePost")
}

// runIn target (timeout path in the async callback). Must stay public.
def socketIoPoll() {
    def sio = sioState()
    if (sio.active != true) return
    if (now() > ((sio.deadline ?: 0L) as long)) {
        finishSocketIo("timeout")
        return
    }
    socketIoSend("GET", null, "poll")
}

private def socketIoEvent(List args) {
    return "42" + JsonOutput.toJson(args)
}

// Watchdog: re-arms every 12 s until the fetch finishes or hits its
// deadline, so a stalled session cannot run forever. Must stay public.
def socketIoWatchdog() {
    def sio = sioState()
    if (sio.active != true) {
        infoLog("socketIoWatchdog: No active fetch; watchdog standing down")
        return
    }
    if (socketIoNeedRemaining().isEmpty()) {
        finishSocketIo("complete")
        return
    }
    if (now() > ((sio.deadline ?: 0L) as long)) {
        finishSocketIo("timeout")
        return
    }
    runIn(12, "socketIoWatchdog", [overwrite: true])
}

// --- Engine.IO / Socket.IO packet helpers (evdev:3335-3395) ---

private def extractSocketIoSid(String text, parsed = null) {
    if (parsed instanceof Map && parsed.sid) return parsed.sid as String
    def jsonText = extractEngineIoOpenPacket(text)
    if (jsonText) {
        try {
            def sid = new JsonSlurper().parseText(jsonText)?.sid as String
            if (sid) return sid
        } catch (ignored) {}
    }
    try {
        def json = new JsonSlurper().parseText(text)
        if (json instanceof Map && json.sid) return json.sid as String
    } catch (ignored) {}
    return null
}

private def extractEngineIoOpenPacket(String text) {
    if (!text) return null
    def found = null
    splitEngineIoMessages(text).each { raw ->
        if (found) return
        def msg = stripEngineIoLengthPrefix(raw)
        if (msg?.startsWith("0") && msg.length() > 1 && !msg.startsWith("40")) {
            found = msg.substring(1)
        }
    }
    return found
}

private def engineIoHasConnectError(String text) {
    return splitEngineIoMessages(text).any { stripEngineIoLengthPrefix(it)?.startsWith("44") }
}

private def engineIoHasConnectAck(String text) {
    return splitEngineIoMessages(text).any { msg ->
        def m = stripEngineIoLengthPrefix(msg)
        m == "40" || m?.startsWith("40{") || m?.startsWith("40,")
    }
}

private def splitEngineIoMessages(String raw) {
    if (!raw) return []
    return raw.contains("\u001e") ? raw.split("\u001e") as List : [raw]
}

private def stripEngineIoLengthPrefix(String msg) {
    if (!msg) return msg
    if (msg.contains(":") && msg.split(":")[0].isNumber()) {
        return msg.split(":", 2)[1]
    }
    return msg
}

// --- Password harvesting (evdev:3397-3530) ---

private def extractSocketIoPasswords(String raw, parsed = null) {
    harvestPasswordNode(parsed)
    if (!raw) return
    splitEngineIoMessages(raw).each { packet ->
        def msg = stripEngineIoLengthPrefix(packet)
        if (!msg) return
        def jsonText = msg
        if (msg.startsWith("42")) jsonText = msg.substring(2)
        else if (!(msg.startsWith("[") || msg.startsWith("{"))) return
        try {
            harvestPasswordNode(new JsonSlurper().parseText(jsonText))
        } catch (Exception e) {
            debugLog("extractSocketIoPasswords: Socket.IO packet parse skipped: ${e.message}")
        }
    }
}

private def harvestPasswordNode(node) {
    if (node == null) return
    if (node instanceof List) {
        if (node.size() >= 2) {
            def eventName = node[0]
            if (eventName instanceof String && eventName != "adapter_update" && eventName != "device_update" && eventName != "subscribed") {
                infoLog("harvestPasswordNode: Socket.IO event ${eventName}")
            }
            def info = node[1]
            def extra = (node.size() > 2) ? node[2] : null
            if (info instanceof Map) {
                harvestPasswordMap(info, eventName as String)
            } else if (extra instanceof Map) {
                harvestPasswordMap(extra, eventName as String)
            }
        }
        node.each { harvestPasswordNode(it) }
        return
    }
    if (node instanceof Map) harvestPasswordMap(node, null)
}

private def harvestPasswordMap(Map info, String eventName) {
    def pw = firstPasswordField(info)
    def s = (jsonGet(info, "deviceSerial") ?: jsonGet(info, "serial")) as String
    if (eventName == "adapter_update") {
        def keys = jsonKeys(info).collect { it?.toString() }.join(",")
        infoLog("harvestPasswordMap: Socket.IO adapter_update serial=${s ? tailSerial(s) : 'none'} hasKey=${pw ? 'yes' : 'no'} keys=${keys}")
        if (s && !pw) socketIoMarkAdapterSeen(s)
    }
    if (s && pw) {
        socketIoSavePassword(s, pw)
        def crypto = jsonGet(info, "cryptoSerial") ?: jsonGet(info, "crypto_serial")
        if (crypto) saveCryptoSerial(s, crypto)
        return
    }
    jsonKeys(info).each { k ->
        def v = jsonGet(info, k)
        if (jsonIsMap(v) || jsonIsList(v)) harvestPasswordNode(v)
    }
}

private def socketIoMarkAdapterSeen(String serial) {
    if (!serial) return
    def sio = sioState()
    def seen = (sio.adapterSeen instanceof List) ? (sio.adapterSeen as List) : []
    if (!seen.find { it?.toString()?.equalsIgnoreCase(serial) }) {
        seen << serial
        sio.adapterSeen = seen
        sioStateSet(sio)
    }
}

// True when at least one adapter_update arrived without a password and no
// keys were received at all - the signature of Mitsubishi withholding keys
// (evdev:3511). This is what produces "not published by cloud".
private def socketIoAllAdapterUpdatesLackPassword() {
    def sio = sioState()
    def got = sio.got
    if (got instanceof Map && !got.isEmpty()) return false
    def seen = sio.adapterSeen
    return (seen instanceof List) && !seen.isEmpty()
}

private def firstPasswordField(info) {
    if (!info || !jsonIsMap(info)) return null
    def found = null
    ["password", "localPassword", "unitPassword", "adapterPassword", "devicePassword"].each { name ->
        if (found) return
        found = coerceSecret(jsonGet(info, name))
    }
    if (found) return found
    jsonKeys(info).each { k ->
        if (found) return
        def name = k?.toString()?.toLowerCase()
        if (name?.contains("pass") && !name.contains("ssid") && !name.contains("network")) {
            found = coerceSecret(jsonGet(info, k))
        }
    }
    return found
}

private def coerceSecret(v) {
    if (v == null) return null
    if (jsonIsMap(v) || jsonIsList(v)) return null
    def s = v.toString()?.trim()
    if (!s || s == "null" || s == "JSONNull" || s == "[:]" || s == "[]") return null
    return s
}

private def socketIoSavePassword(String serial, String password) {
    if (!serial || !password) return
    def sio = sioState()
    def got = [:] + ((sio.got instanceof Map) ? (sio.got as Map) : [:])
    if (got[serial] == password) return
    got[serial] = password
    sio.got = got
    sioStateSet(sio)
    ensureLocalCredEntry(serial)
    def creds = [:] + (localCreds(serial) ?: [:])
    creds.password = password
    saveLocalCreds(serial, creds)
    infoLog("socketIoSavePassword: Received local device key for ${tailSerial(serial)}")
}

private def socketIoNeedRemaining() {
    def sio = sioState()
    def need = (sio.need ?: []) as List
    def got = (sio.got instanceof Map) ? (sio.got as Map) : [:]
    return need.findAll { s ->
        def serial = s as String
        !got[serial] && !got.find { k, v -> k?.toString()?.equalsIgnoreCase(serial) }
    }
}

// runIn target from finishSocketIo (belt-and-braces commit). Must stay public.
def commitFetchedDeviceKeys() {
    def got = sioState().got
    if (!(got instanceof Map)) return
    got.each { serial, password ->
        if (!serial || !password) return
        ensureLocalCredEntry(serial as String)
        def creds = [:] + (localCreds(serial as String) ?: [:])
        creds.password = password
        saveLocalCreds(serial as String, creds)
    }
}

private def saveCryptoSerial(String serial, crypto) {
    if (!serial || crypto == null) return
    def text = crypto.toString()?.trim()
    if (!text) return
    ensureLocalCredEntry(serial)
    def creds = [:] + (localCreds(serial) ?: [:])
    creds.cryptoSerial = text
    saveLocalCreds(serial, creds)
}

// --- Comfort Cloud user id (for the account-level subscribe) ---

private def jwtUserId() {
    if (state.kumoUserId) return state.kumoUserId as String
    try {
        def token = state.accessToken
        if (!token) return null
        def parts = token.split("\\.")
        if (parts.size() < 2) return null
        def payloadB64 = (parts[1] as String).replace("-", "+").replace("_", "/")
        def pad = payloadB64.length() % 4
        if (pad > 0) payloadB64 += "=" * (4 - pad)
        def json = new JsonSlurper().parseText(new String(payloadB64.decodeBase64(), "UTF-8"))
        def id = json?.id ?: json?.sub ?: json?.userId
        if (id) {
            state.kumoUserId = id.toString()
            return state.kumoUserId as String
        }
    } catch (Exception e) {
        debugLog("jwtUserId: JWT user id decode failed: ${e.message}")
    }
    return null
}

private def fetchKumoUserIdFromAccount() {
    try {
        def json = apiRequest("GET", "/v3/accounts/me")
        if (json instanceof Map) {
            def id = json.id ?: json.userId
            if (id) {
                state.kumoUserId = id.toString()
                return state.kumoUserId as String
            }
        }
    } catch (Exception e) {
        debugLog("fetchKumoUserIdFromAccount: accounts/me user id fetch failed: ${e.message}")
    }
    return null
}

// --- Fetch finish + status ---

private def finishSocketIo(String reason) {
    unschedule("socketIoWatchdog")
    unschedule("socketIoPoll")
    def sio = sioState()
    sio.active = false
    sioStateSet(sio)
    commitFetchedDeviceKeys()
    if (reason != "complete" && reason != "cancelled" && state.legacyZoneTableEmpty != true) {
        tryLegacyPasswordFetch()
        if (socketIoNeedRemaining().isEmpty() && (sio.need ?: [])) {
            reason = "complete"
        }
    }
    commitFetchedDeviceKeys()
    runIn(1, "commitFetchedDeviceKeys", [overwrite: true])
    if (reason == "v3-no-password") {
        state.v3AdapterOmitsPassword = true
    }
    infoLog("finishSocketIo: Socket.IO password fetch finished: ${reason}")
    finalizeCredFetch(reason)
}

private def localKeysUnavailableMessage() {
    return "Comfort Cloud is not publishing local device keys (Mitsubishi withheld them from the API around August 2026). Cloud control still works. Local LAN control cannot be enabled until those keys are published again. Cached keys from before the change still work if you have them."
}

private def finalizeCredFetch(String reason) {
    def sio = sioState()
    def need = (sio.need ?: []) as List
    def got = (sio.got instanceof Map) ? (sio.got as Map) : [:]
    def found = need.count { serial ->
        def s = serial as String
        got[s] || got.find { k, v -> k?.toString()?.equalsIgnoreCase(s) }
    } as int
    def total = need.size() as int
    if (reason == "complete" || (total > 0 && found == total)) {
        refreshLocalKeyStatus()
        infoLog("finalizeCredFetch: ${found} of ${total} device keys fetched.")
    } else if (reason == "v3-no-password" || (found == 0 && (state.legacyZoneTableEmpty == true || state.v3AdapterOmitsPassword == true))) {
        setLocalKeyStatus("not published by cloud")
        infoLog("finalizeCredFetch: ${localKeysUnavailableMessage()}")
    } else if (reason == "timeout") {
        setLocalKeyStatus("timeout")
        infoLog("finalizeCredFetch: ${found} of ${total} device keys fetched from Comfort Cloud. ${total - found} still missing - run Fetch local device keys again to retry.")
    } else {
        setLocalKeyStatus("error")
        infoLog("finalizeCredFetch: Could not fetch device keys (${reason}). Run Fetch local device keys again to retry.")
    }
}

// --- Legacy v2 login attempt (evdev:3636-3816) ---
//
// The legacy Kumo Cloud login (geo-c.kumocloud.com, appVersion 2.2.0)
// used to return every adapter's local password + cryptoSerial in its
// login response (ventz KUMO-API-V3-DOC.md, Notes). Comfort-only sites
// now return an empty zoneTable, but the attempt is one cheap POST and
// costs nothing when it finds nothing.

private def tryLegacyPasswordFetch(serials = null) {
    if (!settings.userName || !settings.password) return
    infoLog("tryLegacyPasswordFetch: Trying legacy Comfort Cloud login for local device keys")
    def targets = (serials ?: sioState().need ?: knownSerials()) as List
    def found = [:]
    def attempts = [
        [path: "/login", appKey: false, rawJson: true],
        [path: "/login", appKey: false, rawJson: false]
    ]
    def parsedLogin = false
    attempts.each { spec ->
        if (!found.isEmpty() || parsedLogin) return
        def data = legacyLoginJson(spec)
        if (data == null) return
        parsedLogin = true
        infoLog("tryLegacyPasswordFetch: Legacy login ${spec.path} rawJson=${spec.rawJson} ${legacyDescribe(data)}")
        def stats = [maps: 0, lists: 0, zoneTables: 0, serials: 0, passwords: 0]
        walkLegacyCreds(data, found, stats)
        infoLog("tryLegacyPasswordFetch: Legacy walk stats maps=${stats.maps} lists=${stats.lists} zoneTables=${stats.zoneTables} serials=${stats.serials} passwords=${stats.passwords}")
        if (legacyZoneTablesEmpty(data)) {
            state.legacyZoneTableEmpty = true
            infoLog("tryLegacyPasswordFetch: Legacy V2 zoneTable is empty (Comfort-only site); no local device keys in this login")
        } else {
            state.legacyZoneTableEmpty = false
        }
    }
    infoLog("tryLegacyPasswordFetch: Legacy login walked ${found.size()} credential entr${found.size() == 1 ? 'y' : 'ies'}")
    if (found.isEmpty()) {
        warnLog("tryLegacyPasswordFetch: Legacy login JSON had no device keys")
    }
    def applied = 0
    targets.each { serial ->
        def s = serial as String
        def cred = found[s]
        if (!(cred instanceof Map)) {
            def match = found.find { k, v -> k?.toString()?.equalsIgnoreCase(s) }
            cred = match?.value
        }
        if (!(cred instanceof Map) || !cred.password) return
        socketIoSavePassword(s, cred.password as String)
        if (cred.cryptoSerial) saveCryptoSerial(s, cred.cryptoSerial)
        applied++
    }
    if (applied) {
        infoLog("tryLegacyPasswordFetch: Legacy Comfort Cloud login provided ${applied} local device key(s)")
    } else if (found) {
        warnLog("tryLegacyPasswordFetch: Legacy login returned ${found.size()} key(s) but none matched known zone serials")
    }
}

private def legacyZoneTablesEmpty(data) {
    if (!jsonIsList(data) || data.size() < 3) return false
    def tree = data[2]
    if (!jsonIsMap(tree)) return false
    def zt = jsonGet(tree, "zoneTable")
    if (jsonIsMap(zt) && zt.size() > 0) return false
    def ch = jsonGet(tree, "children")
    if (!jsonIsList(ch)) return true
    def n = 0
    try { n = ch.size() } catch (ignored) { return true }
    for (int i = 0; i < n; i++) {
        def child = ch[i]
        def childZt = jsonIsMap(child) ? jsonGet(child, "zoneTable") : null
        if (jsonIsMap(childZt) && childZt.size() > 0) return false
        def grand = jsonIsMap(child) ? jsonGet(child, "children") : null
        if (jsonIsList(grand)) {
            def gn = 0
            try { gn = grand.size() } catch (ignored) { gn = 0 }
            for (int j = 0; j < gn; j++) {
                def gzt = jsonIsMap(grand[j]) ? jsonGet(grand[j], "zoneTable") : null
                if (jsonIsMap(gzt) && gzt.size() > 0) return false
            }
        }
    }
    return true
}

private def legacyDescribe(data) {
    if (jsonIsList(data)) return "list size=${data.size()}"
    if (jsonIsMap(data)) return "map keys=${jsonKeys(data).join(',')}"
    if (data == null) return "null"
    return "text len=${data.toString().length()}"
}

private def legacyLoginJson(Map spec) {
    def path = (spec.path ?: "/login") as String
    def rawJson = spec.rawJson == true
    try {
        def headers = [
            "Accept": "application/json, text/plain, */*",
            "Accept-Language": "en-US,en"
        ]
        def payload = [
            username: settings.userName,
            password: settings.password,
            appVersion: LEGACY_APP_VERSION
        ]
        def params = [
            uri: "${LEGACY_API_BASE}${path}",
            headers: headers,
            timeout: 20,
            contentType: "text/plain"
        ]
        if (rawJson) {
            headers["Content-Type"] = "application/json"
            params.headers = headers
            params.body = JsonOutput.toJson(payload)
            params.requestContentType = "text/plain"
        } else {
            params.body = payload
            params.requestContentType = "application/json"
        }
        def loginResp = null
        httpPost(params) { response -> loginResp = response }
        def status = loginResp ? (loginResp.status as int) : 0
        if (status != 200) {
            warnLog("legacyLoginJson: Legacy credential fetch ${path} rawJson=${rawJson}: HTTP ${status}")
            return null
        }
        return parseLegacyLoginBody(loginResp)
    } catch (Exception e) {
        warnLog("legacyLoginJson: Legacy credential fetch ${path} rawJson=${rawJson} failed: ${e.message}")
        return null
    }
}

private def parseLegacyLoginBody(loginResp) {
    def raw = null
    try {
        if (loginResp?.respondsTo("getData")) raw = loginResp.getData()
    } catch (ignored) {}
    if (raw == null) raw = loginResp?.data
    def data = null
    if (raw instanceof String || raw instanceof GString) {
        data = parseMaybeJson(raw.toString())
    } else {
        data = parseMaybeJson(raw)
    }
    return repairLegacyLoginNode(data)
}

private def repairLegacyLoginNode(node) {
    if (node instanceof String || node instanceof GString) {
        def text = node.toString().trim()
        if (text.startsWith("{") || text.startsWith("[")) {
            try { return repairLegacyLoginNode(new JsonSlurper().parseText(text)) }
            catch (ignored) {}
        }
        return node
    }
    if (jsonIsList(node)) {
        def repaired = []
        def n = 0
        try { n = node.size() } catch (ignored) { return node }
        for (int i = 0; i < n; i++) {
            repaired << repairLegacyLoginNode(node[i])
        }
        return repaired
    }
    return node
}

private def walkLegacyCreds(node, Map found, Map stats) {
    if (jsonIsList(node)) {
        stats.lists = ((stats.lists ?: 0) as int) + 1
        def n = 0
        try { n = node.size() } catch (ignored) { return }
        for (int i = 0; i < n; i++) {
            walkLegacyCreds(node[i], found, stats)
        }
        return
    }
    if (!jsonIsMap(node)) return
    stats.maps = ((stats.maps ?: 0) as int) + 1
    harvestLegacyDevice(node, null, found, stats)
    def zoneTable = jsonGet(node, "zoneTable")
    if (jsonIsMap(zoneTable)) {
        stats.zoneTables = ((stats.zoneTables ?: 0) as int) + 1
        jsonKeys(zoneTable).each { k ->
            def value = jsonGet(zoneTable, k)
            if (jsonIsMap(value)) harvestLegacyDevice(value, k?.toString(), found, stats)
        }
    }
    jsonKeys(node).each { k ->
        if (k?.toString()?.equalsIgnoreCase("zoneTable")) return
        def value = jsonGet(node, k)
        if (jsonIsMap(value)) {
            harvestLegacyDevice(value, k?.toString(), found, stats)
            walkLegacyCreds(value, found, stats)
        } else if (jsonIsList(value)) {
            walkLegacyCreds(value, found, stats)
        }
    }
}

private def harvestLegacyDevice(raw, String keyHint, Map found, Map stats) {
    if (!jsonIsMap(raw)) return
    def hint = keyHint?.toLowerCase()
    if (hint == "network" || hint == "wifi" || hint == "ssid") return
    def serial = (jsonGet(raw, "serial") ?: jsonGet(raw, "deviceSerial")) as String
    if (!serial && keyHint && looksLikeDeviceSerial(keyHint)) serial = keyHint
    def pw = firstPasswordField(raw)
    if (serial) {
        stats.serials = ((stats.serials ?: 0) as int) + 1
    }
    if (!serial || !pw) return
    stats.passwords = ((stats.passwords ?: 0) as int) + 1
    def crypto = jsonGet(raw, "cryptoSerial") ?: jsonGet(raw, "crypto_serial")
    found[serial] = [
        password: pw as String,
        cryptoSerial: (crypto ?: "") as String
    ]
}

private def looksLikeDeviceSerial(String value) {
    if (!value || value.length() < 10) return false
    def lower = value.toLowerCase()
    if (lower.contains("password") || lower.contains("token") || lower.contains("child") || lower.contains("zone") || lower.contains("user") || lower.contains("email")) return false
    return value.matches(".*[0-9].*")
}

// --- Small JSON / response helpers (evdev:2134-2172, 3840-3872) ---

private def jsonIsMap(node) {
    return node instanceof Map
}

private def jsonIsList(node) {
    return node instanceof List
}

private def isJsonNull(v) {
    if (v == null) return true
    def s = v.toString()
    return s == "null" || s == "JSONNull"
}

private def jsonKeys(node) {
    def keys = []
    if (node == null || !jsonIsMap(node)) return keys
    try {
        node.keySet().each { k -> keys << k }
        return keys
    } catch (Exception ignored) {}
    try {
        node.each { entry ->
            if (entry instanceof Map.Entry) keys << entry.key
        }
    } catch (Exception ignored) {}
    return keys
}

private def jsonGet(node, key) {
    if (node == null || key == null) return null
    def v = null
    try { v = node[key] } catch (ignored) {}
    if (isJsonNull(v)) {
        v = null
        try { v = node.get(key) } catch (ignored) {}
    }
    if (isJsonNull(v) && jsonIsMap(node)) {
        def want = key.toString()
        jsonKeys(node).each { k ->
            if (!isJsonNull(v)) return
            if (k?.toString()?.equalsIgnoreCase(want)) {
                try { v = node[k] } catch (ignored) {}
            }
        }
    }
    return isJsonNull(v) ? null : v
}

private def parseMaybeJson(raw) {
    if (raw == null) return null
    if (raw instanceof Map || raw instanceof List) return raw
    def text = raw as String
    if (!text?.trim()) return null
    return new JsonSlurper().parseText(text)
}

private def safeStatus(response) {
    try {
        if (response == null) return 0
        if (response.respondsTo("getStatus")) return response.getStatus() as int
        if (response.hasProperty("status")) return response.status as int
    } catch (ignored) {}
    return 0
}

// Last 6 chars of a serial, for logs (never log a full serial next to a key).
private def tailSerial(String serial) {
    if (!serial) return "?"
    if (serial.length() <= 6) return serial
    return serial.substring(serial.length() - 6)
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
