/**
 *  Rivian Vehicle Driver for Hubitat
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
 *  Connects to Rivian API via WebSocket for real-time vehicle state updates.
 *
 *  Inspired by the Home Assistant Rivian integration:
 *  https://github.com/bretterer/home-assistant-rivian (Apache 2.0 License)
 *
 */

import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import groovy.transform.Field

@Field static final String VERSION = "1.0.0"
@Field static final String WEBSOCKET_URL = "wss://api.rivian.com/gql-consumer-subscriptions/graphql"
@Field static final Integer RECONNECT_DELAY_MAX = 3600 // 1 hour max

// Drive mode mapping from API values to friendly names
@Field static final Map DRIVE_MODE_MAP = [
    "everyday": "All-Purpose",
    "sport": "Sport",
    "distance": "Conserve",
    "winter": "Snow",
    "towing": "Towing",
    "off_road_auto": "All-Terrain",
    "off_road_sand": "Soft Sand",
    "off_road_rocks": "Rock Crawl",
    "off_road_sport_auto": "Rally",
    "off_road_sport_drift": "Drift"
]

metadata {
    definition(
        name: "Rivian Vehicle",
        namespace: "jlupien",
        author: "Jeff Lupien",
        importUrl: "https://raw.githubusercontent.com/jlupien/hubitat-drivers/master/rivian-connect/drivers/rivian-vehicle.groovy"
    ) {
        // Standard Hubitat Capabilities
        capability "Battery"                  // battery attribute (0-100)
        capability "Lock"                     // lock, unlock commands; lock attribute
        capability "TemperatureMeasurement"   // temperature attribute
        capability "PresenceSensor"           // presence attribute
        capability "Refresh"                  // refresh command
        capability "Actuator"                 // generic actuator
        capability "Initialize"               // initialize on hub startup

        // Battery & Charging Attributes
        attribute "batteryRange", "number"              // miles/km remaining
        attribute "batteryCapacity", "number"           // kWh
        attribute "batteryLimit", "number"              // charge limit %
        attribute "chargingState", "string"             // idle, charging, complete
        attribute "chargerStatus", "string"             // connected, disconnected
        attribute "timeToFullCharge", "number"          // minutes
        attribute "chargePortState", "string"           // open, closed

        // Vehicle State Attributes
        attribute "powerState", "string"                // Sleep, Standby, Ready, Go
        attribute "gearStatus", "string"                // Park, Drive, Reverse, Neutral
        attribute "driveMode", "string"                 // All-Purpose, Sport, etc.
        attribute "odometer", "number"                  // miles/km
        attribute "speed", "number"                     // current speed
        attribute "softwareVersion", "string"           // OTA version
        attribute "otaStatus", "string"                 // OTA update status

        // Climate Attributes
        attribute "cabinTemperature", "number"          // interior temp
        attribute "climateSetpoint", "number"           // target temp
        attribute "preconditioningStatus", "string"     // off, active, complete
        attribute "defrostStatus", "string"             // on, off

        // Location Attributes
        attribute "latitude", "number"
        attribute "longitude", "number"
        attribute "altitude", "number"
        attribute "bearing", "number"
        attribute "lastLocationUpdate", "string"

        // Door & Closure Attributes
        attribute "allDoorsLocked", "string"            // true, false
        attribute "doorFrontLeftLocked", "string"
        attribute "doorFrontRightLocked", "string"
        attribute "doorRearLeftLocked", "string"
        attribute "doorRearRightLocked", "string"
        attribute "doorFrontLeftClosed", "string"
        attribute "doorFrontRightClosed", "string"
        attribute "doorRearLeftClosed", "string"
        attribute "doorRearRightClosed", "string"
        attribute "frunkLocked", "string"
        attribute "frunkClosed", "string"
        attribute "liftgateLocked", "string"            // R1S
        attribute "liftgateClosed", "string"            // R1S
        attribute "tailgateLocked", "string"            // R1T
        attribute "tailgateClosed", "string"            // R1T
        attribute "tonneauLocked", "string"             // R1T
        attribute "tonneauClosed", "string"             // R1T
        attribute "sideBinLeftLocked", "string"         // R1T gear tunnel
        attribute "sideBinLeftClosed", "string"         // R1T gear tunnel
        attribute "sideBinRightLocked", "string"        // R1T gear tunnel
        attribute "sideBinRightClosed", "string"        // R1T gear tunnel
        attribute "liftgateNextAction", "string"        // R1S

        // Window Attributes
        attribute "windowFrontLeftClosed", "string"
        attribute "windowFrontRightClosed", "string"
        attribute "windowRearLeftClosed", "string"
        attribute "windowRearRightClosed", "string"
        attribute "allWindowsClosed", "string"

        // Tire Pressure Attributes
        attribute "tirePressureFrontLeft", "number"
        attribute "tirePressureFrontRight", "number"
        attribute "tirePressureRearLeft", "number"
        attribute "tirePressureRearRight", "number"
        attribute "tirePressureStatusFrontLeft", "string"
        attribute "tirePressureStatusFrontRight", "string"
        attribute "tirePressureStatusRearLeft", "string"
        attribute "tirePressureStatusRearRight", "string"

        // Seat Heater/Vent Attributes
        attribute "seatFrontLeftHeat", "string"
        attribute "seatFrontRightHeat", "string"
        attribute "seatRearLeftHeat", "string"
        attribute "seatRearRightHeat", "string"
        attribute "seatFrontLeftVent", "string"
        attribute "seatFrontRightVent", "string"
        attribute "seatThirdRowLeftHeat", "string"      // R1S
        attribute "seatThirdRowRightHeat", "string"     // R1S
        attribute "steeringWheelHeat", "string"

        // Connection Status
        attribute "websocketStatus", "string"           // connected, disconnected, connecting
        attribute "lastUpdate", "string"                // timestamp of last data update (human readable)
        attribute "lastDataReceivedAt", "number"        // epoch ms of last data - use for Rule Machine
        attribute "minutesSinceLastData", "number"      // minutes since last data received

        // Gear Guard
        attribute "gearGuardLocked", "string"
        attribute "gearGuardAlarm", "string"
        attribute "gearGuardVideoMode", "string"
        attribute "gearGuardVideoStatus", "string"

        // Pet Mode
        attribute "petModeStatus", "string"
        attribute "petModeTemperatureStatus", "string"

        // Service & Vehicle Modes
        attribute "serviceMode", "string"              // Vehicle in service at Rivian
        attribute "carWashMode", "string"              // Car wash mode enabled
        attribute "trailerStatus", "string"            // Trailer connection status

        // Battery & Vehicle Health
        attribute "twelveVoltBatteryHealth", "string"  // 12V battery status
        attribute "limitedAccelCold", "string"         // Cold weather acceleration limit
        attribute "limitedRegenCold", "string"         // Cold weather regen limit
        attribute "batteryThermalStatus", "string"     // HV battery thermal event
        attribute "brakeFluidLow", "string"            // Brake fluid level
        attribute "wiperFluidState", "string"          // Wiper fluid level
        attribute "rangeThreshold", "string"           // Range threshold warning

        // Charging Extended
        attribute "chargerDerateStatus", "string"      // Charger derate status
        attribute "remoteChargingAvailable", "string"  // Remote charging available

        // Climate Extended
        attribute "preconditioningType", "string"      // Preconditioning type

        // OTA Extended
        attribute "otaAvailableVersion", "string"      // Available OTA version
        attribute "otaDownloadProgress", "number"      // OTA download progress %
        attribute "otaInstallProgress", "number"       // OTA install progress %
        attribute "otaInstallReady", "string"          // OTA ready to install

        // Commands - Phase 1 (Read-only)
        command "refresh"
        command "connectWebSocket"
        command "disconnectWebSocket"

        // Commands - Phase 2 (Control) - Commented out for Phase 1
        // command "wake"
        // command "lockAll"
        // command "unlockAll"
        // command "startCharging"
        // command "stopCharging"
        // command "setChargeLimit", [[name:"limit", type:"NUMBER", description:"Charge limit percentage (50-100)"]]

        // Commands - Phase 3 (Climate) - Commented out for Phase 1
        // command "enablePreconditioning"
        // command "disablePreconditioning"
        // command "setClimateTemperature", [[name:"temp", type:"NUMBER", description:"Temperature in degrees"]]

        // Commands - Phase 4 (Closures) - Commented out for Phase 1
        // command "openFrunk"
        // command "closeFrunk"
        // command "openWindows"
        // command "closeWindows"
    }

    preferences {
        input name: "temperatureUnit", type: "enum", title: "Temperature Unit",
              options: ["F": "Fahrenheit", "C": "Celsius"], defaultValue: "F"
        input name: "distanceUnit", type: "enum", title: "Distance Unit",
              options: ["mi": "Miles", "km": "Kilometers"], defaultValue: "mi"
        input name: "pressureUnit", type: "enum", title: "Pressure Unit",
              options: ["psi": "PSI", "bar": "Bar", "kPa": "kPa"], defaultValue: "psi"
        input name: "homeLatitude", type: "text", title: "Home Latitude (for presence)",
              description: "e.g., 42.12345 (use 5+ decimal places for accuracy)", required: false
        input name: "homeLongitude", type: "text", title: "Home Longitude (for presence)",
              description: "e.g., -71.12345 (use 5+ decimal places for accuracy)", required: false
        input name: "homeRadius", type: "decimal", title: "Home Radius (meters)",
              description: "Distance from home to be considered 'present'", defaultValue: 100
        input name: "enableWebSocket", type: "bool", title: "Enable WebSocket (real-time updates)",
              defaultValue: true
        input name: "reconnectCheckInterval", type: "enum", title: "Connection Health Check",
              options: ["5": "5 minutes", "15": "15 minutes", "30": "30 minutes"],
              defaultValue: "15", description: "How often to verify WebSocket is connected"
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "logTrace", type: "bool", title: "Enable trace logging", defaultValue: false
    }
}

