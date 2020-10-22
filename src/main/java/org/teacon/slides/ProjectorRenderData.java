package org.teacon.slides;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.blaze3d.systems.RenderSystem;
import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.NativeImage;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.crash.CrashReport;
import net.minecraft.crash.ReportedException;
import net.minecraft.util.Util;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.ParametersAreNonnullByDefault;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.*;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public final class ProjectorRenderData {

    static final Path LOCAL_CACHE_PATH = Paths.get("slideshow");
    static final Path LOCAL_CACHE_MAP_JSON_PATH = Paths.get("map.json");

    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    static final TypeToken<Map<String, String>> LOCAL_CACHE_MAP_TYPE = new TypeToken<Map<String, String>>() {};

    static final Map<String, String> LOCAL_CACHE = new ConcurrentHashMap<>(saveToCacheJson(loadFromCacheJson()));

    static final Logger LOGGER = LogManager.getLogger(SlideShow.class);

    static final LoadingCache<String, AtomicReference<ProjectorRenderEntry>> RENDER_CACHE = CacheBuilder.newBuilder()
            .expireAfterAccess(20, TimeUnit.MINUTES).refreshAfterWrite(15, TimeUnit.MINUTES)
            .<String, AtomicReference<ProjectorRenderEntry>>removalListener(old -> old.getValue().get().close())
            .build(new CacheLoader<String, AtomicReference<ProjectorRenderEntry>>() {
                @Override
                public AtomicReference<ProjectorRenderEntry> load(String location) {
                    AtomicReference<ProjectorRenderEntry> ref = new AtomicReference<>(ProjectorRenderEntry.loading());
                    Util.getServerExecutor().execute(() -> {
                        try {
                            Path path = Paths.get(LOCAL_CACHE.get(location));
                            NativeImage image = readImage(path);
                            RenderSystem.recordRenderCall(() -> {
                                TextureManager manager = Minecraft.getInstance().getTextureManager();
                                ref.compareAndSet(ProjectorRenderEntry.loading(), ProjectorRenderEntry.of(image, manager));
                                LOGGER.debug("Try attaching to slide show from local path '" + path + "'");
                                LOGGER.debug("(which corresponds to '" + location + "')");
                                RENDER_CACHE.refresh(location);
                            });
                        } catch (Exception ignored) {
                            RenderSystem.recordRenderCall(() -> RENDER_CACHE.refresh(location));
                        }
                    });
                    return ref;
                }

                @Override
                public ListenableFuture<AtomicReference<ProjectorRenderEntry>> reload(String location, AtomicReference<ProjectorRenderEntry> old) {
                    SettableFuture<AtomicReference<ProjectorRenderEntry>> future = SettableFuture.create();
                    Util.getServerExecutor().execute(() -> {
                        try {
                            URL url = new URL(location);
                            byte[] imageBytes = readImageBytes(url);
                            String extension = readImageExtension(imageBytes, url.getPath());
                            NativeImage image = NativeImage.read(new ByteArrayInputStream(imageBytes));
                            Path path = writeCacheImage(imageBytes, extension);
                            LOCAL_CACHE.put(location, path.toString());
                            saveToCacheJson(LOCAL_CACHE);
                            RenderSystem.recordRenderCall(() -> {
                                TextureManager manager = Minecraft.getInstance().getTextureManager();
                                future.set(new AtomicReference<>(ProjectorRenderEntry.of(image, manager)));
                                LOGGER.debug("Try attaching to slide show from '" + location + "'");
                                LOGGER.debug("(which corresponds to local path '" + path + "')");
                            });
                        } catch (Exception e) {
                            old.compareAndSet(ProjectorRenderEntry.loading(), ProjectorRenderEntry.failed());
                            future.set(new AtomicReference<>(old.getAndSet(ProjectorRenderEntry.failed())));
                            // Maybe can be refreshed manually via some client-side command?
                            LOGGER.info("Failed to load slide show from '" + location + "'");
                            LOGGER.debug("Failed to load slide show from '" + location + "'", e);
                        }
                    });
                    return future;
                }
            });

    public static ProjectorRenderEntry getEntry(String location) {
        return StringUtils.isNotBlank(location) ? RENDER_CACHE.getUnchecked(location).get() : ProjectorRenderEntry.empty();
    }

    private static NativeImage readImage(Path location) throws IOException {
        try (InputStream stream = Files.newInputStream(location)) {
            return NativeImage.read(stream);
        }
    }

    private static byte[] readImageBytes(URL location) throws IOException {
        try (InputStream stream = location.openStream()) {
            return IOUtils.toByteArray(stream);
        }
    }

    private static String readImageExtension(byte[] image, String path) throws IOException {
        try (InputStream stream = new ByteArrayInputStream(image)) {
            try (ImageInputStream imageStream = ImageIO.createImageInputStream(stream)) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(imageStream);
                if (readers.hasNext()) {
                    String[] suffixes = readers.next().getOriginatingProvider().getFileSuffixes();
                    if (suffixes.length > 0) {
                        return suffixes[0].toLowerCase(Locale.ENGLISH);
                    }
                }
                return FilenameUtils.getExtension(path);
            }
        }
    }

    private static Path writeCacheImage(byte[] imageBytes, String extension) throws IOException {
        String suffix = extension.isEmpty() ? "" : "." + extension;
        String fileName = DigestUtils.sha1Hex(imageBytes) + suffix;
        Path location = LOCAL_CACHE_PATH.resolve(fileName);
        Files.write(location, imageBytes);
        return location;
    }

    private static Map<String, String> loadFromCacheJson() {
        try {
            OpenOption[] options = {StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE};
            Path path = Files.createDirectories(LOCAL_CACHE_PATH).resolve(LOCAL_CACHE_MAP_JSON_PATH);
            try (FileChannel channel = FileChannel.open(path, options)) {
                try (Reader reader = Channels.newReader(channel, StandardCharsets.UTF_8.name())) {
                    Map<String, String> map = GSON.fromJson(reader, LOCAL_CACHE_MAP_TYPE.getType());
                    return map == null ? Collections.emptyMap() : map;
                }
            }
        } catch (IOException e) {
            throw new ReportedException(new CrashReport("Failed to read slide show cache map", e));
        }
    }

    private static Map<String, String> saveToCacheJson(Map<String, String> map) {
        try {
            OpenOption[] options = {StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE};
            Path path = Files.createDirectories(LOCAL_CACHE_PATH).resolve(LOCAL_CACHE_MAP_JSON_PATH);
            try (FileChannel channel = FileChannel.open(path, options)) {
                try (Writer writer = Channels.newWriter(channel, StandardCharsets.UTF_8.name())) {
                    GSON.toJson(new TreeMap<>(map), LOCAL_CACHE_MAP_TYPE.getType(), writer);
                    return map;
                }
            }
        } catch (IOException e) {
            throw new ReportedException(new CrashReport("Failed to save slideshow cache map", e));
        }
    }

}