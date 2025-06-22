package com.App.Service;

import com.App.DTO.VideoItem;
import com.google.common.util.concurrent.RateLimiter;
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
        } catch (Exception e) {
            log.error("Error during YouTube search: {}", e.getMessage(), e);
            throw new RuntimeException("Search error: " + e.getMessage(), e);
        }

        return results;
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
                File downloadsDir = new File("/tmp/Downloads");
                if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
                    throw new IOException("Failed to create downloads dir: " + downloadsDir.getAbsolutePath());
                }

                String sanitizedTitle = title.replaceAll("[^a-zA-Z0-9_\\- ]", "_").trim();
                String extension = format.equalsIgnoreCase("mp3") ? ".mp3" : ".mp4";
                File outputFile = new File(downloadsDir, sanitizedTitle + extension);

                if (outputFile.exists()) {
                    log.info("✅ File already exists: {}", outputFile.getAbsolutePath());
                    return outputFile;
                }

                File cookieFile = new File("/cookies/cookies.txt");
                if (!cookieFile.exists()) {
                    log.warn("❌ Cookie file not found!");
                } else {
                    log.info("✅ Cookie file loaded.");
                }

                List<String> command = new ArrayList<>();
                command.add("yt-dlp");
                command.add("--cookies");
                command.add("/cookies/cookies.txt");

                if (format.equalsIgnoreCase("mp3")) {
                    command.add("-f");
                    command.add("bestaudio");
                    command.add("--extract-audio");
                    command.add("--audio-format");
                    command.add("mp3");
                } else {
                    command.add("-f");
                    command.add("bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]");
                    command.add("--merge-output-format");
                    command.add("mp4");
                }

                command.add("-o");
                command.add(outputFile.getAbsolutePath());
                command.add("https://www.youtube.com/watch?v=" + videoId);

                log.info("▶️ yt-dlp started for videoId: {}", videoId);

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);
                Process process = pb.start();

                // Limit logging output to prevent Cloud Run log overflow
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    int maxLines = 30; int count = 0;
                    while ((line = reader.readLine()) != null && count++ < maxLines) {
                        log.debug("yt-dlp: {}", line);
                    }
                }

                int exitCode = process.waitFor();
                if (exitCode == 0 && outputFile.exists()) {
                    long elapsed = Duration.between(startTime, Instant.now()).toMillis();
                    log.info("✅ Download completed in {} ms: {}", elapsed, outputFile.getAbsolutePath());
                    return outputFile;
                } else {
                    throw new RuntimeException("Download failed. Exit code: " + exitCode);
                }

            } catch (Exception e) {
                log.error("❌ Download error for videoId {}: {}", videoId, e.getMessage(), e);
                throw new RuntimeException("Download error: " + e.getMessage(), e);
            }
        });

        ongoingDownloads.put(downloadKey, future);
        future.whenComplete((f, t) -> ongoingDownloads.remove(downloadKey));
        return future;
    }

    private JSONObject fetchJson(String urlStr) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new URL(urlStr).openStream(), StandardCharsets.UTF_8))) {
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
        if (input != null && input.matches("^[a-zA-Z0-9_-]{11}$")) {
            return input;
        }
        try {
            URL url = new URL(input);
            String query = url.getQuery();
            if (query != null && query.contains("v=")) {
                for (String param : query.split("&")) {
                    if (param.startsWith("v=")) {
                        return param.substring(2);
                    }
                }
            }
            if (url.getHost().contains("youtu.be")) {
                return url.getPath().substring(1);
            }
        } catch (Exception e) {
            log.warn("Invalid URL provided, treating as keyword: {}", input);
        }
        return null;
    }
}