// ==================== Lifecycle Methods ====================

def installed() {
    logInfo "Rivian Vehicle driver installed"
    initialize()
}

def updated() {
    logInfo "Rivian Vehicle driver updated"
    unschedule()
    initialize()

    // Recalculate presence with new home coordinates if vehicle location is known
    def lat = device.currentValue("latitude")
    def lon = device.currentValue("longitude")
    if (lat != null && lon != null) {
        updatePresence(lat, lon)
    }

    // Auto-disable debug logging after 30 minutes
    if (settings.logEnable) {
        runIn(1800, "logsOff")
        logInfo "Debug logging will auto-disable in 30 minutes"
    }
}

def initialize() {
    logInfo "Initializing Rivian Vehicle driver v${VERSION}"

    state.reconnectDelay = 1
    state.seq = 0

    sendEvent(name: "websocketStatus", value: "disconnected")

    // Set lock to "unknown" until we get real data
    if (device.currentValue("lock") == null) {
        sendEvent(name: "lock", value: "unknown", descriptionText: "Lock state unknown - waiting for data")
    }

    if (settings.enableWebSocket != false) {
        logInfo "Scheduling WebSocket connection in 5 seconds..."
        runIn(5, "connectWebSocket")
    } else {
        logInfo "WebSocket disabled by setting"
    }

    // Schedule connection health check (replaces old polling)
    def interval = settings.reconnectCheckInterval ?: "15"
    switch(interval) {
        case "5":  runEvery5Minutes("watchdog"); break
        case "15": runEvery15Minutes("watchdog"); break
        case "30": runEvery30Minutes("watchdog"); break
    }

    logInfo "Initialization complete - WebSocket will connect in 5 seconds"
}

def uninstalled() {
    logInfo "Rivian Vehicle driver uninstalled"
    disconnectWebSocket()
    unschedule()
}

// ==================== WebSocket Methods ====================

