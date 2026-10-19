package com.cagritasoz.user_service.testsupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Reads the mails Mailpit has caught, through its REST API.
public final class MailpitClient {

    public record Mail(String id, String subject, List<String> to) {
    }

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static final Pattern URL = Pattern.compile("(https?://\\S+)");

    private final HttpClient http = HttpClient.newHttpClient();

    private final String baseUrl;

    public MailpitClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    // Every test uses its own addresses, so asking for the mails of one address keeps the tests independent
    // of each other without ever clearing the mailbox.
    public List<Mail> mailsTo(String address) {

        List<Mail> mails = new ArrayList<>();

        for (JsonNode message : get("/api/v1/messages?limit=500").path("messages")) {

            List<String> recipients = new ArrayList<>();
            for (JsonNode recipient : message.path("To")) {
                recipients.add(recipient.path("Address").asString());
            }

            if (recipients.contains(address)) {
                mails.add(new Mail(message.path("ID").asString(), message.path("Subject").asString(), recipients));
            }

        }

        return mails;

    }

    // The first link in the plain-text body.
    public String firstLink(Mail mail) {

        String text = get("/api/v1/message/" + mail.id()).path("Text").asString();

        Matcher matcher = URL.matcher(text);

        if (!matcher.find()) {
            throw new IllegalStateException("No link in the mail \"" + mail.subject() + "\"");
        }

        return matcher.group(1);

    }

    private JsonNode get(String path) {

        try {

            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            return MAPPER.readTree(response.body());

        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }

    }
}
