/**
 *  Rivian Connect App for Hubitat
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
 *  Handles authentication, vehicle discovery, and token management for Rivian vehicles.
 *
 *  Inspired by the Home Assistant Rivian integration:
 *  https://github.com/bretterer/home-assistant-rivian (Apache 2.0 License)
 *
 */

import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import groovy.transform.Field

@Field static final String VERSION = "1.0.0"
@Field static final String RIVIAN_API_URL = "https://rivian.com/api/gql/gateway/graphql"
@Field static final String DRIVER_NAME = "Rivian Vehicle"
@Field static final String DRIVER_NAMESPACE = "jlupien"

definition(
    name: "Rivian Connect",
    namespace: "jlupien",
    author: "Jeff Lupien",
    description: "Connect and monitor your Rivian vehicles",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    singleInstance: true
)

preferences {
    page(name: "mainPage")
    page(name: "credentialsPage")
    page(name: "authProgressPage")
    page(name: "otpPage")
    page(name: "vehicleSelectPage")
    page(name: "applyVehiclesPage")
}

// Set to true for debug logging during development
@Field static final boolean DEV_MODE = false

// ==================== Pages ====================

def mainPage() {
    dynamicPage(name: "mainPage", title: "Rivian Connect", install: true, uninstall: true) {
        section {
            paragraph "Rivian Connect v${VERSION}"
            paragraph "Connect your Rivian vehicles to Hubitat for monitoring and control."
        }

        if (state.authenticated) {
            section("Status") {
                paragraph "✓ Authenticated to Rivian"
                if (state.vehicles) {
                    paragraph "Found ${state.vehicles.size()} vehicle(s)"
                }
            }

            section("Configuration") {
                href "vehicleSelectPage", title: "Vehicle Selection",
                     description: "Select which vehicles to add to Hubitat"
            }

            section("Actions") {
                input "refreshVehicles", "button", title: "Refresh Vehicle List"
                input "refreshTokens", "button", title: "Refresh Authentication"
                input "logout", "button", title: "Logout"
            }
        } else {
            section("Authentication") {
                href "credentialsPage", title: "Login to Rivian",
                     description: "Enter your Rivian account credentials"
            }
        }

        section("Installed Vehicles") {
            def devices = getChildDevices()
            if (devices) {
                devices.each { device ->
                    paragraph "• ${device.displayName}"
                }
            } else {
                paragraph "No vehicles installed yet"
            }
        }
    }
}

def credentialsPage() {
    logDebug "credentialsPage() called"

    // Clear any previous errors when entering this page fresh
    if (!settings.rivianEmail) {
        state.loginError = null
    }

    dynamicPage(name: "credentialsPage", title: "Rivian Login", nextPage: "authProgressPage") {
        section("Credentials") {
            paragraph "Enter your Rivian account credentials. Your password is not stored - only authentication tokens are saved."
            input "rivianEmail", "email", title: "Email", required: true, submitOnChange: false
            input "rivianPassword", "password", title: "Password", required: true, submitOnChange: false
        }

        section {
            paragraph "Click <b>Next</b> to login with these credentials."
        }

        if (state.loginError) {
            section("Error") {
                paragraph "<span style='color:red'>${state.loginError}</span>"
            }
        }
    }
}

