package com.reqws.goland.manifest

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale

/** Pure textual URL validation equivalent to the Desktop `isSafeRepositoryUrl` policy. */
object RepositoryUrlSafety {
  private val authorityUri = Regex("^([A-Za-z][A-Za-z0-9+.-]*):\\/\\/")
  private val asciiHostCharacter = Regex("^[A-Za-z0-9._~-]$")
  private val decimalHostLabel = Regex("^[0-9]+$")
  private val hexadecimalHostLabel = Regex("^0x[0-9A-Fa-f]+$")
  private val ipv6HostCharacters = Regex("^[0-9A-Fa-f:.]+$")
  private val hostDot = Regex("[.\\u3002\\uff0e\\uff61]")
  private val credentialParameter = Regex(
    "^(?:access[_-]?token|api[_-]?key|auth|authorization|credential|" +
      "oauth[_-]?token|password|private[_-]?key|private[_-]?token|secret|token)$",
    RegexOption.IGNORE_CASE,
  )

  fun isSafe(input: String): Boolean {
    val value = trimEcmaScriptWhitespace(Normalizer.normalize(input, Normalizer.Form.NFC))
    if (value.isEmpty() || value.startsWith('-') || hasControlCharacter(value)) return false

    // Match the structural behavior historically used by HTTPS URL parsers.
    // The original value remains inert manifest data and is never sent through a shell.
    val structuralValue = value.replace('\\', '/')
    val authorityMatch = authorityUri.find(structuralValue)
    if (authorityMatch != null) {
      val protocol = authorityMatch.groupValues[1].lowercase(Locale.US)
      if (protocol != "https" && protocol != "ssh") return false
      if (!hasValidAuthority(structuralValue, protocol)) return false
      return !hasCredentialParameters(structuralValue)
    }

    if (structuralValue.contains("://") || structuralValue.contains("::")) return false
    return isSafeScpLikeRemote(structuralValue)
  }

  private fun hasControlCharacter(value: String): Boolean {
    var offset = 0
    while (offset < value.length) {
      val codePoint = value.codePointAt(offset)
      if (codePoint < 0x20 || codePoint == 0x7f) return true
      offset += Character.charCount(codePoint)
    }
    return false
  }

  private fun hasWhitespaceCharacter(value: String): Boolean {
    var offset = 0
    while (offset < value.length) {
      val codePoint = value.codePointAt(offset)
      if (isEcmaScriptWhitespace(codePoint)) return true
      offset += Character.charCount(codePoint)
    }
    return false
  }

  private fun trimEcmaScriptWhitespace(value: String): String {
    var start = 0
    var end = value.length
    while (start < end && isEcmaScriptWhitespace(value[start].code)) start += 1
    while (end > start && isEcmaScriptWhitespace(value[end - 1].code)) end -= 1
    return value.substring(start, end)
  }

  private fun isEcmaScriptWhitespace(codePoint: Int): Boolean =
    codePoint in 0x0009..0x000d ||
      codePoint == 0x0020 ||
      codePoint == 0x00a0 ||
      codePoint == 0x1680 ||
      codePoint in 0x2000..0x200a ||
      codePoint == 0x2028 ||
      codePoint == 0x2029 ||
      codePoint == 0x202f ||
      codePoint == 0x205f ||
      codePoint == 0x3000 ||
      codePoint == 0xfeff

