package org.popcraft.bluemapessentials;

import com.earth2me.essentials.IEssentials;
import com.earth2me.essentials.User;
import com.earth2me.essentials.UserMap;
import com.earth2me.essentials.api.IWarps;
import com.earth2me.essentials.commands.WarpNotFoundException;
import com.flowpowered.math.vector.Vector2i;
import com.flowpowered.math.vector.Vector3d;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import net.ess3.api.InvalidWorldException;
import org.bstats.bukkit.Metrics;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.UUID;
import java.util.stream.Collectors;

@SuppressWarnings("unused")
public final class BlueMapEssentials extends JavaPlugin {
    private static final String MARKERSET_ID_HOMES = "homes", MARKERSET_ID_WARPS = "warps";
    private static final String MARKERSET_LABEL_HOMES = "Homes", MARKERSET_LABEL_WARPS = "Warps";
    private static final String ICON_ID_HOMES = "essentials/home.png", ICON_ID_WARPS = "essentials/warp.png";
    private IEssentials essentials;
    private BlueMapAPI blueMap;
    private boolean warpsEnabled, homesEnabled;
    private String warpLabelFormat, homeLabelFormat;
    private boolean homesOnlinePlayersOnly;
    private boolean storeIconsPerMap;

    @Override
    public void onEnable() {
        this.getConfig().options().copyDefaults(true);
        this.saveConfig();
        this.essentials = (IEssentials) getServer().getPluginManager().getPlugin("Essentials");
        this.warpsEnabled = getConfig().getBoolean("warps.enabled", true);
        this.homesEnabled = getConfig().getBoolean("homes.enabled", true);
        this.warpLabelFormat = getConfig().getString("warps.label", "%warp%");
        this.homeLabelFormat = getConfig().getString("homes.label", "%home% (%player%'s home)");
        this.homesOnlinePlayersOnly = getConfig().getBoolean("homes.online-players-only", true);
        this.storeIconsPerMap = getConfig().getBoolean("store-icons-per-map", false);
        BlueMapAPI.onEnable(blueMapAPI -> {
            this.blueMap = blueMapAPI;
            loadImages();
            addMarkers();
            final long updateInterval = Math.max(1, getConfig().getLong("update-interval", 300));
            getServer().getScheduler().runTaskTimerAsynchronously(this, this::refreshMarkers, 0, 20 * updateInterval);
        });
        new Metrics(this, 9011);
    }

    @Override
    public void onDisable() {
        BlueMapAPI.onDisable(blueMapAPI -> {
            getServer().getScheduler().cancelTasks(this);
            removeMarkers();
            this.blueMap = null;
        });
    }

