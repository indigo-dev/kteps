# 📡 Shimano STEPS BLE Protocol & Byte Specification

This document describes the proprietary Bluetooth Low Energy (BLE) communication protocol used by Shimano STEPS / EP8 e-bikes (EW-EN100, SC-EM800, SC-E7000, SC-EN600, etc.) and provides a comprehensive byte-by-byte mapping of the telemetry data packets.

---

## 1. Bluetooth Low Energy Architecture

Shimano does not use standard Bluetooth SIG cycling profiles (such as Cycling Speed & Cadence `0x1816` or Cycling Power `0x1818`) for live telemetry. Instead, all live ride data is broadcast through proprietary custom GATT services.

### Base UUID
The Shimano custom base UUID is:
```text
0000xxxx-5348-494d-414e-4f5f424c4500
```
*(The hex sequence `53 48 49 4D 41 4E 4F 5F 42 4C 45 00` spells `SHIMANO_BLE\0` in ASCII).*

### GATT Services & Characteristics

| Service | UUID | Characteristic | UUID | Properties | Description |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **D-Fly Telemetry** | `000018ef-...` | **Telemetry Stream** | `00002ac1-...` | `Notify` | Primary real-time telemetry stream (mode, speed, power, cadence, odometer, remaining range) |
| | | **D-Fly Switches** | `00002ac2-...` | `Indicate` | Button presses from Di2 auxiliary switches (channels CH1–CH4) |
| | | Handshake / Control | `00002ac0-...` / `2ac3` / `2ac4` | `Indicate`, `Read`, `Write` | Connection handshake and system status channels |
| **Battery Service** | `0000180f-...` | **Battery Level** | `00002a19-...` | `Notify`, `Read` | E-bike main battery level in percent (0–100%) |
| **Device Info** | `000018fe-...` | **Hardware MAC** | `00002ae2-...` / `2ae3` | `Read` | Static MAC address of the wireless module |
| **Configuration** | `000018ff-...` | *Various* | *Various* | `Read`, `Write` | E-Tube Project service (firmware updates, tuning). *Not used for live ride data!* |

---

## 2. Hardware Compatibility & Model Matrix

The display or junction unit acts as the Bluetooth LE gateway for the entire Shimano E-Tube bus:

### Displays & Wireless Units

| Model | Type | Cable Standard | Status | Details |
| :--- | :--- | :---: | :---: | :--- |
| **EW-EN100** | Wireless Junction Box (2 LEDs) | EW-SD50 / EW-SD300* | ✅ **Supported** | Reference device. Ultra-compact, fast connection, no passkey required. |
| **SC-EM800** | 1.6" Color LCD | EW-SD300 | ✅ **Supported** | Modern EP8/EP6 display. Supports automatic 6-digit passkey bonding. |
| **SC-E7000** | 1.39" Monochrome LCD | EW-SD50 | ✅ **Supported** | Compact MTB display. Supports passkey bonding. |
| **SC-EN600 / SC-EN610** | Compact Color Display | EW-SD300 | ✅ **Supported** | Latest generation EP801/EP6 display. |
| **SC-E6100** | 2.7" Large Monochrome LCD | EW-SD50 | ✅ **Supported** | Trekking/City display; broadcasts identical D-Fly telemetry. |
| **EW-SW310 / EW-EX310** | Integrated Frame Switch | EW-SD300 | ✅ **Supported** | Clean cockpits without handlebar display. |
| **SC-E8000** | 1.6" Color LCD (Gen 1) | EW-SD50 | ❌ **Incompatible** | **Protocol/firmware limitation:** Lacks the `0x18EF` live telemetry service. BLE operates strictly as a stationary *E-Tube* maintenance/configuration mode (`0x18FF`) that locks out motor assistance while connected, making even battery-only telemetry during rides impossible. |
| **SC-E5000** | Compact Display | EW-SD50 | ❌ **Incompatible** | No Bluetooth LE hardware built into the unit. |

