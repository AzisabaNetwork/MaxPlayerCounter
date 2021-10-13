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

package xyz.acrylicstyle.maxPlayerCounter

import net.md_5.bungee.api.ProxyServer
import net.md_5.bungee.api.plugin.Plugin
import util.base.Bytes
import xyz.acrylicstyle.maxPlayerCounter.listener.JoinQuitEventsListener
import xyz.acrylicstyle.maxPlayerCounter.sql.SQLConnection
import xyz.acrylicstyle.maxPlayerCounter.util.Util
import xyz.acrylicstyle.maxPlayerCounter.util.Util.getBeginAndEndOfMonth
import java.io.File
import java.sql.SQLException
import java.util.Calendar
import java.util.Properties
import java.util.Timer
import java.util.TimerTask

class MaxPlayerCounter: Plugin() {
    companion object {
        val GROUP_PATTERN = "^[a-zA-Z0-9+_\\-]{1,32}$".toRegex()

        lateinit var instance: MaxPlayerCounter
    }

    init {
        instance = this
    }

    private val timer = Timer()

    lateinit var connection: SQLConnection

    override fun onEnable() {
        dataFolder.mkdir()
        val file = File(dataFolder, "config.yml")
        if (!file.exists()) {
            logger.info("Copying default config.yml")
            val input = MaxPlayerCounter::class.java.getResourceAsStream("/config.yml")
                ?: throw AssertionError("Could not find config.yml in jar file")
            Bytes.copy(input, file)
        }
        logger.info("Connecting to database...")
        connection = SQLConnection(
            MaxPlayerCounterConfig.database.host,
            MaxPlayerCounterConfig.database.name,
            MaxPlayerCounterConfig.database.user,
            MaxPlayerCounterConfig.database.password,
        )
        val props = Properties()
        props.setProperty("verifyServerCertificate", MaxPlayerCounterConfig.database.verifyServerCertificate.toString())
        props.setProperty("useSSL", MaxPlayerCounterConfig.database.useSSL.toString())
        connection.connect(props)
        logger.info("Connected.")
        timer.scheduleAtFixedRate(object: TimerTask() {
            override fun run() {
                try {
                    val statement = connection.connection.createStatement()
                    statement.execute("SELECT 1;")
                    statement.close()
                } catch (e: SQLException) {
                    logger.warning("Could not execute keep-alive ping")
                    throw e
                }
            }
        }, MaxPlayerCounterConfig.database.keepAlive * 1000L, MaxPlayerCounterConfig.database.keepAlive * 1000L)
        timer.schedule(object: TimerTask() {
            override fun run() {
                val c = Calendar.getInstance()
                var newMonth = c[Calendar.MONTH] - 1 - MaxPlayerCounterConfig.keepUntil
                if (newMonth < 0) {
                    c.set(Calendar.YEAR, c[Calendar.YEAR] - 1)
                    newMonth += 12
                }
                c[Calendar.MONTH] = newMonth
                val time = c.getBeginAndEndOfMonth().second
                logger.info("Removing old records (<= ${Util.formatDateTime(time)})")
                val s = connection.connection.prepareStatement("DELETE FROM `players` WHERE `timestamp` <= ?")
                s.setLong(1, time)
                val affected = s.executeUpdate()
                logger.info("Removed $affected rows.")
            }
        }, 5000L)
        proxy.pluginManager.registerCommand(this, MaxPlayerCounterCommand)
        proxy.pluginManager.registerListener(this, JoinQuitEventsListener)
    }

    override fun onDisable() {
        timer.cancel()
        if (connection.isConnected()) {
            logger.info("Closing database connection")
            connection.close()
        }
        logger.info("Goodbye!")
    }
}
