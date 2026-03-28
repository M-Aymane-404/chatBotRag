package com.chat.chatbot;

import com.chat.chatbot.entities.Etudiant;
import com.chat.chatbot.repo.EtudiantRepo;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class ChatbotApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatbotApplication.class, args);
    }


    @Bean
    CommandLineRunner commandLineRunner(EtudiantRepo etudiantRepo) {
        return args -> {
            etudiantRepo.save(
                    Etudiant.builder()
                            .nom("ali")
                            .prenom("Alami")
                            .note(12.5)
                            .apogee("123456")
                            .filiere("ILCS")
                            .niveau("1A")
                            .build()
            );

            etudiantRepo.save(
                    Etudiant.builder()
                            .nom("karim")
                            .prenom("benani")
                            .note(15.5)
                            .apogee("123456789")
                            .filiere("ILCS")
                            .niveau("2A")
                            .build()
            );
        };
    }

}