*\*Note: Mixing EW-SD50 and EW-SD300 components requires the passive Shimano EW-AD305 adapter.*

### Compatible Drive Units (Motors)
All Shimano drive units (EP801, EP800, EP6, E8000, E7000, E6100, E5000) broadcast telemetry over E-Tube to the wireless display or junction unit above. Compatibility is determined strictly by the wireless gateway, not by the drive unit itself.

---

## 3. Byte Structure of Telemetry (`Characteristic 0x2AC1`)

The bike sends cyclic notifications on Characteristic `00002ac1-5348-494d-414e-4f5f424c4500`. The **first byte (`bytes[0]`)** identifies the packet type.

All multi-byte numeric values are encoded in **Little-Endian (LE)** order.  
Invalid or unsupported metrics are signaled by the respective maximum values:
* `0xFF` (UInt8, 255)
* `0xFFFF` (UInt16, 65535)
* `0xFFFFFFFF` (UInt32, 4294967295)

---

### A. Packet `0x02`: Real-time Telemetry (10 Bytes)

Broadcast continuously at a high update rate (~4–10 Hz).

| Byte Index | Data Type | Field Name | Kteps Status | Description & Conversion |
| :---: | :---: | :--- | :---: | :--- |
| `0` | UInt8 | **Packet ID** | ✅ Parsed | Fixed value `0x02` |
| `1` | UInt8 | **Assist Mode** | ✅ Parsed | Assistance level:<br>`0` = OFF<br>`1` = ECO<br>`2` = TRAIL<br>`3` = BOOST<br>`4` = WALK |
| `2..3` | UInt16 LE | **Speed** | ✅ Parsed | Current wheel speed in 0.1 km/h increments.<br>`value / 10.0` (e.g., `254` = 25.4 km/h) |
| `4` | UInt8 | **Rider Power / Assist** | ✅ Parsed | Rider pedaling power in Watts (or relative motor assist percentage on some firmware revisions) |
| `5` | UInt8 | **Cadence** | ✅ Parsed | Pedaling cadence in revolutions per minute (RPM) |
| `6` | UInt8 | **Di2 Rear Gear** | ⚪ *Unparsed* | Selected rear cassette gear (e.g., 1–11 / 1–12 on Shimano Di2 systems) |
| `7` | UInt8 | **Di2 Front Gear** | ⚪ *Unparsed* | Front chainring position or total gear count |
| `8` | UInt8 | **Status Flags** | ⚪ *Unparsed* | Bitmask for system flags:<br>• **Bit 0:** Active assist profile (`0` = Profile 1, `1` = Profile 2)<br>• **Bit 1:** E-bike light status (0 = Off, 1 = On)<br>• **Bit 2:** Walk assist actively pushing |
| `9` | UInt8 | **Assist Dynamics** | ⚪ *Unparsed* | Dynamic torque and motor assistance status flags |

---

### B. Packet `0x03`: Distance & Remaining Range (19 Bytes)

Broadcast periodically every 1–2 seconds.

| Byte Index | Data Type | Field Name | Kteps Status | Description & Conversion |
| :---: | :---: | :--- | :---: | :--- |
| `0` | UInt8 | **Packet ID** | ✅ Parsed | Fixed value `0x03` |
| `1..4` | UInt32 LE | **Odometer** | ✅ Parsed | Lifetime drive unit odometer in meters |
| `5..8` | UInt32 LE | **Trip Distance** | ⚪ *Unparsed* | Internal bike trip odometer in meters *(Kteps calculates trip distance from connection start)* |
| `9..10` | UInt16 LE | **Trip Time** | ⚪ *Unparsed* | Elapsed trip ride time in seconds |
| `11..12` | UInt16 LE | **Range Reserve** | ⚪ *Unparsed* | Average range estimate / reserved slot |
| `13..14` | UInt16 LE | **Range Boost** | ✅ Parsed | Estimated remaining range in Boost mode in km |
| `15..16` | UInt16 LE | **Range Trail** | ✅ Parsed | Estimated remaining range in Trail mode in km |
| `17..18` | UInt16 LE | **Range Eco** | ✅ Parsed | Estimated remaining range in Eco mode in km |

