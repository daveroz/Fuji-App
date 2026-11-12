package com.fujiapp.remote.net

/** A device property description, as returned by GetDevicePropDesc / Fuji GetDeviceInfo. */
data class PropDesc(
    val code: Int,
    val dataType: Int,
    val writable: Boolean,
    val currentValue: Long,
    val defaultValue: Long,
    /** Allowed discrete values (enum form), empty if none / range form. */
    val allowed: List<Long> = emptyList(),
    /** min, max, step for range form, or null. */
    val range: Triple<Long, Long, Long>? = null,
) {
    val label: String get() = Ptp.KnownProps.label(code)
    val valueLabel: String get() = Ptp.KnownProps.valueLabel(code, currentValue)
    val hex: String get() = "0x%04X".format(code)
}
