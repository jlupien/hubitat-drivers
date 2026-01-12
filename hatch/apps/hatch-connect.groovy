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

@Field static final String VERSION = "1.0.0"
@Field static final String HATCH_API_URL = "https://data.hatchbaby.com"
@Field static final String DRIVER_NAME = "Hatch Rest+"
@Field static final String DRIVER_NAMESPACE = "jlupien"

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
@Field static final boolean DEV_MODE = true

// ==================== Pages ====================

def mainPage() {
    dynamicPage(name: "mainPage", title: "Hatch Connect", install: true, uninstall: true) {
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

        section("Installed Devices") {
            def devices = getChildDevices()
            if (devices) {
                devices.each { device ->
                    paragraph "${device.displayName}"
                }
            } else {
                paragraph "No devices installed yet"
            }
        }
    }
}

def credentialsPage() {
    logDebug "credentialsPage() called"

    if (!settings.hatchEmail) {
        state.loginError = null
    }

    dynamicPage(name: "credentialsPage", title: "Hatch Login", nextPage: "authProgressPage") {
        section("Credentials") {
            paragraph "Enter your Hatch account credentials. Your password is not stored - only authentication tokens are saved."
            input "hatchEmail", "email", title: "Email", required: true, submitOnChange: false
            input "hatchPassword", "password", title: "Password", required: true, submitOnChange: false
        }

        section {
            paragraph "Click Next to login with these credentials."
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
        return dynamicPage(name: "authProgressPage", title: "Login Failed", nextPage: "credentialsPage") {
            section {
                paragraph "<span style='color:red'>Login failed. Please check your credentials and try again.</span>"
                paragraph "Error: ${loginResult.error}"
            }
        }
    }

    // Step 2: Get AWS IoT token
    def iotTokenResult = fetchIotToken()
    logDebug "IoT token result: ${iotTokenResult}"

    if (!iotTokenResult.success) {
        state.loginError = "Failed to get IoT token: ${iotTokenResult.error}"
        state.authInProgress = false
        return dynamicPage(name: "authProgressPage", title: "Login Failed", nextPage: "credentialsPage") {
            section {
                paragraph "<span style='color:red'>Failed to initialize device connection.</span>"
                paragraph "Error: ${iotTokenResult.error}"
            }
        }
    }

    // Step 3: Exchange for AWS credentials
    def awsResult = exchangeForAwsCredentials()
    logDebug "AWS credentials result: ${awsResult}"

    if (!awsResult.success) {
        state.loginError = "Failed to get AWS credentials: ${awsResult.error}"
        state.authInProgress = false
        return dynamicPage(name: "authProgressPage", title: "Login Failed", nextPage: "credentialsPage") {
            section {
                paragraph "<span style='color:red'>Failed to authenticate with cloud service.</span>"
                paragraph "Error: ${awsResult.error}"
            }
        }
    }

    state.authInProgress = false
    state.authenticated = true
    logInfo "Authentication successful!"

    // Discover devices
    discoverDevices()

    return dynamicPage(name: "authProgressPage", title: "Login Successful", nextPage: "mainPage") {
        section {
            paragraph "<span style='color:green'>Successfully logged in to Hatch!</span>"
            if (state.devices) {
                paragraph "Found ${state.devices.size()} device(s)"
            }
            paragraph "Click Next to continue."
        }
    }
}

def deviceSelectPage() {
    logDebug "deviceSelectPage() called"

    // Refresh device list if empty
    if (!state.devices) {
        discoverDevices()
    }

    dynamicPage(name: "deviceSelectPage", title: "Select Devices", nextPage: "applyDevicesPage") {
        section("Available Devices") {
            if (state.devices && state.devices.size() > 0) {
                paragraph "Select the devices you want to add to Hubitat:"
                state.devices.each { device ->
                    def existingDevice = getChildDevice("hatch-${device.id}")
                    def installed = existingDevice ? " (installed)" : ""
                    input "device_${device.id}", "bool", title: "${device.name}${installed}",
                          defaultValue: existingDevice != null, submitOnChange: false
                }
            } else {
                paragraph "No devices found. Make sure your devices are set up in the Hatch app."
            }
        }
    }
}

def applyDevicesPage() {
    logDebug "applyDevicesPage() called"

    def addedDevices = []
    def removedDevices = []

    state.devices?.each { device ->
        def selected = settings["device_${device.id}"]
        def existingDevice = getChildDevice("hatch-${device.id}")

        if (selected && !existingDevice) {
            // Create new device
            try {
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
                newDevice.updateDataValue("deviceId", device.id)
                newDevice.updateDataValue("product", device.product)
                addedDevices << device.name
                logInfo "Created device: ${device.name}"
            } catch (e) {
                logError "Failed to create device ${device.name}: ${e.message}"
            }
        } else if (!selected && existingDevice) {
            // Remove device
            try {
                deleteChildDevice("hatch-${device.id}")
                removedDevices << device.name
                logInfo "Removed device: ${device.name}"
            } catch (e) {
                logError "Failed to remove device ${device.name}: ${e.message}"
            }
        }
    }

    dynamicPage(name: "applyDevicesPage", title: "Devices Updated", nextPage: "mainPage") {
        section {
            if (addedDevices) {
                paragraph "Added: ${addedDevices.join(', ')}"
            }
            if (removedDevices) {
                paragraph "Removed: ${removedDevices.join(', ')}"
            }
            if (!addedDevices && !removedDevices) {
                paragraph "No changes made."
            }
            paragraph "Click Next to return to the main page."
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
    app.removeSetting("hatchPassword")
}

// ==================== Authentication ====================

def loginToHatch(String email, String password) {
    logDebug "loginToHatch() called"

    def params = [
        uri: "${HATCH_API_URL}/public/v1/login",
        contentType: "application/json",
        headers: [
            "User-Agent": "hatch_rest_api"
        ],
        body: JsonOutput.toJson([email: email, password: password]),
        timeout: 30
    ]

    try {
        def result = [success: false]
        httpPost(params) { resp ->
            logDebug "Login response status: ${resp.status}"
            if (resp.status == 200) {
                def data = resp.data
                if (data?.payload?.token) {
                    state.hatchAuthToken = data.payload.token
                    result.success = true
                    logDebug "Got Hatch auth token"
                } else {
                    result.error = "No token in response"
                }
            } else {
                result.error = "HTTP ${resp.status}"
            }
        }
        return result
    } catch (e) {
        logError "Login error: ${e.message}"
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
            if (resp.status == 200) {
                def data = resp.data
                if (data?.payload) {
                    state.awsRegion = data.payload.region ?: "us-east-1"
                    state.identityId = data.payload.identityId
                    state.cognitoToken = data.payload.token
                    state.iotEndpoint = data.payload.endpoint
                    result.success = true
                    logDebug "Got IoT token - Region: ${state.awsRegion}, Endpoint: ${state.iotEndpoint}"
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

    def cognitoUrl = "https://cognito-identity.${state.awsRegion}.amazonaws.com/"

    def body = JsonOutput.toJson([
        IdentityId: state.identityId,
        Logins: [
            "cognito-identity.amazonaws.com": state.cognitoToken
        ]
    ])

    def params = [
        uri: cognitoUrl,
        contentType: "application/x-amz-json-1.1",
        headers: [
            "X-Amz-Target": "AWSCognitoIdentityService.GetCredentialsForIdentity"
        ],
        body: body,
        timeout: 30
    ]

    try {
        def result = [success: false]
        httpPost(params) { resp ->
            logDebug "Cognito response status: ${resp.status}"
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
                    result.error = "No credentials in response"
                }
            } else {
                result.error = "HTTP ${resp.status}"
            }
        }
        return result
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

def signAwsRequest(String method, String host, String path, String body, String service = "iotdata") {
    def now = new Date()
    def amzDate = now.format("yyyyMMdd'T'HHmmss'Z'", TimeZone.getTimeZone('UTC'))
    def dateStamp = now.format("yyyyMMdd", TimeZone.getTimeZone('UTC'))

    def algorithm = "AWS4-HMAC-SHA256"
    def scope = "${dateStamp}/${state.awsRegion}/${service}/aws4_request"

    // Canonical request
    def canonicalUri = path
    def canonicalQuerystring = ""
    def canonicalHeaders = "host:${host}\nx-amz-date:${amzDate}\n"
    def signedHeaders = "host;x-amz-date"
    def payloadHash = sha256Hex(body ?: "")

    def canonicalRequest = "${method}\n${canonicalUri}\n${canonicalQuerystring}\n${canonicalHeaders}\n${signedHeaders}\n${payloadHash}"
    logDebug "Canonical request:\n${canonicalRequest}"

    // String to sign
    def stringToSign = "${algorithm}\n${amzDate}\n${scope}\n${sha256Hex(canonicalRequest)}"
    logDebug "String to sign:\n${stringToSign}"

    // Signing key
    def kDate = hmacSha256("AWS4${state.awsSecretKey}".getBytes("UTF-8"), dateStamp)
    def kRegion = hmacSha256(kDate, state.awsRegion)
    def kService = hmacSha256(kRegion, service)
    def kSigning = hmacSha256(kService, "aws4_request")

    // Signature
    def signature = hmacSha256Hex(kSigning, stringToSign)

    // Authorization header
    def authHeader = "${algorithm} Credential=${state.awsAccessKeyId}/${scope}, SignedHeaders=${signedHeaders}, Signature=${signature}"

    return [
        "Authorization": authHeader,
        "x-amz-date": amzDate,
        "x-amz-security-token": state.awsSessionToken,
        "Host": host
    ]
}

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

// ==================== Device Shadow API ====================

def getDeviceShadow(String thingName) {
    logDebug "getDeviceShadow(${thingName})"

    refreshTokensIfNeeded()

    def host = state.iotEndpoint
    def path = "/things/${thingName}/shadow"
    def headers = signAwsRequest("GET", host, path, "")

    def params = [
        uri: "https://${host}${path}",
        headers: headers,
        contentType: "application/json",
        timeout: 30
    ]

    try {
        def result = null
        httpGet(params) { resp ->
            logDebug "Shadow GET response status: ${resp.status}"
            if (resp.status == 200) {
                result = resp.data
                logDebug "Shadow data: ${result}"
            }
        }
        return result
    } catch (e) {
        logError "getDeviceShadow error: ${e.message}"
        return null
    }
}

def updateDeviceShadow(String thingName, Map desiredState) {
    logDebug "updateDeviceShadow(${thingName}, ${desiredState})"

    refreshTokensIfNeeded()

    def host = state.iotEndpoint
    def path = "/things/${thingName}/shadow"
    def body = JsonOutput.toJson([state: [desired: desiredState]])
    def headers = signAwsRequest("POST", host, path, body)
    headers["Content-Type"] = "application/json"

    def params = [
        uri: "https://${host}${path}",
        headers: headers,
        contentType: "application/json",
        body: body,
        timeout: 30
    ]

    try {
        def result = [success: false]
        httpPost(params) { resp ->
            logDebug "Shadow POST response status: ${resp.status}"
            if (resp.status == 200) {
                result.success = true
                result.data = resp.data
            } else {
                result.error = "HTTP ${resp.status}"
            }
        }
        return result
    } catch (e) {
        logError "updateDeviceShadow error: ${e.message}"
        return [success: false, error: e.message]
    }
}

// ==================== Favorites & Routines ====================

def getFavorites(String macAddress) {
    logDebug "getFavorites(${macAddress})"

    refreshTokensIfNeeded()

    def params = [
        uri: "${HATCH_API_URL}/service/app/restPlus/favorites/v1/fetch",
        query: [macAddress: macAddress],
        contentType: "application/json",
        headers: [
            "X-HatchBaby-Auth": state.hatchAuthToken
        ],
        timeout: 30
    ]

    try {
        def result = []
        httpGet(params) { resp ->
            if (resp.status == 200 && resp.data?.payload) {
                result = resp.data.payload
                logDebug "Favorites: ${result}"
            }
        }
        return result
    } catch (e) {
        logError "getFavorites error: ${e.message}"
        return []
    }
}

def getRoutines(String macAddress) {
    logDebug "getRoutines(${macAddress})"

    refreshTokensIfNeeded()

    def params = [
        uri: "${HATCH_API_URL}/service/app/restPlus/routines/v1/fetch",
        query: [macAddress: macAddress],
        contentType: "application/json",
        headers: [
            "X-HatchBaby-Auth": state.hatchAuthToken
        ],
        timeout: 30
    ]

    try {
        def result = []
        httpGet(params) { resp ->
            if (resp.status == 200 && resp.data?.payload) {
                result = resp.data.payload
                logDebug "Routines: ${result}"
            }
        }
        return result
    } catch (e) {
        logError "getRoutines error: ${e.message}"
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

def getAudioTracks() {
    return AUDIO_TRACKS
}

// ==================== Lifecycle ====================

def installed() {
    logInfo "Hatch Connect installed"
    initialize()
}

def updated() {
    logInfo "Hatch Connect updated"
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
