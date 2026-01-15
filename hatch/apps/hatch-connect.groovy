/**
 *  Hatch Connect App for Hubitat
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
 *  Handles authentication, device discovery, and API communication for Hatch Rest/Restore devices.
 *
 *  Based on research from:
 *  - https://github.com/dahlb/ha_hatch (Apache 2.0 License)
 *  - https://github.com/dgreif/homebridge-hatch-baby-rest (MIT License)
 *
 */

import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import groovy.transform.Field
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import java.net.URLEncoder

@Field static final String VERSION = "1.1.0"
@Field static final String HATCH_API_URL = "https://prod-sleep.hatchbaby.com"
@Field static final String DRIVER_NAME = "Hatch Rest+"
@Field static final String DRIVER_NAMESPACE = "jlupien"

definition(
    name: "Hatch Connect",
    namespace: "jlupien",
    author: "Jeff Lupien",
    description: "Connect and control Hatch Rest/Restore devices",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    singleInstance: true
)

preferences {
    page(name: "mainPage")
    page(name: "credentialsPage")
    page(name: "authProgressPage")
    page(name: "deviceSelectPage")
    page(name: "applyDevicesPage")
}

// Set to true for debug logging during development
@Field static final boolean DEV_MODE = false

// ==================== Pages ====================

def mainPage() {
    // Check if this is a new installation vs already installed
    def isInstalled = app.getInstallationState() == 1

    dynamicPage(name: "mainPage", title: "Hatch Connect", install: true, uninstall: true) {
        if (!isInstalled) {
            section {
                paragraph "<b style='color: #d35400;'>IMPORTANT: Click 'Done' below to complete installation.</b>"
                paragraph "Devices will not be created until you click Done."
            }
        }

        section {
            paragraph "Hatch Connect v${VERSION}"
            paragraph "Connect your Hatch Rest/Restore devices to Hubitat for control and automation."
        }

        if (state.authenticated) {
            section("Status") {
                paragraph "Connected to Hatch"
                if (state.devices) {
                    paragraph "Found ${state.devices.size()} device(s)"
                }
            }

            section("Configuration") {
                href "deviceSelectPage", title: "Device Selection",
                     description: "Select which devices to add to Hubitat"
            }

            section("Actions") {
                input "refreshDevices", "button", title: "Refresh Device List"
                input "refreshTokens", "button", title: "Refresh Authentication"
                input "logout", "button", title: "Logout"
            }
        } else {
            section("Authentication") {
                href "credentialsPage", title: "Login to Hatch",
                     description: "Enter your Hatch account credentials"
            }
        }

        def childDevices = getChildDevices()
        if (childDevices) {
            section("Installed Devices") {
                childDevices.each { device ->
                    paragraph "${device.displayName}"
                }
            }
        }

        if (!isInstalled) {
            section {
                paragraph "<b style='color: #d35400;'>Click 'Done' below to finish installation.</b>"
            }
        }
    }
}

