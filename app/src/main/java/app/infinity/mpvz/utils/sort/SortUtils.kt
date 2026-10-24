/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.utils.sort

object SortUtils {
  class NaturalOrderComparator(
    private val ignoreCase: Boolean,
    private val shouldSkip: (Char) -> Boolean,
  ) : Comparator<String> {
    companion object {
      val DEFAULT =
        NaturalOrderComparator(
          ignoreCase = true,
          shouldSkip = { it.isWhitespace() },
        )
    }

    override fun compare(
      a: String,
      b: String,
    ): Int {
      var ia = 0
      var ib = 0

      while (true) {
        // Skip ignored characters
        while (ia < a.length && shouldSkip(a[ia])) ia++
        while (ib < b.length && shouldSkip(b[ib])) ib++

        // One or both strings ended => shorter string is smaller
        if (ia >= a.length || ib >= b.length) {
          return when {
            ia >= a.length && ib >= b.length -> 0
            ia >= a.length -> -1
            else -> 1
          }
        }

        val numA = parseNumber(a, ia)
        val numB = parseNumber(b, ib)

        when {
          numA != null && numB != null -> {
            // Both numeric
            val cmp = numA.value.compareTo(numB.value)
            if (cmp != 0) return cmp
            // Numbers equal => advance past them and continue
            ia = numA.exclusiveEndIndex
            ib = numB.exclusiveEndIndex
          }
          else -> {
            // Compare single character
            val ca = if (ignoreCase) a[ia].lowercaseChar() else a[ia]
            val cb = if (ignoreCase) b[ib].lowercaseChar() else b[ib]
            val cmp = ca.compareTo(cb)
            if (cmp != 0) return cmp
            ia++
            ib++
          }
        }
      }
    }

    private data class ParsedNumber(
      val value: Int,
      val exclusiveEndIndex: Int,
    )

    private fun parseNumber(
      s: String,
      start: Int,
    ): ParsedNumber? {
      var i = start

      var hasDigit = false

      while (i < s.length) {
        val c = s[i]
        if (c.isDigit()) {
          hasDigit = true
          i++
        } else {
          break
        }
      }

      if (!hasDigit) return null

      val numStr = s.substring(start, i)
      return try {
        ParsedNumber(numStr.toInt(), i)
      } catch (_: Exception) {
        null
      }
    }
  }
}