def authProgressPage() {
    logInfo "authProgressPage() - Starting authentication"
    logDebug "Email: ${settings.rivianEmail}, Password length: ${settings.rivianPassword?.length() ?: 0}"

    // Perform synchronous-ish login attempt
    state.authInProgress = true
    state.loginError = null
    state.otpRequired = false

    // We need to do this synchronously for the page flow to work
    // First create CSRF token
    def csrfResult = createCsrfTokenSync()
    logDebug "CSRF result: ${csrfResult}"

    if (!csrfResult.success) {
        state.loginError = "Failed to initialize session: ${csrfResult.error}"
        state.authInProgress = false
        logError "CSRF failed: ${csrfResult.error}"
        return dynamicPage(name: "authProgressPage", title: "Login Failed", nextPage: "credentialsPage") {
            section {
                paragraph "<span style='color:red'>Failed to initialize session. Please try again.</span>"
                paragraph "Error: ${csrfResult.error}"
            }
        }
    }

    // Now authenticate
    def authResult = authenticateSync(settings.rivianEmail, settings.rivianPassword)
    logDebug "Auth result: ${authResult}"

    state.authInProgress = false

    if (authResult.success) {
        state.authenticated = true
        logInfo "Authentication successful!"

        // Discover vehicles
        discoverVehicles()

        return dynamicPage(name: "authProgressPage", title: "Login Successful", nextPage: "mainPage") {
            section {
                paragraph "<span style='color:green'>✓ Successfully logged in to Rivian!</span>"
                paragraph "Click Next to continue."
            }
        }
    } else if (authResult.otpRequired) {
        state.otpRequired = true
        state.otpToken = authResult.otpToken
        logInfo "OTP required"

        return dynamicPage(name: "authProgressPage", title: "Verification Required", nextPage: "otpPage") {
            section {
                paragraph "Two-factor authentication is required."
                paragraph "A verification code has been sent to your phone or email."
                paragraph "Click Next to enter the code."
            }
        }
    } else {
        state.loginError = authResult.error ?: "Login failed"
        logError "Authentication failed: ${authResult.error}"

        return dynamicPage(name: "authProgressPage", title: "Login Failed", nextPage: "credentialsPage") {
            section {
                paragraph "<span style='color:red'>Login failed. Please check your credentials and try again.</span>"
                paragraph "Error: ${authResult.error}"
            }
        }
    }
}

def otpPage() {
    logDebug "otpPage() called"

    // If we have an OTP code submitted, validate it
    if (settings.otpCode && state.otpToken && !state.authenticated) {
        logInfo "Validating OTP code"

        def result = validateOtpSync(settings.rivianEmail, settings.otpCode, state.otpToken)

        if (result.success) {
            state.authenticated = true
            state.otpRequired = false
            logInfo "OTP validation successful!"

            // Discover vehicles
            discoverVehicles()

            return dynamicPage(name: "otpPage", title: "Verification Successful", nextPage: "mainPage") {
                section {
                    paragraph "<span style='color:green'>✓ Successfully verified!</span>"
                    paragraph "Click Next to continue."
                }
            }
        } else {
            state.otpError = result.error
            logError "OTP validation failed: ${result.error}"
        }
    }

    dynamicPage(name: "otpPage", title: "Verification Code", nextPage: "otpPage") {
        section {
            paragraph "Enter the verification code sent to your phone"
            input "otpCode", "text", title: "Verification Code", required: true, submitOnChange: false
        }

        section {
            paragraph "Click <b>Next</b> after entering the code."
        }

        if (state.otpError) {
            section("Error") {
                paragraph "<span style='color:red'>${state.otpError}</span>"
            }
        }
    }
}

def vehicleSelectPage() {
    logDebug "vehicleSelectPage() called"
    logDebug "state.vehicles = ${state.vehicles}"
    logDebug "state.vehicles size = ${state.vehicles?.size()}"

    // Auto-discover vehicles if not yet loaded
    if (!state.vehicles || state.vehicles.size() == 0) {
        logDebug "vehicleSelectPage: No vehicles in state, discovering..."
        discoverVehicles()
        logDebug "After discovery, state.vehicles = ${state.vehicles}"
    }

    dynamicPage(name: "vehicleSelectPage", title: "Vehicle Selection", nextPage: "applyVehiclesPage", install: false) {
        if (!state.vehicles || state.vehicles.size() == 0) {
            section {
                paragraph "No vehicles found. Please check your Rivian account or try logging in again."
            }
        } else {
            section("Select Vehicles") {
                paragraph "Select which vehicles to add to Hubitat, then click <b>Next</b>."

                def options = [:]
                state.vehicles.each { id, vehicle ->
                    options[id] = "${vehicle.name ?: vehicle.model} (${vehicle.vin})"
                }

                input "selectedVehicles", "enum", title: "Vehicles", options: options,
                      multiple: true, required: false, submitOnChange: true
            }

            // Show which devices will be created/removed
            def selected = settings.selectedVehicles ?: []
            def existing = getChildDevices().collect { it.getDataValue("vehicleId") }
            def toCreate = selected.findAll { !existing.contains(it) }
            def toRemove = existing.findAll { !selected.contains(it) }

            if (toCreate || toRemove) {
                section("Pending Changes") {
                    if (toCreate) {
                        toCreate.each { id ->
                            def v = state.vehicles[id]
                            paragraph "➕ Will create: ${v?.name ?: 'Vehicle'}"
                        }
                    }
                    if (toRemove) {
                        toRemove.each { id ->
                            def device = getChildDevices().find { it.getDataValue("vehicleId") == id }
                            paragraph "➖ Will remove: ${device?.displayName ?: 'Vehicle'}"
                        }
                    }
                    paragraph "<i>Click Next to apply these changes</i>"
                }
            }
        }
    }
}

