package com.symphony.ps.pollbot.services;

import com.symphony.ps.pollbot.PollBot;
import com.symphony.ps.pollbot.model.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import model.InboundMessage;
import model.OutboundMessage;
import model.StreamTypes;
import model.User;
import model.events.SymphonyElementsAction;
import org.bson.types.ObjectId;

@Slf4j
public class PollService {
    private static final String helpML = "<ul>" +
        "<li><b>/poll</b>: Get a standard create poll form</li>" +
        "<li><b>/poll 4 0,5,15</b>: Get a create poll form with 4 options and time limits of None, 5 and 15 minutes</li>" +
        "<li><b>/endpoll</b>: End your active poll</li></ul>";

    public static void handleIncomingMessage(InboundMessage msg, StreamTypes streamType) {
        long userId = msg.getUser().getUserId();
        String displayName = msg.getUser().getDisplayName();
        String streamId = msg.getStream().getStreamId();
        String[] msgParts = msg.getMessageText().trim().toLowerCase().split(" ", 2);
        PollConfig pollConfig = (msgParts.length == 1) ? new PollConfig()
            : parseConfigInput(streamId, msgParts[1].split(" "));

        if (pollConfig == null) {
            return;
        }

        switch (msgParts[0]) {
            case "/help":
                PollBot.sendMessage(streamId, helpML);
                break;

            case "/poll":
            case "/createpoll":
                handleSendCreatePollForm(streamId, streamType, msg.getUser(), pollConfig);
                break;

            case "/rigpoll":
                handleRigPoll(streamId, userId, displayName);
                break;

            case "/endpoll":
                handleEndPoll(streamId, userId, displayName);
                break;

            case "/history":
                handleHistory(streamId, streamType, userId, displayName);
                break;

            default:
        }
    }

