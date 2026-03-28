package com.chat.chatbot.repo;

import com.chat.chatbot.entities.Etudiant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EtudiantRepo extends JpaRepository<Etudiant,Long> {
    List<Etudiant> findByFiliere(String filiere);
    List<Etudiant> findByNiveau(String niveau);
    List<Etudiant> findByFiliereAndNiveau(String filiere, String niveau);
    List<Etudiant> findByNomContainingIgnoreCaseOrPrenomContainingIgnoreCase(String nom, String prenom);
    List<Etudiant> findByApogee(String apoge);
}
