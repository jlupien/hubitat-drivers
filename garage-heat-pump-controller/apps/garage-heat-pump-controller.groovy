/**
 *  Garage Heat Pump Controller for Hubitat
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
 *  Controls a garage heat pump (or any thermostat) from the average of two
 *  temperature sensors, with hysteresis and a garage-door interlock.
 *
 *  Behavior:
 *  - Temperature is the average of the heat pump's own temperature and a
 *    second sensor (here: the Garage Door Left Sensor). If the second sensor
 *    has not communicated in `staleHours` (default 3), its reading is
 *    ignored and the heat pump temperature is used alone. The heat pump
 *    needs no staleness check: the app is event-driven, so a silent heat
 *    pump simply produces no evaluations and nothing changes.
 *  - Hysteresis: from off, heat turns on below `heatOnTemp` (55) and cool
 *    above `coolOnTemp` (77). From heat, the unit turns off above
 *    `heatOffTemp` (57); from cool, below `coolOffTemp` (75). The unit never
 *    switches directly between heat and cool - it always passes through off.
 *  - Turning on sets the setpoint first (heat 50 / cool 80), then the mode.
 *  - If either garage door stays open for `doorOpenMinutes` (5), the unit
 *    turns off. While a door is open, temperature changes do not turn the
 *    unit on. When both doors close, the temperature is re-evaluated
 *    immediately.
 *  - The app owns the thermostat mode: dry/fan/auto are treated like off,
 *    so a manual mode is normalized on the next temperature or door event.
 *
 *  Change history:
 *    2026-11-13  Jeff Lupien  1.0.0  Initial release.
 *    2026-11-13  Jeff Lupien  1.0.1  Rename evaluate() to evaluateConditions() - evaluate
 *                                  collides with groovy.lang.Script.evaluate() and fails
 *                                  to compile as a private method.
 */

import groovy.transform.Field

definition(
    name: "Garage Heat Pump Controller",
    namespace: "jlupien",
    author: "Jeff Lupien",
    description: "Controls a garage heat pump from averaged temperature sensors with hysteresis and a garage-door interlock.",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    singleInstance: false
)

@Field static final String VERSION = "1.0.1"

preferences {
    page(name: "mainPage", title: "Garage Heat Pump Controller", install: true, uninstall: true) {
        section("Devices") {
            input "thermostat", "capability.thermostat", title: "Heat pump", required: true, multiple: false
            input "secondTempSensor", "capability.temperatureMeasurement", title: "Second temperature sensor (averaged with the heat pump)", required: false, multiple: false
            input "doorSensors", "capability.contactSensor", title: "Garage door contact sensors", required: true, multiple: true
        }
        section("Temperature thresholds (°F)") {
            input "heatOnTemp", "number", title: "Turn heat ON below", defaultValue: 55, required: true
            input "heatOffTemp", "number", title: "Turn heat OFF above", defaultValue: 57, required: true
            input "coolOnTemp", "number", title: "Turn cool ON above", defaultValue: 77, required: true
            input "coolOffTemp", "number", title: "Turn cool OFF below", defaultValue: 75, required: true
        }
        section("Setpoints (°F)") {
            input "heatSetpoint", "number", title: "Heating setpoint", defaultValue: 50, required: true
            input "coolSetpoint", "number", title: "Cooling setpoint", defaultValue: 80, required: true
        }
        section("Garage door") {
            input "doorOpenMinutes", "number", title: "Turn off after door open for (minutes)", defaultValue: 5, required: true
        }
        section("Second sensor staleness") {
            input "staleHours", "number", title: "Ignore second sensor if silent for (hours)", defaultValue: 3, required: true
        }
        section("Logging") {
            input "logDebug", "bool", title: "Enable debug logging (auto-off after 30 minutes)", defaultValue: false
        }
    }
}

def installed() {
    initialize()
}

def updated() {
    unsubscribe()
    unschedule()
    initialize()
}

def initialize() {
    subscribe(thermostat, "temperature", tempHandler)
    if (secondTempSensor) {
        subscribe(secondTempSensor, "temperature", tempHandler)
    }
    subscribe(doorSensors, "contact", contactHandler)
    if (logDebug) {
        runIn(1800, "disableDebugLogging")
    }
    evaluateConditions("initialize")
}

def tempHandler(evt) {
    evaluateConditions("temperature ${evt.device} ${evt.value}")
}

