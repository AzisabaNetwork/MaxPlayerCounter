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
@file:JvmName("MaxPlayerCounter")

package net.azisaba.maxPlayerCounter

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import util.base.Bytes
import net.azisaba.maxPlayerCounter.sql.SQLConnection
import net.azisaba.maxPlayerCounter.util.Util
import net.azisaba.maxPlayerCounter.util.Util.getBeginAndEndOfMonth
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.util.Calendar
import java.util.Properties
import java.util.Timer
import java.util.TimerTask

@Suppress("LeakingThis")
@Plugin(id = "maxplayercounter", name = "MaxPlayerCounter")
open class MaxPlayerCounter @Inject constructor(val server: ProxyServer, val logger: Logger, @DataDirectory val dataFolder: Path) {
    companion object {
        val GROUP_PATTERN = "^[a-zA-Z0-9+_\\-]{1,32}$".toRegex()

        lateinit var instance: MaxPlayerCounter
    }

    init {
        instance = this
    }

    private val timer = Timer()

    lateinit var connection: SQLConnection

    @Subscribe
    fun onProxyInitialization(e: ProxyInitializeEvent) {
        if (!Files.exists(dataFolder)) {
            Files.createDirectory(dataFolder)
        }
        val configPath = dataFolder.resolve("config.yml")
        if (!Files.exists(configPath)) {
            logger.info("Copying default config.yml")
            val input = MaxPlayerCounter::class.java.getResourceAsStream("/config.yml")
                ?: throw AssertionError("Could not find config.yml in jar file")
            Bytes.copy(input, configPath.toFile())
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
                    connection.updatePlayerCountAll()
                } catch (e: Exception) {
                    logger.warn("Could not record player count")
                    throw e
                }
            }
        }, 60000L, 60000L)
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
        server.commandManager.register(MaxPlayerCounterCommand.createCommand())
    }

    @Subscribe
    fun onProxyShutdown(e: ProxyShutdownEvent) {
        timer.cancel()
        if (connection.isConnected()) {
            logger.info("Closing database connection")
            connection.close()
        }
        logger.info("Goodbye!")
    }
}
