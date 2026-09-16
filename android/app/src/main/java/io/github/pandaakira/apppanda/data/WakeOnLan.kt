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

    /** Manda el magic packet por cada red local del celular. Devuelve cuántas
     *  redes lo enviaron (0 = sin Wi-Fi/Ethernet o todo falló). */
    @Suppress("DEPRECATION")  // allNetworks: la alternativa por callback no aporta aquí
    fun sendDirect(context: Context, mac: String): Int {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return 0
        val packet = magicPacket(mac)
        var sent = 0
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
            val targets = mutableListOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
            cm.getLinkProperties(network)?.linkAddresses
                ?.filter { it.address is Inet4Address }
                ?.forEach { targets.add(0, directedBroadcast(it.address as Inet4Address, it.prefixLength)) }
            val ok = runCatching {
                DatagramSocket().use { socket ->
                    network.bindSocket(socket)
                    socket.broadcast = true
                    for (addr in targets.distinct()) {
                        for (port in intArrayOf(9, 7)) {
                            socket.send(DatagramPacket(packet, packet.size, addr, port))
                        }
                    }
                }
            }.isSuccess
            if (ok) sent++
        }
        return sent
    }

    private fun directedBroadcast(addr: Inet4Address, prefix: Int): InetAddress {
        val ip = addr.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
        val mask = if (prefix <= 0) 0 else -1 shl (32 - prefix.coerceAtMost(32))
        val bc = ip or mask.inv()
        return InetAddress.getByAddress(ByteArray(4) { i -> (bc shr (24 - 8 * i)).toByte() })
    }
}
