package com.chat.chatbot.audio;

public class OpenAiCompatibleHttpException extends RuntimeException {
    private final String operation;
    private final int statusCode;
    private final String responseBody;

    public OpenAiCompatibleHttpException(String operation, int statusCode, String responseBody) {
        super(operation + " erreur HTTP " + statusCode + ": " + responseBody);
        this.operation = operation;
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public String getOperation() {
        return operation;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }
}

