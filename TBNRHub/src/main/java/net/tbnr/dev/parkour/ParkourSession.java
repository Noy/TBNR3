package net.tbnr.dev.parkour;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import net.cogzmc.core.player.CPlayer;
import net.cogzmc.core.player.DatabaseConnectException;
import net.cogzmc.core.util.Point;
import net.cogzmc.core.util.TimeUtils;
import net.tbnr.dev.TBNRHub;
import net.tbnr.dev.enderBar.EnderBarManager;
import net.tbnr.dev.inventory.SettingUtils;
import net.tbnr.dev.setting.PlayerSetting;
import net.tbnr.dev.setting.PlayerSettingsManager;
import net.tbnr.dev.setting.SettingChangeException;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.joda.time.Duration;
import org.joda.time.Instant;

import java.util.*;

@EqualsAndHashCode
@ToString
public final class ParkourSession implements Listener {
    private static final Integer PARKOUR_PRIORITY = 3;

    @Getter private final CPlayer player;
    @Getter private final World world;
    @Getter private final Parkour parkour;
    private final ParkourManager manager;

    private Map<PlayerSetting, Boolean> previousValues = new LinkedHashMap<>();

    private ParkourLevel lastLevel;
    private ParkourLevel level;
    private ParkourLevel nextLevel;
    private Integer levelNumber;
    private List<Point> hitBlocks;
    private boolean inAirLast = false;
    private Point lastPoint; //Last location tracked at
    private TimerRunnable timerTask;
    private boolean completedWithinTargetTime = true;

    private boolean playing = false;
    private boolean tickBack = false;

    private Instant levelStart;
    private Map<ParkourLevel, Duration> levelTimes = new HashMap<>();

    public ParkourSession(Parkour parkour, ParkourManager manager, Integer levelNumber, CPlayer player) {
        this.parkour = parkour;
        this.manager = manager;
        this.player = player;
        this.world = parkour.getWorld();
        this.levelNumber = levelNumber;
        nextLevel = getNextLevel();
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player bukkitPlayer = player.getBukkitPlayer();
        if (!event.getPlayer().equals(bukkitPlayer)) return;
        Location to = event.getTo();
        Point current = Point.of(to);
        Block block = to.getBlock().getRelative(0, -1, 0);
        Point currentBlock = Point.of(block);
        boolean inAirCurrent = !bukkitPlayer.isOnGround() || block.getType() == Material.AIR;
        do {
            if (parkour.getEndRegion().isWithin(current)) {
                completedLevel(new Duration(levelStart, new Instant()));
                endParkour();
                break;
            }
            boolean b = nextLevel != null && nextLevel.getStartRegion().isWithin(current);
            if (playing && b && level != null) {
                //finished a level
                Duration levelTime = new Duration(levelStart, new Instant());
                levelTimes.put(level, levelTime);
                player.sendMessage(TBNRHub.getInstance().getFormat("parkour-level-complete", new String[]{"<level>", String.valueOf(levelNumber)}));
                player.playSoundForPlayer(Sound.LEVEL_UP, 1f, 1.2f);
                for (Point hitBlock : hitBlocks) {
                    Block block1 = hitBlock.getLocation(world).getBlock();
                    bukkitPlayer.sendBlockChange(block1.getLocation(), block1.getType(), block1.getData());
                }
                if (completedWithinTargetTime) {
                    player.playSoundForPlayer(Sound.LEVEL_UP, 1f, 0.8F);
                    player.sendMessage(TBNRHub.getInstance().getFormat("parkour-target-time"));
                }
                if (timerTask != null) timerTask.cancel();
                completedLevel(levelTime);
                try {
                    player.saveIntoDatabase();
                } catch (DatabaseConnectException e) {
                    e.printStackTrace();
                }
                timerTask = null;
                playing = false;
            }
            if (parkour.getStartRegion().isWithin(currentBlock) ||  (nextLevel != null && nextLevel.getStartRegion().isWithin(current))) break;
            if (!playing && (lastPoint == null || nextLevel.getStartRegion().isWithin(lastPoint)) && !nextLevel.getStartRegion().isWithin(current)) {
                //move onto next level
                lastLevel = level;
                tickBack = true;
                level = nextLevel;
                levelNumber++;
                nextLevel = getNextLevel();
                player.sendMessage(TBNRHub.getInstance().getFormat("parkour-level-begin", new String[]{"<level>", String.valueOf(levelNumber)}));
                player.sendMessage(TBNRHub.getInstance().getFormat("parkour-time-info", new String[]{"<duration>", TimeUtils.formatDurationNicely(level.getTargetDuration())}));
                player.playSoundForPlayer(Sound.ORB_PICKUP, 1f, 1.2f);
                hitBlocks = new ArrayList<>();
                levelStart = new Instant();
                completedWithinTargetTime = true;
                timerTask = new TimerRunnable().schedule();
                playing = true;
            }
            if (!parkour.getStartRegion().isWithin(current) && (nextLevel != null && !nextLevel.getStartRegion().isWithin(current)) && !playing) {
                cleanupParkour();
                break;
            }
            if (!inAirCurrent && inAirLast && playing) {
                if (block.getType() != Material.STAINED_CLAY && block.getType() != Material.LADDER) {
                    resetParkour();
                    player.playSoundForPlayer(Sound.NOTE_BASS_DRUM);
                    playing = false;
                } else if (!hitBlocks.contains(currentBlock)){
                    hitBlocks.add(currentBlock);
                    player.playSoundForPlayer(Sound.ORB_PICKUP, 1F, 1.8F);
                    if (block.getType() == Material.STAINED_CLAY) bukkitPlayer.sendBlockChange(block.getLocation(), Material.STAINED_CLAY, (byte) 8);
                }
            }
        } while (false);
        inAirLast = inAirCurrent;
        lastPoint = current;
    }

