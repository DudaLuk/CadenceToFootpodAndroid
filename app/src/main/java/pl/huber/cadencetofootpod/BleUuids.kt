package pl.huber.cadencetofootpod

import java.util.UUID

object BleUuids {
    // Cycling Speed and Cadence
    val CSC_SERVICE: UUID = uuid16(0x1816)
    val CSC_MEASUREMENT: UUID = uuid16(0x2A5B)

    // Fitness Machine Service - smart trainers such as KICKR CORE 2
    val FTMS_SERVICE: UUID = uuid16(0x1826)
    val INDOOR_BIKE_DATA: UUID = uuid16(0x2AD2)

    // Cycling Power fallback for trainers / power meters
    val CYCLING_POWER_SERVICE: UUID = uuid16(0x1818)
    val CYCLING_POWER_MEASUREMENT: UUID = uuid16(0x2A63)

    // Running Speed and Cadence - virtual footpod
    val RSC_SERVICE: UUID = uuid16(0x1814)
    val RSC_MEASUREMENT: UUID = uuid16(0x2A53)
    val RSC_FEATURE: UUID = uuid16(0x2A54)
    val SENSOR_LOCATION: UUID = uuid16(0x2A5D)

    val CCCD: UUID = uuid16(0x2902)

    private fun uuid16(value: Int): UUID =
        UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", value))
}
