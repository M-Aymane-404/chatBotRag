package com.chat.chatbot.tools;


import com.chat.chatbot.entities.Etudiant;
import com.chat.chatbot.repo.EtudiantRepo;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AgentTools {

    private final EtudiantRepo etudiantRepo;

    public AgentTools(EtudiantRepo etudiantRepo) {
        this.etudiantRepo = etudiantRepo;
    }

    @Tool(description = "récuperer les informations sur un étudiant par son apogee")
    public List<Etudiant> getEtudiantParApogee(
            @ToolParam(description = "le apogée de l'étudiant") String apogee) {
        return etudiantRepo.findByApogee(apogee);
    }

    @Tool(description = "récuperer les informations sur un étudiant par filière")
    public List<Etudiant> getEtudiantParFiliere(
            @ToolParam(description = "le nom de la filière") String filiere) {
        return etudiantRepo.findByFiliere(filiere);
    }

    @Tool(description = "récuperer les informations sur un étudiant par son nom ou prénom")
    public List<Etudiant> getEtudiants(
            @ToolParam(description = "nom ou prénom d'un étudiant") String nomOuPrenom) {
        return etudiantRepo.findByNomContainingIgnoreCaseOrPrenomContainingIgnoreCase(nomOuPrenom, nomOuPrenom);
    }


 }
