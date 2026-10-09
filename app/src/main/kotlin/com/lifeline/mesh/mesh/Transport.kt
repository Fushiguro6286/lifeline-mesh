package com.lifeline.mesh.mesh

/**
 * What the [Router] needs from a radio: connected neighbors and byte delivery.
 * Implemented by [NearbyTransport] (real phones) and [VirtualTransport] (simulator and tests).
 */
interface Transport {
    /** The router registers itself before the transport starts. Callbacks may arrive on any thread. */
    fun setListener(listener: Listener?)

    /** Hand [bytes] to connected neighbor [peer]. [onResult] is true once the neighbor has received them. */
    fun send(peer: String, bytes: ByteArray, onResult: (Boolean) -> Unit = {})

    interface Listener {
        fun onConnected(peer: String)
        fun onDisconnected(peer: String)
        fun onReceived(peer: String, bytes: ByteArray)
    }
}
