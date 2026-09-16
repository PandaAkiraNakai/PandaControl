package io.github.pandaakira.apppanda.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Wake-on-LAN directo desde el celular. Solo sirve cuando el celular está en
 * la misma LAN que el PC apagado; fuera de casa el paquete lo manda otro PC
 * encendido (relay, ver [PandaApi.wakeRelay]).
 *
 * El socket se ata a cada red Wi-Fi/Ethernet del celular: con Tailscale activo
 * (sobre todo usando la torre como exit node) el tráfico normal va por la VPN
 * y un broadcast nunca llegaría a la LAN.
 */
object WakeOnLan {

    private val MAC_RE = Regex("""^[0-9A-Fa-f]{2}([:.\-]?[0-9A-Fa-f]{2}){5}$""")

    fun isValidMac(mac: String): Boolean = MAC_RE.matches(mac.trim())

    /** Normaliza a aa:bb:cc:dd:ee:ff (minúsculas). */
    fun normalizeMac(mac: String): String =
        mac.filter { it.isLetterOrDigit() }.lowercase().chunked(2).joinToString(":")

    private fun magicPacket(mac: String): ByteArray {
        val raw = mac.filter { it.isLetterOrDigit() }.chunked(2).map { it.toInt(16).toByte() }
        return ByteArray(6) { 0xFF.toByte() } + List(16) { raw }.flatten().toByteArray()
    }

    /** Resultado del envío directo: por qué vía salió y, si nada salió, por qué. */
    data class DirectResult(val sentVia: List<String>, val errors: List<String>) {
        val sent: Boolean get() = sentVia.isNotEmpty()
    }

    /** Manda el magic packet por cada red local del celular. Primero atando el
     *  socket a la red Wi-Fi/Ethernet; si la VPN no lo permite (Tailscale no
     *  deja saltarse el túnel: EPERM), igual con un socket normal, que para
     *  el broadcast de la LAN sale por el Wi-Fi mientras no se use exit node. */
    @Suppress("DEPRECATION")  // allNetworks: la alternativa por callback no aporta aquí
    fun sendDirect(context: Context, mac: String): DirectResult {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return DirectResult(emptyList(), listOf("sin ConnectivityManager"))
        val packet = magicPacket(mac)
        val via = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val broadcasts = mutableListOf<InetAddress>()
        var hasLan = false
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
            hasLan = true
            val targets = cm.getLinkProperties(network)?.linkAddresses
                ?.filter { it.address is Inet4Address }
                ?.map { directedBroadcast(it.address as Inet4Address, it.prefixLength) }
                .orEmpty()
            broadcasts += targets
            runCatching {
                DatagramSocket().use { socket ->
                    network.bindSocket(socket)
                    sendAll(socket, packet, targets)
                }
            }.onSuccess { via += "Wi-Fi" }
             .onFailure { errors += "Wi-Fi: ${it.message ?: it::class.simpleName}" }
        }
        if (!hasLan) return DirectResult(emptyList(), listOf("el celular no está en Wi-Fi"))
        if (via.isEmpty()) {
            runCatching {
                DatagramSocket().use { socket -> sendAll(socket, packet, broadcasts) }
            }.onSuccess { via += "Wi-Fi (sin atar)" }
             .onFailure { errors += "socket normal: ${it.message ?: it::class.simpleName}" }
        }
        return DirectResult(via, errors)
    }

    private fun sendAll(socket: DatagramSocket, packet: ByteArray, broadcasts: List<InetAddress>) {
        socket.broadcast = true
        val targets = (broadcasts + InetAddress.getByName("255.255.255.255")).distinct()
        for (addr in targets) {
            for (port in intArrayOf(9, 7)) {
                socket.send(DatagramPacket(packet, packet.size, addr, port))
            }
        }
    }

    private fun directedBroadcast(addr: Inet4Address, prefix: Int): InetAddress {
        val ip = addr.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
        val mask = if (prefix <= 0) 0 else -1 shl (32 - prefix.coerceAtMost(32))
        val bc = ip or mask.inv()
        return InetAddress.getByAddress(ByteArray(4) { i -> (bc shr (24 - 8 * i)).toByte() })
    }
}
