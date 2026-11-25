package az.easyconnect.mobile

import java.net.URI
import java.net.URLDecoder

/**
 * Kompüterin QR kodundakı link:
 * `easyconnect://pair?v=1&n=<ad>&h=<ip1,ip2>&p=<port>&t=<token>&f=<sha256>`
 *
 * Təhlükəsizlik: link kənardan gələ bilər (saxta QR, başqa tətbiq), ona görə hər sahə ciddi yoxlanır:
 *  - ünvanlar yalnız **lokal şəbəkə** IPv4-ləridir (10/8, 172.16/12, 192.168/16) — internetdəki
 *    serverə ekran göndərmək mümkün deyil;
 *  - sertifikat barmaq izi tam 64 hex simvol olmalıdır (TLS pinning üçün);
 *  - token 32 simvol, yalnız hərf/rəqəm.
 */
data class PairingLink(
    val desktopName: String,
    val hosts: List<String>,
    val port: Int,
    val token: String,
    val fingerprint: String,
) {
    /** İstifadəçiyə göstərilən qısa kod — kompüter ekranındakı kodla müqayisə üçün. */
    val shortCode: String
        get() = fingerprint.take(8).uppercase().chunked(4).joinToString("-")

    companion object {
        private val TOKEN = Regex("^[A-Za-z0-9]{32}$")
        private val FINGERPRINT = Regex("^[0-9a-f]{64}$")
        private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

        fun parse(raw: String): PairingLink {
            val uri = try {
                URI(raw.trim())
            } catch (e: Exception) {
                throw LinkError(LinkError.Reason.NOT_EASYCONNECT)
            }
            check(uri.scheme == "easyconnect" && uri.host == "pair", LinkError.Reason.NOT_EASYCONNECT)
            val q = parseQuery(uri.rawQuery ?: "")
            check(q["v"] == "1", LinkError.Reason.VERSION)

            val hosts = (q["h"] ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
            check(hosts.isNotEmpty() && hosts.size <= 8, LinkError.Reason.NO_HOST)
            hosts.forEach { check(isPrivateIpv4(it), LinkError.Reason.NOT_LOCAL) }

            val port = q["p"]?.toIntOrNull()
            check(port != null && port in 1..65535, LinkError.Reason.BAD_PORT)

            val token = q["t"] ?: ""
            check(TOKEN.matches(token), LinkError.Reason.DAMAGED)
            val fp = q["f"] ?: ""
            check(FINGERPRINT.matches(fp), LinkError.Reason.DAMAGED)

            val name = (q["n"] ?: "EasyConnect")
                .filter { !it.isISOControl() }
                .take(64)
                .ifBlank { "EasyConnect" }

            return PairingLink(name, hosts, port!!, token, fp)
        }

        private fun check(ok: Boolean, reason: LinkError.Reason) {
            if (!ok) throw LinkError(reason)
        }

        fun isPrivateIpv4(ip: String): Boolean {
            val m = IPV4.matchEntire(ip) ?: return false
            val o = m.groupValues.drop(1).map { it.toInt() }
            if (o.any { it > 255 }) return false
            return o[0] == 10 ||
                (o[0] == 172 && o[1] in 16..31) ||
                (o[0] == 192 && o[1] == 168)
        }

        private fun parseQuery(raw: String): Map<String, String> =
            raw.split('&').filter { it.isNotEmpty() }.associate { part ->
                val i = part.indexOf('=')
                val k = if (i < 0) part else part.substring(0, i)
                val v = if (i < 0) "" else part.substring(i + 1)
                URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
            }
    }
}

/** QR/link səhvi. Mətni ekran tərcümə edir (MainActivity). */
class LinkError(val reason: Reason) : IllegalArgumentException(reason.name) {
    enum class Reason { NOT_EASYCONNECT, VERSION, NO_HOST, NOT_LOCAL, BAD_PORT, DAMAGED }
}
