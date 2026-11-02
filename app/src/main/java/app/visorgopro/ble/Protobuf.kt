package app.visorgopro.ble

import java.io.ByteArrayOutputStream

/** Codificador mínimo de protobuf (varint y campos con longitud) para los mensajes de Open GoPro. */
class PbWriter {
    private val out = ByteArrayOutputStream()

    private fun varint(value: Long) {
        var v = value
        while ((v and 0x7FL.inv()) != 0L) {
            out.write(((v and 0x7FL) or 0x80L).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    fun uint(field: Int, value: Long): PbWriter {
        varint(field.toLong() shl 3)
        varint(value)
        return this
    }

    fun bool(field: Int, value: Boolean): PbWriter = uint(field, if (value) 1L else 0L)

    fun string(field: Int, value: String): PbWriter {
        val bytes = value.toByteArray(Charsets.UTF_8)
        varint((field.toLong() shl 3) or 2L)
        varint(bytes.size.toLong())
        out.write(bytes)
        return this
    }

    fun toBytes(): ByteArray = out.toByteArray()
}

/** Decodificador mínimo: guarda los enteros como Long y los campos con longitud como ByteArray. */
class PbMessage private constructor(private val fields: Map<Int, List<Any>>) {

    fun long(field: Int): Long? = fields[field]?.firstOrNull() as? Long

    fun str(field: Int): String? = (fields[field]?.firstOrNull() as? ByteArray)?.toString(Charsets.UTF_8)

    companion object {
        /** Lee el mensaje que empieza en [from] (los mensajes de la cámara llevan 2 bytes de identificador antes). */
        fun parse(data: ByteArray, from: Int = 0): PbMessage {
            val map = HashMap<Int, MutableList<Any>>()
            var pos = from

            fun readVarint(): Long {
                var shift = 0
                var result = 0L
                while (true) {
                    val b = data[pos++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if ((b and 0x80) == 0) break
                    shift += 7
                }
                return result
            }

            try {
                while (pos < data.size) {
                    val tag = readVarint()
                    val field = (tag ushr 3).toInt()
                    when ((tag and 7L).toInt()) {
                        0 -> map.getOrPut(field) { ArrayList() }.add(readVarint())
                        2 -> {
                            val len = readVarint().toInt()
                            map.getOrPut(field) { ArrayList() }.add(data.copyOfRange(pos, pos + len))
                            pos += len
                        }
                        1 -> pos += 8
                        5 -> pos += 4
                        else -> break
                    }
                }
            } catch (e: IndexOutOfBoundsException) {
                // mensaje cortado: se devuelve lo que se alcanzó a leer
            }
            return PbMessage(map)
        }
    }
}