def connectWebSocket() {
    logInfo "connectWebSocket() called"

    // Prevent multiple simultaneous connection attempts
    def currentStatus = device.currentValue("websocketStatus")
    if (currentStatus == "connecting") {
        logDebug "Already connecting, skipping duplicate connect request"
        return
    }
    if (currentStatus == "connected") {
        logDebug "Already connected, skipping connect request"
        return
    }

    // Cancel any pending reconnect attempts to prevent race conditions
    unschedule("connectWebSocket")
    state.reconnectPending = false

    if (!parent) {
        logError "No parent app found - cannot connect WebSocket"
        return
    }

    def tokens = parent.getTokens()
    logDebug "Got tokens from parent: accessToken=${tokens?.accessToken ? 'present' : 'missing'}, appSession=${tokens?.appSessionToken ? 'present' : 'missing'}, userSession=${tokens?.userSessionToken ? 'present' : 'missing'}, csrf=${tokens?.csrfToken ? 'present' : 'missing'}"

    if (!tokens || !tokens.appSessionToken) {
        logError "No authentication tokens available (need appSessionToken)"
        sendEvent(name: "websocketStatus", value: "disconnected")
        return
    }

    logInfo "Connecting to Rivian WebSocket at ${WEBSOCKET_URL}..."
    sendEvent(name: "websocketStatus", value: "connecting")

    try {
        def headers = [
            "a-sess": tokens.appSessionToken ?: "",
            "u-sess": tokens.userSessionToken ?: "",
            "csrf-token": tokens.csrfToken ?: "",
            "User-Agent": "RivianConnect/1.0 Hubitat",
            "Sec-WebSocket-Protocol": "graphql-transport-ws"
        ]
        logDebug "WebSocket headers prepared"

        // Hubitat WebSocket: connect(Map options, String url) - Map comes FIRST
        // pingInterval: 120 = send ping every 2 minutes to keep connection alive
        // Rivian server appears to timeout connections after ~3 minutes without activity
        interfaces.webSocket.connect([
            headers: headers,
            pingInterval: 120
        ], WEBSOCKET_URL)
        logInfo "WebSocket connect() called successfully"
    } catch (e) {
        logError "WebSocket connection failed: ${e.message}"
        sendEvent(name: "websocketStatus", value: "disconnected")
        scheduleReconnect()
    }
}

def disconnectWebSocket() {
    logInfo "Disconnecting WebSocket"
    state.intentionalDisconnect = true

    try {
        // Send complete message for subscription
        if (state.subscriptionId) {
            def completeMsg = JsonOutput.toJson([
                type: "complete",
                id: state.subscriptionId
            ])
            interfaces.webSocket.sendMessage(completeMsg)
        }

        interfaces.webSocket.close()
    } catch (e) {
        logDebug "Error during disconnect: ${e.message}"
    }

    sendEvent(name: "websocketStatus", value: "disconnected")
}

def webSocketStatus(String status) {
    logInfo "WebSocket status callback received: ${status}"

    // Hubitat sends status like "status: open" or just "failure: reason"
    if (status.contains("open")) {
        logInfo "WebSocket connected successfully!"
        state.reconnectDelay = 1
        state.lastWebSocketConnect = now()
        state.lastWsMessage = now()
        sendEvent(name: "websocketStatus", value: "connected")

        // Send connection init
        sendWebSocketInit()

    } else if (status.contains("closing")) {
        // Stop periodic resubscription and clear subscription ID
        unschedule("sendVehicleSubscription")
        state.subscriptionId = null  // Clear so we don't try to complete non-existent subscription on reconnect

        // Diagnostic logging for disconnect debugging
        def connectTime = state.lastWebSocketConnect ?: 0
        def lastMsgTime = state.lastWsMessage ?: 0
        def currentTime = now()
        def connectionDuration = connectTime ? Math.round((currentTime - connectTime) / 1000) : "unknown"
        def secsSinceLastMsg = lastMsgTime ? Math.round((currentTime - lastMsgTime) / 1000) : "unknown"
        logWarn "WebSocket closing - connection was open for ${connectionDuration}s, last message ${secsSinceLastMsg}s ago"

        sendEvent(name: "websocketStatus", value: "disconnected")
        if (state.intentionalDisconnect) {
            logDebug "Intentional WebSocket close"
            state.intentionalDisconnect = false
        } else {
            // Only schedule reconnect if one isn't already pending
            def pendingReconnect = state.reconnectPending ?: false
            if (!pendingReconnect) {
                logWarn "WebSocket closing unexpectedly - will reconnect"
                scheduleReconnect()
            } else {
                logDebug "WebSocket closing, but reconnect already pending"
            }
        }

    } else if (status.contains("failure")) {
        logError "WebSocket connection failure: ${status}"
        sendEvent(name: "websocketStatus", value: "disconnected")
        scheduleReconnect()

    } else {
        logWarn "Unknown WebSocket status: ${status}"
    }
}

def sendWebSocketInit() {
    logInfo "Sending WebSocket connection_init"

    def tokens = parent?.getTokens()
    def deviceCid = UUID.randomUUID().toString()

    def initMsg = JsonOutput.toJson([
        type: "connection_init",
        payload: [
            "dc-cid": deviceCid,
            "u-sess": tokens?.userSessionToken ?: "",
            "client-name": "com.rivian.android.consumer",
            "client-version": "2.6.1-2065"
        ]
    ])

    logDebug "Sending init message"

    try {
        interfaces.webSocket.sendMessage(initMsg)
        logInfo "Init message sent successfully"
    } catch (e) {
        logError "Failed to send init message: ${e.message}"
    }
}

def sendVehicleSubscription() {
    // Check if still connected
    def wsStatus = device.currentValue("websocketStatus")
    if (wsStatus != "connected") {
        logDebug "Not sending subscription - WebSocket not connected"
        unschedule("sendVehicleSubscription")
        return
    }

    def vehicleId = device.getDataValue("vehicleId")
    if (!vehicleId) {
        logError "No vehicle ID configured"
        return
    }

    // Complete old subscription first if exists (graphql-transport-ws requires unique IDs)
    if (state.subscriptionId) {
        try {
            def completeMsg = JsonOutput.toJson([
                id: state.subscriptionId,
                type: "complete"
            ])
            interfaces.webSocket.sendMessage(completeMsg)
            logDebug "Completed old subscription ${state.subscriptionId}"
        } catch (e) {
            logDebug "Failed to complete old subscription: ${e.message}"
        }
    }

    // Generate new subscription ID
    state.subscriptionId = UUID.randomUUID().toString()
    logDebug "Sending subscription for vehicle ${vehicleId} with ID ${state.subscriptionId}"

    // Build the subscription query
    def query = buildVehicleStateSubscription()

    def subscribeMsg = JsonOutput.toJson([
        id: state.subscriptionId,
        type: "subscribe",
        payload: [
            operationName: "vehicleState",
            variables: [vehicleID: vehicleId],  // Note: API uses vehicleID not vehicleId
            query: query
        ]
    ])

    try {
        interfaces.webSocket.sendMessage(subscribeMsg)
        logDebug "Subscription message sent"
    } catch (e) {
        logError "Failed to send subscription: ${e.message}"
    }
}

