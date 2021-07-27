/*
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 */

package xyz.acrylicstyle.maxPlayerCounter.util

import net.md_5.bungee.api.CommandSender
import net.md_5.bungee.api.chat.TextComponent
import java.util.Calendar

object Util {
    fun CommandSender.send(message: String) {
        sendMessage(*TextComponent.fromLegacyText(message))
    }

    fun formatDateTime(millis: Long): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = millis
        return cal.formatDateTime()
    }

    fun Calendar.formatDateTime(): String {
        val year = this[Calendar.YEAR]
        val month = this[Calendar.MONTH] + 1
        val day = this[Calendar.DAY_OF_MONTH]
        val hour = this[Calendar.HOUR_OF_DAY]
        val minute = this[Calendar.MINUTE]
        val second = this[Calendar.SECOND]
        val millisecond = this[Calendar.MILLISECOND]
        return "$year/$month/$day $hour:$minute:$second.$millisecond"
    }

    fun Calendar.getBeginAndEndOfMonth(): Pair<Long, Long> {
        val c = this.clone() as Calendar
        c[Calendar.DAY_OF_MONTH] = 1
        c[Calendar.HOUR_OF_DAY] = 0
        c[Calendar.MINUTE] = 0
        c[Calendar.SECOND] = 0
        c[Calendar.MILLISECOND] = 0
        val first = c.timeInMillis
        var newMonth = c[Calendar.MONTH] + 1
        if (newMonth > 11) {
            c.set(Calendar.YEAR, c[Calendar.YEAR] + 1)
            newMonth -= 12
        }
        c[Calendar.MONTH] = newMonth
        val second = c.timeInMillis - 1
        return first to second
    }

    fun getCurrentMonth() = Calendar.getInstance()[Calendar.MONTH]

    fun Calendar.convertMonth(month: Int) {
        if (month < 0 || month > 11) error("Invalid month (must be 0-11 inclusive): $month")
        val backToThePast = month > getCurrentMonth()
        if (backToThePast) {
            this[Calendar.YEAR]--
            this[Calendar.MONTH] = month
        }
        this[Calendar.MONTH] = month
    }
}
