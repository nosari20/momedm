package edu.fnosari.momedm.connectivity.ble.services

import edu.fnosari.momedm.connectivity.ble.characteristics.BLECharacteristic
import java.util.UUID

/**
 * Represents a Bluetooth Low Energy (BLE) service.
 *
 * This abstract class serves as a blueprint for creating BLE services,
 * which are composed of various characteristics. Each BLE service has a
 * unique UUID that identifies it, and optionally a type defining its use case
 * (e.g., primary or secondary service).
 *
 * The class maintains a list of [BLECharacteristic] instances, which represent
 * the characteristics associated with the service.
 *
 * @param uuid The unique identifier (UUID) for the BLE service.
 * @param name The name of the BLE service.
 * @param type The type of the service, usually used to indicate whether it's a primary or secondary service.
 *
 * @property uuid The UUID of the BLE service.
 * @property name The name of the BLE service.
 * @property type The type of the BLE service.
 * @property characteristics A list of BLE characteristics associated with the service.
 *
 * @function addCharacteristic Adds a [BLECharacteristic] to the list of characteristics for the service.
 */
abstract class BLEService(val uuid: UUID, val name: String, val type: Int) {

    var characteristics: List<BLECharacteristic> = arrayListOf()

    /**
     * Adds a [BLECharacteristic] to the list of characteristics.
     *
     * @param characteristic The characteristic to be added to the service.
     */
    fun addCharacteristic(characteristic: BLECharacteristic){
        characteristics += characteristic
    }

}