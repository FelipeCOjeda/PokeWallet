package com.pokewallet.crypto

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom

class EntropyCollector {

    private val userEvents = ByteArrayOutputStream()

    fun addUserEvent(x: Float, y: Float, timestamp: Long = System.nanoTime()) {
        val buffer = ByteBuffer.allocate(24)
        buffer.putFloat(x)
        buffer.putFloat(y)
        buffer.putLong(timestamp)
        userEvents.write(buffer.array())
    }

    fun collect(): ByteArray {
        val secureRandom = SecureRandom()
        val systemEntropy = ByteArray(64)
        secureRandom.nextBytes(systemEntropy)

        val timeEntropy = ByteBuffer.allocate(8)
            .putLong(System.nanoTime())
            .array()

        return systemEntropy +
                timeEntropy +
                userEvents.toByteArray()
    }
}