def contactHandler(evt) {
    if (anyDoorOpen()) {
        debugLog("contactHandler: ${evt.device} ${evt.value} - door open, scheduling off in ${doorOpenMinutes} min")
        runIn(((doorOpenMinutes ?: 5) as int) * 60, "doorTimerFired", [overwrite: true])
    } else {
        debugLog("contactHandler: all doors closed - cancelling timer, re-evaluating")
        unschedule("doorTimerFired")
        evaluateConditions("doors closed")
    }
}

def doorTimerFired() {
    if (!anyDoorOpen()) {
        return
    }
    def mode = thermostat.currentValue("thermostatMode")
    if (mode == "heat" || mode == "cool") {
        infoLog("doorTimerFired: door open for ${doorOpenMinutes} min, turning ${mode} off")
        setMode("off")
    }
}

def disableDebugLogging() {
    app.updateSetting("logDebug", [value: "false", type: "bool"])
    infoLog("Debug logging disabled after 30 minutes")
}

private def anyDoorOpen() {
    return doorSensors?.any { it.currentValue("contact") == "open" } ?: false
}

private def evaluateConditions(String reason) {
    if (anyDoorOpen()) {
        debugLog("evaluateConditions (${reason}): door open, skipping temperature evaluation")
        return
    }
    def temp = averageTemp()
    if (temp == null) {
        debugLog("evaluateConditions (${reason}): no temperature available, doing nothing")
        return
    }
    def mode = thermostat.currentValue("thermostatMode")
    debugLog("evaluateConditions (${reason}): avg temp ${temp}°F, mode ${mode}")
    if (mode == "heat") {
        if (temp > heatOffTemp) {
            infoLog("evaluateConditions: ${temp}°F above heat-off ${heatOffTemp}°F, turning off")
            setMode("off")
        }
        return
    }
    if (mode == "cool") {
        if (temp < coolOffTemp) {
            infoLog("evaluateConditions: ${temp}°F below cool-off ${coolOffTemp}°F, turning off")
            setMode("off")
        }
        return
    }
    // Off (or dry/fan/auto, which are treated like off - the app owns the mode).
    if (temp < heatOnTemp) {
        infoLog("evaluateConditions: ${temp}°F below heat-on ${heatOnTemp}°F, turning heat on at ${heatSetpoint}°F")
        setMode("heat")
    } else if (temp > coolOnTemp) {
        infoLog("evaluateConditions: ${temp}°F above cool-on ${coolOnTemp}°F, turning cool on at ${coolSetpoint}°F")
        setMode("cool")
    } else if (mode != "off") {
        infoLog("evaluateConditions: ${temp}°F in the dead band, turning ${mode} off")
        setMode("off")
    }
}

private def averageTemp() {
    def temps = []
    def pumpTemp = thermostat.currentValue("temperature")?.toFloat()
    if (pumpTemp != null) {
        temps << pumpTemp
    }
    if (secondTempSensor) {
        def secondTemp = secondTempSensor.currentValue("temperature")?.toFloat()
        if (secondTemp != null && !isSecondSensorStale()) {
            temps << secondTemp
        }
    }
    if (temps.isEmpty()) {
        return null
    }
    return (temps.sum() / temps.size())
}

private def isSecondSensorStale() {
    try {
        def lastActivity = secondTempSensor.getLastActivity()
        if (lastActivity == null) {
            return false
        }
        def staleMs = ((staleHours ?: 3) as int) * 3600000
        def stale = (now() - lastActivity.time) > staleMs
        if (stale) {
            debugLog("Second sensor last communicated ${lastActivity}, older than ${staleHours}h - ignoring its temperature")
        }
        return stale
    } catch (Exception e) {
        debugLog("isSecondSensorStale: could not read last activity (${e}), treating sensor as fresh")
        return false
    }
}

private def setMode(String mode) {
    if (mode == "heat") {
        thermostat.setHeatingSetpoint(heatSetpoint)
        thermostat.setThermostatMode("heat")
    } else if (mode == "cool") {
        thermostat.setCoolingSetpoint(coolSetpoint)
        thermostat.setThermostatMode("cool")
    } else {
        thermostat.setThermostatMode("off")
    }
}

private def debugLog(String message) {
    if (logDebug) {
        log.debug("${app.label ?: app.name}: ${message}")
    }
}

private def infoLog(String message) {
    log.info("${app.label ?: app.name}: ${message}")
}
