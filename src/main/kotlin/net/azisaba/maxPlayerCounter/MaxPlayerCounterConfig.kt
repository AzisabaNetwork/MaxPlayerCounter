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

package net.azisaba.maxPlayerCounter

import util.yaml.YamlConfiguration
import util.yaml.YamlObject
import java.io.File

object MaxPlayerCounterConfig {
    private val cfg: YamlObject

    init {
        val dir = File("./plugins/MaxPlayerCounter")
        dir.mkdir()
        val file = File(dir, "config.yml")
        cfg = YamlConfiguration(file).asObject()
    }

    val keepUntil = cfg.getInt("keepUntil")

    val database = DatabaseSettings(cfg.getObject("database"))

    class DatabaseSettings internal constructor(obj: YamlObject) {
        val host = obj.getString("host") ?: "localhost"
        val name = obj.getString("name") ?: "maxplayercounter"
        val user = obj.getString("user") ?: "maxplayercounter"
        val password = obj.getString("password") ?: "maxplayercounter"
        val verifyServerCertificate = obj.getBoolean("verifyServerCertificate", false)
        val useSSL = obj.getBoolean("useSSL", true)
        //val keepAlive = obj.getInt("keepAlive", 300)
    }
}