def buildVehicleStateSubscription() {
    return """subscription vehicleState(\$vehicleID: String!) {
  vehicleState(id: \$vehicleID) {
    gnssLocation { latitude longitude timeStamp }
    gnssAltitude { value timeStamp }
    gnssBearing { value timeStamp }
    gnssSpeed { value timeStamp }
    batteryLevel { value timeStamp }
    batteryLimit { value timeStamp }
    batteryCapacity { value timeStamp }
    batteryHvThermalEvent { value timeStamp }
    batteryHvThermalEventPropagation { value timeStamp }
    distanceToEmpty { value timeStamp }
    rangeThreshold { value timeStamp }
    chargerState { value timeStamp }
    chargerStatus { value timeStamp }
    chargerDerateStatus { value timeStamp }
    chargePortState { value timeStamp }
    timeToEndOfCharge { value timeStamp }
    remoteChargingAvailable { value timeStamp }
    powerState { value timeStamp }
    gearStatus { value timeStamp }
    driveMode { value timeStamp }
    vehicleMileage { value timeStamp }
    brakeFluidLow { value timeStamp }
    cabinClimateInteriorTemperature { value timeStamp }
    cabinClimateDriverTemperature { value timeStamp }
    cabinPreconditioningStatus { value timeStamp }
    cabinPreconditioningType { value timeStamp }
    defrostDefogStatus { value timeStamp }
    steeringWheelHeat { value timeStamp }
    doorFrontLeftLocked { value timeStamp }
    doorFrontRightLocked { value timeStamp }
    doorRearLeftLocked { value timeStamp }
    doorRearRightLocked { value timeStamp }
    doorFrontLeftClosed { value timeStamp }
    doorFrontRightClosed { value timeStamp }
    doorRearLeftClosed { value timeStamp }
    doorRearRightClosed { value timeStamp }
    closureFrunkLocked { value timeStamp }
    closureFrunkClosed { value timeStamp }
    closureLiftgateLocked { value timeStamp }
    closureLiftgateClosed { value timeStamp }
    closureLiftgateNextAction { value timeStamp }
    closureTailgateLocked { value timeStamp }
    closureTailgateClosed { value timeStamp }
    closureTonneauLocked { value timeStamp }
    closureTonneauClosed { value timeStamp }
    closureSideBinLeftLocked { value timeStamp }
    closureSideBinLeftClosed { value timeStamp }
    closureSideBinRightLocked { value timeStamp }
    closureSideBinRightClosed { value timeStamp }
    windowFrontLeftClosed { value timeStamp }
    windowFrontRightClosed { value timeStamp }
    windowRearLeftClosed { value timeStamp }
    windowRearRightClosed { value timeStamp }
    windowFrontLeftCalibrated { value timeStamp }
    windowFrontRightCalibrated { value timeStamp }
    windowRearLeftCalibrated { value timeStamp }
    windowRearRightCalibrated { value timeStamp }
    windowsNextAction { value timeStamp }
    tirePressureFrontLeft { value timeStamp }
    tirePressureFrontRight { value timeStamp }
    tirePressureRearLeft { value timeStamp }
    tirePressureRearRight { value timeStamp }
    tirePressureStatusFrontLeft { value timeStamp }
    tirePressureStatusFrontRight { value timeStamp }
    tirePressureStatusRearLeft { value timeStamp }
    tirePressureStatusRearRight { value timeStamp }
    tirePressureStatusValidFrontLeft { value timeStamp }
    tirePressureStatusValidFrontRight { value timeStamp }
    tirePressureStatusValidRearLeft { value timeStamp }
    tirePressureStatusValidRearRight { value timeStamp }
    seatFrontLeftHeat { value timeStamp }
    seatFrontRightHeat { value timeStamp }
    seatRearLeftHeat { value timeStamp }
    seatRearRightHeat { value timeStamp }
    seatFrontLeftVent { value timeStamp }
    seatFrontRightVent { value timeStamp }
    seatThirdRowLeftHeat { value timeStamp }
    seatThirdRowRightHeat { value timeStamp }
    gearGuardLocked { value timeStamp }
    gearGuardVideoMode { value timeStamp }
    gearGuardVideoStatus { value timeStamp }
    gearGuardVideoTermsAccepted { value timeStamp }
    alarmSoundStatus { value timeStamp }
    petModeStatus { value timeStamp }
    petModeTemperatureStatus { value timeStamp }
    otaCurrentVersion { value timeStamp }
    otaCurrentStatus { value timeStamp }
    otaStatus { value timeStamp }
    otaDownloadProgress { value timeStamp }
    otaInstallDuration { value timeStamp }
    otaInstallProgress { value timeStamp }
    otaInstallReady { value timeStamp }
    otaInstallTime { value timeStamp }
    otaInstallType { value timeStamp }
    otaAvailableVersion { value timeStamp }
    serviceMode { value timeStamp }
    limitedAccelCold { value timeStamp }
    limitedRegenCold { value timeStamp }
    twelveVoltBatteryHealth { value timeStamp }
    trailerStatus { value timeStamp }
    carWashMode { value timeStamp }
    wiperFluidState { value timeStamp }
    btmFfHardwareFailureStatus { value timeStamp }
    btmRfHardwareFailureStatus { value timeStamp }
    btmIcHardwareFailureStatus { value timeStamp }
    btmRfdHardwareFailureStatus { value timeStamp }
    btmLfdHardwareFailureStatus { value timeStamp }
  }
}"""
}

def scheduleReconnect() {
    if (settings.enableWebSocket == false) {
        logDebug "WebSocket disabled, not reconnecting"
        return
    }

    // Cancel any existing scheduled reconnect
    unschedule("connectWebSocket")

    // Exponential backoff: 2, 4, 8, 16, 32, 64, ... up to max
    state.reconnectDelay = Math.min((state.reconnectDelay ?: 1) * 2, RECONNECT_DELAY_MAX)
    state.reconnectPending = true

    logInfo "Scheduling WebSocket reconnect in ${state.reconnectDelay} seconds"
    runIn(state.reconnectDelay, "connectWebSocket")
}

