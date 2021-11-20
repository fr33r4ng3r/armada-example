package armada.example.api

import kotlinx.serialization.Serializable

@Serializable
data class ScanData(val x: Int, val y: Int, val thermalIndex: Double)
