package com.downloader.service;

import com.google.common.util.concurrent.RateLimiter;
import com.downloader.DTO.VideoItem;
import com.downloader.Exception.YouTubeException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class YouTubeService {

    private static final Logger log = LoggerFactory.getLogger(YouTubeService.class);

    @Value("${youtube.api.key}")
    private String apiKey;

    private final RateLimiter rateLimiter = RateLimiter.create(10.0);
    private final ConcurrentHashMap<String, CompletableFuture<File>> ongoingDownloads = new ConcurrentHashMap<>();

    @Cacheable("searchResults")
    public List<VideoItem> searchVideos(String keywordOrLink) {
        rateLimiter.acquire();
        List<VideoItem> results = new ArrayList<>();

        try {
            String videoId = extractVideoIdFromUrl(keywordOrLink);

            if (videoId != null) {
                JSONObject video = fetchVideoDetails(videoId);
                if (video != null) {
                    String title = video.getJSONObject("snippet").getString("title");
                    long seconds = Duration.parse(video.getJSONObject("contentDetails").getString("duration")).getSeconds();
                    if (seconds <= 900) {
                        results.add(new VideoItem(videoId, title));
                    }
                }
            } else {
                List<String> videoIds = new ArrayList<>();
                Map<String, String> titles = new HashMap<>();

                String encodedKeyword = URLEncoder.encode(keywordOrLink, StandardCharsets.UTF_8);
                String searchURL = String.format(
                        "https://www.googleapis.com/youtube/v3/search?part=snippet&q=%s&type=video&maxResults=10&key=%s",
                        encodedKeyword, apiKey
                );

                JSONObject searchResponse = fetchJson(searchURL);
                JSONArray items = searchResponse.getJSONArray("items");

                for (int i = 0; i < items.length(); i++) {
                    JSONObject video = items.getJSONObject(i);
                    String id = video.getJSONObject("id").getString("videoId");
                    videoIds.add(id);
                    titles.put(id, video.getJSONObject("snippet").getString("title"));
                }

                String idsParam = String.join(",", videoIds);
                String detailsURL = String.format(
                        "https://www.googleapis.com/youtube/v3/videos?part=contentDetails&id=%s&key=%s",
                        idsParam, apiKey
                );

                JSONObject detailsResponse = fetchJson(detailsURL);
                JSONArray videoDetails = detailsResponse.getJSONArray("items");

                for (int i = 0; i < videoDetails.length(); i++) {
                    JSONObject item = videoDetails.getJSONObject(i);
                    String id = item.getString("id");
                    long seconds = Duration.parse(item.getJSONObject("contentDetails").getString("duration")).getSeconds();
                    if (seconds <= 900) {
                        results.add(new VideoItem(id, titles.get(id)));
                    }
                }
            }

            return results;

        } catch (Exception e) {
            throw new YouTubeException("YouTube search failed", e);
        }
    }

    @Async
    public CompletableFuture<File> downloadVideo(String videoId, String title, String format) {

        String downloadKey = videoId + "_" + format;
        if (ongoingDownloads.containsKey(downloadKey)) {
            return ongoingDownloads.get(downloadKey);
        }

        CompletableFuture<File> future = CompletableFuture.supplyAsync(() -> {
            try {
                Instant startTime = Instant.now();

                File downloadsDir = new File(System.getProperty("java.io.tmpdir"), "downloads");
                if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
                    throw new IOException("Cannot create downloads folder");
                }

                File cookieFile = new File("cookies/cookies.txt");
                if (!cookieFile.exists()) {
                    log.warn("⚠️ Cookie file missing");
                }

                List<String> command = new ArrayList<>();
                command.add("yt-dlp");
                command.add("--no-playlist");
                command.add("--geo-bypass");
                command.add("--force-overwrites");

                if (cookieFile.exists()) {
                    command.add("--cookies");
                    command.add(cookieFile.getAbsolutePath());
                }

                command.add("-f");
                command.add("bv*+ba/b");

                if (format.equalsIgnoreCase("mp3")) {
                    command.add("--extract-audio");
                    command.add("--audio-format");
                    command.add("mp3");
                } else {
                    command.add("--merge-output-format");
                    command.add("mp4");
                }

                command.add("-o");
                command.add(downloadsDir.getAbsolutePath() + "/%(title)s.%(ext)s");
                command.add("https://www.youtube.com/watch?v=" + videoId);

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);

                Process process = pb.start();

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("yt-dlp: {}", line);
                    }
                }

                int exitCode = process.waitFor();

                if (exitCode == 0) {
                    File[] files = downloadsDir.listFiles((dir, name) ->
                            name.toLowerCase().endsWith(format.equalsIgnoreCase("mp3") ? ".mp3" : ".mp4")
                    );

                    if (files != null && files.length > 0) {
                        File latestFile = Arrays.stream(files)
                                .max(Comparator.comparingLong(File::lastModified))
                                .orElse(null);

                        if (latestFile != null) {
                            long elapsed = Duration.between(startTime, Instant.now()).toMillis();
                            log.info("✅ Download completed in {} ms", elapsed);
                            return latestFile;
                        }
                    }
                    throw new YouTubeException("Download finished but file not found");
                }

                throw new YouTubeException("yt-dlp failed with exit code: " + exitCode);

            } catch (Exception e) {
                log.error("❌ Download failed", e);
                throw new YouTubeException("Download error: " + e.getMessage(), e);
            }
        });

        ongoingDownloads.put(downloadKey, future);
        future.whenComplete((f, t) -> ongoingDownloads.remove(downloadKey));
        return future;
    }

    private JSONObject fetchJson(String urlStr) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new URL(urlStr).openStream(), StandardCharsets.UTF_8))) {

            StringBuilder response = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                response.append(buffer, 0, read);
            }
            return new JSONObject(response.toString());
        }
    }

    private JSONObject fetchVideoDetails(String videoId) throws IOException {
        String url = String.format(
                "https://www.googleapis.com/youtube/v3/videos?part=snippet,contentDetails&id=%s&key=%s",
                videoId, apiKey
        );
        JSONObject response = fetchJson(url);
        JSONArray items = response.optJSONArray("items");
        return (items != null && items.length() > 0) ? items.getJSONObject(0) : null;
    }

    private String extractVideoIdFromUrl(String input) {
        try {
            if (input.matches("^[a-zA-Z0-9_-]{11}$")) return input;

            URL url = new URL(input);
            if (url.getQuery() != null && url.getQuery().contains("v=")) {
                for (String param : url.getQuery().split("&")) {
                    if (param.startsWith("v=")) {
                        return param.substring(2);
                    }
                }
            }
            if (url.getHost().contains("youtu.be")) {
                return url.getPath().substring(1);
            }
        } catch (Exception ignored) {}
        return null;
    }
}