def watchdog() {
    logTrace "Running watchdog check"

    def wsStatus = device.currentValue("websocketStatus")
    def lastDataMs = device.currentValue("lastDataReceivedAt") ?: 0
    def currentTime = now()

    // Update minutesSinceLastData attribute for Rule Machine monitoring
    // This lets users set up notifications for extended periods without data
    // (could indicate connection issues OR vehicle is just asleep - both are valid)
    if (lastDataMs > 0) {
        def minutesSinceData = Math.round((currentTime - lastDataMs) / 60000)
        sendEvent(name: "minutesSinceLastData", value: minutesSinceData)
    }

    // NOTE: We do NOT reconnect based on stale data!
    // The vehicle may be asleep for hours/days - this is normal.
    // We trust the WebSocket ping/pong (pingInterval: 120) to detect dead connections.
    // Hubitat will call webSocketStatus("failure") if the connection dies.

    // Backup reconnection: if status is disconnected but no reconnect is pending
    // This handles edge cases where scheduleReconnect didn't fire
    if (settings.enableWebSocket != false && wsStatus == "disconnected") {
        def lastConnect = state.lastWebSocketConnect ?: 0
        // Only attempt if it's been a while since last attempt (respect backoff)
        def minWait = Math.min(state.reconnectDelay ?: 60, 300) * 1000 // At least reconnectDelay, max 5 min
        if ((currentTime - lastConnect) > minWait) {
            logInfo "WebSocket disconnected, watchdog attempting reconnect"
            connectWebSocket()
        }
    }
}

// ==================== Message Parsing ====================

def parse(String message) {
    logDebug "WebSocket message received: ${message?.take(200)}..."

    // Track last message time for disconnect diagnostics
    state.lastWsMessage = now()

    try {
        def json = new JsonSlurper().parseText(message)

        switch(json.type) {
            case "connection_ack":
                logInfo "WebSocket connection acknowledged - sending subscription"
                // Send initial subscription
                sendVehicleSubscription()
                // Schedule periodic resubscription to keep connection alive
                // Rivian server closes idle connections after ~180s
                // Resubscribing every 60s prevents this (matches Home Assistant behavior)
                runEvery1Minute("sendVehicleSubscription")
                break

            case "next":
                // Data update
                logInfo "Received vehicle data update"
                if (json.payload?.data?.vehicleState) {
                    processVehicleData(json.payload.data.vehicleState)
                }
                break

            case "error":
                logError "Subscription error: ${json.payload}"
                break

            case "complete":
                logWarn "Subscription completed unexpectedly"
                scheduleReconnect()
                break

            case "ping":
                // Server sent ping, respond with pong
                logDebug "Received GraphQL ping from server, sending pong"
                interfaces.webSocket.sendMessage(JsonOutput.toJson([type: "pong"]))
                break

            case "ka":
                // Keep-alive, just acknowledge
                logDebug "Keep-alive received"
                break

            default:
                logDebug "Unknown message type: ${json.type}"
        }

    } catch (e) {
        logError "Error parsing WebSocket message: ${e.message}"
    }
}

// ==================== Data Processing ====================