def applyVehiclesPage() {
    logDebug "applyVehiclesPage() called"

    // Don't create devices here - that happens in installed()/updated() after Done is clicked
    // Just show a preview of what will happen

    def selected = settings.selectedVehicles ?: []
    def existingDeviceIds = getChildDevices().collect { it.getDataValue("vehicleId") }

    def toAdd = selected.findAll { !existingDeviceIds.contains(it) }
    def toRemove = existingDeviceIds.findAll { !selected.contains(it) }

    dynamicPage(name: "applyVehiclesPage", title: "Confirm Changes", nextPage: "mainPage") {
        section {
            paragraph "<b>Important:</b> You must click <b>Done</b> on the next page to save your changes and create the vehicle devices."
        }

        if (toAdd || toRemove) {
            section("Pending Changes") {
                toAdd.each { vehicleId ->
                    def vehicle = state.vehicles[vehicleId]
                    paragraph "➕ Will add: ${vehicle?.name ?: vehicle?.model ?: vehicleId}"
                }
                toRemove.each { vehicleId ->
                    def device = getChildDevices().find { it.getDataValue("vehicleId") == vehicleId }
                    paragraph "➖ Will remove: ${device?.displayName ?: vehicleId}"
                }
            }
        } else if (selected) {
            section("No Changes") {
                paragraph "Your vehicle selection hasn't changed."
            }
        } else {
            section("No Vehicles Selected") {
                paragraph "No vehicles will be installed."
            }
        }

        section {
            paragraph "Click <b>Next</b> to return to the main page, then click <b>Done</b> to apply changes."
        }
    }
}

// ==================== Button Handlers ====================

def appButtonHandler(btn) {
    switch(btn) {
        case "doLogin":
            doLogin()
            break
        case "submitOtp":
            submitOtp()
            break
        case "refreshVehicles":
            discoverVehicles()
            break
        case "refreshTokens":
            refreshTokens()
            break
        case "logout":
            doLogout()
            break
        case "applyVehicleSelection":
            applyVehicleSelection()
            break
    }
}

// ==================== Lifecycle ====================

def installed() {
    logInfo "Rivian Connect installed"
    initialize()
    // Apply vehicle selection on first install
    applyVehicleSelection()
}

def updated() {
    logInfo "Rivian Connect updated"
    unschedule()
    initialize()
    // Apply any pending vehicle selection changes
    applyVehicleSelection()
}

def initialize() {
    logInfo "Initializing Rivian Connect v${VERSION}"

    // Schedule token refresh every 12 hours (at minute 0 of hours 0 and 12)
    schedule("0 0 0,12 * * ?", "refreshTokens")
}

