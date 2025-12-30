package com.example.myapplication3

import java.util.UUID

object UUIDs {
    val serviceUuid = UUID.fromString("bb21801d-a324-418f-abc7-f23d10e7d588")
    val characteristicUuidBidirectional = UUID.fromString("b6a0912e-e715-438b-96a2-b21149015db1")
    val characteristicUuidWrite = UUID.fromString("b6a0912e-e715-438b-96a2-b21149015db2")
    val characteristicUuidNotify = UUID.fromString("b6a0912e-e715-438b-96a2-b21149015db3")
    val characteristicUuidRead = UUID.fromString("00001104-0000-1000-8000-00805F9B34FB")
    val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}