    private static PollConfig parseConfigInput(String streamId, String[] inputs) {
        int options = 6;
        List<Integer> timeLimits = Arrays.asList(0, 2, 5);

        for (String input : inputs) {
            if (input.matches("^\\d+$")) {
                options = Integer.parseInt(input);
                if (options < 2 || options > 10) {
                    PollBot.sendMessage(streamId, "Number of options must be between 2 and 10");
                    return null;
                }
            } else if (input.matches("^\\d+(,\\d+)+$")) {
                timeLimits = Arrays.stream(input.split(","))
                    .map(Integer::parseInt)
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());
                if (timeLimits.size() > 10) {
                    PollBot.sendMessage(streamId, "Number of time limits should be 10 or lower");
                    return null;
                }
            }
        }
        return new PollConfig(options, timeLimits);
    }

    private static boolean userHasActivePoll(User user, String streamId) {
        if (PollBot.getDataService().hasActivePoll(user.getUserId())) {
            String msg = String.format("<mention uid=\"%d\"/> You already have an existing active poll. " +
                "Use <b>/endpoll</b> to end it before starting a new one", user.getUserId());
            PollBot.sendMessage(streamId, msg);
            log.info("User {} has an existing active poll. Refusing to create a new one.", user.getDisplayName());
            return true;
        }
        return false;
    }

    private static void handleSendCreatePollForm(String streamId, StreamTypes streamType, User user, PollConfig pollConfig) {
        // Reject poll creation if an active one exists
        if (userHasActivePoll(user, streamId)) {
            return;
        }
        log.info("Get new poll form via {} requested by {}", streamType, user.getDisplayName());

        boolean isIM = streamType == StreamTypes.IM;
        String pollML = MarkupService.pollCreateTemplate;
        String data = MarkupService.getPollCreateData(isIM, pollConfig.getOptions(), pollConfig.getTimeLimits());
        PollBot.sendMessage(streamId, pollML, data);

        log.info("New poll form sent to {} stream {}", streamType, streamId);
    }

    @SuppressWarnings("unchecked")
    public static void handleCreatePoll(User initiator, SymphonyElementsAction action) {
        // Reject poll creation if an active one exists
        if (userHasActivePoll(initiator, action.getStreamId())) {
            return;
        }

        Map<String, Object> formValues = action.getFormValues();
        log.info("New poll by {} creation in progress: {}", initiator.getDisplayName(), formValues.toString());

        // Collate options
        Map<String, String> answersMap = new HashMap<>();
        formValues.entrySet().stream()
            .filter(k -> k.getKey().startsWith("option"))
            .map(entry -> entry.getValue().toString().trim())
            .filter(answer -> !answer.isEmpty())
            .forEach(answer -> answersMap.putIfAbsent(answer.toLowerCase(), answer));
        List<String> answers = new ArrayList<>(answersMap.values());

        if (answers.size() < 2) {
            String rejectMsg = String.format("<mention uid=\"%d\"/> Your poll contains less than 2 valid options",
                initiator.getUserId());
            PollBot.sendMessage(action.getStreamId(), rejectMsg);
            log.info("Create poll by {} rejected as there are less than 2 valid options", initiator.getDisplayName());
            return;
        }

        // Obtain IM stream IDs if audience is specified
        List<PollParticipant> participants = null;
        if (formValues.get("audience") != null) {
            participants = ((List<Long>) formValues.get("audience"))
                .parallelStream()
                .map(userId -> {
                    String streamId = PollBot.getBotClient().getStreamsClient().getUserIMStreamId(userId);
                    return new PollParticipant(userId, streamId);
                })
                .collect(Collectors.toList());
        }

        int timeLimit = Integer.parseInt(formValues.get("timeLimit").toString());

        // Create poll object and persist to database
        Poll poll = Poll.builder()
            .creator(initiator.getUserId())
            .created(Instant.now())
            .timeLimit(timeLimit)
            .questionText(formValues.get("question").toString())
            .streamId(action.getStreamId())
            .participants(participants)
            .answers(answers)
            .build();
        PollBot.getDataService().createPoll(poll);

        // Construct poll form and blast to audience
        String blastPollML = MarkupService.pollBlastTemplate;
        String blastPollData = MarkupService.getPollBlastData(poll);

        if (participants != null) {
            participants.forEach(
                participant -> PollBot.sendMessage(participant.getImStreamId(), blastPollML, blastPollData)
            );
        } else {
            PollBot.sendMessage(action.getStreamId(), blastPollML, blastPollData);
        }

        // Start timer
        String endPollByTimerNote = "";
        if (timeLimit > 0) {
            Timer timer = new Timer("PollTimer" + poll.getId());
            timer.schedule(new TimerTask() {
                public void run() {
                    handleEndPoll(null, poll.getCreator(), null);
                }
            }, 60000L * timeLimit);

            endPollByTimerNote = " or wait " + timeLimit + " minute" + (timeLimit > 1 ? "s" : "");
        }

        PollBot.getBotClient().getMessagesClient().sendMessage(action.getStreamId(),
            new OutboundMessage(String.format(
                "<mention uid=\"%d\"/> Your poll has been created. You can use <b>/endpoll</b>%s to end this poll.",
                initiator.getUserId(), endPollByTimerNote
            )));
        log.info("New poll by {} creation complete", initiator.getDisplayName());
    }

    public static void handleSubmitVote(User initiator, SymphonyElementsAction action) {
        String answerIndexString = action.getFormValues().get("action").toString().replace("option-", "");
        int answerIndex = Integer.parseInt(answerIndexString);

        String pollId = action.getFormId().replace("poll-blast-form-", "");
        Poll poll = PollBot.getDataService().getPoll(pollId);

        if (poll == null) {
            PollBot.sendMessage(action.getStreamId(), String.format(
                "<mention uid=\"%d\"/> You have submitted a vote for an invalid poll", initiator.getUserId()));
            log.info("Invalid vote cast by {} on {} stream {}",
                initiator.getDisplayName(), action.getStreamType(), action.getStreamId());
            return;
        }
        if (poll.getEnded() != null) {
            PollBot.sendMessage(action.getStreamId(), String.format(
                "<mention uid=\"%d\"/> This poll is no longer active for voting", initiator.getUserId()));
            log.info("Vote cast on expired poll by {} on {} stream {}",
                initiator.getDisplayName(), action.getStreamType(), action.getStreamId());
            return;
        }
        String answer = poll.getAnswers().get(answerIndex);

        String response;
        if (PollBot.getDataService().hasVoted(initiator.getUserId(), pollId)) {
            PollBot.getDataService().changeVote(initiator.getUserId(), pollId, answer);
            response = "Your vote has been updated";
            log.info("Vote updated to [{}] on poll {} by {}", answer, poll.getId(), initiator.getDisplayName());
        } else {
            PollVote vote = PollVote.builder()
                .pollId(new ObjectId(pollId))
                .answer(answer)
                .userId(initiator.getUserId())
                .build();
            PollBot.getDataService().createVote(vote);
            response = "Thanks for voting";
            log.info("New vote [{}] cast on poll {} by {}", answer, poll.getId(), initiator.getDisplayName());
        }

        PollBot.sendMessage(action.getStreamId(),
            String.format("<mention uid=\"%d\"/> %s!", initiator.getUserId(), response));
    }

    private static void handleRigPoll(String streamId, long userId, String displayName) {
        log.info("Rig poll requested by {}", displayName);

        Poll pollToRig = PollBot.getDataService().getActivePoll(userId);
        if (pollToRig == null) {
            PollBot.sendMessage(streamId, "You have no active poll to rig");
            log.info("User {} has no active poll to rig", displayName);
            return;
        }

        List<PollVote> votes = new ArrayList<>();
        List<String> randomAnswers = new ArrayList<>(pollToRig.getAnswers());
        Collections.shuffle(randomAnswers);
        int answersSize = pollToRig.getAnswers().size();
        int rigVolume = ThreadLocalRandom.current().nextInt(17, 77);
        for (int i=0; i < answersSize; i++) {
            for (int r = 0; r < rigVolume; r++) {
                votes.add(PollVote.builder()
                    .pollId(pollToRig.getId())
                    .answer(randomAnswers.get(i))
                    .build());
            }
            rigVolume += (Math.random() * 387) - (Math.random() * 27);
        }
        PollBot.getDataService().createVotes(votes);

        PollBot.sendMessage(streamId, "Your active poll has been rigged");
        log.info("User {} has rigged active poll", displayName);
    }

    private static void handleEndPoll(String streamId, long userId, String displayName) {
        log.info("End poll requested by {}", displayName);

        Poll poll = PollBot.getDataService().getActivePoll(userId);
        if (poll == null) {
            if (streamId != null) {
                PollBot.sendMessage(streamId, "You have no active poll to end");
                log.info("User {} has no active poll to end", displayName);
            } else {
                log.info("Poll by {} time limit reached but poll was already ended", userId);
            }
            return;
        }

        List<PollVote> votes = PollBot.getDataService().getVotes(poll.getId());
        String response, data = null;

        if (votes.isEmpty()) {
            response = String.format("<mention uid=\"%d\" /> Poll ended but with no results to show", poll.getCreator());
            log.info("Poll {} ended with no votes", poll.getId());
        } else {
            // Aggregate vote results
            List<PollResult> pollResults = PollBot.getDataService().getPollResults(poll.getId());

            // Add in widths
            long maxVal = Collections.max(pollResults, Comparator.comparingLong(PollResult::getCount)).getCount();
            pollResults.forEach(r -> r.setWidth(Math.max(1, (int) (((float) r.getCount() / maxVal) * 200))));

            // Add in 0 votes for options nobody voted on
            poll.getAnswers().stream()
                .map(a -> new PollResult(a, 0, 0))
                .filter(a -> !pollResults.contains(a))
                .forEach(pollResults::add);

            response = MarkupService.pollResultsTemplate;
            data = MarkupService.wrapData(PollResultsData.builder()
                .creatorId(poll.getCreator())
                .question(poll.getQuestionText())
                .results(pollResults)
                .build());

            log.info("Poll {} ended with results {}", poll.getId(), pollResults.toString());
        }

        PollBot.getDataService().endPoll(poll.getCreator());
        PollBot.sendMessage(streamId, response, data);
    }

    private static void handleHistory(String streamId, StreamTypes streamType, long userId, String displayName) {
        log.info("Poll history requested by {}", displayName);

        List<Poll> polls = (streamType == StreamTypes.IM) ?
            PollBot.getDataService().getPolls(userId) :
            PollBot.getDataService().getPolls(userId, streamId);

        if (polls == null || polls.isEmpty()) {
            PollBot.sendMessage(streamId, String.format("<mention uid=\"%d\" /> You have no poll history", userId));
            return;
        }

        List<PollDTO> pollData = polls.stream()
            .map(PollDTO::fromPoll)
            .collect(Collectors.toList());

        String pollHistoryTemplate = MarkupService.pollHistoryTemplate;
        PollHistory history = PollHistory.builder()
            .polls(pollData)
            .creatorId(userId + "")
            .room(streamType == StreamTypes.ROOM)
            .build();
        PollBot.sendMessage(streamId, pollHistoryTemplate, MarkupService.wrapData(history));
    }
}
