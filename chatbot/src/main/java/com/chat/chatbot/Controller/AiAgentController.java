package com.chat.chatbot.Controller;

import com.chat.chatbot.agent.AIAgent;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@CrossOrigin("*")
@RestController
@RequestMapping
public class AiAgentController {

    private final AIAgent aiAgent;

    public AiAgentController(AIAgent aiAgent) {
        this.aiAgent = aiAgent;
    }

    @GetMapping(value = "/askAgent", produces = MediaType.TEXT_PLAIN_VALUE)
    public Flux<String> askAgent(@RequestParam(defaultValue = "Bonjour") String question) {
        return aiAgent.onQuestion(question);
    }






}
