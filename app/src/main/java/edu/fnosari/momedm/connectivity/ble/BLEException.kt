package edu.fnosari.momedm.connectivity.ble

/**
 * Represents a custom exception for handling Bluetooth Low Energy (BLE) errors.
 *
 * This class extends the standard [Exception] class and is used to signal
 * specific BLE-related errors that might occur during BLE operations.
 *
 * @param message An optional message providing additional information about the error.
 */
class BLEException(message: String?) : Exception(message)