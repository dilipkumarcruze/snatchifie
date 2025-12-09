package com.downloader.controller;


import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.downloader.DTO.VideoItem;
import com.downloader.service.YouTubeService;

@RestController
@RequestMapping("/api/youtube")
public class YouTubeController {

    @Autowired
    private YouTubeService youTubeService;

    @GetMapping("/search")
    public List<VideoItem> searchVideos(@RequestParam String query) {
        return youTubeService.searchVideos(query);
    }

    @PostMapping("/download")
    public ResponseEntity<InputStreamResource> downloadVideo(@RequestParam String videoId,
                                                             @RequestParam String title,
                                                             @RequestParam String format) {
        File file = youTubeService.downloadVideo(videoId, title, format).join();

        if (file == null || !file.exists()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        try {
            InputStreamResource resource = new InputStreamResource(new FileInputStream(file));

            String contentType = format.equalsIgnoreCase("mp3") ? "audio/mpeg" : "video/mp4";
            String encodedFileName = file.getName().replaceAll("[^a-zA-Z0-9\\.\\-]", "_");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(contentType));
            headers.setContentLength(file.length());
            headers.setContentDisposition(ContentDisposition.attachment().filename(encodedFileName).build());

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(resource);
        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        } finally {
            // Optional: delay delete to avoid race condition (stream might not fully start)
            new Thread(() -> {
                try {
                    Thread.sleep(10000); // 10 sec buffer
                    if (!file.delete()) {
                        System.err.println("Warning: Failed to delete file " + file.getAbsolutePath());
                    }
                } catch (InterruptedException ignored) {}
            }).start();
        }
    }
}