    private void storeTime(Integer levelNumber, Duration levelTime) {
        Long settingValue = player.getSettingValue("parkour_level_time_" + levelNumber, Long.class);
        if (settingValue == null || levelTime.getMillis() < settingValue) player.storeSettingValue("parkour_level_time_" + levelNumber, levelTime.getMillis());
    }

    private void completedLevel(Duration levelTime) {
        player.storeSettingValue("parkour_complete_" + levelNumber, true);
        storeTime(levelNumber, levelTime);
    }

    private void resetParkour() {
        if (hitBlocks != null) {
            resetHitBlocks();
            hitBlocks.clear();
        }
        player.getBukkitPlayer().teleport(level.getCheckpoint().getLocation(world));
        inAirLast = false;
        lastPoint = level.getCheckpoint().deepCopy();
        timerTask.cancel();
        timerTask = null;
        completedWithinTargetTime = true;
        if (tickBack) {
            nextLevel = level;
            level = lastLevel;
            lastLevel = null;
            tickBack = false;
        }
        levelNumber--;
    }

    private void resetHitBlocks() {
        Player bukkitPlayer = player.getBukkitPlayer();
        for (Point hitBlock : hitBlocks) {
            Block block1 = hitBlock.getLocation(world).getBlock();
            bukkitPlayer.sendBlockChange(block1.getLocation(), block1.getType(), block1.getData());
        }
    }

