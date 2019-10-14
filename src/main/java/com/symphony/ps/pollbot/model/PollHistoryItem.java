package com.symphony.ps.pollbot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Transient;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PollHistoryItem {
    private Instant created;
    private Instant ended;
    private String questionText;
    private List<PollResult> results;

    @Transient
    private static DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.of("UTC"));

    public String getCreated() {
        return formatter.format(created);
    }

    public String getEnded() {
        return formatter.format(ended);
    }
}