  private fun decodePercentEscapes(value: String): String {
    val decoded = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
      when {
        value[index] == '+' -> {
          decoded.append(' ')
          index += 1
        }
        value[index] == '%' && index + 2 < value.length -> {
          val high = value[index + 1].digitToIntOrNull(16)
          val low = value[index + 2].digitToIntOrNull(16)
          if (high != null && low != null) {
            decoded.append(((high shl 4) or low).toChar())
            index += 3
          } else {
            decoded.append('%')
            index += 1
          }
        }
        else -> {
          decoded.append(value[index])
          index += 1
        }
      }
    }
    return decoded.toString()
  }

  private fun decodeParameterKey(value: String): String =
    decodePercentEscapes(value.replace('+', ' '))

  private fun decodeHostPercentEscapes(value: String): String? {
    val decoded = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
      if (value[index] != '%') {
        decoded.append(value[index])
        index += 1
        continue
      }

      val bytes = ByteArrayOutputStream()
      while (index < value.length && value[index] == '%') {
        if (index + 2 >= value.length) return null
        val high = value[index + 1].digitToIntOrNull(16) ?: return null
        val low = value[index + 2].digitToIntOrNull(16) ?: return null
        bytes.write((high shl 4) or low)
        index += 3
      }
      val text = try {
        StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes.toByteArray()))
          .toString()
      } catch (_: Exception) {
        return null
      }
      decoded.append(text)
    }
    return decoded.toString()
  }

  private fun hasCredentialParameter(rawParameters: String?): Boolean {
    if (rawParameters.isNullOrEmpty()) return false
    return rawParameters.split('&').any { entry ->
      credentialParameter.matches(decodeParameterKey(entry.substringBefore('=')))
    }
  }

  private fun hasCredentialParameters(value: String): Boolean {
    val fragmentStart = value.indexOf('#')
    val queryStart = value.indexOf('?')
    val query = if (queryStart >= 0 && (fragmentStart < 0 || queryStart < fragmentStart)) {
      value.substring(queryStart + 1, if (fragmentStart < 0) value.length else fragmentStart)
    } else {
      null
    }
    val fragment = if (fragmentStart < 0) null else value.substring(fragmentStart + 1)
    return hasCredentialParameter(query) || hasCredentialParameter(fragment)
  }

  private fun rawAuthority(value: String): String {
    val authorityStart = value.indexOf("://") + 3
    val tailStart = value.indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
    return if (tailStart < 0) {
      value.substring(authorityStart)
    } else {
      value.substring(authorityStart, tailStart)
    }
  }

  private fun hasValidAuthority(value: String, protocol: String): Boolean {
    val authority = rawAuthority(value)
    if (authority.isEmpty()) return false

    // WHATWG URL parsing treats the final literal `@` as the authority
    // separator and percent-encodes earlier `@` characters into the username.
    val userInfoSeparator = authority.lastIndexOf('@')
    val hasUserInfo = userInfoSeparator >= 0
    val userInfo = if (hasUserInfo) authority.substring(0, userInfoSeparator) else ""
    val hostAndPort = if (hasUserInfo) authority.substring(userInfoSeparator + 1) else authority
    // WHATWG applies numeric-host/IPv4 coercion to special HTTPS URLs but not
    // to the non-special ssh scheme. Preserve that legacy persisted-state
    // boundary while keeping both implementations parser-independent.
    if (!hasValidHostAndPort(hostAndPort, protocol == "https")) return false

    // Preserve legacy empty HTTPS userinfo (`https://@host`) and the equivalent
    // empty username/password boundary (`https://:@host`) without accepting any
    // non-empty HTTPS credential text.
    if (protocol == "https") {
      return !hasUserInfo || userInfo.isEmpty() || userInfo == ":"
    }
    if (!hasUserInfo) return true

    // SSH usernames are identity selectors rather than persisted credentials.
    // A literal colon creates the password boundary. The legacy parser also
    // accepted a single trailing colon because that password is empty; preserve
    // that credential-free representation along with encoded-colon usernames.
    val firstColon = userInfo.indexOf(':')
    return firstColon < 0 ||
      (
        firstColon == userInfo.lastIndex &&
          firstColon == userInfo.lastIndexOf(':')
      )
  }

  private fun isSafeScpLikeRemote(value: String): Boolean {
    val firstColon = value.indexOf(':')
    if (firstColon <= 0 || firstColon == value.lastIndex) return false
    val identity = value.substring(0, firstColon)
    val suffix = value.substring(firstColon + 1)
    val firstAt = identity.indexOf('@')
    if (firstAt != identity.lastIndexOf('@')) return false
    val username = if (firstAt < 0) null else identity.substring(0, firstAt)
    val host = if (firstAt < 0) identity else identity.substring(firstAt + 1)
    if (
      host.isEmpty() ||
      host.contains('/') ||
      hasWhitespaceCharacter(host) ||
      !hasValidDnsHost(host, enforceSpecialSchemeIpv4 = false)
    ) return false
    if (
      username != null &&
      (
        username.isEmpty() ||
          username.any { it == ':' || it == '@' || it == '/' } ||
          hasWhitespaceCharacter(username)
      )
    ) return false
    return !suffix.contains('@') && !hasCredentialParameters(suffix)
  }

  private fun hasValidHostAndPort(
    rawAuthority: String,
    enforceSpecialSchemeIpv4: Boolean,
  ): Boolean {
    if (rawAuthority.startsWith('[')) {
      val closingBracket = rawAuthority.indexOf(']')
      if (closingBracket <= 1) return false
      if (!isValidIpv6(rawAuthority.substring(1, closingBracket))) return false
      val remainder = rawAuthority.substring(closingBracket + 1)
      return remainder.isEmpty() ||
        (remainder.startsWith(':') && isValidPort(remainder.substring(1)))
    }

    if (rawAuthority.count { it == ':' } > 1) return false
    val separator = rawAuthority.lastIndexOf(':')
    val host = if (separator < 0) rawAuthority else rawAuthority.substring(0, separator)
    val port = if (separator < 0) null else rawAuthority.substring(separator + 1)
    return hasValidDnsHost(host, enforceSpecialSchemeIpv4) &&
      (port == null || isValidPort(port))
  }

  private fun hasValidDnsHost(
    value: String,
    enforceSpecialSchemeIpv4: Boolean = true,
  ): Boolean {
    if (value.isEmpty()) return false
    val decodedValue = decodeHostPercentEscapes(value) ?: return false
    if (decodedValue.contains('%')) return false
    val comparableValue = if (isHostDot(decodedValue.last())) {
      decodedValue.dropLast(1)
    } else {
      decodedValue
    }
    val labels = comparableValue.split(hostDot)
    val lastLabel = labels.last()
    if (
      enforceSpecialSchemeIpv4 &&
      (decimalHostLabel.matches(lastLabel) || hexadecimalHostLabel.matches(lastLabel))
    ) {
      return isValidIpv4(comparableValue)
    }
    return labels.all { label ->
      var offset = 0
      while (offset < label.length) {
        val codePoint = label.codePointAt(offset)
        val valid = if (codePoint > 0x7f) {
          !isForbiddenHostCodePoint(codePoint) &&
            !hasWhitespaceCharacter(String(Character.toChars(codePoint)))
        } else {
          asciiHostCharacter.matches(codePoint.toChar().toString())
        }
        if (!valid) return@all false
        offset += Character.charCount(codePoint)
      }
      true
    }
  }

  private fun parseIpv4Number(value: String): Long? {
    val (digits, radix) = when {
      value.startsWith("0x", ignoreCase = true) -> value.substring(2) to 16
      value.length >= 2 && value.startsWith('0') -> value.substring(1) to 8
      else -> value to 10
    }
    if (digits.isEmpty()) return null
    val valid = when (radix) {
      16 -> digits.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }
      8 -> digits.all { it in '0'..'7' }
      else -> digits.all { it in '0'..'9' }
    }
    return if (valid) digits.toLongOrNull(radix) else null
  }

  private fun isValidIpv4(value: String): Boolean {
    val parts = value.split('.').toMutableList()
    if (parts.lastOrNull().isNullOrEmpty()) parts.removeLastOrNull()
    if (parts.size !in 1..4) return false
    val numbers = parts.map { parseIpv4Number(it) ?: return false }
    if (numbers.dropLast(1).any { it > 255 }) return false
    val lastLimit = 1L shl (8 * (5 - numbers.size))
    return numbers.last() < lastLimit
  }

  private fun isForbiddenHostCodePoint(codePoint: Int): Boolean =
    codePoint in 0x80..0x9f ||
      codePoint in 0xd800..0xdfff ||
      codePoint == 0x200c ||
      codePoint == 0x200d

  private fun isHostDot(character: Char): Boolean =
    character == '.' || character == '\u3002' || character == '\uff0e' || character == '\uff61'

  private fun isValidEmbeddedIpv4(value: String): Boolean {
    val parts = value.split('.')
    return parts.size == 4 && parts.all { part ->
      part.isNotEmpty() &&
        part.all { it in '0'..'9' } &&
        (part == "0" || !part.startsWith('0')) &&
        part.length <= 3 &&
        part.toInt() <= 255
    }
  }

  private fun isValidIpv6(value: String): Boolean {
    if (!ipv6HostCharacters.matches(value)) return false
    val comparableValue = if (value.contains('.')) {
      val ipv4Separator = value.lastIndexOf(':')
      if (
        ipv4Separator < 0 ||
        !isValidEmbeddedIpv4(value.substring(ipv4Separator + 1))
      ) return false
      // An embedded IPv4 address occupies the final two 16-bit IPv6 groups.
      value.substring(0, ipv4Separator) + ":0:0"
    } else {
      value
    }

    val firstCompression = comparableValue.indexOf("::")
    if (firstCompression != comparableValue.lastIndexOf("::")) return false
    if (firstCompression < 0) {
      val groups = comparableValue.split(':')
      return groups.size == 8 && groups.all { it.length in 1..4 }
    }
    val left = comparableValue.substring(0, firstCompression)
    val right = comparableValue.substring(firstCompression + 2)
    if (left.endsWith(':') || right.startsWith(':')) return false
    val groups = buildList {
      if (left.isNotEmpty()) addAll(left.split(':'))
      if (right.isNotEmpty()) addAll(right.split(':'))
    }
    return groups.size < 8 && groups.all { it.length in 1..4 }
  }

  private fun isValidPort(value: String): Boolean {
    if (value.any { it !in '0'..'9' }) return false
    if (value.isEmpty()) return true
    val significantDigits = value.trimStart('0').ifEmpty { "0" }
    return significantDigits.length < 5 ||
      (significantDigits.length == 5 && significantDigits <= "65535")
  }
}