    private void loadImages() {
        if(storeIconsPerMap) {
            for(final BlueMapMap map : blueMap.getMaps()) {
                try {
                    copyResourceToBlueMapMap(map, "home.png", ICON_ID_HOMES);
                    copyResourceToBlueMapMap(map, "warp.png", ICON_ID_WARPS);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        } else {
            //store icons globally, in webapp
            try {
                copyResourceToBlueMapWebApp(blueMap.getWebApp().getWebRoot(), "home.png", ICON_ID_HOMES);
                copyResourceToBlueMapWebApp(blueMap.getWebApp().getWebRoot(), "warp.png", ICON_ID_WARPS);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void copyResourceToBlueMapWebApp(final Path webroot, final String fromResource, final String toAsset) throws IOException {
        final Path toPath = webroot.resolve("assets").resolve(toAsset);

        // Skip if the file already exists
        if (Files.exists(toPath)) {
            return;
        }

        Files.createDirectories(toPath.getParent());
        try (
                final InputStream in = getResource(fromResource);
                final OutputStream out = Files.newOutputStream(toPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        ){
            if (in == null) throw new IOException("Resource not found: " + fromResource);
            in.transferTo(out);
        }
    }

    private void copyResourceToBlueMapMap(final BlueMapMap map, final String fromResource, final String toAsset) throws IOException {
        try (
                final InputStream in = getResource(fromResource);
                final OutputStream out = map.getAssetStorage().writeAsset(toAsset)
        ){
            if (in == null) throw new IOException("Resource not found: " + fromResource);
            in.transferTo(out);
        }
    }

    private void addMarkers() {
        if (essentials == null) {
            return;
        }
        if (warpsEnabled) {
            addWarpMarkers();
        }
        if (homesEnabled) {
            addHomeMarkers();
        }
    }

    private String getMarkerURL(final BlueMapMap map, final String id) {
        if (storeIconsPerMap) {
            return map.getAssetStorage().getAssetUrl(id);
        } else {
            return "assets/" + id;
        }
    }

    private void addWarpMarkers() {
        IWarps warps = essentials.getWarps();

        for (final String warp : warps.getList()) {
            final Location warpLocation;
            try {
                warpLocation = warps.getWarp(warp);
            } catch (WarpNotFoundException | InvalidWorldException e) {
                continue;
            }

            World warpWorld = warpLocation.getWorld();
            if (warpWorld == null) continue;

            blueMap.getWorld(warpWorld.getUID()).ifPresent(blueWorld ->
                    blueWorld.getMaps().forEach(map -> {

                        // Ensure marker set exists and is configured properly
                        MarkerSet markerSetWarps = map.getMarkerSets().get(MARKERSET_ID_WARPS);
                        if (markerSetWarps == null) {
                            markerSetWarps = MarkerSet.builder()
                                    .label(MARKERSET_LABEL_WARPS)
                                    .defaultHidden(true)
                                    .toggleable(true)
                                    .build();
                            map.getMarkerSets().put(MARKERSET_ID_WARPS, markerSetWarps);
                        }

                        String warpMarkerId = String.format("warp:%s:%s", map.getName(), warp);
                        Vector3d warpMarkerPos = Vector3d.from(
                                warpLocation.getX(),
                                warpLocation.getY(),
                                warpLocation.getZ()
                        );

                        POIMarker warpMarker = POIMarker.builder()
                                .label(warpLabelFormat.replace("%warp%", warp))
                                .icon(getMarkerURL(map, ICON_ID_WARPS), Vector2i.from(19, 19))
                                .position(warpMarkerPos)
                                .build();

                        markerSetWarps.getMarkers().put(warpMarkerId, warpMarker);
                    })
            );
        }
    }


    private void addHomeMarkers() {
        final UserMap userMap = essentials.getUserMap();
        final Collection<UUID> users;

        if (homesOnlinePlayersOnly) {
            users = getServer().getOnlinePlayers().stream()
                    .map(Player::getUniqueId)
                    .collect(Collectors.toSet());
        } else {
            users = userMap.getAllUniqueUsers();
        }

        for (UUID uuid : users) {
            final User user = userMap.getUser(uuid);
            if (user == null) continue;

            for (final String home : user.getHomes()) {
                final Location homeLocation;
                try {
                    homeLocation = user.getHome(home);
                } catch (Exception e) {
                    continue;
                }

                if (homeLocation == null || homeLocation.getWorld() == null) continue;

                World homeWorld = homeLocation.getWorld();

                blueMap.getWorld(homeWorld.getUID()).ifPresent(blueWorld ->
                        blueWorld.getMaps().forEach(map -> {

                            // Ensure marker set exists
                            MarkerSet markerSetHomes = map.getMarkerSets().get(MARKERSET_ID_HOMES);
                            if (markerSetHomes == null) {
                                markerSetHomes = MarkerSet.builder()
                                        .label(MARKERSET_LABEL_HOMES)
                                        .defaultHidden(true)
                                        .toggleable(true)
                                        .build();
                                map.getMarkerSets().put(MARKERSET_ID_HOMES, markerSetHomes);
                            }

                            String homeMarkerId = String.format("home:%s:%s", user.getName(), home);
                            Vector3d homeMarkerPos = Vector3d.from(
                                    homeLocation.getX(),
                                    homeLocation.getY(),
                                    homeLocation.getZ()
                            );

                            POIMarker homeMarker = POIMarker.builder()
                                    .label(homeLabelFormat
                                            .replace("%home%", home)
                                            .replace("%player%", user.getName()))
                                    .icon(getMarkerURL(map, ICON_ID_HOMES), Vector2i.from(18, 18))
                                    .position(homeMarkerPos)
                                    .build();

                            markerSetHomes.getMarkers().put(homeMarkerId, homeMarker);
                        })
                );
            }
        }
    }


    private void removeMarkers() {
        if (essentials == null) {
            return;
        }
        blueMap.getWorlds().forEach(blueMapWorld -> blueMapWorld.getMaps().forEach(map -> {
            map.getMarkerSets().remove(MARKERSET_ID_WARPS);
            map.getMarkerSets().remove(MARKERSET_ID_HOMES);
        }));
    }

    private void refreshMarkers() {
        removeMarkers();
        addMarkers();
    }
}
