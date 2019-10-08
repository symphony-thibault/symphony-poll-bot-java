package com.symphony.ps.pollbot.model;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class NewPollData {
    private int count;
    private List<Integer> timeLimits;
    private boolean showPersonSelector;
}