def processVehicleData(Map data) {
    logDebug "Processing vehicle data update"

    // Debug: log which fields have non-null values
    def fieldsWithData = data.findAll { k, v -> v?.value != null }.keySet()
    logDebug "Fields with data: ${fieldsWithData}"

    def nowMs = now()
    def nowFormatted = new Date().format("yyyy-MM-dd HH:mm:ss")
    sendEvent(name: "lastUpdate", value: nowFormatted)
    sendEvent(name: "lastDataReceivedAt", value: nowMs)
    sendEvent(name: "minutesSinceLastData", value: 0)

    // Battery
    if (data.batteryLevel?.value != null) {
        def level = data.batteryLevel.value
        if (level instanceof Number) {
            sendEvent(name: "battery", value: Math.round(level), unit: "%")
        }
    }

    if (data.distanceToEmpty?.value != null) {
        def range = convertDistance(data.distanceToEmpty.value, "km")
        sendEvent(name: "batteryRange", value: range, unit: getDistanceUnit())
    }

    if (data.batteryCapacity?.value != null) {
        sendEvent(name: "batteryCapacity", value: data.batteryCapacity.value, unit: "kWh")
    }

    if (data.batteryLimit?.value != null) {
        sendEvent(name: "batteryLimit", value: data.batteryLimit.value, unit: "%")
    }

    // Charging
    if (data.chargerState?.value != null) {
        def state = mapChargingState(data.chargerState.value)
        sendEvent(name: "chargingState", value: state)
    }

    if (data.chargerStatus?.value != null) {
        def status = data.chargerStatus.value == "chrgr_sts_not_connected" ? "disconnected" : "connected"
        sendEvent(name: "chargerStatus", value: status)
    }

    if (data.chargePortState?.value != null) {
        sendEvent(name: "chargePortState", value: data.chargePortState.value)
    }

    if (data.timeToEndOfCharge?.value != null) {
        sendEvent(name: "timeToFullCharge", value: data.timeToEndOfCharge.value, unit: "min")
    }

    // Vehicle State
    if (data.powerState?.value != null) {
        def state = data.powerState.value.replace("_", " ").capitalize()
        sendEvent(name: "powerState", value: state)
    }

    if (data.gearStatus?.value != null) {
        sendEvent(name: "gearStatus", value: data.gearStatus.value.capitalize())
    }

    if (data.driveMode?.value != null) {
        def mode = DRIVE_MODE_MAP[data.driveMode.value] ?: data.driveMode.value
        sendEvent(name: "driveMode", value: mode)
    }

    if (data.vehicleMileage?.value != null) {
        def miles = convertDistance(data.vehicleMileage.value, "m")
        sendEvent(name: "odometer", value: miles, unit: getDistanceUnit())
    }

    if (data.gnssSpeed?.value != null) {
        def speed = convertSpeed(data.gnssSpeed.value)
        sendEvent(name: "speed", value: speed, unit: getSpeedUnit())
    }

    // OTA
    if (data.otaCurrentVersion?.value != null) {
        sendEvent(name: "softwareVersion", value: data.otaCurrentVersion.value)
    }

    if (data.otaStatus?.value != null) {
        sendEvent(name: "otaStatus", value: data.otaStatus.value.replace("_", " ").capitalize())
    }

    // Climate
    if (data.cabinClimateInteriorTemperature?.value != null) {
        def temp = convertTemperature(data.cabinClimateInteriorTemperature.value)
        sendEvent(name: "temperature", value: temp, unit: getTempUnit())
        sendEvent(name: "cabinTemperature", value: temp, unit: getTempUnit())
    }

    if (data.cabinClimateDriverTemperature?.value != null) {
        def temp = convertTemperature(data.cabinClimateDriverTemperature.value)
        sendEvent(name: "climateSetpoint", value: temp, unit: getTempUnit())
    }

    if (data.cabinPreconditioningStatus?.value != null) {
        def status = mapPreconditioningStatus(data.cabinPreconditioningStatus.value)
        sendEvent(name: "preconditioningStatus", value: status)
    }

    if (data.defrostDefogStatus?.value != null) {
        sendEvent(name: "defrostStatus", value: data.defrostDefogStatus.value == "Off" ? "off" : "on")
    }

    // Location
    if (data.gnssLocation) {
        def loc = data.gnssLocation
        logDebug "Processing gnssLocation: lat=${loc.latitude}, lon=${loc.longitude}"
        if (loc.latitude != null) sendEvent(name: "latitude", value: loc.latitude)
        if (loc.longitude != null) sendEvent(name: "longitude", value: loc.longitude)
        if (loc.timeStamp) sendEvent(name: "lastLocationUpdate", value: loc.timeStamp)

        if (loc.latitude != null && loc.longitude != null) {
            updatePresence(loc.latitude, loc.longitude)
        }
    } else {
        logDebug "No gnssLocation in data"
    }

    if (data.gnssAltitude?.value != null) {
        def alt = convertDistance(data.gnssAltitude.value, "m")
        sendEvent(name: "altitude", value: alt, unit: getDistanceUnit())
    }

    if (data.gnssBearing?.value != null) {
        sendEvent(name: "bearing", value: data.gnssBearing.value, unit: "°")
    }

    // Doors - Locked
    processLockState(data, "doorFrontLeftLocked")
    processLockState(data, "doorFrontRightLocked")
    processLockState(data, "doorRearLeftLocked")
    processLockState(data, "doorRearRightLocked")

    // Doors - Closed
    processClosedState(data, "doorFrontLeftClosed")
    processClosedState(data, "doorFrontRightClosed")
    processClosedState(data, "doorRearLeftClosed")
    processClosedState(data, "doorRearRightClosed")

    // Closures
    if (data.closureFrunkLocked?.value != null) {
        sendEvent(name: "frunkLocked", value: data.closureFrunkLocked.value)
    }
    if (data.closureFrunkClosed?.value != null) {
        sendEvent(name: "frunkClosed", value: data.closureFrunkClosed.value)
    }
    if (data.closureLiftgateLocked?.value != null) {
        sendEvent(name: "liftgateLocked", value: data.closureLiftgateLocked.value)
    }
    if (data.closureLiftgateClosed?.value != null) {
        sendEvent(name: "liftgateClosed", value: data.closureLiftgateClosed.value)
    }
    if (data.closureTailgateLocked?.value != null) {
        sendEvent(name: "tailgateLocked", value: data.closureTailgateLocked.value)
    }
    if (data.closureTailgateClosed?.value != null) {
        sendEvent(name: "tailgateClosed", value: data.closureTailgateClosed.value)
    }
    if (data.closureTonneauLocked?.value != null) {
        sendEvent(name: "tonneauLocked", value: data.closureTonneauLocked.value)
    }
    if (data.closureTonneauClosed?.value != null) {
        sendEvent(name: "tonneauClosed", value: data.closureTonneauClosed.value)
    }
    // R1T Gear Tunnels (Side Bins)
    if (data.closureSideBinLeftLocked?.value != null) {
        sendEvent(name: "sideBinLeftLocked", value: data.closureSideBinLeftLocked.value)
    }
    if (data.closureSideBinLeftClosed?.value != null) {
        sendEvent(name: "sideBinLeftClosed", value: data.closureSideBinLeftClosed.value)
    }
    if (data.closureSideBinRightLocked?.value != null) {
        sendEvent(name: "sideBinRightLocked", value: data.closureSideBinRightLocked.value)
    }
    if (data.closureSideBinRightClosed?.value != null) {
        sendEvent(name: "sideBinRightClosed", value: data.closureSideBinRightClosed.value)
    }
    // R1S Liftgate next action
    if (data.closureLiftgateNextAction?.value != null) {
        sendEvent(name: "liftgateNextAction", value: data.closureLiftgateNextAction.value)
    }

    // Windows
    processClosedState(data, "windowFrontLeftClosed")
    processClosedState(data, "windowFrontRightClosed")
    processClosedState(data, "windowRearLeftClosed")
    processClosedState(data, "windowRearRightClosed")

    // Update aggregate window status
    updateWindowsStatus()

    // Tire Pressure
    if (data.tirePressureFrontLeft?.value != null) {
        sendEvent(name: "tirePressureFrontLeft", value: convertPressure(data.tirePressureFrontLeft.value), unit: getPressureUnit())
    }
    if (data.tirePressureFrontRight?.value != null) {
        sendEvent(name: "tirePressureFrontRight", value: convertPressure(data.tirePressureFrontRight.value), unit: getPressureUnit())
    }
    if (data.tirePressureRearLeft?.value != null) {
        sendEvent(name: "tirePressureRearLeft", value: convertPressure(data.tirePressureRearLeft.value), unit: getPressureUnit())
    }
    if (data.tirePressureRearRight?.value != null) {
        sendEvent(name: "tirePressureRearRight", value: convertPressure(data.tirePressureRearRight.value), unit: getPressureUnit())
    }

    // Tire Pressure Status
    if (data.tirePressureStatusFrontLeft?.value != null) {
        sendEvent(name: "tirePressureStatusFrontLeft", value: data.tirePressureStatusFrontLeft.value)
    }
    if (data.tirePressureStatusFrontRight?.value != null) {
        sendEvent(name: "tirePressureStatusFrontRight", value: data.tirePressureStatusFrontRight.value)
    }
    if (data.tirePressureStatusRearLeft?.value != null) {
        sendEvent(name: "tirePressureStatusRearLeft", value: data.tirePressureStatusRearLeft.value)
    }
    if (data.tirePressureStatusRearRight?.value != null) {
        sendEvent(name: "tirePressureStatusRearRight", value: data.tirePressureStatusRearRight.value)
    }

    // Seat Heaters & Vents
    if (data.seatFrontLeftHeat?.value != null) {
        sendEvent(name: "seatFrontLeftHeat", value: data.seatFrontLeftHeat.value)
    }
    if (data.seatFrontRightHeat?.value != null) {
        sendEvent(name: "seatFrontRightHeat", value: data.seatFrontRightHeat.value)
    }
    if (data.seatRearLeftHeat?.value != null) {
        sendEvent(name: "seatRearLeftHeat", value: data.seatRearLeftHeat.value)
    }
    if (data.seatRearRightHeat?.value != null) {
        sendEvent(name: "seatRearRightHeat", value: data.seatRearRightHeat.value)
    }
    if (data.seatFrontLeftVent?.value != null) {
        sendEvent(name: "seatFrontLeftVent", value: data.seatFrontLeftVent.value)
    }
    if (data.seatFrontRightVent?.value != null) {
        sendEvent(name: "seatFrontRightVent", value: data.seatFrontRightVent.value)
    }
    if (data.seatThirdRowLeftHeat?.value != null) {
        sendEvent(name: "seatThirdRowLeftHeat", value: data.seatThirdRowLeftHeat.value)
    }
    if (data.seatThirdRowRightHeat?.value != null) {
        sendEvent(name: "seatThirdRowRightHeat", value: data.seatThirdRowRightHeat.value)
    }
    if (data.steeringWheelHeat?.value != null) {
        sendEvent(name: "steeringWheelHeat", value: data.steeringWheelHeat.value)
    }

    // Gear Guard
    if (data.gearGuardLocked?.value != null) {
        sendEvent(name: "gearGuardLocked", value: data.gearGuardLocked.value)
    }
    if (data.alarmSoundStatus?.value != null) {
        sendEvent(name: "gearGuardAlarm", value: data.alarmSoundStatus.value == "true" ? "triggered" : "off")
    }
    if (data.gearGuardVideoMode?.value != null) {
        sendEvent(name: "gearGuardVideoMode", value: data.gearGuardVideoMode.value.replace("_", " ").capitalize())
    }
    if (data.gearGuardVideoStatus?.value != null) {
        sendEvent(name: "gearGuardVideoStatus", value: data.gearGuardVideoStatus.value.replace("_", " ").capitalize())
    }

    // Pet Mode
    if (data.petModeStatus?.value != null) {
        sendEvent(name: "petModeStatus", value: data.petModeStatus.value)
    }
    if (data.petModeTemperatureStatus?.value != null) {
        sendEvent(name: "petModeTemperatureStatus", value: data.petModeTemperatureStatus.value.replace("_", " ").capitalize())
    }

    // Service & Vehicle Modes
    if (data.serviceMode?.value != null) {
        sendEvent(name: "serviceMode", value: data.serviceMode.value)
    }
    if (data.carWashMode?.value != null) {
        sendEvent(name: "carWashMode", value: data.carWashMode.value)
    }
    if (data.trailerStatus?.value != null) {
        sendEvent(name: "trailerStatus", value: data.trailerStatus.value)
    }

    // Battery & Vehicle Health
    if (data.twelveVoltBatteryHealth?.value != null) {
        sendEvent(name: "twelveVoltBatteryHealth", value: data.twelveVoltBatteryHealth.value)
    }
    if (data.limitedAccelCold?.value != null) {
        sendEvent(name: "limitedAccelCold", value: data.limitedAccelCold.value)
    }
    if (data.limitedRegenCold?.value != null) {
        sendEvent(name: "limitedRegenCold", value: data.limitedRegenCold.value)
    }
    if (data.batteryHvThermalEvent?.value != null) {
        sendEvent(name: "batteryThermalStatus", value: data.batteryHvThermalEvent.value.replace("_", " ").capitalize())
    }
    if (data.brakeFluidLow?.value != null) {
        sendEvent(name: "brakeFluidLow", value: data.brakeFluidLow.value)
    }
    if (data.wiperFluidState?.value != null) {
        sendEvent(name: "wiperFluidState", value: data.wiperFluidState.value)
    }
    if (data.rangeThreshold?.value != null) {
        sendEvent(name: "rangeThreshold", value: data.rangeThreshold.value.replace("_", " ").capitalize())
    }

    // Charging Extended
    if (data.chargerDerateStatus?.value != null) {
        sendEvent(name: "chargerDerateStatus", value: data.chargerDerateStatus.value)
    }
    if (data.remoteChargingAvailable?.value != null) {
        sendEvent(name: "remoteChargingAvailable", value: data.remoteChargingAvailable.value)
    }

    // Climate Extended
    if (data.cabinPreconditioningType?.value != null) {
        sendEvent(name: "preconditioningType", value: data.cabinPreconditioningType.value.replace("_", " ").capitalize())
    }

    // OTA Extended
    if (data.otaAvailableVersion?.value != null) {
        sendEvent(name: "otaAvailableVersion", value: data.otaAvailableVersion.value)
    }
    if (data.otaDownloadProgress?.value != null) {
        sendEvent(name: "otaDownloadProgress", value: data.otaDownloadProgress.value, unit: "%")
    }
    if (data.otaInstallProgress?.value != null) {
        sendEvent(name: "otaInstallProgress", value: data.otaInstallProgress.value, unit: "%")
    }
    if (data.otaInstallReady?.value != null) {
        sendEvent(name: "otaInstallReady", value: data.otaInstallReady.value.replace("_", " ").capitalize())
    }

    // Update aggregate lock status
    updateLockStatus()
}