    private void endParkour() {
        player.sendMessage(TBNRHub.getInstance().getFormat("parkour-end"));
        for (Map.Entry<ParkourLevel, Duration> parkourLevelDurationEntry : levelTimes.entrySet()) {
            player.sendMessage(TBNRHub.getInstance().getFormat("parkour-level-stat",
                    new String[]{"<lnumber>", String.valueOf(parkour.getLevels().indexOf(parkourLevelDurationEntry.getKey())+1)},
                    new String[]{"<ltime>", TimeUtils.formatDurationNicely(parkourLevelDurationEntry.getValue())}
            ));
        }
        player.playSoundForPlayer(Sound.FIREWORK_LARGE_BLAST, 1f, 1.2f);
        player.sendMessage(TBNRHub.getInstance().getFormat("parkour-end-teleport"));
        cleanupParkour();
        Bukkit.getScheduler().runTaskLater(TBNRHub.getInstance(), new Runnable() {
            @Override
            public void run() {
                if (player.isOnline()) TBNRHub.getInstance().getSpawnManager().teleportToSpawn(player);
            }
        }, 40L);
    }

    public void cleanupParkour() {
        if (timerTask != null) timerTask.cancel();
        for (Map.Entry<PlayerSetting, Boolean> playerSettingBooleanEntry : previousValues.entrySet()) {
            PlayerSettingsManager settingsManager = TBNRHub.getInstance().getSettingsManager();
            Boolean currentState = settingsManager.getStateFor(playerSettingBooleanEntry.getKey(), player);
            try {
                settingsManager.unlockSetting(playerSettingBooleanEntry.getKey(), player);
                settingsManager.setStateFor(playerSettingBooleanEntry.getKey(), player, playerSettingBooleanEntry.getValue());
            } catch (SettingChangeException e) {
                if (player.isOnline()) SettingUtils.onSettingDeny(player);
                continue;
            }
            if (player.isOnline() && currentState != playerSettingBooleanEntry.getValue()) SettingUtils.onSettingToggle(player, playerSettingBooleanEntry.getKey());
        }
        manager.parkourCompleted(this);
    }

    public void start() {
        toggleSetting(PlayerSetting.FLY_IN_HUB, false);
        toggleSetting(PlayerSetting.PLAYERS, false);
        toggleSetting(PlayerSetting.JUMP_BOOST, false);
        toggleSetting(PlayerSetting.PARTICLE_EFFECT, false);
        TBNRHub.getInstance().registerListener(this);
    }

    private void toggleSetting(PlayerSetting setting, boolean targetState) {
        PlayerSettingsManager settingsManager = TBNRHub.getInstance().getSettingsManager();
        Boolean stateFor = settingsManager.getStateFor(setting, player);
        SETTING_CHANGE: if (stateFor != targetState) {
            try {
                settingsManager.setStateFor(setting, player, targetState);
            } catch (SettingChangeException e) {
                SettingUtils.onSettingDeny(player);
                break SETTING_CHANGE;
            }
            SettingUtils.onSettingToggle(player, setting);
        }
        previousValues.put(setting, stateFor);
        settingsManager.lockSetting(setting, player);
    }

    private ParkourLevel getNextLevel() {
        return parkour.getLevels().size() <= levelNumber ? null : parkour.getLevels().get(levelNumber);
    }

    private class TimerRunnable extends BukkitRunnable {
        private int secondsPassed = 0;

        public TimerRunnable schedule() {
            EnderBarManager.clearId(player, PARKOUR_PRIORITY);
            runTaskTimer(TBNRHub.getInstance(), 0L, 20L);
            return this;
        }

        @Override
        public void run() {
            long i = level.getTargetDuration().getStandardSeconds() - secondsPassed;
            if (i == 0) {
                completedWithinTargetTime = false;
                cancel();
                return;
            }
            String color = (i <= 5 ? ChatColor.RED : ChatColor.DARK_GREEN).toString();
            float percentage = (float)i/(float)level.getTargetDuration().getStandardSeconds();
            String msg = color + ">>" + ChatColor.GREEN + " Challenge Time: Level " + levelNumber + " " + color + "<<";
            EnderBarManager.setStateForID(player, PARKOUR_PRIORITY, msg, percentage);
            secondsPassed++;
        }

        @Override
        public synchronized void cancel() throws IllegalStateException {
            super.cancel();
            EnderBarManager.clearId(player, PARKOUR_PRIORITY);
        }
    }
}