---

### C. Packet `0x01`: System Status & Diagnostics (~15–17 Bytes)

Transmitted on state changes or fault conditions.

| Byte Index | Data Type | Field Name | Kteps Status | Description & Conversion |
| :---: | :---: | :--- | :---: | :--- |
| `0` | UInt8 | **Packet ID** | ⚪ *Unparsed* | Fixed value `0x01` |
| `1..4` | UInt32 | **Error / Warning Codes** | ⚪ *Unparsed* | Active Shimano system fault flags (e.g., `W013`, `E010`). `0` when no error is present |
| `5` | UInt8 | **Di2 Rear Gear** | ⚪ *Unparsed* | Redundant gear index for cycling computers |
| `6` | UInt8 | **Di2 Front Gear** | ⚪ *Unparsed* | Redundant chainring index |
| `7..8` | UInt16 | **Di2 Shifting Mode** | ⚪ *Unparsed* | Di2 shifting mode (Manual, Semi-Synchro, Full-Synchro) |
| `9..13` | - | **Bus / Device States** | ⚪ *Unparsed* | Status of attached E-Tube bus peripherals |
| `14` | UInt8 | **Walk Button** | ⚪ *Unparsed* | Walk assist button pressed (`1`) / released (`0`) |

---

## 4. Battery Level (Battery Service `0x180F` / `0x2A19`)

* **Packet Format:** 1 Byte (UInt8, range `0..100`%).
* **Characteristics & Quirk:** Many Shimano wireless units (especially older EW-EN100 firmware builds) do not reliably publish automatic push notifications for battery percentage changes.
* **Kteps Solution:** A hybrid approach combining instantaneous notification processing with a periodic 10-second active GATT read fallback to keep the battery percentage accurate throughout long rides.

---

## 5. Connection Parameter Negotiation Quirk (`status=59` / `0x3B`)

Shimano wireless gateways such as the **EW-EN100** run on a Nordic Semiconductor nRF51 SoftDevice using Nordic SDK's `ble_conn_params` module configured with:
* `start_on_notify_cccd_handle = 0x2AC1` (telemetry CCCD)
* Preferred parameters: `min_interval = 50` (`62.5 ms`), `max_interval = 100` (`125.0 ms`), `latency = 0`, `timeout = 600` (`6.0 s`)
* `max_conn_params_update_count = 1`, `disconnect_on_fail = true`

During full GATT service discovery, Android Fluoride temporarily switches the BLE link to `interval = 6` (`7.5 ms`). Immediately after discovery finishes, Android issues an `HCI_LE_Connection_Update` back to `interval = 36` (`45.0 ms`), which takes ~85 ms to complete over the air.

If the client writes the `0x2AC1` CCCD before that post-discovery update completes:
1. Writing `0x2AC1` CCCD arms `ble_conn_params` on the bike, which sees `7.5 ms` and immediately sends `L2CAP_CMD_BLE_UPDATE_REQ (62.5–125 ms)`, consuming its single allowed update attempt (`update_count = 1`).
2. Android queues the bike's request behind its already-in-flight `45 ms` update.
3. When the `45 ms` update lands first (~45 ms later), the bike sees `45 ms < 62.5 ms` with `update_count == 1` and immediately terminates the link with `0x3B` (`status = 59`, `HCI_ERR_CONN_INTERVAL_UNACCEPTABLE`).

**Kteps Solution:** Wait `600 ms` (`POST_DISCOVERY_SETTLE_MS`) after `onServicesDiscovered` before reading `0x2A19` and enabling `0x2AC1` notifications so Android's `45 ms` transition finishes while `ble_conn_params` is still unarmed, allowing the bike's subsequent `62.5–125 ms` L2CAP request to succeed cleanly on the first attempt.