def processLockState(Map data, String field) {
    if (data[field]?.value != null) {
        sendEvent(name: field, value: data[field].value)
    }
}

def processClosedState(Map data, String field) {
    if (data[field]?.value != null) {
        sendEvent(name: field, value: data[field].value)
    }
}

def updateLockStatus() {
    // Check all lockable closures (matches Home Assistant LOCK_STATE_ENTITIES)
    def lockFields = [
        "doorFrontLeftLocked", "doorFrontRightLocked",
        "doorRearLeftLocked", "doorRearRightLocked",
        "frunkLocked",           // closureFrunkLocked
        "liftgateLocked",        // closureLiftgateLocked (R1S)
        "tailgateLocked",        // closureTailgateLocked (R1T)
        "tonneauLocked",         // closureTonneauLocked (R1T)
        "sideBinLeftLocked",     // closureSideBinLeftLocked (R1T)
        "sideBinRightLocked"     // closureSideBinRightLocked (R1T)
    ]

    // Only check fields that have values (model-specific closures may be null)
    def allLocked = lockFields.every { field ->
        def value = device.currentValue(field)
        value == null || value == "locked"  // null means not applicable to this model
    }

    sendEvent(name: "allDoorsLocked", value: allLocked.toString())
    sendEvent(name: "lock", value: allLocked ? "locked" : "unlocked")
}

