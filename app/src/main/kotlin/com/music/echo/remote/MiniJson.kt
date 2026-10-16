package echo.music.iad1tya.remote

/**
 * Minimal JSON reader/writer for the Remote Web Player API.
 *
 * The remote module deliberately depends on nothing outside the Kotlin/Java
 * standard library so the whole HTTP core can be unit-tested on a plain JVM
 * (no Android, no kotlinx-serialization runtime). Only the JSON subset the
 * bridge emits and accepts is supported: objects, arrays, strings, numbers,
 * booleans and null. Parsed numbers are [Double]; use [objLong]/[objString]
 * helpers instead of casting by hand.
 */
object MiniJson {

  fun parse(text: String): Any? {
    val parser = Parser(text)
    val value = parser.readValue()
    parser.skipWhitespace()
    if (!parser.atEnd()) throw RemoteException.validation("Trailing characters in JSON body")
    return value
  }

  fun parseObject(text: String): Map<String, Any?> {
    val value = parse(text)
    if (value !is Map<*, *>) throw RemoteException.validation("JSON body must be an object")
    @Suppress("UNCHECKED_CAST")
    return value as Map<String, Any?>
  }

  fun stringify(value: Any?): String {
    val out = StringBuilder()
    writeValue(out, value)
    return out.toString()
  }

  fun obj(vararg pairs: Pair<String, Any?>): Map<String, Any?> = linkedMapOf(*pairs)

  private fun writeValue(out: StringBuilder, value: Any?) {
    when (value) {
      null -> out.append("null")
      is Boolean -> out.append(if (value) "true" else "false")
      is Int, is Long, is Short, is Byte -> out.append(value.toString())
      is Double, is Float -> {
        val d = (value as Number).toDouble()
        if (d.isNaN() || d.isInfinite()) out.append("null") else out.append(d.toString())
      }
      is String -> writeString(out, value)
      is Map<*, *> -> {
        out.append('{')
        var first = true
        for ((k, v) in value) {
          if (!first) out.append(',')
          first = false
          writeString(out, k.toString())
          out.append(':')
          writeValue(out, v)
        }
        out.append('}')
      }
      is Iterable<*> -> {
        out.append('[')
        var first = true
        for (item in value) {
          if (!first) out.append(',')
          first = false
          writeValue(out, item)
        }
        out.append(']')
      }
      is Array<*> -> writeValue(out, value.toList())
      else -> writeString(out, value.toString())
    }
  }

  private fun writeString(out: StringBuilder, value: String) {
    out.append('"')
    for (c in value) {
      when (c) {
        '"' -> out.append("\\\"")
        '\\' -> out.append("\\\\")
        '\n' -> out.append("\\n")
        '\r' -> out.append("\\r")
        '\t' -> out.append("\\t")
        '\b' -> out.append("\\b")
        '' -> out.append("\\f")
        else -> {
          if (c.code < 0x20) out.append(String.format("\\u%04x", c.code)) else out.append(c)
        }
      }
    }
    out.append('"')
  }

  private class Parser(private val text: String) {
    private var pos = 0

    fun atEnd(): Boolean = pos >= text.length

    fun skipWhitespace() {
      while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    fun readValue(): Any? {
      skipWhitespace()
      if (atEnd()) throw RemoteException.validation("Empty JSON body")
      return when (text[pos]) {
        '{' -> readObject()
        '[' -> readArray()
        '"' -> readString()
        't' -> readLiteral("true", true)
        'f' -> readLiteral("false", false)
        'n' -> readLiteral("null", null)
        else -> readNumber()
      }
    }

    private fun readObject(): Map<String, Any?> {
      expect('{')
      val map = LinkedHashMap<String, Any?>()
      skipWhitespace()
      if (peek() == '}') {
        pos++
        return map
      }
      while (true) {
        skipWhitespace()
        val key = readString()
        skipWhitespace()
        expect(':')
        map[key] = readValue()
        skipWhitespace()
        when (next()) {
          '}' -> return map
          ',' -> continue
          else -> throw RemoteException.validation("Expected ',' or '}' in JSON object")
        }
      }
    }

    private fun readArray(): List<Any?> {
      expect('[')
      val list = ArrayList<Any?>()
      skipWhitespace()
      if (peek() == ']') {
        pos++
        return list
      }
      while (true) {
        list.add(readValue())
        skipWhitespace()
        when (next()) {
          ']' -> return list
          ',' -> continue
          else -> throw RemoteException.validation("Expected ',' or ']' in JSON array")
        }
      }
    }

    private fun readString(): String {
      expect('"')
      val out = StringBuilder()
      while (true) {
        if (atEnd()) throw RemoteException.validation("Unterminated JSON string")
        val c = text[pos++]
        when (c) {
          '"' -> return out.toString()
          '\\' -> {
            if (atEnd()) throw RemoteException.validation("Unterminated JSON escape")
            when (val e = text[pos++]) {
              '"' -> out.append('"')
              '\\' -> out.append('\\')
              '/' -> out.append('/')
              'b' -> out.append('\b')
              'f' -> out.append('')
              'n' -> out.append('\n')
              'r' -> out.append('\r')
              't' -> out.append('\t')
              'u' -> {
                if (pos + 4 > text.length) throw RemoteException.validation("Bad \\u escape")
                val hex = text.substring(pos, pos + 4)
                pos += 4
                val code = hex.toIntOrNull(16)
                  ?: throw RemoteException.validation("Bad \\u escape")
                out.append(code.toChar())
              }
              else -> throw RemoteException.validation("Bad JSON escape: \\$e")
            }
          }
          else -> out.append(c)
        }
      }
    }

    private fun readNumber(): Double {
      val start = pos
      if (peek() == '-') pos++
      while (!atEnd() && (text[pos].isDigit() || text[pos] in ".eE+-")) pos++
      if (start == pos) throw RemoteException.validation("Invalid JSON value at $pos")
      return text.substring(start, pos).toDoubleOrNull()
        ?: throw RemoteException.validation("Invalid JSON number")
    }

    private fun readLiteral(word: String, value: Any?): Any? {
      if (!text.startsWith(word, pos)) throw RemoteException.validation("Invalid JSON literal")
      pos += word.length
      return value
    }

    private fun peek(): Char {
      if (atEnd()) throw RemoteException.validation("Unexpected end of JSON")
      return text[pos]
    }

    private fun next(): Char {
      if (atEnd()) throw RemoteException.validation("Unexpected end of JSON")
      return text[pos++]
    }

    private fun expect(c: Char) {
      if (next() != c) throw RemoteException.validation("Expected '$c' in JSON")
    }
  }
}

/** Typed accessors for parsed JSON objects. */
fun Map<String, Any?>.objString(key: String): String? = this[key] as? String

fun Map<String, Any?>.objLong(key: String): Long? =
  when (val v = this[key]) {
    is Number -> v.toLong()
    is String -> v.toLongOrNull()
    else -> null
  }

fun Map<String, Any?>.objBool(key: String): Boolean? = this[key] as? Boolean

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.objList(key: String): List<Any?>? = this[key] as? List<Any?>

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.objMap(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>