def uninstalled() {
    logInfo "Rivian Connect uninstalled"
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

// ==================== Authentication ====================

def doLogout() {
    logInfo "Logging out"

    // Delete all child devices
    getChildDevices().each { device ->
        logInfo "Removing vehicle device: ${device.displayName}"
        deleteChildDevice(device.deviceNetworkId)
    }

    // Clear all state
    state.authenticated = false
    state.accessToken = null
    state.refreshToken = null
    state.userSessionToken = null
    state.csrfToken = null
    state.appSessionToken = null
    state.vehicles = null
    state.otpRequired = false
    state.otpToken = null
    state.loginError = null

    // Clear all user settings
    app.removeSetting("selectedVehicles")
    app.removeSetting("otpCode")
    app.removeSetting("rivianEmail")
    app.removeSetting("rivianPassword")

    logInfo "Logout complete - all devices and settings cleared"
}

def createCsrfTokenSync() {
    logDebug "createCsrfTokenSync() called"

    def query = """mutation CreateCSRFToken { createCsrfToken { __typename csrfToken appSessionToken } }"""

    try {
        def result = graphqlRequestSync(query, [:], "CreateCSRFToken", false)

        if (result.error) {
            logError "CSRF token creation failed: ${result.error}"
            return [success: false, error: result.error]
        }

        def data = result.response?.data?.createCsrfToken
        if (data?.csrfToken) {
            state.csrfToken = data.csrfToken
            state.appSessionToken = data.appSessionToken
            logDebug "CSRF token created successfully"
            return [success: true]
        } else {
            logError "Invalid CSRF response: ${result.response}"
            return [success: false, error: "Invalid response from Rivian"]
        }
    } catch (e) {
        logError "CSRF token exception: ${e.message}"
        return [success: false, error: e.message]
    }
}

def authenticateSync(String email, String password) {
    logDebug "authenticateSync() called for ${email}"

    def query = """mutation Login(\$email: String!, \$password: String!) { login(email: \$email, password: \$password) { __typename ... on MobileLoginResponse { accessToken refreshToken userSessionToken } ... on MobileMFALoginResponse { otpToken } } }"""

    def variables = [email: email, password: password]

    try {
        def result = graphqlRequestSync(query, variables, "Login", false)

        if (result.error) {
            return [success: false, error: result.error]
        }

        def loginData = result.response?.data?.login
        if (!loginData) {
            logError "No login data in response: ${result.response}"
            return [success: false, error: "Invalid response from Rivian"]
        }

        logDebug "Login response type: ${loginData.__typename}"

        if (loginData.__typename == "MobileMFALoginResponse") {
            return [success: false, otpRequired: true, otpToken: loginData.otpToken]
        } else if (loginData.__typename == "MobileLoginResponse") {
            state.accessToken = loginData.accessToken
            state.refreshToken = loginData.refreshToken
            state.userSessionToken = loginData.userSessionToken
            state.lastTokenRefresh = now()
            return [success: true]
        } else {
            return [success: false, error: "Unknown response type: ${loginData.__typename}"]
        }
    } catch (e) {
        logError "Authentication exception: ${e.message}"
        return [success: false, error: e.message]
    }
}

def validateOtpSync(String email, String otp, String otpToken) {
    logDebug "validateOtpSync() called"

    def query = """
        mutation LoginWithOTP(\$email: String!, \$otpCode: String!, \$otpToken: String!) {
            loginWithOTP(email: \$email, otpCode: \$otpCode, otpToken: \$otpToken) {
                __typename
                ... on MobileLoginResponse {
                    accessToken
                    refreshToken
                    userSessionToken
                }
            }
        }
    """

    def variables = [email: email, otpCode: otp, otpToken: otpToken]

    try {
        def result = graphqlRequestSync(query, variables, "LoginWithOTP", false)

        if (result.error) {
            return [success: false, error: result.error]
        }

        def loginData = result.response?.data?.loginWithOTP
        if (loginData?.__typename == "MobileLoginResponse") {
            state.accessToken = loginData.accessToken
            state.refreshToken = loginData.refreshToken
            state.userSessionToken = loginData.userSessionToken
            state.lastTokenRefresh = now()
            return [success: true]
        } else {
            return [success: false, error: "OTP validation failed"]
        }
    } catch (e) {
        logError "OTP validation exception: ${e.message}"
        return [success: false, error: e.message]
    }
}

def refreshTokens() {
    logInfo "Refreshing authentication tokens"

    createCsrfToken { success ->
        if (success) {
            state.lastTokenRefresh = now()
            logInfo "Tokens refreshed successfully"
        } else {
            logError "Token refresh failed"
        }
    }
}

// ==================== Vehicle Discovery ====================

def discoverVehicles() {
    logInfo "Discovering vehicles"

    def query = """query GetCurrentUser { currentUser { id vehicles { id name vin vas { vasVehicleId vehiclePublicKey } vehicle { model modelYear vehicleState { supportedFeatures { name status } } } } } }"""

    def result = graphqlRequestSync(query, [:], "GetCurrentUser", true)

    if (result.error) {
        logError "Vehicle discovery failed: ${result.error}"
        return
    }

    logDebug "Raw API response: ${result.response}"

    def user = result.response?.data?.currentUser
    logDebug "currentUser: ${user}"
    logDebug "currentUser.vehicles: ${user?.vehicles}"

    if (!user?.vehicles) {
        logWarn "No vehicles found in response"
        state.vehicles = [:]
        return
    }

    state.vehicles = [:]
    user.vehicles.each { v ->
        logDebug "Processing vehicle entry: ${v}"
        def vehicle = v.vehicle ?: [:]
        def vehicleData = [
            id: v.id,
            name: v.name,
            vin: v.vin,
            model: vehicle.model ?: "Rivian",
            modelYear: vehicle.modelYear,
            vasId: v.vas?.vasVehicleId,
            publicKey: v.vas?.vehiclePublicKey,
            supportedFeatures: vehicle.vehicleState?.supportedFeatures?.findAll {
                it.status == "AVAILABLE"
            }?.collect { it.name } ?: []
        ]
        logDebug "Storing vehicle data: ${vehicleData}"
        state.vehicles[v.id] = vehicleData
    }

    logDebug "Final state.vehicles: ${state.vehicles}"
    logInfo "Discovered ${state.vehicles.size()} vehicle(s)"
}

def applyVehicleSelection() {
    logInfo "Applying vehicle selection"

    def selected = settings.selectedVehicles ?: []
    logDebug "Selected vehicles: ${selected}"

    def existingDevices = getChildDevices()
    logDebug "Existing devices: ${existingDevices.collect { it.getDataValue('vehicleId') }}"

    // Remove devices for deselected vehicles FIRST
    existingDevices.each { device ->
        def vehicleId = device.getDataValue("vehicleId")
        logDebug "Checking device ${device.displayName} with vehicleId ${vehicleId}"
        if (!selected || !selected.contains(vehicleId)) {
            logInfo "Removing deselected vehicle: ${device.displayName} (vehicleId: ${vehicleId})"
            try {
                deleteChildDevice(device.deviceNetworkId)
            } catch (e) {
                logError "Failed to delete device ${device.displayName}: ${e.message}"
            }
        }
    }

    // Create devices for selected vehicles
    selected.each { vehicleId ->
        def vehicle = state.vehicles[vehicleId]
        if (vehicle) {
            createVehicleDevice(vehicle)
        } else {
            logWarn "Vehicle ${vehicleId} not found in state.vehicles"
        }
    }
}

def createVehicleDevice(Map vehicle) {
    def dni = "rivian-${vehicle.vin}"

    def existingDevice = getChildDevice(dni)
    if (existingDevice) {
        logDebug "Vehicle device already exists: ${vehicle.name}"
        return existingDevice
    }

    logInfo "Creating vehicle device: ${vehicle.name ?: vehicle.model}"

    try {
        def device = addChildDevice(
            DRIVER_NAMESPACE,
            DRIVER_NAME,
            dni,
            [
                name: "Rivian ${vehicle.model}",
                label: vehicle.name ?: "Rivian ${vehicle.model}",
                isComponent: false
            ]
        )

        device.updateDataValue("vehicleId", vehicle.id)
        device.updateDataValue("vin", vehicle.vin)
        device.updateDataValue("model", vehicle.model)
        device.updateDataValue("modelYear", vehicle.modelYear?.toString() ?: "")
        device.updateDataValue("vasId", vehicle.vasId ?: "")

        logInfo "Created vehicle device: ${device.displayName}"
        return device

    } catch (e) {
        logError "Failed to create vehicle device: ${e.message}"
        return null
    }
}

// ==================== GraphQL API ====================

def graphqlRequestSync(String query, Map variables, String operationName, boolean authenticated) {
    logDebug "graphqlRequestSync: ${operationName}"

    def headers = [
        "Content-Type": "application/json",
        "User-Agent": "RivianConnect/1.0 Hubitat",
        "Apollographql-Client-Name": "com.rivian.android.consumer"
    ]

    if (authenticated && state.accessToken) {
        headers["Authorization"] = "Bearer ${state.accessToken}"
    }

    if (state.csrfToken) {
        headers["Csrf-Token"] = state.csrfToken
    }

    if (state.appSessionToken) {
        headers["A-Sess"] = state.appSessionToken
    }

    if (state.userSessionToken) {
        headers["U-Sess"] = state.userSessionToken
    }

    def body = [
        query: query,
        operationName: operationName
    ]

    if (variables) {
        body.variables = variables
    }

    def bodyJson = JsonOutput.toJson(body)

    def params = [
        uri: RIVIAN_API_URL,
        headers: headers,
        body: bodyJson,
        contentType: "application/json",
        requestContentType: "application/json",
        timeout: 30
    ]

    logDebug "Making sync request to ${RIVIAN_API_URL}"
    logDebug "Headers: ${headers.findAll { k, v -> k != 'Authorization' }}"
    logDebug "Request body: ${bodyJson}"

    try {
        def responseData = null

        httpPost(params) { resp ->
            logDebug "Response status: ${resp.status}"

            if (resp.status == 200) {
                responseData = resp.data
                logDebug "Response data received"
            } else {
                logError "HTTP error: ${resp.status}"
                return [error: "HTTP ${resp.status}", response: null]
            }
        }

        if (responseData) {
            // Check for GraphQL errors
            if (responseData.errors) {
                def errorMsg = responseData.errors[0]?.message ?: "GraphQL error"
                logError "GraphQL error: ${errorMsg}"
                return [error: errorMsg, response: responseData]
            }

            return [error: null, response: responseData]
        } else {
            return [error: "No response data", response: null]
        }

    } catch (groovyx.net.http.HttpResponseException e) {
        logError "HTTP exception: ${e.statusCode} - ${e.message}"
        try {
            def errorBody = e.response?.data
            logError "Error response body: ${errorBody}"
        } catch (ex) {
            logDebug "Could not read error body: ${ex.message}"
        }
        return [error: "HTTP ${e.statusCode}: ${e.message}", response: null]
    } catch (e) {
        logError "Request exception: ${e.class.name} - ${e.message}"
        logError "Exception details: ${e}"
        return [error: e.message, response: null]
    }
}

// ==================== Child Device Communication ====================

def getTokens() {
    // Called by child devices to get authentication tokens
    return [
        accessToken: state.accessToken,
        refreshToken: state.refreshToken,
        userSessionToken: state.userSessionToken,
        csrfToken: state.csrfToken,
        appSessionToken: state.appSessionToken
    ]
}

def refreshVehicle(String vehicleId) {
    logDebug "Refreshing vehicle: ${vehicleId}"

    // Note: Rivian has deprecated HTTP polling for vehicleState
    // Vehicle data is now obtained via WebSocket subscriptions only
    // This method now triggers the driver to reconnect its WebSocket

    def device = getChildDevices().find { it.getDataValue("vehicleId") == vehicleId }
    if (device) {
        logDebug "Triggering WebSocket reconnect for ${device.displayName}"
        device.connectWebSocket()
    } else {
        logWarn "No device found for vehicle ${vehicleId}"
    }
}

// Phase 2: Vehicle Commands
def sendVehicleCommand(String vehicleId, String command, Map params = [:]) {
    logInfo "Sending command ${command} to vehicle ${vehicleId}"
    // TODO: Implement in Phase 2
    logWarn "Vehicle commands not yet implemented (Phase 2)"
}

// ==================== Logging ====================

def logInfo(msg)  { log.info  "Rivian Connect: ${msg}" }
def logWarn(msg)  { log.warn  "Rivian Connect: ${msg}" }
def logError(msg) { log.error "Rivian Connect: ${msg}" }
def logDebug(msg) { if (DEV_MODE || settings.logEnable) log.debug "Rivian Connect: ${msg}" }
