package me.abhigya.randomevent

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent
import me.abhigya.randomevent.event.EventWinEvent
import me.abhigya.randomevent.util.LocationSerializer
import me.abhigya.randomevent.util.Util
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.entity.TNTPrimed
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.BoundingBox
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class ChaseSequence(private val plugin: RandomEvent) : Listener {

    var radius = 0
    var started = false
    var chestLocation: Location? = null
    var diamondOwner: Player? = null
    var parkourBox: BoundingBox? = null
    val spawnLocations = HashMap<UUID, Location>()

    fun scheduleStart(diamondHaver: Player) {
        radius = plugin.config!!.getInt("radius")
        val bossbar = BossBar.bossBar(
            Component.text("PvP starting in ", NamedTextColor.YELLOW)
                .append(Component.text("10 seconds...", NamedTextColor.RED)),
            1.0f,
            BossBar.Color.PURPLE,
            BossBar.Overlay.NOTCHED_10,
            setOf(BossBar.Flag.PLAY_BOSS_MUSIC)
        )

        for (player in Bukkit.getOnlinePlayers()) {
            player.showBossBar(bossbar)
            player.playSound(Sound.sound(org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 1.0f, 1.0f))
        }

        val ran = AtomicInteger(10)
        plugin.server.scheduler.runTaskTimer(plugin, { task ->
            if (ran.getAndDecrement() == 1) {
                task.cancel()
                for (player in Bukkit.getOnlinePlayers()) {
                    player.hideBossBar(bossbar)
                }
                init(diamondHaver)
                return@runTaskTimer
            }

            bossbar.name(Component.text("PvP starting in ", NamedTextColor.YELLOW)
                .append(Component.text("${ran.get()} ", NamedTextColor.RED))
                .append(Component.text("seconds...", NamedTextColor.YELLOW)))
            bossbar.progress(ran.get() / 10.0f)
            for (player in Bukkit.getOnlinePlayers()) {
                player.playSound(Sound.sound(org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 1.0f, 1.0f))
            }

        }, 20L, 20L)
    }

    fun init(diamondHaver: Player) {
        started = true
        diamondOwner = diamondHaver
        plugin.server.pluginManager.registerEvents(this, plugin)
        for (player in Bukkit.getOnlinePlayers()) {
            teleportInArena(player)
            player.addPotionEffect(PotionEffect(PotionEffectType.SPEED, Int.MAX_VALUE, 0))
            for (i in 0 until player.inventory.size) {
                val itemStack = player.inventory.getItem(i)
                if (itemStack != null && itemStack.type == Material.DIAMOND && !Util.isCustomDiamond(itemStack)) {
                    player.inventory.setItem(i, Util.bamboozledPotato)
                }
            }
        }
        applyOwnerPotionEffects(diamondHaver)

        chestLocation = LocationSerializer(plugin.config!!, "submit-location").toLocation()
        parkourBox = BoundingBox.of(LocationSerializer(plugin.config!!, "parkour1").toLocation().toVector(), LocationSerializer(plugin.config!!, "parkour2").toLocation().toVector())

        val time = AtomicInteger(plugin.config!!.getInt("arena-timer"))
        val bossbar = BossBar.bossBar(
            Component.text("Next Phase in", NamedTextColor.YELLOW)
                .append(Component.text(" 3 minutes", NamedTextColor.RED)),
            1.0f,
            BossBar.Color.PURPLE,
            BossBar.Overlay.NOTCHED_10,
            setOf(BossBar.Flag.PLAY_BOSS_MUSIC)
        )
        for (player in Bukkit.getOnlinePlayers()) {
            player.showBossBar(bossbar)
        }
        plugin.server.scheduler.runTaskTimer(plugin, { task ->
            if (time.decrementAndGet() == 0) {
                task.cancel()
                spawnTnt()
                val title = Title.title(
                    Component.text("A door opened somewhere!", NamedTextColor.BLUE),
                    Component.text("Get to next zone!", NamedTextColor.YELLOW)
                )
                for (player in Bukkit.getOnlinePlayers()) {
                    player.hideBossBar(bossbar)
                    player.showTitle(title)
                }
                return@runTaskTimer
            }
            var timeFormat = "${time.get() / 60} minutes ${time.get() % 60} seconds"
            if (time.get() % 60 == 0) {
                timeFormat = "${time.get() / 60} minutes"
            } else if (time.get() < 60) {
                timeFormat = "${time.get()} seconds"
            }
            bossbar.name(Component.text("Next Phase in ", NamedTextColor.YELLOW)
                .append(Component.text(timeFormat, NamedTextColor.RED)))
            bossbar.progress(time.get() / 180.0f)
        }, 20L, 20L)
    }

    fun spawnTnt() {
        val loc1 = LocationSerializer(plugin.config!!, "tnt1").toLocation()
        val loc2 = LocationSerializer(plugin.config!!, "tnt2").toLocation()
        val loc3 = LocationSerializer(plugin.config!!, "tnt-spawn").toLocation()
        val box = BoundingBox.of(loc1.toVector(), loc2.toVector())
        loc3.world.spawn(loc3, TNTPrimed::class.java).apply {
            fuseTicks = 20
        }
        plugin.server.scheduler.runTaskLater(plugin, { task ->
            for (x in box.minX.toInt() until box.maxX.toInt() + 1) {
                for (y in box.minY.toInt() until box.maxY.toInt() + 1) {
                    for (z in box.minZ.toInt() until box.maxZ.toInt() + 1) {
                        loc3.world.getBlockAt(x, y, z).breakNaturally()
                    }
                }
            }
        }, 20)
    }

    fun end(winner: Player) {
        plugin.server.pluginManager.callEvent(EventWinEvent(winner))
    }

    private fun applyOwnerPotionEffects(player: Player) {
        player.removePotionEffect(PotionEffectType.SPEED)
        if (spawnLocations.containsKey(player.uniqueId)) return
        player.addPotionEffects(listOf(
            PotionEffect(PotionEffectType.HEALTH_BOOST, Int.MAX_VALUE, 2),
            PotionEffect(PotionEffectType.INCREASE_DAMAGE, Int.MAX_VALUE, 0),
            PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, Int.MAX_VALUE, 0)
        ))
    }

    private fun removeOwnerPotionEffects(player: Player) {
        player.removePotionEffect(PotionEffectType.HEALTH_BOOST)
        player.removePotionEffect(PotionEffectType.INCREASE_DAMAGE)
        player.removePotionEffect(PotionEffectType.DAMAGE_RESISTANCE)
    }

    private fun teleportInArena(player: Player) {
        val arenaLocation = LocationSerializer(plugin.config!!, "arena-location").toLocation()
        val maxY = plugin.config!!.getInt("max-arena-y", 300)
        var loc: Location
        do {
            loc = Util.randomCircleVector(radius, arenaLocation.toVector()).toLocation(arenaLocation.world).apply {
                y = arenaLocation.world.getHighestBlockAt(x.toInt(), z.toInt()).y + 1.0
            }
        } while (loc.y > maxY)
        player.teleport(loc)
    }

    @EventHandler
    fun handleMove(event: PlayerMoveEvent) {
        if (spawnLocations.contains(event.player.uniqueId)) return
        if (parkourBox!!.contains(event.from.toVector())) {
            spawnLocations[event.player.uniqueId] = LocationSerializer(plugin.config!!, "parkour-spawn").toLocation()
            event.player.removePotionEffect(PotionEffectType.SPEED)
            removeOwnerPotionEffects(event.player)
        }
    }

    @EventHandler
    fun handleRespawn(event: PlayerPostRespawnEvent) {
        if (!spawnLocations.containsKey(event.player.uniqueId)) {
            event.player.addPotionEffect(PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 0))
        }
        if (spawnLocations.containsKey(event.player.uniqueId)) {
            event.player.teleport(spawnLocations[event.player.uniqueId]!!)
        } else {
            teleportInArena(event.player)
        }
    }

    @EventHandler
    fun handlePickup(event: EntityPickupItemEvent) {
        if (event.entity !is Player) return
        if (Util.isCustomDiamond(event.item.itemStack)) {
            diamondOwner = event.entity as Player
            if (spawnLocations.containsKey(diamondOwner!!.uniqueId)) return
            applyOwnerPotionEffects(diamondOwner!!)
        }
    }

    @EventHandler
    fun handleDrop(event: PlayerDropItemEvent) {
        if (Util.isCustomDiamond(event.itemDrop.itemStack)) {
            diamondOwner = null
            removeOwnerPotionEffects(event.player)
            if (spawnLocations.containsKey(event.player.uniqueId)) return
            event.player.addPotionEffect(PotionEffect(PotionEffectType.SPEED, Int.MAX_VALUE, 0))
        }
    }

    @EventHandler
    fun handlePlayerLeave(event: PlayerQuitEvent) {
        if (event.player != diamondOwner) return
        for (itemStack in event.player.inventory) {
            if (!Util.isCustomDiamond(itemStack)) continue
            event.player.inventory.remove(itemStack)
            event.player.location.world.dropItem(event.player.location, itemStack)
            break
        }
        removeOwnerPotionEffects(event.player)
    }

    @EventHandler
    fun handlePlayerJoin(event: PlayerJoinEvent) {
        if (spawnLocations.containsKey(event.player.uniqueId)) return
        event.player.addPotionEffect(PotionEffect(PotionEffectType.SPEED, Int.MAX_VALUE, 0))
    }

    @EventHandler
    fun handleEntityExplode(event: EntityExplodeEvent) {
        event.blockList().clear()
    }

    @EventHandler
    fun handleBlockExplode(event: BlockExplodeEvent) {
        event.blockList().clear()
    }

    @EventHandler
    fun handleItemBurn(event: EntityDamageEvent) {
        if (event.entity !is Item) return
        if (!Util.isCustomDiamond((event.entity as Item).itemStack)) return
        event.isCancelled = true
    }

    @EventHandler
    fun handleInteract(event: PlayerInteractEvent) {
        if (chestLocation == null) return
        if (!event.hasBlock()) return
        if (event.clickedBlock?.type != Material.ENDER_CHEST) return
        if (event.clickedBlock?.location != chestLocation) return
        if (!Util.isCustomDiamond(event.player.inventory.itemInMainHand)) return
        event.isCancelled = true
        event.player.inventory.remove(event.player.inventory.itemInMainHand)
        end(event.player)
    }

}