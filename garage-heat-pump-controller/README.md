# Garage Heat Pump Controller

A Hubitat app that controls a garage heat pump (or any thermostat) from the
average of two temperature sensors, with hysteresis and a garage-door
interlock. Built to replace a pair of complicated Rule Machine rules that ran
the garage mini-split.

## Behavior

- **Temperature** is the average of the heat pump's own temperature and a
  second sensor (here: the Garage Door Left Sensor). If the second sensor has
  not communicated in `staleHours` (default 3), its reading is ignored and
  the heat pump temperature is used alone. The heat pump needs no staleness
  check: the app is event-driven, so a silent heat pump simply produces no
  evaluations and nothing changes.
- **Hysteresis:** from off, heat turns on below 55°F and cool above 77°F.
  From heat, the unit turns off above 57°F; from cool, below 75°F. The unit
  never switches directly between heat and cool — it always passes through
  off.
- **Turning on sets the setpoint first** (heat 50°F / cool 80°F), then the
  mode, so the unit never runs at a stale setpoint.
- **Garage door:** if either door stays open for 5 minutes, the unit turns
  off. While a door is open, temperature changes do not turn the unit on.
  When both doors close, the temperature is re-evaluated immediately.
- **The app owns the thermostat mode:** dry/fan/auto are treated like off, so
  a manual mode is normalized on the next temperature or door event.

All thresholds, setpoints, the door timer, and the staleness window are
settings on the app's page — no code changes needed to tune them.

## Install

Install from HPM — search for "Garage Heat Pump Controller". Or import the
app code manually from
`apps/garage-heat-pump-controller.groovy` in this repo, then add it under
Apps → Add User App.

## Requires

- A thermostat device (tested with the [Mitsubishi Comfort](../mitsubishi-comfort)
  driver for Mitsubishi mini-splits)
- A second temperature sensor (optional, but the averaging is the point)
- One or more garage door contact sensors