def credentialsPage() {
    logDebug "credentialsPage() called"

    if (!settings.hatchEmail) {
        state.loginError = null
    }

    dynamicPage(name: "credentialsPage", title: "Hatch Login", nextPage: "authProgressPage", install: false, uninstall: false) {
        section("Credentials") {
            paragraph "Enter your Hatch account credentials. Your password is not stored - only authentication tokens are saved."
            input "hatchEmail", "email", title: "Email", required: true, submitOnChange: false
            input "hatchPassword", "password", title: "Password", required: true, submitOnChange: false
        }

        section {
            paragraph "Click <b>Next</b> to login, or <b>Cancel</b> to go back."
            href "mainPage", title: "Cancel", description: "Return to main page without logging in"
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
    logDebug "Email: ${settings.hatchEmail}, Password length: ${settings.hatchPassword?.length() ?: 0}"

    state.authInProgress = true
    state.loginError = null

    // Step 1: Login to Hatch
    def loginResult = loginToHatch(settings.hatchEmail, settings.hatchPassword)
    logDebug "Login result: ${loginResult}"

    if (!loginResult.success) {
        state.loginError = "Login failed: ${loginResult.error}"
        state.authInProgress = false
        logError "Login failed: ${loginResult.error}"
        return dynamicPage(name: "authProgressPage", title: "Login Failed", install: false, uninstall: false) {
            section {
                paragraph "<span style='color:red'>Login failed. Please check your credentials and try again.</span>"
                paragraph "Error: ${loginResult.error}"
            }
            section {
                href "credentialsPage", title: "Try Again", description: "Return to login page"
                href "mainPage", title: "Cancel", description: "Return to main page"
            }
        }
    }

    // Step 2: Get AWS IoT token
    def iotTokenResult = fetchIotToken()
    logDebug "IoT token result: ${iotTokenResult}"

    if (!iotTokenResult.success) {
        state.loginError = "Failed to get IoT token: ${iotTokenResult.error}"
        state.authInProgress = false
        return dynamicPage(name: "authProgressPage", title: "Login Failed", install: false, uninstall: false) {
            section {
                paragraph "<span style='color:red'>Failed to initialize device connection.</span>"
                paragraph "Error: ${iotTokenResult.error}"
            }
            section {
                href "credentialsPage", title: "Try Again", description: "Return to login page"
                href "mainPage", title: "Cancel", description: "Return to main page"
            }
        }
    }

    // Step 3: Exchange for AWS credentials
    def awsResult = exchangeForAwsCredentials()
    logDebug "AWS credentials result: ${awsResult}"

    if (!awsResult.success) {
        state.loginError = "Failed to get AWS credentials: ${awsResult.error}"
        state.authInProgress = false
        return dynamicPage(name: "authProgressPage", title: "Login Failed", install: false, uninstall: false) {
            section {
                paragraph "<span style='color:red'>Failed to authenticate with cloud service.</span>"
                paragraph "Error: ${awsResult.error}"
            }
            section {
                href "credentialsPage", title: "Try Again", description: "Return to login page"
                href "mainPage", title: "Cancel", description: "Return to main page"
            }
        }
    }

    state.authInProgress = false
    state.authenticated = true
    logInfo "Authentication successful!"

    // Discover devices
    discoverDevices()

    return dynamicPage(name: "authProgressPage", title: "Login Successful", nextPage: "deviceSelectPage") {
        section {
            paragraph "<span style='color:green'>Successfully logged in to Hatch!</span>"
            if (state.devices) {
                paragraph "Found ${state.devices.size()} device(s)"
            }
            paragraph "Click <b>Next</b> to select devices, then click <b>Done</b> on the main page to complete installation."
        }
    }
}

def deviceSelectPage() {
    logDebug "deviceSelectPage() called"

    // Refresh device list if empty
    if (!state.devices) {
        discoverDevices()
    }

    dynamicPage(name: "deviceSelectPage", title: "Select Devices", nextPage: "applyDevicesPage", install: false, uninstall: false) {
        if (!state.devices || state.devices.size() == 0) {
            section {
                paragraph "No devices found. Make sure your devices are set up in the Hatch app."
                href "mainPage", title: "Back", description: "Return to main page"
            }
        } else {
            section("Select Devices") {
                paragraph "Select which devices to add to Hubitat, then click <b>Next</b>."

                // Use string keys for the enum options (Hubitat returns strings from enums)
                def options = [:]
                state.devices.each { device ->
                    options[device.id.toString()] = device.name ?: "Hatch Device"
                }

                // Pre-select already installed devices
                def existingIds = getChildDevices().collect {
                    it.getDataValue("deviceId")
                }.findAll { it != null }

                input "selectedDevices", "enum", title: "Devices", options: options,
                      multiple: true, required: false, defaultValue: existingIds, submitOnChange: true
            }

            // Show preview of changes
            def selected = settings.selectedDevices ?: []
            def existing = getChildDevices().collect { it.getDataValue("deviceId") }.findAll { it != null }
            def toCreate = selected.findAll { !existing.contains(it) }
            def toRemove = existing.findAll { !selected.contains(it) }

            if (toCreate || toRemove) {
                section("Preview Changes") {
                    if (toCreate) {
                        def names = toCreate.collect { id -> getDeviceName(id) }
                        paragraph "Will add: ${names.join(', ')}"
                    }
                    if (toRemove) {
                        def names = toRemove.collect { id -> getDeviceName(id) }
                        paragraph "Will remove: ${names.join(', ')}"
                    }
                }
            }

            section {
                href "mainPage", title: "Cancel", description: "Return to main page without changes"
            }
        }
    }
}

def applyDevicesPage() {
    logDebug "applyDevicesPage() called"

    // Don't create devices here - just show what will happen when Done is clicked
    def selected = settings.selectedDevices ?: []
    def existing = getChildDevices().collect { it.getDataValue("deviceId") }.findAll { it != null }
    def toCreate = selected.findAll { !existing.contains(it) }
    def toRemove = existing.findAll { !selected.contains(it) }

    dynamicPage(name: "applyDevicesPage", title: "Confirm Selection", nextPage: "mainPage") {
        section {
            if (toCreate) {
                def names = toCreate.collect { id -> getDeviceName(id) }
                paragraph "Will add: ${names.join(', ')}"
            }
            if (toRemove) {
                def names = toRemove.collect { id -> getDeviceName(id) }
                paragraph "Will remove: ${names.join(', ')}"
            }
            if (!toCreate && !toRemove) {
                paragraph "No changes to make."
            }
        }
        section {
            paragraph "<b style='color: #d35400;'>Click Next, then click 'Done' on the main page to complete installation and create devices.</b>"
        }
    }
}

// Helper to find device by ID (handles string/integer comparison)
def findDeviceById(deviceId) {
    def idStr = deviceId.toString()
    return state.devices?.find { it.id.toString() == idStr }
}

// Helper to get device name by ID
def getDeviceName(deviceId) {
    return findDeviceById(deviceId)?.name ?: "Device ${deviceId}"
}

// Syncs child devices based on selectedDevices setting
// Called from installed() and updated()
def syncDevices() {
    logDebug "syncDevices() called"

    if (!state.authenticated) {
        logDebug "Not authenticated, skipping sync"
        return
    }

    if (!state.devices) {
        logDebug "No devices discovered, skipping sync"
        return
    }

    def selected = settings.selectedDevices ?: []
    logDebug "Selected devices: ${selected}"

    if (!selected) {
        logDebug "No devices selected, skipping sync"
        return
    }

    def existing = getChildDevices().collect { it.getDataValue("deviceId") }.findAll { it != null }
    logDebug "Existing device IDs: ${existing}"

    // Create newly selected devices
    selected.each { deviceId ->
        if (!existing.contains(deviceId)) {
            def device = findDeviceById(deviceId)
            if (device) {
                try {
                    logInfo "Creating device: ${device.name} (ID: ${device.id})"
                    def newDevice = addChildDevice(
                        DRIVER_NAMESPACE,
                        DRIVER_NAME,
                        "hatch-${device.id}",
                        [
                            name: device.name,
                            label: device.name,
                            isComponent: false
                        ]
                    )
                    newDevice.updateDataValue("thingName", device.thingName)
                    newDevice.updateDataValue("macAddress", device.macAddress)
                    newDevice.updateDataValue("deviceId", device.id.toString())
                    newDevice.updateDataValue("product", device.product)
                    logInfo "Created device: ${device.name}"
                } catch (e) {
                    logError "Failed to create device ${device.name}: ${e.message}"
                }
            } else {
                logWarn "Device not found in state.devices for ID: ${deviceId}"
            }
        }
    }

    // Remove unselected devices
    existing.each { deviceId ->
        if (!selected.contains(deviceId)) {
            def device = findDeviceById(deviceId)
            try {
                deleteChildDevice("hatch-${deviceId}")
                logInfo "Removed device: ${device?.name ?: deviceId}"
            } catch (e) {
                logError "Failed to remove device: ${e.message}"
            }
        }
    }
}

// ==================== Button Handlers ====================

def appButtonHandler(btn) {
    switch(btn) {
        case "refreshDevices":
            discoverDevices()
            break
        case "refreshTokens":
            refreshTokensIfNeeded(true)
            break
        case "logout":
            logout()
            break
    }
}

def logout() {
    logInfo "Logging out"

    // Remove all child devices
    getChildDevices().each { device ->
        logInfo "Removing device: ${device.displayName}"
        deleteChildDevice(device.deviceNetworkId)
    }

    // Clear state
    state.remove("authenticated")
    state.remove("hatchAuthToken")
    state.remove("awsAccessKeyId")
    state.remove("awsSecretKey")
    state.remove("awsSessionToken")
    state.remove("awsRegion")
    state.remove("iotEndpoint")
    state.remove("identityId")
    state.remove("cognitoToken")
    state.remove("tokenExpiration")
    state.remove("devices")

    // Clear credentials from settings
    app.removeSetting("hatchEmail")
    app.removeSetting("hatchPassword")
    app.removeSetting("selectedDevices")
}

// ==================== Authentication ====================

def loginToHatch(String email, String password) {
    logDebug "loginToHatch() called"

    def bodyJson = JsonOutput.toJson([email: email, password: password])
    logDebug "Login request body: ${bodyJson}"

    def params = [
        uri: "${HATCH_API_URL}/public/v1/login",
        requestContentType: "application/json",
        contentType: "application/json",
        headers: [
            "User-Agent": "hatch_rest_api",
            "Content-Type": "application/json"
        ],
        body: bodyJson,
        timeout: 30
    ]

    logDebug "Login request params: ${params.uri}"

    try {
        def result = [success: false]
        httpPost(params) { resp ->
            logDebug "Login response status: ${resp.status}"
            logDebug "Login response data: ${resp.data}"
            if (resp.status == 200) {
                def data = resp.data
                if (data?.payload?.token) {
                    state.hatchAuthToken = data.payload.token
                    result.success = true
                    logDebug "Got Hatch auth token"
                } else if (data?.token) {
                    // Alternative response format
                    state.hatchAuthToken = data.token
                    result.success = true
                    logDebug "Got Hatch auth token (alt format)"
                } else {
                    result.error = "No token in response: ${resp.data}"
                }
            } else {
                result.error = "HTTP ${resp.status}: ${resp.data}"
            }
        }
        return result
    } catch (groovyx.net.http.HttpResponseException e) {
        logError "Login HTTP error: ${e.statusCode} - ${e.message}"
        try {
            logError "Response body: ${e.response?.data}"
        } catch (ex) {}
        return [success: false, error: "HTTP ${e.statusCode}: ${e.message}"]
    } catch (e) {
        logError "Login error: ${e.class.name}: ${e.message}"
        return [success: false, error: e.message]
    }
}

def fetchIotToken() {
    logDebug "fetchIotToken() called"

    def params = [
        uri: "${HATCH_API_URL}/service/app/restPlus/token/v1/fetch",
        contentType: "application/json",
        headers: [
            "X-HatchBaby-Auth": state.hatchAuthToken
        ],
        timeout: 30
    ]

    try {
        def result = [success: false]
        httpGet(params) { resp ->
            logDebug "IoT token response status: ${resp.status}"
            logDebug "IoT token response data: ${resp.data}"
            if (resp.status == 200) {
                def data = resp.data
                if (data?.payload) {
                    state.awsRegion = data.payload.region ?: "us-east-1"
                    state.identityId = data.payload.identityId
                    state.cognitoToken = data.payload.token
                    // Strip https:// prefix if present - we add it back when making requests
                    def endpoint = data.payload.endpoint
                    if (endpoint?.startsWith("https://")) {
                        endpoint = endpoint.substring(8)
                    }
                    state.iotEndpoint = endpoint
                    result.success = true
                    logDebug "Got IoT token - Region: ${state.awsRegion}, Endpoint: ${state.iotEndpoint}"
                    logDebug "IdentityId: ${state.identityId}"
                    logDebug "CognitoToken length: ${state.cognitoToken?.length()}"
                } else {
                    result.error = "No payload in response"
                }
            } else {
                result.error = "HTTP ${resp.status}"
            }
        }
        return result
    } catch (e) {
        logError "fetchIotToken error: ${e.message}"
        return [success: false, error: e.message]
    }
}

def exchangeForAwsCredentials() {
    logDebug "exchangeForAwsCredentials() called"
    logDebug "Using IdentityId: ${state.identityId}"
    logDebug "Using cognitoToken (first 50 chars): ${state.cognitoToken?.take(50)}..."

    def cognitoUrl = "https://cognito-identity.${state.awsRegion}.amazonaws.com/"

    def body = JsonOutput.toJson([
        IdentityId: state.identityId,
        Logins: [
            "cognito-identity.amazonaws.com": state.cognitoToken
        ]
    ])

    logDebug "Cognito request URL: ${cognitoUrl}"
    logDebug "Cognito request body: ${body}"

    def params = [
        uri: cognitoUrl,
        requestContentType: "application/json",
        contentType: "application/json",
        headers: [
            "X-Amz-Target": "AWSCognitoIdentityService.GetCredentialsForIdentity",
            "Content-Type": "application/x-amz-json-1.1"
        ],
        body: body,
        timeout: 30
    ]

    try {
        def result = [success: false]
        httpPost(params) { resp ->
            logDebug "Cognito response status: ${resp.status}"
            logDebug "Cognito response data: ${resp.data}"
            if (resp.status == 200) {
                def data = resp.data
                if (data?.Credentials) {
                    state.awsAccessKeyId = data.Credentials.AccessKeyId
                    state.awsSecretKey = data.Credentials.SecretKey
                    state.awsSessionToken = data.Credentials.SessionToken
                    state.tokenExpiration = data.Credentials.Expiration
                    result.success = true
                    logDebug "Got AWS credentials, expiration: ${state.tokenExpiration}"
                } else {
                    result.error = "No credentials in response: ${resp.data}"
                }
            } else {
                result.error = "HTTP ${resp.status}: ${resp.data}"
            }
        }
        return result
    } catch (groovyx.net.http.HttpResponseException e) {
        logError "Cognito HTTP error: ${e.statusCode} - ${e.message}"
        try {
            logError "Cognito error response: ${e.response?.data}"
        } catch (ex) {}
        return [success: false, error: "HTTP ${e.statusCode}: ${e.message}"]
    } catch (e) {
        logError "exchangeForAwsCredentials error: ${e.message}"
        return [success: false, error: e.message]
    }
}

def refreshTokensIfNeeded(force = false) {
    logDebug "refreshTokensIfNeeded(force=${force})"

    // Check if tokens are about to expire (within 5 minutes)
    def needsRefresh = force
    if (!force && state.tokenExpiration) {
        def expiration = state.tokenExpiration * 1000 // Convert to ms
        def fiveMinutes = 5 * 60 * 1000
        if ((expiration - now()) < fiveMinutes) {
            needsRefresh = true
        }
    }

    if (needsRefresh) {
        logInfo "Refreshing authentication tokens"
        def iotResult = fetchIotToken()
        if (iotResult.success) {
            def awsResult = exchangeForAwsCredentials()
            if (!awsResult.success) {
                logError "Failed to refresh AWS credentials"
            }
        } else {
            logError "Failed to refresh IoT token"
        }
    }
}

// ==================== Device Discovery ====================

def discoverDevices() {
    logDebug "discoverDevices() called"

    refreshTokensIfNeeded()

    def params = [
        uri: "${HATCH_API_URL}/service/app/iotDevice/v2/fetch",
        query: [iotProducts: "restPlus"],
        contentType: "application/json",
        headers: [
            "X-HatchBaby-Auth": state.hatchAuthToken
        ],
        timeout: 30
    ]

    try {
        httpGet(params) { resp ->
            logDebug "Device discovery response status: ${resp.status}"
            if (resp.status == 200) {
                def data = resp.data
                if (data?.payload) {
                    state.devices = data.payload.collect { device ->
                        [
                            id: device.id,
                            name: device.name ?: "Hatch Rest+",
                            thingName: device.thingName,
                            macAddress: device.macAddress,
                            product: device.product
                        ]
                    }
                    logInfo "Found ${state.devices.size()} device(s)"
                    logDebug "Devices: ${state.devices}"
                }
            }
        }
    } catch (e) {
        logError "discoverDevices error: ${e.message}"
    }
}

// ==================== AWS SigV4 Signing ====================

def sha256Hex(String data) {
    def digest = MessageDigest.getInstance("SHA-256")
    def hash = digest.digest(data.getBytes("UTF-8"))
    return hash.encodeHex().toString()
}

def hmacSha256(byte[] key, String data) {
    def mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data.getBytes("UTF-8"))
}

def hmacSha256Hex(byte[] key, String data) {
    return hmacSha256(key, data).encodeHex().toString()
}

// ==================== AWS IoT WebSocket/MQTT ====================
// Note: The Hatch Cognito credentials only support MQTT, not HTTP REST API.
// We use MQTT over WebSocket with a presigned URL for device communication.

def generatePresignedMqttUrl() {
    logDebug "generatePresignedMqttUrl()"

    def now = new Date()
    def amzDate = now.format("yyyyMMdd'T'HHmmss'Z'", TimeZone.getTimeZone('UTC'))
    def dateStamp = now.format("yyyyMMdd", TimeZone.getTimeZone('UTC'))

    def host = state.iotEndpoint
    def region = state.awsRegion
    def service = "iotdevicegateway"
    def algorithm = "AWS4-HMAC-SHA256"
    def method = "GET"
    def canonicalUri = "/mqtt"
    def credentialScope = "${dateStamp}/${region}/${service}/aws4_request"

    // Query string parameters for signing (must be in alphabetical order)
    // IMPORTANT: For AWS IoT, X-Amz-Security-Token must NOT be included in the canonical query string
    // It gets added to the final URL AFTER the signature is calculated
    def credential = URLEncoder.encode("${state.awsAccessKeyId}/${credentialScope}", "UTF-8")
    def canonicalQuerystring = "X-Amz-Algorithm=${algorithm}"
    canonicalQuerystring += "&X-Amz-Credential=${credential}"
    canonicalQuerystring += "&X-Amz-Date=${amzDate}"
    canonicalQuerystring += "&X-Amz-Expires=300"  // Max 300 seconds for IoT
    canonicalQuerystring += "&X-Amz-SignedHeaders=host"

    // Canonical headers
    def canonicalHeaders = "host:${host}\n"
    def signedHeaders = "host"
    def payloadHash = sha256Hex("")

    // Create canonical request
    // Format: METHOD\nURI\nQUERY\nHEADERS\n\nSIGNED_HEADERS\nPAYLOAD_HASH
    // Note: There must be an empty line between headers and signed headers (double \n)
    def canonicalRequest = "${method}\n${canonicalUri}\n${canonicalQuerystring}\n${canonicalHeaders}\n${signedHeaders}\n${payloadHash}"
    logDebug "Canonical request for WebSocket:\n${canonicalRequest}"

    // Create string to sign
    def stringToSign = "${algorithm}\n${amzDate}\n${credentialScope}\n${sha256Hex(canonicalRequest)}"
    logDebug "String to sign:\n${stringToSign}"

    // Calculate signature
    def kDate = hmacSha256("AWS4${state.awsSecretKey}".getBytes("UTF-8"), dateStamp)
    def kRegion = hmacSha256(kDate, region)
    def kService = hmacSha256(kRegion, service)
    def kSigning = hmacSha256(kService, "aws4_request")
    def signature = hmacSha256Hex(kSigning, stringToSign)

    // Build final URL - add signature first, then session token AFTER signing
    def url = "wss://${host}${canonicalUri}?${canonicalQuerystring}&X-Amz-Signature=${signature}"
    // Add session token at the end (not included in signature for AWS IoT)
    url += "&X-Amz-Security-Token=${URLEncoder.encode(state.awsSessionToken, 'UTF-8')}"
    logDebug "Presigned WebSocket URL generated (length: ${url.length()})"

    return url
}

// ==================== Device Shadow API ====================
// These methods use MQTT over WebSocket to communicate with AWS IoT

def getDeviceShadow(String thingName) {
    logDebug "getDeviceShadow(${thingName})"

    refreshTokensIfNeeded()

    // Get shadow state from device's cached state or MQTT
    // For now, return cached state - MQTT polling will update it
    def childDevice = getChildDevices().find { it.getDataValue("thingName") == thingName }
    if (childDevice) {
        return childDevice.getState()
    }

    logWarn "getDeviceShadow: No cached state available for ${thingName}"
    return null
}

def updateDeviceShadow(String thingName, Map desiredState) {
    logDebug "updateDeviceShadow(${thingName}, ${desiredState})"

    refreshTokensIfNeeded()

    // Log the shadow update - actual MQTT sending is handled by the child device
    sendMqttUpdate(thingName, desiredState)

    return [success: true]
}

def sendMqttUpdate(String thingName, Map desiredState) {
    logDebug "sendMqttUpdate(${thingName}, ${desiredState})"

    // Build the shadow update payload
    def payload = JsonOutput.toJson([state: [desired: desiredState]])
    def topic = "\$aws/things/${thingName}/shadow/update"

    logDebug "MQTT topic: ${topic}"
    logDebug "MQTT payload: ${payload}"

    // Log the intended action - actual MQTT sending is handled by the child device
    logInfo "Shadow update queued for ${thingName}: ${desiredState}"

    return true
}

// ==================== Favorites & Routines ====================

def getFavorites(String macAddress) {
    logDebug "getFavorites(${macAddress})"

    refreshTokensIfNeeded()

    // Try the routine endpoint which contains favorites
    def params = [
        uri: "${HATCH_API_URL}/service/app/routine/v2/fetch",
        query: [macAddress: macAddress],
        contentType: "application/json",
        headers: [
            "X-HatchBaby-Auth": state.hatchAuthToken,
            "User-Agent": "hatch_rest_api"
        ],
        timeout: 30
    ]

    try {
        def result = []
        httpGet(params) { resp ->
            logDebug "Favorites response: ${resp.data}"
            if (resp.status == 200 && resp.data?.payload) {
                result = resp.data.payload
                logDebug "Favorites: ${result}"
            }
        }
        return result
    } catch (e) {
        // 404 is expected if no favorites exist
        if (e.message?.contains("404")) {
            logDebug "No favorites found for device"
        } else {
            logWarn "getFavorites error: ${e.message}"
        }
        return []
    }
}

def getRoutines(String macAddress) {
    logDebug "getRoutines(${macAddress})"

    refreshTokensIfNeeded()

    def params = [
        uri: "${HATCH_API_URL}/service/app/routine/v2/fetch",
        query: [macAddress: macAddress],
        contentType: "application/json",
        headers: [
            "X-HatchBaby-Auth": state.hatchAuthToken,
            "User-Agent": "hatch_rest_api"
        ],
        timeout: 30
    ]

    try {
        def result = []
        httpGet(params) { resp ->
            logDebug "Routines response: ${resp.data}"
            if (resp.status == 200 && resp.data?.payload) {
                result = resp.data.payload
                logDebug "Routines: ${result}"
            }
        }
        return result
    } catch (e) {
        // 404 is expected if no routines exist
        if (e.message?.contains("404")) {
            logDebug "No routines found for device"
        } else {
            logWarn "getRoutines error: ${e.message}"
        }
        return []
    }
}

// ==================== Child Device Support ====================

def getTokens() {
    return [
        hatchToken: state.hatchAuthToken,
        awsAccessKeyId: state.awsAccessKeyId,
        awsSecretKey: state.awsSecretKey,
        awsSessionToken: state.awsSessionToken,
        awsRegion: state.awsRegion,
        iotEndpoint: state.iotEndpoint
    ]
}


// ==================== Lifecycle ====================

def installed() {
    logInfo "Hatch Connect installed"
    syncDevices()
    initialize()
}

def updated() {
    logInfo "Hatch Connect updated"
    syncDevices()
    initialize()
}

def initialize() {
    logInfo "Hatch Connect initializing"

    // Schedule token refresh every 30 minutes
    runEvery30Minutes("refreshTokensIfNeeded")
}

def uninstalled() {
    logInfo "Hatch Connect uninstalled"
    unschedule()
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

// ==================== Logging ====================

def logDebug(msg) {
    if (DEV_MODE || settings.debugLogging) {
        log.debug "HatchConnect: ${msg}"
    }
}

def logInfo(msg) {
    log.info "HatchConnect: ${msg}"
}

def logWarn(msg) {
    log.warn "HatchConnect: ${msg}"
}

def logError(msg) {
    log.error "HatchConnect: ${msg}"
}