def updateWindowsStatus() {
    def windowFields = [
        "windowFrontLeftClosed", "windowFrontRightClosed",
        "windowRearLeftClosed", "windowRearRightClosed"
    ]

    def allClosed = windowFields.every { field ->
        device.currentValue(field) == "closed"
    }

    sendEvent(name: "allWindowsClosed", value: allClosed.toString())
}

// ==================== Presence Detection ====================

def updatePresence(latitude, longitude) {
    if (!settings.homeLatitude || !settings.homeLongitude) {
        logDebug "Home coordinates not configured, skipping presence update"
        return
    }
    logDebug "updatePresence called: vehicle=${latitude},${longitude} home=${settings.homeLatitude},${settings.homeLongitude}"

    // Parse text inputs to Double for precise coordinates
    def homeLat = settings.homeLatitude.toString().toBigDecimal()
    def homeLon = settings.homeLongitude.toString().toBigDecimal()

    def distance = calculateDistance(
        latitude, longitude,
        homeLat as Double, homeLon as Double
    )

    def radius = settings.homeRadius ?: 100
    def present = distance <= radius

    logDebug "Distance from home: ${distance}m, radius: ${radius}m, present: ${present}"
    sendEvent(name: "presence", value: present ? "present" : "not present")
}

def calculateDistance(lat1, lon1, lat2, lon2) {
    // Haversine formula
    def R = 6371000 // Earth radius in meters
    def dLat = Math.toRadians(lat2 - lat1)
    def dLon = Math.toRadians(lon2 - lon1)
    def a = Math.sin(dLat/2) * Math.sin(dLat/2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon/2) * Math.sin(dLon/2)
    def c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a))
    return R * c
}

// ==================== Unit Conversion ====================

def convertTemperature(celsius) {
    if (settings.temperatureUnit == "C") {
        return Math.round(celsius * 10) / 10
    }
    return Math.round((celsius * 9/5 + 32) * 10) / 10
}

def convertDistance(value, fromUnit) {
    // Convert to user's preferred unit
    def meters
    switch(fromUnit) {
        case "km": meters = value * 1000; break
        case "m": meters = value; break
        default: meters = value
    }

    if (settings.distanceUnit == "km") {
        return Math.round(meters / 100) / 10 // km with 1 decimal
    }
    return Math.round(meters / 1609.34 * 10) / 10 // miles with 1 decimal
}

def convertSpeed(metersPerSecond) {
    if (settings.distanceUnit == "km") {
        return Math.round(metersPerSecond * 3.6) // km/h
    }
    return Math.round(metersPerSecond * 2.237) // mph
}

def convertPressure(bar) {
    switch(settings.pressureUnit) {
        case "psi": return Math.round(bar * 14.504 * 10) / 10
        case "kPa": return Math.round(bar * 100)
        default: return Math.round(bar * 100) / 100
    }
}

def getTempUnit() {
    return settings.temperatureUnit == "C" ? "°C" : "°F"
}

def getDistanceUnit() {
    return settings.distanceUnit == "km" ? "km" : "mi"
}

def getSpeedUnit() {
    return settings.distanceUnit == "km" ? "km/h" : "mph"
}

def getPressureUnit() {
    return settings.pressureUnit ?: "psi"
}

// ==================== State Mapping ====================

def mapChargingState(apiValue) {
    switch(apiValue) {
        case "charging_active": return "charging"
        case "charging_connecting": return "connecting"
        case "charging_complete": return "complete"
        case "charging_stopped": return "stopped"
        default: return "idle"
    }
}

def mapPreconditioningStatus(apiValue) {
    switch(apiValue) {
        case "active": return "active"
        case "complete_maintain": return "maintaining"
        case "initiate": return "starting"
        default: return "off"
    }
}

// ==================== Commands ====================

def refresh() {
    logInfo "Manual refresh requested"

    // Reconnect WebSocket to get fresh data
    if (settings.enableWebSocket != false) {
        disconnectWebSocket()
        runIn(2, "connectWebSocket")
    } else {
        poll()
    }
}

def poll() {
    // Legacy poll method - now triggers watchdog check
    logDebug "Poll called - running watchdog check"
    watchdog()
}

def lock() {
    logInfo "Lock command - requires Phase 2 implementation"
    // Phase 2: parent?.sendVehicleCommand(device.getDataValue("vehicleId"), "LOCK_ALL_CLOSURES_FEEDBACK")
}

def unlock() {
    logInfo "Unlock command - requires Phase 2 implementation"
    // Phase 2: parent?.sendVehicleCommand(device.getDataValue("vehicleId"), "UNLOCK_ALL_CLOSURES")
}

// ==================== Logging ====================

def logInfo(msg)  { log.info  "${device.displayName}: ${msg}" }
def logWarn(msg)  { log.warn  "${device.displayName}: ${msg}" }
def logError(msg) { log.error "${device.displayName}: ${msg}" }
def logDebug(msg) { if (settings.logEnable) log.debug "${device.displayName}: ${msg}" }
def logTrace(msg) { if (settings.logTrace) log.trace "${device.displayName}: ${msg}" }

def logsOff() {
    logInfo "Debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}
